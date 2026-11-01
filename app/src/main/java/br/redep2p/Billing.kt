package br.redep2p

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import br.redep2p.crypto.Schnorr
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

/*
 * Créditos — prova de conceito, sem servidor.
 *
 * O cliente assina recibos cumulativos ("já recebi N bytes e devo A créditos nesta sessão")
 * a cada 1 MB cobrado. O compartilhador só aceita recibos com assinatura válida e corta
 * quem passar de 2 MB sem pagar. Os saldos ficam em cada aparelho (Ledger); na próxima
 * etapa, o último recibo de cada sessão será liquidado num servidor ou num canal de
 * pagamento na Kaspa — o formato do recibo já foi pensado para isso.
 */

/** Valores em mili-créditos (1 crédito = 1000). */
object Pricing {
    const val MB = 1_048_576L
    const val PRICE_MILLI_PER_MB = 1_000L
    const val MAX_ACCEPTED_PRICE_MILLI_PER_MB = 5_000L
    const val BILL_STEP_BYTES = MB
    const val UNPAID_LIMIT_BYTES = 2 * MB
    const val INITIAL_BALANCE_MILLI = 100_000L
    const val TEST_TOPUP_MILLI = 100_000L

    fun priceFor(bytes: Long, priceMilliPerMb: Long): Long =
        (bytes * priceMilliPerMb + MB - 1) / MB

    fun credits(milli: Long): String = String.format(Locale("pt", "BR"), "%.2f", milli / 1000.0)

    fun megabytes(bytes: Long): String = String.format(Locale("pt", "BR"), "%.1f MB", bytes / MB.toDouble())
}

/** Saldo local deste aparelho (prova de conceito). */
object Ledger {
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("ledger", Context.MODE_PRIVATE)
        if (!p.contains("balance")) p.edit().putLong("balance", Pricing.INITIAL_BALANCE_MILLI).apply()
        prefs = p
    }

    val balance: Long get() = prefs?.getLong("balance", 0) ?: 0
    val earned: Long get() = prefs?.getLong("earned", 0) ?: 0
    val spent: Long get() = prefs?.getLong("spent", 0) ?: 0

    @Synchronized
    fun credit(milli: Long) {
        val p = prefs ?: return
        p.edit().putLong("balance", balance + milli).putLong("earned", earned + milli).apply()
    }

    @Synchronized
    fun debit(milli: Long): Boolean {
        val p = prefs ?: return false
        if (balance < milli) return false
        p.edit().putLong("balance", balance - milli).putLong("spent", spent + milli).apply()
        return true
    }

    @Synchronized
    fun topUp(milli: Long) {
        val p = prefs ?: return
        p.edit().putLong("balance", balance + milli).apply()
    }
}

/** Mensagens assinadas. O hash inclui um rótulo de domínio para não confundir tipos. */
object Receipts {
    private fun be64(v: Long) = ByteBuffer.allocate(8).putLong(v).array()

    fun offerHash(session: ByteArray, host: ByteArray, client: ByteArray, priceMilliPerMb: Long): ByteArray =
        Schnorr.sha256("RedeP2P/oferta/v1".toByteArray(), session, host, client, be64(priceMilliPerMb))

    /** Recibo cumulativo: é este hash que um contrato na blockchain verificaria. */
    fun receiptHash(session: ByteArray, host: ByteArray, client: ByteArray, seq: Long, bytes: Long, amountMilli: Long): ByteArray =
        Schnorr.sha256("RedeP2P/recibo/v1".toByteArray(), session, host, client, be64(seq), be64(bytes), be64(amountMilli))
}

/** Estado exibido na tela. */
object BillingState {
    @Volatile
    var hostSummary: String? = null

    @Volatile
    var clientSummary: String? = null
}

fun normalizeIp(addr: InetAddress): String = (addr.hostAddress ?: "").substringBefore('%')

/** Bytes que cada cliente (por endereço IP) passou pelo proxy, e se ainda pode usar. */
class TrafficMeter {
    private val used = ConcurrentHashMap<String, AtomicLong>()

    /** Recebe (ip, bytes usados) e diz se o cliente ainda pode usar. */
    @Volatile
    var policy: ((String, Long) -> Boolean)? = null

    fun add(ip: String, bytes: Long) {
        used.getOrPut(ip) { AtomicLong() }.addAndGet(bytes)
    }

    fun used(ip: String): Long = used[ip]?.get() ?: 0

    fun allowed(ip: String): Boolean = policy?.invoke(ip, used(ip)) ?: true
}

private fun JSONObject.send(writer: BufferedWriter) {
    synchronized(writer) {
        writer.write(toString())
        writer.write("\n")
        writer.flush()
    }
}

/**
 * Compartilhador: recebe os clientes na porta de controle, envia ofertas e contas,
 * confere os recibos e libera ou bloqueia o tráfego de cada um.
 */
class BillingServer(private val executor: ExecutorService, private val meter: TrafficMeter) {

