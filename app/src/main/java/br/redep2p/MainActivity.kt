package br.redep2p

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_VPN = 2
    }

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Rede P2P · versão de teste 0.1"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        root.addView(section("Este aparelho tem Wi-Fi com internet"))
        root.addView(button("Compartilhar internet") { startHost() })
        root.addView(button("Parar de compartilhar") { stopHost() })

        root.addView(section("Este aparelho está sem internet"))
        root.addView(button("Usar internet de outro aparelho") { startClient() })
        root.addView(button("Parar de usar") { stopClient() })

        root.addView(section("Registro (log)"))
        root.addView(button("Copiar log") { copyLog() })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
        setContentView(scroll)

        AppLog.listener = { runOnUiThread { logView.text = AppLog.text() } }
        logView.text = AppLog.text()
        if (savedInstanceState == null) {
            AppLog.i("App aberto · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setOnClickListener { action() }
    }

    private fun refreshStatus() {
        val host = buildString {
            append("Compartilhar: ").append(HostService.status)
            if (HostService.running && HostService.status == "Compartilhando") {
                append("\n").append(Stats.summary())
            }
        }
        val client = when {
            ClientVpnService.running -> "Usar internet: conectado ao compartilhador (${ClientP2p.hostAddress})"
            ClientP2p.connecting -> "Usar internet: procurando o compartilhador…"
            else -> "Usar internet: desligado"
        }
        statusView.text = "$host\n$client"
    }

    // ------------------------------------------------------------ permissões

    private fun neededPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestNeededPermissions(): Boolean {
        val missing = neededPermissions()
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        for (i in permissions.indices) {
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            AppLog.i("Permissão ${permissions[i].substringAfterLast('.')}: ${if (ok) "concedida" else "NEGADA"}")
        }
    }

    private fun hasWifiDirectPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (checkSelfPermission(required) == PackageManager.PERMISSION_GRANTED) return true
        AppLog.e("Falta a permissão de Wi-Fi Direct (${if (Build.VERSION.SDK_INT >= 33) "dispositivos próximos" else "localização"}). Aceite o pedido e tente de novo.")
        requestNeededPermissions()
        return false
    }

    // ---------------------------------------------------------- compartilhar

    private fun startHost() {
        if (!hasWifiDirectPermission()) return
        if (ClientVpnService.running || ClientP2p.connecting) {
            AppLog.e("Pare o \"Usar internet\" antes de compartilhar neste aparelho.")
            return
        }
        AppLog.i("Iniciando o compartilhamento…")
        startForegroundService(Intent(this, HostService::class.java))
    }

    private fun stopHost() {
        if (!HostService.running) {
            AppLog.i("O compartilhamento já está parado")
            return
        }
        startService(Intent(this, HostService::class.java).setAction(HostService.ACTION_STOP))
    }

    // ---------------------------------------------------------- usar (cliente)

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) connectClient() else AppLog.e("Permissão de VPN negada")
        }
    }

    private fun startClient() {
        if (!hasWifiDirectPermission()) return
        if (HostService.running) {
            AppLog.e("Pare o \"Compartilhar\" antes de usar a internet de outro aparelho.")
            return
        }
        if (ClientVpnService.running) {
            AppLog.i("Já está conectado")
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            AppLog.i("Pedindo permissão para criar a VPN local…")
            @Suppress("DEPRECATION")
            startActivityForResult(prepare, REQ_VPN)
        } else {
            connectClient()
        }
    }

    private fun connectClient() {
        val app = applicationContext
        ClientP2p.connect(this) { host ->
            app.startService(
                Intent(app, ClientVpnService::class.java)
                    .setAction(ClientVpnService.ACTION_START)
                    .putExtra(ClientVpnService.EXTRA_HOST, host)
            )
        }
    }

    private fun stopClient() {
        startService(Intent(this, ClientVpnService::class.java).setAction(ClientVpnService.ACTION_STOP))
        ClientP2p.disconnect()
        AppLog.i("Desconectado do compartilhador")
    }

    // ------------------------------------------------------------------- log

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Log Rede P2P", AppLog.text()))
        Toast.makeText(this, "Log copiado. Cole na conversa com o Claude.", Toast.LENGTH_LONG).show()
    }
}
