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
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Compartilhador: cria o grupo Wi-Fi Direct (este aparelho vira o "dono do grupo")
 * e roda o proxy que encaminha o tráfego dos clientes pela rede Wi-Fi comum.
 */
class HostService : Service() {

    companion object {
        const val ACTION_STOP = "br.redep2p.STOP_HOST"

        @Volatile
        var running = false

        @Volatile
        var status = "Parado"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var executor: ExecutorService? = null
    private var proxy: ProxyServer? = null

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
        logUpstream()
        startGroup()
        return START_STICKY
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
    private fun logUpstream() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> AppLog.e("Este aparelho está sem internet. Conecte-o a um Wi-Fi antes de compartilhar.")
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    AppLog.i("Atenção: a internet deste aparelho não vem de Wi-Fi.")
                else -> {
                    val wifi = applicationContext.getSystemService(WifiManager::class.java)
                    val freq = wifi.connectionInfo?.frequency ?: 0
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

    @SuppressLint("MissingPermission")
    private fun createGroup(m: WifiP2pManager, ch: WifiP2pManager.Channel) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(Config.NETWORK_NAME)
            .setPassphrase(Config.PASSPHRASE)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .enablePersistentMode(false)
            .build()
        m.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("Grupo Wi-Fi Direct criado")
                handler.postDelayed({ checkGroup(m, ch, 0) }, 1000)
            }

            override fun onFailure(reason: Int) {
                fail("Falha ao criar o grupo Wi-Fi Direct: ${p2pReason(reason)}")
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
            startProxy()
        }
    }

    private fun startProxy() {
        if (proxy != null) return
        try {
            val ex = Executors.newCachedThreadPool()
            executor = ex
            proxy = ProxyServer(ex).also { it.start() }
            status = "Compartilhando"
            AppLog.i("Pronto. No outro aparelho, toque em \"Usar internet de outro aparelho\".")
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
        running = false
        proxy?.stop()
        proxy = null
        executor?.shutdownNow()
        executor = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            m.removeGroup(ch, null)
        }
        if (status == "Compartilhando" || status == "Iniciando…") status = "Parado"
        AppLog.i("Compartilhamento encerrado")
        super.onDestroy()
    }
}