    private class Session(
        val ip: String,
        val client: ByteArray,
        val id: ByteArray,
        val writer: BufferedWriter,
        val baseUsed: Long
    ) {
        @Volatile var paidBytes = 0L
        @Volatile var paidAmount = 0L
        @Volatile var seq = 0L
        @Volatile var billedBytes = 0L
        @Volatile var warnedBlocked = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        running = true
        meter.policy = { ip, used -> allow(ip, used) }
        val s = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(Config.CONTROL_PORT))
        }
        server = s
        executor.execute { acceptLoop(s) }
        executor.execute { tickLoop() }
        AppLog.i("Cobrança ativa: ${Pricing.credits(Pricing.PRICE_MILLI_PER_MB)} crédito(s) por MB, porta ${Config.CONTROL_PORT}")
        refreshSummary()
    }

    fun stop() {
        running = false
        meter.policy = null
        server.closeQuietly()
        sessions.values.forEach { it.writer.closeQuietly() }
        sessions.clear()
        BillingState.hostSummary = null
    }

    private fun allow(ip: String, used: Long): Boolean {
        val s = sessions[ip]
        val unpaid = if (s == null) used else used - s.baseUsed - s.paidBytes
        val ok = unpaid <= Pricing.UNPAID_LIMIT_BYTES
        if (!ok && s != null && !s.warnedBlocked) {
            s.warnedBlocked = true
            AppLog.i("Cliente ${Identity.shortId(s.client)} passou de 2 MB sem pagar: tráfego bloqueado até chegar um recibo")
        }
        return ok
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            executor.execute {
                try {
                    handle(socket)
                } catch (ignored: IOException) {
                } catch (e: Exception) {
                    AppLog.e("Erro na cobrança", e)
                } finally {
                    socket.closeQuietly()
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val ip = normalizeIp(socket.inetAddress)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        val hello = JSONObject(reader.readLine() ?: return)
        if (hello.optString("t") != "hello") return
        val client = Schnorr.unhex(hello.getString("client"))
        if (client.size != 32) return
        val id = ByteArray(16).also { random.nextBytes(it) }
        val session = Session(ip, client, id, writer, meter.used(ip))
        sessions[ip]?.writer?.closeQuietly()
        sessions[ip] = session
        val offerSig = Identity.sign(Receipts.offerHash(id, Identity.publicKey, client, Pricing.PRICE_MILLI_PER_MB))
        JSONObject()
            .put("t", "offer")
            .put("session", Schnorr.hex(id))
            .put("host", Identity.publicHex)
            .put("price", Pricing.PRICE_MILLI_PER_MB)
            .put("sig", Schnorr.hex(offerSig))
            .send(writer)
        AppLog.i("Cliente ${Identity.shortId(client)} ($ip) abriu uma sessão de cobrança")
        refreshSummary()
        try {
            while (true) {
                val line = reader.readLine() ?: break
                val msg = JSONObject(line)
                when (msg.optString("t")) {
                    "receipt" -> onReceipt(session, msg)
                    "dispute" -> AppLog.e("Cliente ${Identity.shortId(client)} contestou a conta: ${msg.optString("reason")}")
                    "low_balance" -> AppLog.i("Cliente ${Identity.shortId(client)} está sem saldo")
                }
            }
        } finally {
            if (sessions[ip] === session) sessions.remove(ip)
            AppLog.i(
                "Sessão de ${Identity.shortId(client)} encerrada: ${Pricing.megabytes(session.paidBytes)} pagos, " +
                    "${Pricing.credits(session.paidAmount)} créditos recebidos"
            )
            refreshSummary()
        }
    }

    private fun onReceipt(s: Session, msg: JSONObject) {
        val seq = msg.getLong("seq")
        val bytes = msg.getLong("bytes")
        val amount = msg.getLong("amount")
        val sig = Schnorr.unhex(msg.getString("sig"))
        val usedNow = meter.used(s.ip) - s.baseUsed
        val hash = Receipts.receiptHash(s.id, Identity.publicKey, s.client, seq, bytes, amount)
        val problem = when {
            seq <= s.seq -> "sequência repetida"
            bytes < s.paidBytes -> "bytes menores que o último recibo"
            bytes > usedNow + 65_536 -> "recibo acima do que foi usado"
            amount != Pricing.priceFor(bytes, Pricing.PRICE_MILLI_PER_MB) -> "valor não confere com o preço"
            !Schnorr.verify(hash, s.client, sig) -> "assinatura inválida"
            else -> null
        }
        if (problem != null) {
            AppLog.e("Recibo recusado de ${Identity.shortId(s.client)}: $problem")
            return
        }
        val delta = amount - s.paidAmount
        s.seq = seq
        s.paidBytes = bytes
        s.paidAmount = amount
        s.warnedBlocked = false
        Ledger.credit(delta)
        refreshSummary()
    }

    private fun tickLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
            for (s in sessions.values) {
                val usedNow = meter.used(s.ip) - s.baseUsed
                if (usedNow - s.billedBytes >= Pricing.BILL_STEP_BYTES) {
                    s.billedBytes = usedNow
                    try {
                        JSONObject()
                            .put("t", "bill")
                            .put("bytes", usedNow)
                            .put("amount", Pricing.priceFor(usedNow, Pricing.PRICE_MILLI_PER_MB))
                            .send(s.writer)
                    } catch (ignored: IOException) {
                    }
                }
            }
            refreshSummary()
        }
    }

    private fun refreshSummary() {
        BillingState.hostSummary = if (sessions.isEmpty()) {
            "Nenhum cliente pagante conectado"
        } else {
            sessions.values.joinToString("\n") { s ->
                val used = meter.used(s.ip) - s.baseUsed
                "Cliente ${Identity.shortId(s.client)}: usou ${Pricing.megabytes(used)}, pagou ${Pricing.credits(s.paidAmount)} créditos"
            }
        }
    }
}

