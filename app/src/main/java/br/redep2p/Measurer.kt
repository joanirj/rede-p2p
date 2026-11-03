package br.redep2p

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Uma medição de alcance num ponto. */
data class Measurement(
    val device: String,
    val scenario: String,
    val distance: Int,
    val time: String,
    val ssid: String,
    val rssi: Int?,
    val linkMbps: Int?,
    val latencyMs: Int,
    val mbps: Double,
    val downBytes: Long,
    val hostBytes: Long,
    val origin: String,
    val ok: Boolean,
    val error: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device", device).put("scenario", scenario).put("distance", distance).put("time", time)
        .put("ssid", ssid).put("rssi", rssi ?: JSONObject.NULL).put("linkMbps", linkMbps ?: JSONObject.NULL)
        .put("latencyMs", latencyMs).put("mbps", mbps).put("downBytes", downBytes).put("hostBytes", hostBytes)
        .put("origin", origin).put("ok", ok).put("error", error ?: JSONObject.NULL)

    /** Vídeo, útil, muito lenta ou sem conexão. */
    val quality: String
        get() = when {
            !ok -> "Sem conexão"
            mbps >= 3 && latencyMs < 100 -> "Vídeo"
            mbps >= 0.5 && latencyMs < 300 -> "Útil"
            else -> "Muito lenta"
        }

    companion object {
        fun fromJson(o: JSONObject) = Measurement(
            o.optString("device"), o.optString("scenario"), o.optInt("distance"), o.optString("time"),
            o.optString("ssid"), if (o.isNull("rssi")) null else o.optInt("rssi"),
            if (o.isNull("linkMbps")) null else o.optInt("linkMbps"), o.optInt("latencyMs"), o.optDouble("mbps"),
            o.optLong("downBytes"), o.optLong("hostBytes"), o.optString("origin"), o.optBoolean("ok"),
            if (o.isNull("error")) null else o.optString("error")
        )
    }
}

/**
 * Mede sinal, latência e velocidade até o compartilhador e confere a origem: o
 * compartilhador informa quantos bytes contou para este aparelho, e a medição só é
 * "confirmada" se isso bater com o que foi baixado.
 */
object Measurer {

    /** Bytes baixados pela medição direto pelo compartilhador (entram na cobrança). */
    val extraBytes = AtomicLong()

    private const val DOWNLOAD_HOST = "speed.cloudflare.com"
    private const val DOWNLOAD_PATH = "/__down?bytes=3000000"
    private const val FALLBACK_HOST = "speedtest.tele2.net"
    private const val FALLBACK_PATH = "/1MB.zip"

    private fun socket(network: Network?): Socket = Socket().also { s -> network?.bindSocket(s) }

    private fun httpGetLocal(host: String, network: Network?, path: String): String {
        socket(network).use { s ->
            s.connect(InetSocketAddress(host, Config.HTTP_PORT), 4000)
            s.soTimeout = 4000
            s.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val text = s.getInputStream().bufferedReader().readText()
            if (!text.startsWith("HTTP/1.0 200") && !text.startsWith("HTTP/1.1 200")) {
                throw IllegalStateException("o compartilhador não tem a verificação (atualize o app dele)")
            }
            return text.substringAfter("\r\n\r\n")
        }
    }

    fun hostReachable(host: String, network: Network?): Boolean = try {
        httpGetLocal(host, network, Config.INFO_PATH).contains("hops=")
    } catch (e: Exception) {
        false
    }

    /** Bytes que o compartilhador contou para este aparelho. */
    private fun hostUsage(host: String, network: Network?): Long {
        val body = httpGetLocal(host, network, Config.USAGE_PATH)
        return body.lines().firstOrNull { it.startsWith("bytes=") }?.substringAfter("=")?.trim()?.toLong()
            ?: throw IllegalStateException("resposta inválida do compartilhador")
    }

    @Suppress("DEPRECATION")
    private fun wifiSignal(context: Context): Triple<String, Int?, Int?> {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        val ssid = info?.ssid?.removeSurrounding("\"") ?: ""
        return if (ssid.startsWith(Config.NETWORK_NAME.take(10))) {
            Triple(ssid, info?.rssi, info?.linkSpeed)
        } else {
            Triple(if (ssid.isEmpty() || ssid == "<unknown ssid>") "Wi-Fi Direct" else ssid, null, null)
        }
    }

