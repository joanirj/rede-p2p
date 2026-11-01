package br.redep2p

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Proxy do compartilhador (ou do repetidor).
 * - SOCKS5 (TCP + UDP ASSOCIATE) na porta 1080: usado pelo app cliente.
 * - HTTP (CONNECT e requisições simples) na porta 8080: permite testar
 *   configurando o proxy manualmente no Wi-Fi do outro aparelho.
 *   Também responde em /redep2p/info com os saltos até a internet e o
 *   endereço IPv6 link-local deste aparelho (usado por repetidores).
 * Só aceita conexões da rede Wi-Fi Direct (192.168.49.x ou IPv6 link-local).
 *
 * Sem [upstream], sai direto para a internet. Com [upstream], encaminha tudo
 * ao proxy do aparelho de cima (modo repetidor).
 */
class ProxyServer(private val executor: ExecutorService, private val upstream: Upstream? = null) {

    /** Saltos até a internet: 0 no compartilhador, 1 no primeiro repetidor… */
    private val hops = upstream?.let { it.hops + 1 } ?: 0

    @Volatile
    private var running = false
    private var socksServer: ServerSocket? = null
    private var httpServer: ServerSocket? = null

    fun start() {
        running = true
        val socks = listen(Config.SOCKS_PORT)
        val http = listen(Config.HTTP_PORT)
        socksServer = socks
        httpServer = http
        executor.execute { acceptLoop(socks, "SOCKS5") { handleSocks(it) } }
        executor.execute { acceptLoop(http, "HTTP") { handleHttp(it) } }
        AppLog.i("Proxy ativo: SOCKS5 na porta ${Config.SOCKS_PORT}, HTTP na porta ${Config.HTTP_PORT}")
    }

    fun stop() {
        running = false
        socksServer.closeQuietly()
        httpServer.closeQuietly()
        socksServer = null
        httpServer = null
    }

