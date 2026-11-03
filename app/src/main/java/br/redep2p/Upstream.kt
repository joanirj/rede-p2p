package br.redep2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Rede Wi-Fi comum que, na verdade, é a rede Wi-Fi Direct de outro aparelho do app. */
data class DirectWifi(val network: Network, val gateway: String, val iface: String?)

/**
 * Procura uma rede Wi-Fi conectada que seja de um aparelho do app: roteador 192.168.49.x
 * (grupo Wi-Fi Direct do Android) ou 192.168.137.x (Hotspot Móvel do Windows).
 */
@Suppress("DEPRECATION")
fun findDirectWifi(context: Context): DirectWifi? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    for (network in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        val lp = cm.getLinkProperties(network) ?: continue
        for (route in lp.routes) {
            val gw = route.gateway as? Inet4Address ?: continue
            val b = gw.address
            val ours = b[0] == 192.toByte() && b[1] == 168.toByte() && (b[2] == 49.toByte() || b[2] == 137.toByte())
            if (route.isDefaultRoute && ours) {
                return DirectWifi(network, gw.hostAddress ?: continue, lp.interfaceName)
            }
        }
    }
    return null
}

/**
 * Conexão do repetidor à rede do aparelho de cima, feita pelo próprio app
 * (sem a pessoa ir às configurações de Wi-Fi). O Android mostra uma janela
 * "Conectar a DIRECT-RP-…?" e basta um toque.
 */
object UpstreamLink {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var current: DirectWifi? = null
        private set

    fun request(
        context: Context,
        onReady: (DirectWifi) -> Unit,
        onFail: (String) -> Unit,
        onLost: () -> Unit,
        /** Nome exato da rede; sem ele, aceita qualquer rede DIRECT-RP-… */
        ssid: String? = null
    ) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        release(app)
        val specBuilder = WifiNetworkSpecifier.Builder().setWpa2Passphrase(Config.PASSPHRASE)
        if (ssid != null) {
            specBuilder.setSsid(ssid)
        } else {
            specBuilder.setSsidPattern(PatternMatcher("DIRECT-RP-", PatternMatcher.PATTERN_PREFIX))
        }
        val spec = specBuilder.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var reported = false

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (reported) return
                val gw = lp.routes.firstNotNullOfOrNull { r ->
                    (r.gateway as? Inet4Address)?.takeIf { r.isDefaultRoute }
                } ?: return
                // O nome DIRECT-RP-… já identifica a rede; o endereço do roteador pode ser
                // qualquer um (no Android é 192.168.49.1; num PC compartilhando, outro).
                val address = gw.hostAddress ?: return
                reported = true
                val wifi = DirectWifi(network, address, lp.interfaceName)
                current = wifi
                AppLog.i("Conectado à rede do aparelho de cima (${lp.interfaceName})")
                onReady(wifi)
            }

            override fun onUnavailable() {
                callback = null
                onFail("Não conectei à rede de nenhum aparelho do app (pedido recusado ou rede fora de alcance)")
            }

            override fun onLost(network: Network) {
                if (current?.network == network) {
                    current = null
                    AppLog.e("A conexão com a rede do aparelho de cima caiu")
                    onLost()
                }
            }
        }
        callback = cb
        AppLog.i("Pedindo ao Android para entrar na rede ${ssid ?: "de um aparelho do app (DIRECT-RP-…)"} como Wi-Fi comum. Confirme na janela que aparecer.")
        cm.requestNetwork(request, cb, 60_000)
    }

    fun release(context: Context) {
        val cb = callback ?: return
        callback = null
        current = null
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (ignored: Exception) {
        }
    }
}

/**
 * Proxy SOCKS5 do aparelho de cima, usado pelo repetidor.
 *
 * Todo dono de grupo Wi-Fi Direct no Android tem o endereço 192.168.49.1. Quando o
 * repetidor cria a própria rede, ele também passa a ter esse endereço, e falar com
 * 192.168.49.1 seria falar consigo mesmo. Por isso o repetidor fala com o aparelho
 * de cima pelo endereço IPv6 link-local dele (fe80::…), que é único, com o escopo
 * da interface Wi-Fi comum.
 */
class Upstream(val address: InetAddress, private val network: Network?, val hops: Int) {

    private fun socket(): Socket = Socket().also { s -> bind { network?.bindSocket(s) } }

    fun datagramSocket(): DatagramSocket =
        DatagramSocket(InetSocketAddress(0)).also { ds -> bind { network?.bindSocket(ds) } }

