package br.redep2p

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.VpnService
import android.os.Build
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

        /** Rede Wi-Fi comum até o compartilhador (quando não é a rede padrão do aparelho). */
        const val EXTRA_NETWORK = "network"

        @Volatile
        var running = false

        /** Compartilhador em uso e a rede até ele (usados pela tela de medição). */
        @Volatile
        var currentHost: String? = null

        @Volatile
        var currentNetwork: Network? = null

        /** Verificação contínua da origem da navegação. */
        @Volatile
        var originOk = false

        @Volatile
        var originMsg: String? = null
    }

    @Volatile
    private var monitor: Thread? = null

    /** Proxy local (127.0.0.1) que leva o tráfego ao compartilhador pela rede certa. */
    private var relay: ProxyServer? = null
    private var relayExecutor: ExecutorService? = null
    private var billing: BillingClient? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.init(applicationContext)
        Identity.init(applicationContext)
        Ledger.init(applicationContext)
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
                val network: Network? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_NETWORK, Network::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_NETWORK)
                }
                if (host == null) {
                    AppLog.e("Endereço do compartilhador ausente")
                } else {
                    startTunnel(host, network)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(host: String, network: Network?) {
        if (running) {
            AppLog.i("O túnel já está ativo")
            return
        }
        // Wi-Fi comum que não é a rede padrão: o tun2socks (em Go) não sabe escolher a rede,
        // então ele fala com um proxy local, e este (em Kotlin) fala com o compartilhador
        // preso à rede certa.
        val target = if (network == null) {
            host
        } else {
            try {
                val ex = Executors.newCachedThreadPool()
                relayExecutor = ex
                relay = ProxyServer(
                    ex,
                    upstream = Upstream(InetAddress.getByName(host), network, 0),
                    loopbackOnly = true
                ).also { it.start() }
                AppLog.i("Proxy local ativo, encaminhando ao compartilhador $host")
                "127.0.0.1"
            } catch (e: Exception) {
                AppLog.e("Não consegui iniciar o proxy local", e)
                stopRelay()
                return
            }
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
            stopRelay()
            return
        }
        // O tun2socks passa a ser o dono do descritor e o fecha ao parar.
        val fd = pfd.detachFd()
        val proxy = "socks5://$target:${Config.SOCKS_PORT}"
        running = true
        Thread {
            testProxy(target)
            try {
                AppLog.i("Iniciando o tun2socks (fd $fd, $proxy)…")
                bridge.Bridge.start(fd.toLong(), proxy, Config.MTU.toLong())
                AppLog.i("Túnel ativo: todo o tráfego passa pelo compartilhador. Teste abrindo um site.")
                if (host != "127.0.0.1") {
                    currentHost = host
                    currentNetwork = network
                    // O que a tela de medição baixa direto pelo compartilhador também é pago.
                    billing = BillingClient(InetAddress.getByName(host), network) {
                        bridge.Bridge.uploaded() + bridge.Bridge.downloaded() + Measurer.extraBytes.get()
                    }.also { it.start() }
                    startOriginMonitor(host, network)
                }
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

    /**
     * A cada 5 s confere se a navegação continua passando pela Rede P2P: a VPN do app é a
     * rede ativa e o compartilhador responde. Se o Android derrubar a VPN ou a conexão com
     * o compartilhador, o aparelho pode voltar a navegar pela rede padrão sem avisar.
     */
    private fun startOriginMonitor(host: String, network: Network?) {
        val t = Thread {
            val cm = getSystemService(ConnectivityManager::class.java)
            while (running && monitor === Thread.currentThread()) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val viaVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                val reachable = Measurer.hostReachable(host, network)
                val (ok, msg) = when {
                    !viaVpn -> false to "A VPN do app não é a rede ativa: a navegação pode estar indo pela rede padrão"
                    !reachable -> false to "O compartilhador não responde: a navegação pode não estar passando pela Rede P2P"
                    else -> true to "Navegação pela Rede P2P (compartilhador $host)"
                }
                if (ok != originOk || msg != originMsg) {
                    if (ok) AppLog.i("Origem confirmada: $msg") else AppLog.e(msg)
                }
                originOk = ok
                originMsg = msg
                try {
                    Thread.sleep(5000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        monitor = t
        t.start()
    }

    private fun stopTunnel() {
        monitor?.interrupt()
        monitor = null
        originOk = false
        originMsg = null
        currentHost = null
        currentNetwork = null
        billing?.stop()
        billing = null
        if (running) {
            running = false
            try {
                bridge.Bridge.stop()
            } catch (e: Throwable) {
                AppLog.e("Falha ao parar o túnel", e)
            }
            AppLog.i("Túnel encerrado")
        }
        stopRelay()
    }

    private fun stopRelay() {
        relay?.stop()
        relay = null
        relayExecutor?.shutdownNow()
        relayExecutor = null
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
