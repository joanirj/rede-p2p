package br.redep2p

import android.content.Intent
import android.net.VpnService
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

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

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
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
            testProxy(host)
            try {
                AppLog.i("Iniciando o tun2socks (fd $fd, $proxy)…")
                bridge.Bridge.start(fd.toLong(), proxy, Config.MTU.toLong())
                AppLog.i("Túnel ativo: todo o tráfego passa pelo compartilhador. Teste abrindo um site.")
            } catch (e: Throwable) {
                running = false
                AppLog.e("Falha ao iniciar o túnel", e)
            }
        }.start()
    }

    /**
     * Diagnóstico: abre uma página pelo proxy do compartilhador sem usar a VPN.
     * Se isto funciona e a navegação não, o problema está no túnel; se falha, na rede.
     */
    private fun testProxy(host: String) {
        val socket = Socket()
        try {
            val t0 = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, Config.SOCKS_PORT), 5000)
            socket.soTimeout = 10_000
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) throw IOException("resposta SOCKS inesperada")
            val name = "example.com".toByteArray()
            out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(0, 80))
            out.flush()
            val reply = ByteArray(10)
            input.readFully(reply)
            if (reply[1].toInt() != 0) throw IOException("compartilhador não alcançou a internet (código ${reply[1]})")
            out.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val status = input.bufferedReader().readLine() ?: "(sem resposta)"
            AppLog.i("Teste sem VPN pelo compartilhador: $status em ${System.currentTimeMillis() - t0} ms")
        } catch (e: Exception) {
            AppLog.e("Teste sem VPN pelo compartilhador falhou", e)
        } finally {
            socket.closeQuietly()
        }
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