    private inline fun bind(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AppLog.e("Não consegui prender o socket à rede de cima", e)
        }
    }

    /** Abre um túnel TCP até host:port pelo proxy de cima. */
    fun connect(host: String, port: Int): Socket {
        val s = socket()
        try {
            s.connect(InetSocketAddress(address, Config.SOCKS_PORT), 10_000)
            s.tcpNoDelay = true
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            handshake(input, out)
            out.write(request(1, host, port))
            out.flush()
            val (rep, _) = readReply(input)
            if (rep != 0) throw IOException("o aparelho de cima recusou a conexão (código $rep)")
            return s
        } catch (e: IOException) {
            s.closeQuietly()
            throw e
        }
    }

    /** Pede uma associação UDP ao proxy de cima: devolve o socket de controle e o endereço do relay. */
    fun udpAssociate(): Pair<Socket, InetSocketAddress> {
        val s = socket()
        try {
            s.connect(InetSocketAddress(address, Config.SOCKS_PORT), 10_000)
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            handshake(input, out)
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            val (rep, port) = readReply(input)
            if (rep != 0) throw IOException("o aparelho de cima recusou o UDP (código $rep)")
            // O endereço devolvido pode ser 0.0.0.0: usamos sempre o endereço único conhecido.
            return s to InetSocketAddress(address, port)
        } catch (e: IOException) {
            s.closeQuietly()
            throw e
        }
    }

    private fun handshake(input: DataInputStream, out: OutputStream) {
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) {
            throw IOException("o aparelho de cima não respondeu como SOCKS5")
        }
    }

    private fun request(cmd: Int, host: String, port: Int): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(5)
        b.write(cmd)
        b.write(0)
        when {
            IPV4.matches(host) -> {
                b.write(1)
                b.write(InetAddress.getByName(host).address)
            }
            host.contains(':') -> {
                b.write(4)
                b.write(InetAddress.getByName(host.substringBefore('%')).address)
            }
            else -> {
                val name = host.toByteArray(Charsets.US_ASCII)
                b.write(3)
                b.write(name.size)
                b.write(name)
            }
        }
        b.write(port shr 8)
        b.write(port and 0xff)
        return b.toByteArray()
    }

    /** Lê a resposta SOCKS5: devolve (código, porta). */
    private fun readReply(input: DataInputStream): Pair<Int, Int> {
        if (input.readUnsignedByte() != 5) throw IOException("resposta SOCKS5 inválida")
        val rep = input.readUnsignedByte()
        input.readUnsignedByte()
        when (input.readUnsignedByte()) {
            1 -> input.readFully(ByteArray(4))
            3 -> input.readFully(ByteArray(input.readUnsignedByte()))
            4 -> input.readFully(ByteArray(16))
            else -> throw IOException("endereço SOCKS5 inválido")
        }
        val port = input.readUnsignedShort()
        return rep to port
    }

    companion object {
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /**
         * Consulta o aparelho de cima pelo IPv4 (antes de criar a própria rede, quando
         * ainda não há conflito) e descobre quantos saltos ele está da internet e o
         * endereço IPv6 link-local dele.
         */
        fun discover(wifi: DirectWifi): Upstream {
            val s = Socket()
            try {
                try {
                    wifi.network.bindSocket(s)
                } catch (e: Exception) {
                    AppLog.e("Não consegui prender o socket à rede de cima", e)
                }
                s.connect(InetSocketAddress(wifi.gateway, Config.HTTP_PORT), 5000)
                s.soTimeout = 5000
                val out = s.getOutputStream()
                out.write("GET ${Config.INFO_PATH} HTTP/1.0\r\nHost: ${wifi.gateway}\r\n\r\n".toByteArray())
                out.flush()
                val text = s.getInputStream().bufferedReader().readText()
                val values = text.substringAfter("\r\n\r\n").lines()
                    .mapNotNull { line ->
                        val parts = line.split('=', limit = 2)
                        if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                    }
                    .toMap()
                val hops = values["hops"]?.toIntOrNull()
                    ?: throw IOException("o aparelho de cima não respondeu. Ele está com a versão nova do app?")
                val ll = values["ll"].orEmpty()
                if (ll.isEmpty()) throw IOException("o aparelho de cima não tem endereço IPv6 local")
                val nif = wifi.iface?.let { NetworkInterface.getByName(it) }
                    ?: throw IOException("interface Wi-Fi deste aparelho não encontrada")
                val raw = InetAddress.getByName(ll.substringBefore('%')).address
                val scoped = Inet6Address.getByAddress(null, raw, nif)
                return Upstream(scoped, wifi.network, hops)
            } finally {
                s.closeQuietly()
            }
        }
    }
}