    private fun download(up: Upstream, tls: Boolean, host: String, path: String): Pair<Long, Double> {
        val raw = up.connect(host, if (tls) 443 else 80)
        val s = if (tls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        } else raw
        s.use {
            it.soTimeout = 10_000
            val t0 = System.nanoTime()
            if (it is SSLSocket) it.startHandshake()
            it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val buf = ByteArray(32 * 1024)
            var total = 0L
            val input = it.getInputStream()
            val limit = t0 + 12_000_000_000L
            while (System.nanoTime() < limit) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                extraBytes.addAndGet(n.toLong())
            }
            val secs = (System.nanoTime() - t0) / 1e9
            return total to secs
        }
    }

    fun measure(context: Context, host: String, network: Network?, device: String, scenario: String, distance: Int): Measurement {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val (ssid, rssi, link) = wifiSignal(context)
        val up = Upstream(InetAddress.getByName(host), network, 0)

        val lat = mutableListOf<Int>()
        repeat(3) {
            try {
                val t0 = System.nanoTime()
                up.connect("1.1.1.1", 443).close()
                lat += ((System.nanoTime() - t0) / 1_000_000).toInt()
            } catch (ignored: Exception) {
            }
        }
        if (lat.isEmpty()) {
            AppLog.i("Medição $scenario $distance m: sem conexão com o compartilhador")
            return Measurement(device, scenario, distance, time, ssid, rssi, link, -1, 0.0, 0, 0, "—", false, "sem conexão com o compartilhador")
        }
        val latency = lat.sorted()[lat.size / 2]

        val usage0 = try {
            hostUsage(host, network)
        } catch (e: Exception) {
            null
        }
        var down = 0L
        var mbps = 0.0
        var error: String? = null
        for ((tls, h, p) in listOf(Triple(true, DOWNLOAD_HOST, DOWNLOAD_PATH), Triple(false, FALLBACK_HOST, FALLBACK_PATH))) {
            try {
                val (n, secs) = download(up, tls, h, p)
                if (n > 100_000 && secs > 0) {
                    down = n
                    mbps = n * 8 / secs / 1e6
                    error = null
                    break
                }
                error = "download incompleto"
            } catch (e: Exception) {
                error = "download falhou: ${e.message}"
            }
        }
        val ok = error == null
        var hostBytes = 0L
        val origin = when {
            usage0 == null -> "sem verificação"
            !ok -> "—"
            else -> try {
                hostBytes = hostUsage(host, network) - usage0
                if (hostBytes >= down * 9 / 10) "confirmada"
                else "NÃO confirmada: compartilhador contou ${Pricing.megabytes(hostBytes)}, baixado ${Pricing.megabytes(down)}"
            } catch (e: Exception) {
                "sem verificação"
            }
        }
        val m = Measurement(device, scenario, distance, time, ssid, rssi, link, latency, mbps, down, hostBytes, origin, ok, error)
        AppLog.i(
            "Medição $device $scenario $distance m: sinal ${rssi?.let { "$it dBm" } ?: "—"}, latência $latency ms, " +
                String.format(Locale.US, "%.1f", mbps) + " Mbps, origem $origin ${error ?: ""}"
        )
        return m
    }

    // ------------------------------------------------------------ resultados salvos

    private fun file(context: Context) = File(context.filesDir, "medicoes.json")

    fun load(context: Context): MutableList<Measurement> = try {
        val arr = JSONArray(file(context).readText())
        MutableList(arr.length()) { Measurement.fromJson(arr.getJSONObject(it)) }
    } catch (e: Exception) {
        mutableListOf()
    }

    fun save(context: Context, list: List<Measurement>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        file(context).writeText(arr.toString())
    }

    fun csv(list: List<Measurement>): String = buildString {
        append("compartilhador,cenario,distancia_m,rede,sinal_dbm,latencia_ms,velocidade_mbps,origem,erro\n")
        for (m in list) {
            append("${m.device},${m.scenario},${m.distance},${m.ssid},${m.rssi ?: ""},${m.latencyMs},")
            append(String.format(Locale.US, "%.1f", m.mbps)).append(",${m.origin},${m.error ?: ""}\n")
        }
    }
}
