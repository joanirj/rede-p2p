package br.redep2p

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Compartilhador: cria o grupo Wi-Fi Direct (este aparelho vira o "dono do grupo")
 * e roda o proxy que encaminha o tráfego dos clientes pela rede Wi-Fi comum.
 *
 * Modo repetidor: se a rede Wi-Fi comum deste aparelho for a rede de outro aparelho
 * do app (DIRECT-RP-…), ele cria a própria rede e repassa o tráfego para o de cima.
 */
class HostService : Service() {

    companion object {
        const val ACTION_STOP = "br.redep2p.STOP_HOST"

        /** Entra sozinho na rede de um aparelho do app e repete a internet dele. */
        const val ACTION_REPEAT = "br.redep2p.REPEAT"

        /** Reconecta ao Wi-Fi de casa na faixa de 2,4 GHz e compartilha nesse canal. */
        const val ACTION_SHARE_24 = "br.redep2p.SHARE_24"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_PASS = "pass"
        const val EXTRA_BSSID = "bssid"
        const val EXTRA_FREQ = "freq"

        @Volatile
        var running = false

        @Volatile
        var status = "Parado"

        /** Proxy no ar e aceitando clientes. */
        @Volatile
        var ready = false

        /** Este aparelho está repetindo a internet de outro (e não do Wi-Fi de casa). */
        @Volatile
        var repeater = false

        /**
         * A saída do proxy não é a rede padrão do aparelho (repetidor ou modo 2,4 GHz).
         * Para o próprio aparelho navegar, ele usa a VPN local apontando para o próprio proxy.
         */
        @Volatile
        var ownTrafficViaProxy = false

        /** Aviso curto para a tela quando os canais ficaram diferentes. */
        @Volatile
        var channelWarning: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var executor: ExecutorService? = null
    private var proxy: ProxyServer? = null
    private var upstreamFrequency = 0
    private var upstream: Upstream? = null
    private var outNetwork: Network? = null
    private var upstreamNetwork: Network? = null
    private var billing: BillingServer? = null
    private var upstreamBilling: BillingClient? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        Identity.init(applicationContext)
        Ledger.init(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        startInForeground()
        running = true
        Stats.reset()
        status = "Iniciando…"

        if (intent?.action == ACTION_SHARE_24) {
            val ssid = intent.getStringExtra(EXTRA_SSID)
            val pass = intent.getStringExtra(EXTRA_PASS)
            if (ssid == null || pass == null) {
                fail("Falha: faltou o nome ou a senha do Wi-Fi")
                return START_NOT_STICKY
            }
            status = "Conectando ao Wi-Fi de casa em 2,4 GHz…"
            AppLog.i("Pontos de acesso \"$ssid\" vistos: ${scanSummary(this, ssid)}")
            // Tentativas: pela faixa (Android 12+) e pelo ponto de acesso 2,4 GHz exato.
            val attempts = mutableListOf<Pair<String?, Int>>()
            if (Build.VERSION.SDK_INT >= 31) attempts += null to 0
            val ap = intent.getStringExtra(EXTRA_BSSID)?.let { it to intent.getIntExtra(EXTRA_FREQ, 0) }
                ?: find24Bssid(this, ssid)
            if (ap != null) attempts += ap.first to ap.second
            if (attempts.isEmpty()) {
                fail("Falha: não achei a versão 2,4 GHz do Wi-Fi \"$ssid\" por perto (a localização está ligada?)")
                return START_NOT_STICKY
            }
            tryHome24(ssid, pass, attempts, 0)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REPEAT) {
            status = "Entrando na rede do aparelho de cima…"
            UpstreamLink.request(
                this,
                onReady = { wifi -> handler.post { if (running) startRepeater(wifi) } },
                onFail = { msg -> handler.post { fail(msg) } },
                onLost = { handler.post { if (running) fail("Falha: a conexão com o aparelho de cima caiu") } }
            )
            return START_NOT_STICKY
        }

        val direct = findDirectWifi(this)
        if (direct == null) {
            logUpstream(null)
            startGroup()
        } else {
            startRepeater(direct)
        }
        return START_NOT_STICKY
    }

    private fun tryHome24(ssid: String, pass: String, attempts: List<Pair<String?, Int>>, index: Int) {
        val (bssid, freq) = attempts[index]
        HomeLink.request(
            this, ssid, pass,
            bssid = bssid,
            knownFrequency = freq,
            onReady = { net, f -> handler.post { if (running) startHome24(net, f) } },
            onFail = { msg, immediate ->
                handler.post {
                    if (!running) return@post
                    AppLog.e(msg)
                    if (index + 1 < attempts.size) {
                        AppLog.i("Tentando outro formato de pedido…")
                        tryHome24(ssid, pass, attempts, index + 1)
                    } else {
                        // Só apaga a senha salva se o Android chegou a tentar conectar.
                        if (!immediate) WifiPasswords.forget(this, ssid)
                        fail("Falha: não consegui ligar este aparelho ao Wi-Fi \"$ssid\" em 2,4 GHz")
                    }
                }
            },
            onLost = { handler.post { if (running) fail("Falha: a conexão 2,4 GHz com o Wi-Fi de casa caiu") } }
        )
    }

    /** Modo 2,4 GHz: já conectado ao Wi-Fi de casa nessa faixa; cria a rede no mesmo canal. */
    private fun startHome24(net: Network, freq: Int) {
        outNetwork = net
        ownTrafficViaProxy = true
        upstreamFrequency = if (isBand24(freq)) freq else 0
        AppLog.i("Conectado ao Wi-Fi de casa em 2,4 GHz (${if (freq > 0) "$freq MHz" else "frequência desconhecida"})")
        startGroup()
    }

    /** Modo repetidor: descobre o aparelho de cima e então cria a própria rede. */
    private fun startRepeater(wifi: DirectWifi) {
        status = "Conectando ao aparelho de cima…"
        AppLog.i("Este aparelho está na rede de outro aparelho do app: modo repetidor")
        logUpstream(wifi.network)
        upstreamNetwork = wifi.network
        Thread {
            try {
                val up = Upstream.discover(wifi)
                upstream = up
                repeater = true
                ownTrafficViaProxy = true
                AppLog.i("Aparelho de cima: ${up.hops} salto(s) da internet, endereço ${up.address.hostAddress}")
                handler.post { if (running) startGroup() }
            } catch (e: Exception) {
                AppLog.e("Não consegui falar com o aparelho de cima", e)
                handler.post { fail("Falha no modo repetidor") }
            }
        }.start()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("rede", "Rede P2P", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, "rede")
            .setContentTitle("Compartilhando internet")
            .setContentText("Rede P2P ativa. Toque para abrir.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    /** Registra como está a rede de saída (Wi-Fi comum), útil para diagnóstico. */
    @Suppress("DEPRECATION")
    private fun logUpstream(network: Network?) {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(network ?: cm.activeNetwork)
            when {
                caps == null -> AppLog.e("Este aparelho está sem internet. Conecte-o a um Wi-Fi antes de compartilhar.")
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    AppLog.i("Atenção: a internet deste aparelho não vem de Wi-Fi.")
                else -> {
                    val wifi = applicationContext.getSystemService(WifiManager::class.java)
                    val freq = wifi.connectionInfo?.frequency ?: 0
                    upstreamFrequency = if (freq in 2400..5900) freq else 0
                    AppLog.i("Saída: Wi-Fi comum, frequência $freq MHz, ${caps.linkDownstreamBandwidthKbps / 1000} Mbps estimados")
                }
            }
        } catch (e: Exception) {
            AppLog.e("Não consegui ler a rede de saída", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGroup() {
        val m = getSystemService(WifiP2pManager::class.java)
        if (m == null) {
            fail("Este aparelho não tem Wi-Fi Direct")
            return
        }
        manager = m
        val ch = m.initialize(this, Looper.getMainLooper()) {
            AppLog.e("O sistema encerrou o canal Wi-Fi Direct")
        }
        channel = ch
        // Remove um grupo antigo (se houver) antes de criar o nosso.
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed({ createGroup(m, ch) }, 500)
            }

            override fun onFailure(reason: Int) {
                createGroup(m, ch)
            }
        })
    }

    /**
     * Tentativas, da melhor para a mais compatível:
     * 0 = mesmo canal do Wi-Fi de casa (o rádio não precisa alternar entre canais);
     * 1 = faixa de 2,4 GHz; 2 = o sistema escolhe.
     */
    @SuppressLint("MissingPermission")
    private fun createGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel, attempt: Int = 0) {
        if (!running) return
        val builder = WifiP2pConfig.Builder()
            .setNetworkName(if (upstream != null) Config.REPEATER_NAME else Config.NETWORK_NAME)
            .setPassphrase(Config.PASSPHRASE)
            .enablePersistentMode(false)
        val description = when {
            attempt == 0 && upstreamFrequency > 0 -> {
                builder.setGroupOperatingFrequency(upstreamFrequency)
                "no mesmo canal do Wi-Fi de casa ($upstreamFrequency MHz)"
            }
            attempt <= 1 -> {
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                "na faixa de 2,4 GHz"
            }
            else -> {
                builder.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                "na faixa escolhida pelo sistema"
            }
        }
        AppLog.i("Criando a rede Wi-Fi Direct $description…")
        m.createGroup(ch, builder.build(), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("Grupo Wi-Fi Direct criado")
                handler.postDelayed({ checkGroup(m, ch, 0) }, 1000)
            }

            override fun onFailure(reason: Int) {
                if (attempt < 2) {
                    AppLog.i("Não deu ${description}: ${p2pReason(reason)}. Tentando outra opção…")
                    handler.postDelayed({ createGroup(m, ch, attempt + 1) }, 1000)
                } else {
                    fail("Falha ao criar o grupo Wi-Fi Direct: ${p2pReason(reason)}")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun checkGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel, attempt: Int) {
        if (!running) return
        m.requestGroupInfo(ch) { group ->
            if (group == null || !group.isGroupOwner) {
                if (attempt < 10) {
                    handler.postDelayed({ checkGroup(m, ch, attempt + 1) }, 1000)
                } else {
                    fail("O grupo Wi-Fi Direct não ficou pronto")
                }
                return@requestGroupInfo
            }
            AppLog.i("Rede: ${group.networkName} · senha: ${group.passphrase} · ${group.frequency} MHz · interface ${group.`interface`}")
            warnIfChannelsDiffer(group.frequency)
            startProxy()
        }
    }

    /**
     * Se a rede Wi-Fi Direct ficou num canal diferente do Wi-Fi de saída, o rádio
     * alterna entre os dois e os outros aparelhos costumam não conseguir conectar.
     */
    private fun warnIfChannelsDiffer(groupFrequency: Int) {
        val up = upstreamFrequency
        if (up <= 0 || groupFrequency <= 0 || up == groupFrequency) {
            channelWarning = null
            return
        }
        val dfs = up in 5250..5730
        val msg = "Atenção: a rede Wi-Fi Direct ficou em $groupFrequency MHz e o Wi-Fi de saída está em $up MHz" +
            (if (dfs) " (canal de 5 GHz que o Wi-Fi Direct não pode usar)" else "") +
            ". Os outros aparelhos podem não conseguir conectar. Ligue este aparelho à rede de 2,4 GHz do roteador e reinicie o compartilhamento."
        AppLog.e(msg)
        channelWarning = "Canais diferentes ($up e $groupFrequency MHz): use a rede 2,4 GHz do roteador"
    }

    private fun startProxy() {
        if (proxy != null) return
        try {
            val ex = Executors.newCachedThreadPool()
            executor = ex
            val up = upstream
            val meter = TrafficMeter()
            proxy = ProxyServer(ex, up, outNetwork, meter = meter).also { it.start() }
            billing = BillingServer(ex, meter).also { it.start() }
            if (up != null) {
                // O repetidor também paga ao aparelho de cima, pelo que repassa e pelo que usa.
                upstreamBilling = BillingClient(up.address, upstreamNetwork) { Stats.up.get() + Stats.down.get() }
                    .also { it.start() }
            }
            ready = true
            if (up == null) {
                status = if (outNetwork != null) "Compartilhando (2,4 GHz)" else "Compartilhando"
                if (outNetwork != null) {
                    AppLog.i("Para este aparelho também navegar, toque em \"Usar internet de outro aparelho\".")
                }
                AppLog.i("Pronto. No outro aparelho, toque em \"Usar internet de outro aparelho\".")
            } else {
                status = "Repetindo (${up.hops + 1} salto(s) até a internet)"
                AppLog.i("Pronto. No aparelho seguinte, toque em \"Usar internet de um repetidor\".")
            }
        } catch (e: Exception) {
            AppLog.e("Falha ao iniciar o proxy", e)
            fail("Falha ao iniciar o proxy")
        }
    }

    private fun fail(msg: String) {
        AppLog.e(msg)
        status = msg
        handler.post { stopSelf() }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        UpstreamLink.release(this)
        HomeLink.release(this)
        outNetwork = null
        running = false
        ready = false
        repeater = false
        ownTrafficViaProxy = false
        channelWarning = null
        billing?.stop()
        billing = null
        upstreamBilling?.stop()
        upstreamBilling = null
        upstreamNetwork = null
        proxy?.stop()
        proxy = null
        executor?.shutdownNow()
        executor = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            m.removeGroup(ch, null)
        }
        if (!status.startsWith("Falha")) status = "Parado"
        upstream = null
        AppLog.i("Compartilhamento encerrado")
        super.onDestroy()
    }
}
