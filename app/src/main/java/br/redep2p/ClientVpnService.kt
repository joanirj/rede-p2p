package br.redep2p

import android.content.Intent
import android.net.VpnService

/**
 * Cliente: VPN local que captura todo o tráfego do aparelho e o envia,
 * via tun2socks, ao proxy SOCKS5 do compartilhador.
 * O próprio app fica fora da VPN, para que a conexão com o compartilhador
 * saia direto pela rede Wi-Fi Direct.
 */
class ClientVpnService : VpnService() {

    companion object {
        const val ACTION_START = "br.redep2p.START_CLIENT"
        const val ACTION_STOP = "br.redep2p.STOP_CLIENT"
        const val EXTRA_HOST = "host"

        @Volatile
        var running = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                if (host == null) {
                    AppLog.e("Endereço do compartilhador ausente")
                } else {
                    startTunnel(host)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String) {
        if (running) {
            AppLog.i("O túnel já está ativo")
            return
        }
        val pfd = try {
            Builder()
                .setSession("Rede P2P")
                .setMtu(Config.MTU)
                .addAddress("10.88.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)
                .establish()
        } catch (e: Exception) {
            AppLog.e("Não consegui criar a VPN", e)
            null
        }
        if (pfd == null) {
            AppLog.e("A VPN não foi autorizada pelo sistema")
            return
        }
        // O tun2socks passa a ser o dono do descritor e o fecha ao parar.
        val fd = pfd.detachFd()
        val proxy = "socks5://$host:${Config.SOCKS_PORT}"
        running = true
        Thread {
            try {
                bridge.Bridge.start(fd.toLong(), proxy, Config.MTU.toLong())
                AppLog.i("Túnel ativo: todo o tráfego passa pelo compartilhador. Teste abrindo um site.")
            } catch (e: Throwable) {
                running = false
                AppLog.e("Falha ao iniciar o túnel", e)
            }
        }.start()
    }

    private fun stopTunnel() {
        if (!running) return
        running = false
        try {
            bridge.Bridge.stop()
        } catch (e: Throwable) {
            AppLog.e("Falha ao parar o túnel", e)
        }
        AppLog.i("Túnel encerrado")
    }

    override fun onRevoke() {
        AppLog.i("A VPN foi desligada pelo sistema")
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
