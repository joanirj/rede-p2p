package br.redep2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

/** Wi-Fi comum a que o aparelho está conectado agora. */
data class CurrentWifi(val ssid: String?, val frequency: Int)

fun isBand24(frequency: Int) = frequency in 2400..2500

/** Lê o Wi-Fi atual. O nome (SSID) só aparece com a permissão de localização. */
@Suppress("DEPRECATION")
fun currentWifi(context: Context): CurrentWifi? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null
    val raw = info.ssid
    val ssid = if (raw == null || raw == WifiManager.UNKNOWN_SSID || raw.isBlank()) null else raw.removeSurrounding("\"")
    return CurrentWifi(ssid, info.frequency)
}

/** Pontos de acesso da rede [ssid] vistos na última busca do Android, para diagnóstico. */
@SuppressLint("MissingPermission")
fun scanSummary(context: Context, ssid: String): String {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return "sem Wi-Fi"
    return try {
        val aps = wifi.scanResults.filter { it.SSID == ssid }
        if (aps.isEmpty()) {
            "nenhum ponto de acesso \"$ssid\" na última busca"
        } else {
            aps.sortedBy { it.frequency }.joinToString("; ") { "${it.frequency} MHz ${it.level} dBm" }
        }
    } catch (e: SecurityException) {
        "busca bloqueada (permissão de localização)"
    }
}

/** Ponto de acesso 2,4 GHz da rede [ssid] com melhor sinal. */
@SuppressLint("MissingPermission")
fun find24Bssid(context: Context, ssid: String): Pair<String, Int>? {
    val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    return try {
        wifi.scanResults
            .filter { it.SSID == ssid && isBand24(it.frequency) }
            .maxByOrNull { it.level }
            ?.let { it.BSSID to it.frequency }
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Modo 2,4 GHz do compartilhador.
 *
 * O rádio Wi-Fi do aparelho atende o roteador e a rede Wi-Fi Direct ao mesmo tempo;
 * isso só funciona bem quando os dois estão no mesmo canal. Se o roteador colocou o
 * aparelho em 5 GHz (às vezes num canal proibido para Wi-Fi Direct), o app pede ao
 * Android uma conexão ao mesmo Wi-Fi na faixa de 2,4 GHz e cria a rede Wi-Fi Direct
 * nesse mesmo canal. Os sockets de saída do proxy são presos a essa conexão.
 */
object HomeLink {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    fun request(
        context: Context,
        ssid: String,
        passphrase: String,
        /** Ponto de acesso 2,4 GHz exato; sem ele, pede "qualquer um na faixa de 2,4 GHz" (Android 12+). */
        bssid: String?,
        knownFrequency: Int,
        onReady: (Network, Int) -> Unit,
        /** Mensagem e se a recusa foi imediata (o próprio Android rejeitou, sem tentar conectar). */
        onFail: (String, Boolean) -> Unit,
        onLost: () -> Unit
    ) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        release(app)
        val how = when {
            bssid != null -> "ponto de acesso $bssid"
            Build.VERSION.SDK_INT >= 31 -> "faixa de 2,4 GHz"
            else -> {
                onFail("Falha: não achei o ponto de acesso 2,4 GHz do Wi-Fi \"$ssid\"", true)
                return
            }
        }
        val request = try {
            val builder = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(passphrase)
            if (bssid != null) {
                builder.setBssid(MacAddress.fromString(bssid))
            } else if (Build.VERSION.SDK_INT >= 31) {
                builder.setBand(ScanResult.WIFI_BAND_24_GHZ)
            }
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()
        } catch (e: Exception) {
            onFail("Falha: o Android não aceitou o pedido (${e.javaClass.simpleName}: ${e.message})", true)
            return
        }
        val startedAt = System.currentTimeMillis()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var reported = false

            @Suppress("DEPRECATION")
            override fun onCapabilitiesChanged(net: Network, caps: NetworkCapabilities) {
                if (reported) return
                var freq = 0
                if (Build.VERSION.SDK_INT >= 31) {
                    freq = (caps.transportInfo as? WifiInfo)?.frequency ?: 0
                }
                if (!isBand24(freq) && isBand24(knownFrequency)) freq = knownFrequency
                if (!isBand24(freq)) {
                    freq = app.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency ?: 0
                }
                reported = true
                network = net
                onReady(net, freq)
            }

            override fun onUnavailable() {
                callback = null
                val elapsed = System.currentTimeMillis() - startedAt
                val immediate = elapsed < 3000
                val why = if (immediate) {
                    "o Android recusou o pedido na hora, sem tentar conectar"
                } else {
                    "a conexão não completou em ${elapsed / 1000} s (senha errada, pedido negado na janela ou sinal fraco)"
                }
                onFail("Pedido por $how: $why", immediate)
            }

            override fun onLost(net: Network) {
                if (network == net) {
                    network = null
                    onLost()
                }
            }
        }
        callback = cb
        AppLog.i("Pedindo ao Android para conectar ao Wi-Fi \"$ssid\" ($how). Confirme na janela que aparecer.")
        try {
            cm.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            callback = null
            onFail("Falha: o Android não aceitou o pedido (${e.javaClass.simpleName}: ${e.message})", true)
        }
    }

    fun release(context: Context) {
        val cb = callback ?: return
        callback = null
        network = null
        try {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (ignored: Exception) {
        }
    }
}

/** Senhas de Wi-Fi digitadas pela pessoa, guardadas só neste aparelho. */
object WifiPasswords {
    private const val PREFS = "wifi"

    fun get(context: Context, ssid: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("pass:$ssid", null)

    fun put(context: Context, ssid: String, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("pass:$ssid", passphrase).apply()
    }

    fun forget(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("pass:$ssid").apply()
    }
}
