package br.redep2p

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
    private lateinit var creditsView: TextView
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
        AppLog.init(applicationContext)
        Identity.init(applicationContext)
        Ledger.init(applicationContext)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Rede P2P · versão de teste ${BuildInfo.VERSION}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)

        root.addView(section("Este aparelho tem Wi-Fi com internet"))
        root.addView(button("Compartilhar internet") { startHost(null) })
        root.addView(button("Parar de compartilhar") { stopHost() })

        root.addView(section("Este aparelho vai repassar a internet de outro"))
        root.addView(button("Repetir internet de outro aparelho") { startHost(HostService.ACTION_REPEAT) })
        root.addView(hint("O Android vai perguntar a qual rede conectar: escolha DIRECT-RP-RedeP2P (ou outro DIRECT-RP-…). Para parar, use \"Parar de compartilhar\"."))

        root.addView(section("Este aparelho está sem internet"))
        root.addView(button("Usar internet de outro aparelho") { startClient(Config.NETWORK_NAME) })
        root.addView(button("Usar internet de um repetidor") { startClient(Config.REPEATER_NAME) })
        root.addView(button("Parar de usar") { stopClient() })

        root.addView(section("Créditos"))
        creditsView = TextView(this).apply { textSize = 14f }
        root.addView(creditsView)
        root.addView(button("Recarregar saldo de teste (+${Pricing.credits(Pricing.TEST_TOPUP_MILLI)} créditos)") {
            Ledger.topUp(Pricing.TEST_TOPUP_MILLI)
            AppLog.i("Saldo de teste recarregado")
            refreshStatus()
        })

        root.addView(section("Registro (log)"))
        root.addView(button("Enviar log ao Claude (abre o GitHub)") { sendLogToGitHub() })
        root.addView(button("Copiar log") { copyLog() })
        root.addView(button("Limpar log") { AppLog.clear() })
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
            AppLog.i("App aberto · versão ${BuildInfo.VERSION} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
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

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
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
            if (HostService.running && HostService.ready) {
                append("\n").append(Stats.summary())
            }
            HostService.channelWarning?.let { append("\n⚠ ").append(it) }
        }
        val client = when {
            ClientVpnService.running && HostService.ownTrafficViaProxy -> "Usar internet: pelo próprio proxy deste aparelho"
            ClientVpnService.running -> "Usar internet: conectado ao compartilhador (${ClientP2p.hostAddress}) por ${ClientP2p.path}"
            ClientP2p.connecting -> "Usar internet: procurando o compartilhador…"
            else -> "Usar internet: desligado"
        }
        statusView.text = "$host\n$client"

        creditsView.text = buildString {
            append("Saldo: ").append(Pricing.credits(Ledger.balance)).append(" créditos")
            append(" (ganhou ").append(Pricing.credits(Ledger.earned))
            append(", gastou ").append(Pricing.credits(Ledger.spent)).append(")\n")
            append("Preço: ").append(Pricing.credits(Pricing.PRICE_MILLI_PER_MB)).append(" crédito por MB\n")
            append("Sua identidade: ").append(Identity.shortId)
            if (HostService.running) BillingState.hostSummary?.let { append("\n").append(it) }
            BillingState.clientSummary?.let { append("\n").append(it) }
        }
    }

    // ------------------------------------------------------------ permissões

    private fun neededPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        // Localização: para o Wi-Fi Direct até o Android 12 e, em todos, para ler o
        // nome do Wi-Fi de casa e achar a versão 2,4 GHz dele.
        perms += Manifest.permission.ACCESS_FINE_LOCATION
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

    private fun startHost(action: String?) {
        if (!hasWifiDirectPermission()) return
        if (ClientVpnService.running || ClientP2p.connecting) {
            AppLog.e("Pare o \"Usar internet\" antes de compartilhar neste aparelho. Para repetir, conecte-o à rede ${Config.NETWORK_NAME} em Configurações → Wi-Fi.")
            return
        }
        if (HostService.running) {
            AppLog.i("Já está ${if (HostService.repeater) "repetindo" else "compartilhando"}. Para trocar, toque em \"Parar de compartilhar\" antes.")
            return
        }
        if (action == null) {
            val wifi = currentWifi(this)
            if (wifi != null && wifi.frequency > 0 && !isBand24(wifi.frequency) && findDirectWifi(this) == null) {
                prepareShare24(wifi)
                return
            }
        }
        AppLog.i(if (action == HostService.ACTION_REPEAT) "Iniciando o modo repetidor…" else "Iniciando o compartilhamento…")
        startForegroundService(Intent(this, HostService::class.java).setAction(action))
    }

    /**
     * O Wi-Fi de casa está em 5 GHz: o compartilhamento precisa ir para 2,4 GHz.
     * Pede a senha do Wi-Fi uma vez (o Android exige) e guarda neste aparelho.
     */
    private fun prepareShare24(wifi: CurrentWifi) {
        AppLog.i("Wi-Fi de casa em ${wifi.frequency} MHz (5 GHz): o compartilhamento vai usar a faixa de 2,4 GHz")
        val known = wifi.ssid
        val saved = known?.let { WifiPasswords.get(this, it) }
        if (known != null && saved != null) {
            launchShare24(known, saved)
            return
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val ssidField = EditText(this).apply {
            hint = "Nome do Wi-Fi"
            setText(known ?: "")
            isSingleLine = true
        }
        val passField = EditText(this).apply {
            hint = "Senha do Wi-Fi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        if (known == null) form.addView(ssidField)
        form.addView(passField)
        AlertDialog.Builder(this)
            .setTitle("Compartilhar em 2,4 GHz")
            .setMessage(
                "Este aparelho está no Wi-Fi ${known?.let { "\"$it\" " } ?: ""}na faixa de 5 GHz. " +
                    "Para os outros aparelhos conseguirem se conectar, o app vai ligá-lo ao mesmo Wi-Fi na faixa de 2,4 GHz.\n\n" +
                    "O Android exige a senha do Wi-Fi para isso. Ela fica guardada só neste aparelho."
            )
            .setView(form)
            .setPositiveButton("Continuar") { _, _ ->
                val ssid = ssidField.text.toString().trim()
                val pass = passField.text.toString()
                if (ssid.isEmpty() || pass.length < 8) {
                    AppLog.e("Nome do Wi-Fi vazio ou senha com menos de 8 caracteres")
                } else {
                    WifiPasswords.put(this, ssid, pass)
                    launchShare24(ssid, pass)
                }
            }
            .setNeutralButton("Usar 5 GHz mesmo") { _, _ ->
                AppLog.i("Iniciando o compartilhamento em 5 GHz (pode não funcionar com todos os aparelhos)…")
                startForegroundService(Intent(this, HostService::class.java))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun launchShare24(ssid: String, pass: String) {
        val intent = Intent(this, HostService::class.java)
            .setAction(HostService.ACTION_SHARE_24)
            .putExtra(HostService.EXTRA_SSID, ssid)
            .putExtra(HostService.EXTRA_PASS, pass)
        if (Build.VERSION.SDK_INT < 31) {
            // Android 10 e 11 não deixam escolher a faixa: escolhemos o ponto de acesso 2,4 GHz.
            val ap = find24Bssid(this, ssid)
            if (ap == null) {
                AppLog.e("Não achei a versão 2,4 GHz do Wi-Fi \"$ssid\" por perto (a localização está ligada?)")
                return
            }
            intent.putExtra(HostService.EXTRA_BSSID, ap.first).putExtra(HostService.EXTRA_FREQ, ap.second)
        }
        AppLog.i("Iniciando o compartilhamento em 2,4 GHz…")
        startForegroundService(intent)
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
            if (resultCode == RESULT_OK) connectClient(pendingNetwork) else AppLog.e("Permissão de VPN negada")
        }
    }

    /** Rede escolhida enquanto o Android pede a permissão de VPN. */
    private var pendingNetwork = Config.NETWORK_NAME

    private fun startClient(networkName: String) {
        if (!hasWifiDirectPermission()) return
        if (HostService.running && !(HostService.ownTrafficViaProxy && HostService.ready)) {
            AppLog.e("Pare o \"Compartilhar\" antes de usar a internet de outro aparelho.")
            return
        }
        if (ClientVpnService.running) {
            AppLog.i("Já está conectado")
            return
        }
        pendingNetwork = networkName
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            AppLog.i("Pedindo permissão para criar a VPN local…")
            @Suppress("DEPRECATION")
            startActivityForResult(prepare, REQ_VPN)
        } else {
            connectClient(networkName)
        }
    }

    private fun connectClient(networkName: String) {
        val app = applicationContext
        if (HostService.ownTrafficViaProxy && HostService.ready) {
            // Repetidor ou modo 2,4 GHz: este aparelho navega pelo próprio proxy.
            AppLog.i("Usando a internet pelo próprio proxy deste aparelho")
            app.startService(
                Intent(app, ClientVpnService::class.java)
                    .setAction(ClientVpnService.ACTION_START)
                    .putExtra(ClientVpnService.EXTRA_HOST, "127.0.0.1")
            )
            return
        }
        ClientP2p.connect(this, networkName) { host, network ->
            val intent = Intent(app, ClientVpnService::class.java)
                .setAction(ClientVpnService.ACTION_START)
                .putExtra(ClientVpnService.EXTRA_HOST, host)
            if (network != null) intent.putExtra(ClientVpnService.EXTRA_NETWORK, network)
            app.startService(intent)
        }
    }

    private fun stopClient() {
        startService(Intent(this, ClientVpnService::class.java).setAction(ClientVpnService.ACTION_STOP))
        ClientP2p.disconnect(this)
        AppLog.i("Desconectado do compartilhador")
    }

    // ------------------------------------------------------------------- log

    /**
     * Abre a página "nova issue" do repositório já preenchida com o log.
     * A pessoa só confirma; o Claude lê a issue pelo GitHub.
     * Precisa de internet: use depois de voltar ao Wi-Fi de casa (o log fica salvo).
     */
    private fun sendLogToGitHub() {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val full = AppLog.text()
        // URLs muito longas são recusadas; mandamos o final do log.
        val maxChars = 5500
        val tail = if (full.length > maxChars) "(início cortado)\n" + full.takeLast(maxChars) else full
        val title = "Log · $device"
        val body = "Versão: ${BuildInfo.VERSION}\nAparelho: $device\n\n```\n$tail\n```"
        val url = "https://github.com/joanirj/rede-p2p/issues/new?title=" +
            Uri.encode(title) + "&body=" + Uri.encode(body)
        copyLog()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            AppLog.i("Página do GitHub aberta. Toque em \"Submit new issue\" para enviar.")
        } catch (e: Exception) {
            AppLog.e("Não consegui abrir o navegador. O log foi copiado: cole na conversa.", e)
        }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Log Rede P2P", AppLog.text()))
        Toast.makeText(this, "Log copiado. Cole na conversa com o Claude.", Toast.LENGTH_LONG).show()
    }
}
