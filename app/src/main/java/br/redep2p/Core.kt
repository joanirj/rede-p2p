package br.redep2p

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Versão exibida no app e enviada com o log. */
object BuildInfo {
    const val VERSION = "0.4.3"
}

/** Parâmetros fixos da versão de teste. */
object Config {
    /** O Android exige que redes Wi-Fi Direct comecem com "DIRECT-xy-". */
    const val NETWORK_NAME = "DIRECT-RP-RedeP2P"

    /** Rede criada por um repetidor (aparelho que recebe e repassa a internet). */
    const val REPEATER_NAME = "DIRECT-RP-Repetidor"

    /** Endereço que informa saltos até a internet e o IPv6 link-local do aparelho. */
    const val INFO_PATH = "/redep2p/info"
    const val PASSPHRASE = "redep2p123"
    const val SOCKS_PORT = 1080
    const val HTTP_PORT = 8080
    /** Porta da sessão de cobrança (ofertas, contas e recibos). */
    const val CONTROL_PORT = 8090
    const val MTU = 1500
}

/**
 * Registro exibido na tela, copiável e guardado em arquivo,
 * para sobreviver a um travamento do app.
 */
object AppLog {
    private const val TAG = "RedeP2P"
    private const val MAX_LINES = 800
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var file: File? = null

    @Volatile
    var listener: (() -> Unit)? = null

    /** Carrega o registro salvo e captura o motivo de um travamento anterior. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "log.txt")
        file = f
        try {
            if (f.exists()) {
                val saved = f.readLines().takeLast(MAX_LINES)
                lines.addAll(saved)
                f.writeText(saved.joinToString("\n", postfix = if (saved.isEmpty()) "" else "\n"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "falha ao ler log salvo", e)
        }
        captureSystemLog()
        installCrashHandler()
    }

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
        val line = "${fmt.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.appendText(line + "\n")
        } catch (ignored: Exception) {
        }
        listener?.invoke()
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file?.writeText("")
        } catch (ignored: Exception) {
        }
        listener?.invoke()
    }

    /** Erros de código Kotlin/Java: grava antes de o app fechar. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = Log.getStackTraceString(error).lines().take(25).joinToString("\n")
                add("TRAVAMENTO na thread ${thread.name}:\n$trace")
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Erros do código nativo (Go/tun2socks) não passam pelo Kotlin.
     * O registro do sistema (logcat) do próprio app ainda guarda essas linhas
     * por algum tempo, então lemos as relevantes ao abrir de novo.
     */
    private fun captureSystemLog() {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "3000"))
            val keys = listOf(
                "AndroidRuntime", "GoLog", "GoStderr", "GoStdout", "Fatal signal", "SIGSYS",
                "SIGSEGV", "SIGABRT", "F libc", "F DEBUG", "F/libc", "F/DEBUG", "tun2socks",
                "seccomp", "panic", "goroutine", "E RedeP2P", "E/RedeP2P"
            )
            val relevant = process.inputStream.bufferedReader().readLines()
                .filter { line -> keys.any { line.contains(it, ignoreCase = false) } }
                .takeLast(80)
            process.destroy()
            if (relevant.isNotEmpty()) {
                add("— Registro do sistema (inclui a execução anterior) —\n" + relevant.joinToString("\n") + "\n— fim do registro do sistema —")
            }
        } catch (e: Exception) {
            Log.e(TAG, "falha ao ler logcat", e)
        }
    }
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
