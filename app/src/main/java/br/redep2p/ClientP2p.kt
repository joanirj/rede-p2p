package br.redep2p

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

/**
 * Cliente: chega ao compartilhador por um de dois caminhos.
 *
 * 1. Wi-Fi Direct: entra no grupo pelo nome e senha conhecidos (só entre Androids).
 * 2. Wi-Fi comum: se o primeiro falhar, pede ao Android a rede DIRECT-RP-… como
 *    um Wi-Fi normal. É o mesmo caminho que um computador usa, e funciona também
 *    quando o compartilhador for um PC.
 *
 * No caminho 2, a rede não é a padrão do aparelho; [connect] entrega a [Network]
 * para o app prender a ela os sockets que vão ao compartilhador.
 */
object ClientP2p {

    private val handler = Handler(Looper.getMainLooper())
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @Volatile
    var hostAddress: String? = null
        private set

    @Volatile
    var connecting = false
        private set

    /** Caminho em uso: "Wi-Fi Direct" ou "Wi-Fi comum". */
    @Volatile
    var path: String? = null
        private set

    @SuppressLint("MissingPermission")
    /**
     * [wifiOnly]: vai direto como Wi-Fi comum, sem tentar o Wi-Fi Direct. É o caso de
     * notebooks e PCs, que compartilham pelo Hotspot Móvel do Windows.
     */
    fun connect(context: Context, networkName: String, wifiOnly: Boolean = false, onReady: (String, Network?) -> Unit) {
        val app = context.applicationContext
        connecting = true
        hostAddress = null
        path = null

        // Já está na rede do compartilhador como Wi-Fi comum (conectado nas configurações).
        val legacy = findDirectWifi(app)
        if (legacy != null) {
            AppLog.i("Este aparelho já está na rede do compartilhador como Wi-Fi comum")
            ready(legacy.gateway, legacy.network, "Wi-Fi comum", onReady)
            return
        }

        if (wifiOnly) {
            joinAsWifi(app, networkName, onReady)
            return
        }

        val m = app.getSystemService(WifiP2pManager::class.java)
        if (m == null) {
            AppLog.i("Este aparelho não tem Wi-Fi Direct; usando Wi-Fi comum")
            joinAsWifi(app, networkName, onReady)
            return
        }
        manager = m
        val ch = channel ?: m.initialize(app, Looper.getMainLooper()) {
            AppLog.e("O sistema encerrou o canal Wi-Fi Direct")
            channel = null
        }
        channel = ch

        // Se já estiver num grupo, usa a conexão existente.
        m.requestConnectionInfo(ch) { info ->
            val addr = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && !info.isGroupOwner && addr != null) {
                ready(addr, null, "Wi-Fi Direct", onReady)
            } else {
                join(app, m, ch, networkName, onReady)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun join(
        app: Context,
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        networkName: String,
        onReady: (String, Network?) -> Unit,
        attempt: Int = 0
    ) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(Config.PASSPHRASE)
            .build()
        AppLog.i("Procurando a rede $networkName pelo Wi-Fi Direct…")
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("Pedido de conexão aceito pelo sistema, aguardando…")
                handler.postDelayed({ poll(app, m, ch, networkName, onReady, 0) }, 1000)
            }

            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY && attempt < 3) {
                    // Outra operação Wi-Fi Direct em andamento: cancela tudo e tenta de novo.
                    AppLog.i("Wi-Fi Direct ocupado; liberando e tentando de novo (${attempt + 1}/3)…")
                    m.stopPeerDiscovery(ch, null)
                    m.cancelConnect(ch, null)
                    m.removeGroup(ch, null)
                    handler.postDelayed({ join(app, m, ch, networkName, onReady, attempt + 1) }, 2500)
                    return
                }
                AppLog.i("Wi-Fi Direct não disponível (${p2pReason(reason)}); tentando como Wi-Fi comum")
                joinAsWifi(app, networkName, onReady)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun poll(
        app: Context,
        m: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        networkName: String,
        onReady: (String, Network?) -> Unit,
        attempt: Int
    ) {
        if (!connecting) return
        m.requestConnectionInfo(ch) { info ->
            val addr = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && !info.isGroupOwner && addr != null) {
                ready(addr, null, "Wi-Fi Direct", onReady)
            } else if (attempt < 25) {
                handler.postDelayed({ poll(app, m, ch, networkName, onReady, attempt + 1) }, 1000)
            } else {
                AppLog.i("Wi-Fi Direct não conectou em 25 s; tentando como Wi-Fi comum")
                m.cancelConnect(ch, null)
                joinAsWifi(app, networkName, onReady)
            }
        }
    }

    /** Caminho 2: pede ao Android a rede como Wi-Fi comum (uma confirmação na tela). */
    private fun joinAsWifi(app: Context, networkName: String, onReady: (String, Network?) -> Unit) {
        if (!connecting) return
        UpstreamLink.request(
            app,
            onReady = { wifi ->
                handler.post { if (connecting) ready(wifi.gateway, wifi.network, "Wi-Fi comum", onReady) }
            },
            onFail = { msg ->
                handler.post {
                    connecting = false
                    AppLog.e(msg)
                }
            },
            onLost = {
                handler.post {
                    AppLog.e("A conexão Wi-Fi com o compartilhador caiu")
                    app.startService(Intent(app, ClientVpnService::class.java).setAction(ClientVpnService.ACTION_STOP))
                }
            },
            ssid = networkName
        )
    }

    private fun ready(address: String, network: Network?, how: String, onReady: (String, Network?) -> Unit) {
        connecting = false
        hostAddress = address
        path = how
        AppLog.i("Conectado ao compartilhador ($address) por $how")
        onReady(address, network)
    }

    fun disconnect(context: Context) {
        connecting = false
        hostAddress = null
        path = null
        UpstreamLink.release(context.applicationContext)
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            m.cancelConnect(ch, null)
            m.removeGroup(ch, null)
        }
    }
}