/**
 * Cliente (ou repetidor pagando ao aparelho de cima): abre a sessão de cobrança,
 * confere cada conta contra o que ele mesmo mediu e assina os recibos.
 */
class BillingClient(
    private val host: InetAddress,
    private val network: Network?,
    /** Total de bytes medidos por este aparelho (enviados + recebidos). */
    private val measure: () -> Long
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var stopped = false

    fun start() {
        Thread { run() }.start()
    }

    fun stop() {
        stopped = true
        socket.closeQuietly()
        BillingState.clientSummary = null
    }

    private fun run() {
        val s = Socket()
        socket = s
        try {
            network?.let {
                try {
                    it.bindSocket(s)
                } catch (e: Exception) {
                    AppLog.e("Não consegui prender a cobrança à rede do compartilhador", e)
                }
            }
            s.connect(InetSocketAddress(host, Config.CONTROL_PORT), 10_000)
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            JSONObject().put("t", "hello").put("v", 1).put("client", Identity.publicHex).send(writer)

            val offer = JSONObject(reader.readLine() ?: throw IOException("sem oferta"))
            val session = Schnorr.unhex(offer.getString("session"))
            val hostKey = Schnorr.unhex(offer.getString("host"))
            val price = offer.getLong("price")
            val offerOk = Schnorr.verify(
                Receipts.offerHash(session, hostKey, Identity.publicKey, price),
                hostKey,
                Schnorr.unhex(offer.getString("sig"))
            )
            if (!offerOk) throw IOException("oferta com assinatura inválida")
            if (price > Pricing.MAX_ACCEPTED_PRICE_MILLI_PER_MB) throw IOException("preço acima do limite (${Pricing.credits(price)} por MB)")
            val hostId = Identity.shortId(hostKey)
            AppLog.i("Sessão de cobrança com o compartilhador $hostId: ${Pricing.credits(price)} crédito(s) por MB")

            val baseline = measure()
            var seq = 0L
            var paidAmount = 0L
            var paidBytes = 0L
            BillingState.clientSummary = "Pagando ao compartilhador $hostId: nada ainda"
            while (!stopped) {
                val line = reader.readLine() ?: break
                val msg = JSONObject(line)
                if (msg.optString("t") != "bill") continue
                val bytes = msg.getLong("bytes")
                val amount = msg.getLong("amount")
                val measured = measure() - baseline
                // Tolerância: o compartilhador conta cabeçalhos que o túnel não conta, e as medições não são simultâneas.
                if (bytes > measured + measured / 10 + 256 * 1024) {
                    AppLog.e("Conta acima do medido: compartilhador diz ${Pricing.megabytes(bytes)}, medi ${Pricing.megabytes(measured)}")
                    JSONObject().put("t", "dispute").put("reason", "medido ${measured}, cobrado $bytes").send(writer)
                    continue
                }
                if (amount != Pricing.priceFor(bytes, price) || bytes < paidBytes) {
                    JSONObject().put("t", "dispute").put("reason", "valor não confere").send(writer)
                    continue
                }
                val delta = amount - paidAmount
                if (!Ledger.debit(delta)) {
                    AppLog.e("Saldo insuficiente para pagar ${Pricing.credits(delta)} créditos. Use \"Recarregar saldo de teste\".")
                    JSONObject().put("t", "low_balance").send(writer)
                    continue
                }
                seq++
                val sig = Identity.sign(Receipts.receiptHash(session, hostKey, Identity.publicKey, seq, bytes, amount))
                JSONObject()
                    .put("t", "receipt")
                    .put("seq", seq)
                    .put("bytes", bytes)
                    .put("amount", amount)
                    .put("sig", Schnorr.hex(sig))
                    .send(writer)
                paidAmount = amount
                paidBytes = bytes
                BillingState.clientSummary =
                    "Pagando ao compartilhador $hostId: ${Pricing.megabytes(bytes)}, ${Pricing.credits(amount)} créditos"
            }
        } catch (e: Exception) {
            if (!stopped) AppLog.e("Cobrança encerrada", e)
        } finally {
            s.closeQuietly()
        }
    }
}

/** Nome curto do endereço, para o log. */
fun describe(addr: InetAddress): String = if (addr is Inet6Address) "IPv6 local" else (addr.hostAddress ?: "?")