    private fun listen(port: Int): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    private fun acceptLoop(server: ServerSocket, name: String, handler: (Socket) -> Unit) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running) AppLog.e("Falha ao aceitar conexão $name", e)
                break
            }
            if (!isAllowed(client.inetAddress)) {
                AppLog.i("Conexão recusada de ${client.inetAddress.hostAddress} (fora da rede Wi-Fi Direct)")
                client.closeQuietly()
                continue
            }
            executor.execute {
                Stats.active.incrementAndGet()
                Stats.total.incrementAndGet()
                try {
                    handler(client)
                } catch (ignored: IOException) {
                    // Conexão encerrada pelo cliente ou pelo destino: normal.
                } catch (e: Exception) {
                    AppLog.e("Erro no proxy $name", e)
                } finally {
                    Stats.active.decrementAndGet()
                    client.closeQuietly()
                }
            }
        }
    }

    private fun isAllowed(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr is Inet6Address && addr.isLinkLocalAddress) return true
        val b = (addr as? Inet4Address)?.address ?: return false
        return b[0] == 192.toByte() && b[1] == 168.toByte() && b[2] == 49.toByte()
    }

    // ---------------------------------------------------------------- SOCKS5

    private fun handleSocks(client: Socket) {
        client.tcpNoDelay = true
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val out = client.getOutputStream()

        if (input.readUnsignedByte() != 5) return
        val methods = input.readUnsignedByte()
        input.readFully(ByteArray(methods))
        out.write(byteArrayOf(5, 0)) // sem autenticação
        out.flush()

        if (input.readUnsignedByte() != 5) return
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // reservado
        val host = readSocksAddress(input)
        val port = input.readUnsignedShort()

        when (cmd) {
            1 -> socksConnect(client, input, out, host, port)
            3 -> socksUdpAssociate(client, input, out)
            else -> socksReply(out, 7) // comando não suportado
        }
    }

    private fun readSocksAddress(input: DataInputStream): String =
        when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress ?: "" }
            3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
            4 -> ByteArray(16).also { input.readFully(it) }.let { InetAddress.getByAddress(it).hostAddress ?: "" }
            else -> throw IOException("tipo de endereço SOCKS inválido")
        }

    private fun socksReply(out: OutputStream, rep: Int, addr: InetAddress? = null, port: Int = 0) {
        val a = (addr as? Inet4Address)?.address ?: byteArrayOf(0, 0, 0, 0)
        val msg = byteArrayOf(5, rep.toByte(), 0, 1) + a + byteArrayOf((port shr 8).toByte(), port.toByte())
        out.write(msg)
        out.flush()
    }

    /** Conecta ao destino: direto pela internet ou pelo aparelho de cima. */
    private fun openRemote(host: String, port: Int): Socket {
        upstream?.let { return it.connect(host, port) }
        val remote = Socket()
        try {
            remote.connect(InetSocketAddress(host, port), 10_000)
            remote.tcpNoDelay = true
            return remote
        } catch (e: IOException) {
            remote.closeQuietly()
            throw e
        }
    }

    private fun socksConnect(client: Socket, input: InputStream, out: OutputStream, host: String, port: Int) {
        val remote = try {
            openRemote(host, port)
        } catch (e: IOException) {
            socksReply(out, 5) // conexão recusada
            return
        }
        socksReply(out, 0, remote.localAddress, remote.localPort)
        try {
            relay(input, client, remote)
        } finally {
            remote.closeQuietly()
        }
    }

    private fun socksUdpAssociate(client: Socket, input: InputStream, out: OutputStream) {
        val up = upstream
        if (up != null) {
            chainedUdpAssociate(client, input, out, up)
            return
        }
        val udp = DatagramSocket(InetSocketAddress(0))
        val clientIp = client.inetAddress
        socksReply(out, 0, client.localAddress, udp.localPort)
        val worker = executor.submit { udpLoop(udp, clientIp) }
        try {
            // A associação UDP vive enquanto a conexão TCP de controle estiver aberta.
            while (input.read() >= 0) {
                // descarta
            }
        } catch (ignored: IOException) {
        } finally {
            udp.close()
            worker.cancel(true)
        }
    }

    private fun udpLoop(udp: DatagramSocket, clientIp: InetAddress) {
        val buf = ByteArray(65535)
        var clientPort = -1
        while (!udp.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                udp.receive(packet)
            } catch (ignored: IOException) {
                break
            }
            val len = packet.length
            if (packet.address == clientIp) {
                // Do cliente: cabeçalho SOCKS5 UDP + dados → envia ao destino.
                clientPort = packet.port
                if (len < 10 || buf[2].toInt() != 0) continue // fragmentação não suportada
                var pos: Int
                val dst: InetAddress = when (buf[3].toInt()) {
                    1 -> {
                        pos = 8
                        InetAddress.getByAddress(buf.copyOfRange(4, 8))
                    }
                    3 -> {
                        val n = buf[4].toInt() and 0xff
                        pos = 5 + n
                        val name = String(buf, 5, n, Charsets.US_ASCII)
                        try {
                            InetAddress.getByName(name)
                        } catch (ignored: Exception) {
                            continue
                        }
                    }
                    4 -> {
                        pos = 20
                        InetAddress.getByAddress(buf.copyOfRange(4, 20))
                    }
                    else -> continue
                }
                if (pos + 2 > len) continue
                val port = ((buf[pos].toInt() and 0xff) shl 8) or (buf[pos + 1].toInt() and 0xff)
                pos += 2
                val dataLen = len - pos
                try {
                    udp.send(DatagramPacket(buf, pos, dataLen, dst, port))
                    Stats.up.addAndGet(dataLen.toLong())
                } catch (ignored: IOException) {
                }
            } else if (clientPort > 0) {
                // Resposta da internet: acrescenta o cabeçalho e devolve ao cliente.
                val src = packet.address
                val atyp: Byte = if (src is Inet6Address) 4 else 1
                val header = byteArrayOf(0, 0, 0, atyp) + src.address +
                    byteArrayOf((packet.port shr 8).toByte(), packet.port.toByte())
                val reply = header + buf.copyOfRange(0, len)
                try {
                    udp.send(DatagramPacket(reply, reply.size, clientIp, clientPort))
                    Stats.down.addAndGet(len.toLong())
                } catch (ignored: IOException) {
                }
            }
        }
    }

    /**
     * Repetidor: os pacotes UDP do cliente já vêm com o cabeçalho SOCKS5, no mesmo
     * formato que o proxy de cima espera. Basta repassá-los sem mexer, nos dois sentidos.
     */
    private fun chainedUdpAssociate(client: Socket, input: InputStream, out: OutputStream, up: Upstream) {
        val (control, relay) = try {
            up.udpAssociate()
        } catch (e: IOException) {
            AppLog.e("UDP pelo aparelho de cima falhou", e)
            socksReply(out, 1)
            return
        }
        val local = DatagramSocket(InetSocketAddress(0))
        val upward = up.datagramSocket()
        val clientIp = client.inetAddress
        val clientPort = AtomicInteger(-1)
        socksReply(out, 0, client.localAddress, local.localPort)

        val fromClient = executor.submit {
            val buf = ByteArray(65535)
            while (!local.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    local.receive(p)
                } catch (ignored: IOException) {
                    break
                }
                if (p.address != clientIp) continue
                clientPort.set(p.port)
                try {
                    upward.send(DatagramPacket(buf, 0, p.length, relay))
                    Stats.up.addAndGet(p.length.toLong())
                } catch (ignored: IOException) {
                }
            }
        }
        val fromUpstream = executor.submit {
            val buf = ByteArray(65535)
            while (!upward.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    upward.receive(p)
                } catch (ignored: IOException) {
                    break
                }
                val port = clientPort.get()
                if (port <= 0) continue
                try {
                    local.send(DatagramPacket(buf, 0, p.length, clientIp, port))
                    Stats.down.addAndGet(p.length.toLong())
                } catch (ignored: IOException) {
                }
            }
        }
        // Encerra quando qualquer uma das conexões de controle (de baixo ou de cima) fechar.
        val watchUp = executor.submit {
            try {
                while (control.getInputStream().read() >= 0) {
                    // descarta
                }
            } catch (ignored: IOException) {
            }
            client.closeQuietly()
        }
        try {
            while (input.read() >= 0) {
                // descarta
            }
        } catch (ignored: IOException) {
        } finally {
            control.closeQuietly()
            local.close()
            upward.close()
            fromClient.cancel(true)
            fromUpstream.cancel(true)
            watchUp.cancel(true)
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun handleHttp(client: Socket) {
        client.tcpNoDelay = true
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(" ")
        if (parts.size < 3) return
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]

        if (target.startsWith(Config.INFO_PATH)) {
            sendInfo(client, out)
            return
        }

        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = splitHostPort(target, 443)
            val remote = try {
                openRemote(host, port)
            } catch (e: IOException) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            out.flush()
            try {
                relay(input, client, remote)
            } finally {
                remote.closeQuietly()
            }
            return
        }

        val uri = try {
            URI(target)
        } catch (ignored: Exception) {
            null
        }
        val host = uri?.host
        if (uri == null || host == null) {
            out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

        val newHead = StringBuilder("$method $path $version\r\n")
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val name = line.substringBefore(':').trim().lowercase()
            if (name == "proxy-connection" || name == "connection" || name == "keep-alive") continue
            newHead.append(line).append("\r\n")
        }
        newHead.append("Connection: close\r\n\r\n")

        val remote = try {
            openRemote(host, port)
        } catch (e: IOException) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        try {
            val headBytes = newHead.toString().toByteArray(Charsets.ISO_8859_1)
            remote.getOutputStream().write(headBytes)
            Stats.up.addAndGet(headBytes.size.toLong())
            relay(input, client, remote)
        } finally {
            remote.closeQuietly()
        }
    }

    /** Informa saltos até a internet e o endereço IPv6 link-local da interface Wi-Fi Direct. */
    private fun sendInfo(client: Socket, out: OutputStream) {
        val ll = try {
            NetworkInterface.getByInetAddress(client.localAddress)?.inetAddresses?.toList()
                ?.firstOrNull { it is Inet6Address && it.isLinkLocalAddress }
                ?.hostAddress?.substringBefore('%')
        } catch (e: Exception) {
            null
        } ?: ""
        val body = "hops=$hops\nll=$ll\n"
        out.write(
            ("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n$body").toByteArray()
        )
        out.flush()
        AppLog.i("Repetidor ${client.inetAddress.hostAddress} pediu informações (saltos=$hops, IPv6 ${ll.ifEmpty { "ausente" }})")
    }

    /** Lê o cabeçalho HTTP até a linha em branco. */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (buf.size() < 65536) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (matched == 4) return buf.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun splitHostPort(s: String, defaultPort: Int): Pair<String, Int> {
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            val host = s.substring(1, close)
            val port = s.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return host to port
        }
        val i = s.lastIndexOf(':')
        if (i < 0) return s to defaultPort
        return s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: defaultPort)
    }

    // ----------------------------------------------------------------- relay

    /** Copia nos dois sentidos até as duas pontas encerrarem. */
    private fun relay(clientIn: InputStream, client: Socket, remote: Socket) {
        val upTask = executor.submit {
            copy(clientIn, remote.getOutputStream(), Stats.up)
            try {
                remote.shutdownOutput()
            } catch (ignored: Exception) {
            }
        }
        copy(remote.getInputStream(), client.getOutputStream(), Stats.down)
        try {
            client.shutdownOutput()
        } catch (ignored: Exception) {
        }
        try {
            upTask.get(30, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
        }
        client.closeQuietly()
        remote.closeQuietly()
    }

    private fun copy(input: InputStream, output: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (ignored: IOException) {
        }
    }
}
