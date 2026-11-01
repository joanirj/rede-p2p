package br.redep2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * Cliente: entra no grupo Wi-Fi Direct do compartilhador usando o nome e a senha
 * conhecidos (sem precisar procurar aparelhos nem aceitar convite).
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

    @SuppressLint("MissingPermission")
    fun connect(context: Context, onReady: (String) -> Unit) {
        val app = context.applicationContext
        val m = app.getSystemService(WifiP2pManager::class.java)
        if (m == null) {
            AppLog.e("Este aparelho não tem Wi-Fi Direct")
            return
        }
        manager = m
        val ch = channel ?: m.initialize(app, Looper.getMainLooper()) {
            AppLog.e("O sistema encerrou o canal Wi-Fi Direct")
            channel = null
        }
        channel = ch
        connecting = true
        hostAddress = null

        // Plano B: o aparelho já está na rede do compartilhador como Wi-Fi comum
        // (conectado manualmente em Configurações → Wi-Fi).
        val legacy = legacyGateway(app)
        if (legacy != null) {
            AppLog.i("Este aparelho está na rede do compartilhador como Wi-Fi comum")
            ready(legacy, onReady)
            return
        }

        // Se já estiver num grupo, usa a conexão existente.
        m.requestConnectionInfo(ch) { info ->
            if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                ready(info.groupOwnerAddress.hostAddress ?: return@requestConnectionInfo, onReady)
            } else {
                join(m, ch, onReady)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun join(m: WifiP2pManager, ch: WifiP2pManager.Channel, onReady: (String) -> Unit) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(Config.NETWORK_NAME)
            .setPassphrase(Config.PASSPHRASE)
            .build()
        AppLog.i("Procurando a rede ${Config.NETWORK_NAME}…")
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("Pedido de conexão aceito pelo sistema, aguardando…")
                handler.postDelayed({ poll(m, ch, onReady, 0) }, 1000)
            }

            override fun onFailure(reason: Int) {
                connecting = false
                AppLog.e("Não consegui pedir a conexão: ${p2pReason(reason)}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun poll(m: WifiP2pManager, ch: WifiP2pManager.Channel, onReady: (String) -> Unit, attempt: Int) {
        if (!connecting) return
        m.requestConnectionInfo(ch) { info ->
            val addr = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && !info.isGroupOwner && addr != null) {
                ready(addr, onReady)
            } else if (attempt < 40) {
                handler.postDelayed({ poll(m, ch, onReady, attempt + 1) }, 1000)
            } else {
                connecting = false
                AppLog.e("Não encontrei o compartilhador em 40 s pelo Wi-Fi Direct.")
                AppLog.i("Plano B: em Configurações → Wi-Fi, conecte à rede ${Config.NETWORK_NAME} (senha ${Config.PASSPHRASE}), volte aqui e toque de novo em \"Usar internet de outro aparelho\".")
                m.cancelConnect(ch, null)
            }
        }
    }

    /** Gateway 192.168.49.x de uma rede Wi-Fi comum, se o aparelho estiver nela. */
    @Suppress("DEPRECATION")
    private fun legacyGateway(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(network) ?: continue
            for (route in lp.routes) {
                val gw = route.gateway as? Inet4Address ?: continue
                val b = gw.address
                if (route.isDefaultRoute && b[0] == 192.toByte() && b[1] == 168.toByte() && b[2] == 49.toByte()) {
                    return gw.hostAddress
                }
            }
        }
        return null
    }

    private fun ready(address: String, onReady: (String) -> Unit) {
        connecting = false
        hostAddress = address
        AppLog.i("Conectado ao compartilhador ($address)")
        onReady(address)
    }

    fun disconnect() {
        connecting = false
        hostAddress = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) {
            m.cancelConnect(ch, null)
            m.removeGroup(ch, null)
        }
    }
}
