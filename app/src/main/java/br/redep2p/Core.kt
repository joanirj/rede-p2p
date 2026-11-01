package br.redep2p

import android.util.Log
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Parâmetros fixos da versão de teste. */
object Config {
    /** O Android exige que redes Wi-Fi Direct comecem com "DIRECT-xy-". */
    const val NETWORK_NAME = "DIRECT-RP-RedeP2P"
    const val PASSPHRASE = "redep2p123"
    const val SOCKS_PORT = 1080
    const val HTTP_PORT = 8080
    const val MTU = 1500
}

/** Registro em memória, exibido na tela e copiável para colar no chat. */
object AppLog {
    private const val TAG = "RedeP2P"
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: (() -> Unit)? = null

    fun i(msg: String) {
        Log.i(TAG, msg)
        add(msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        val detail = t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        add("ERRO: $msg$detail")
    }

    @Synchronized
    private fun add(msg: String) {
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
        listener?.invoke()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}

/** Contadores do compartilhador. "Enviado" = do cliente para a internet. */
object Stats {
    val up = AtomicLong()
    val down = AtomicLong()
    val active = AtomicInteger()
    val total = AtomicInteger()

    fun reset() {
        up.set(0); down.set(0); active.set(0); total.set(0)
    }

    fun summary(): String =
        "Conexões ativas: ${active.get()} · total: ${total.get()}\n" +
            "Enviado: ${mb(up.get())} · Recebido: ${mb(down.get())}"

    private fun mb(bytes: Long) = String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
}

fun Closeable?.closeQuietly() {
    try {
        this?.close()
    } catch (ignored: Exception) {
    }
}

fun p2pReason(code: Int): String = when (code) {
    0 -> "erro interno (ERROR)"
    1 -> "Wi-Fi Direct não suportado (P2P_UNSUPPORTED)"
    2 -> "Wi-Fi ocupado (BUSY)"
    3 -> "sem pedidos de serviço (NO_SERVICE_REQUESTS)"
    else -> "código $code"
}
