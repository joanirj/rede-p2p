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

/** Ponto de acesso 2,4 GHz da rede [ssid] com melhor sinal (Android 10 e 11). */
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
        bssid: String?,
        knownFrequency: Int,
        onReady: (Network, Int) -> Unit,
        onFail: (String) -> Unit,
        onLost: () -> Unit
    ) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        release(app)
        val builder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setBand(ScanResult.WIFI_BAND_24_GHZ)
        } else if (bssid != null) {
            builder.setBssid(MacAddress.fromString(bssid))
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
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
                onFail("Falha: não consegui conectar ao Wi-Fi \"$ssid\" em 2,4 GHz (senha errada, pedido recusado ou o roteador não tem 2,4 GHz)")
            }

            override fun onLost(net: Network) {
                if (network == net) {
                    network = null
                    onLost()
                }
            }
        }
        callback = cb
        AppLog.i("Pedindo ao Android para conectar ao Wi-Fi \"$ssid\" na faixa de 2,4 GHz. Confirme na janela que aparecer.")
        cm.requestNetwork(request, cb, 60_000)
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
