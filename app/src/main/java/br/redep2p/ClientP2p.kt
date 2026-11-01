package br.redep2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper

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
    fun connect(context: Context, networkName: String, onReady: (String) -> Unit) {
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
        val legacy = findDirectWifi(app)
        if (legacy != null) {
            AppLog.i("Este aparelho está na rede do compartilhador como Wi-Fi comum")
            ready(legacy.gateway, onReady)
            return
        }

        // Se já estiver num grupo, usa a conexão existente.
        m.requestConnectionInfo(ch) { info ->
            if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                ready(info.groupOwnerAddress.hostAddress ?: return@requestConnectionInfo, onReady)
            } else {
                join(m, ch, networkName, onReady)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun join(m: WifiP2pManager, ch: WifiP2pManager.Channel, networkName: String, onReady: (String) -> Unit) {
        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(Config.PASSPHRASE)
            .build()
        AppLog.i("Procurando a rede $networkName…")
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("Pedido de conexão aceito pelo sistema, aguardando…")
                handler.postDelayed({ poll(m, ch, networkName, onReady, 0) }, 1000)
            }

            override fun onFailure(reason: Int) {
                connecting = false
                AppLog.e("Não consegui pedir a conexão: ${p2pReason(reason)}")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun poll(m: WifiP2pManager, ch: WifiP2pManager.Channel, networkName: String, onReady: (String) -> Unit, attempt: Int) {
        if (!connecting) return
        m.requestConnectionInfo(ch) { info ->
            val addr = info?.groupOwnerAddress?.hostAddress
            if (info != null && info.groupFormed && !info.isGroupOwner && addr != null) {
                ready(addr, onReady)
            } else if (attempt < 40) {
                handler.postDelayed({ poll(m, ch, networkName, onReady, attempt + 1) }, 1000)
            } else {
                connecting = false
                AppLog.e("Não encontrei a rede $networkName em 40 s pelo Wi-Fi Direct.")
                AppLog.i("Plano B: em Configurações → Wi-Fi, conecte à rede $networkName (senha ${Config.PASSPHRASE}), volte aqui e toque de novo no botão.")
                m.cancelConnect(ch, null)
            }
        }
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
