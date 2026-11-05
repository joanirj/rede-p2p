package br.redep2p

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Teste de alcance guiado.
 *
 * Papéis fixos: os compartilhadores (tablet e notebook) ficam parados, lado a lado; este
 * celular é o medidor e anda pela calçada. Em cada ponto, o celular conecta a cada
 * compartilhador, mede e passa para o próximo. Assim a comparação é justa: mesmo lugar,
 * mesmo momento.
 *
 * Ordem das etapas: 1 ao ar livre (referência e conferência de que tudo funciona),
 * 2 na janela, 3 num cômodo de dentro (os aparelhos vão sendo levados para dentro).
 */
class RangeTestActivity : Activity() {

    private data class Sharer(val name: String, val ssid: String)

    private data class Stage(
        val key: String,
        val title: String,
        val from: String,
        val prep: List<String>,
        val distances: List<Int>,
    )

    private val sharers = listOf(
        Sharer("Tablet", Config.NETWORK_NAME),
        Sharer("Notebook", Config.NOTEBOOK_NAME),
    )

    private val stages = listOf(
        Stage(
            "ar-livre", "Ao ar livre", "dos aparelhos",
            listOf(
                "Leve os compartilhadores para fora, lado a lado, na calçada ou no portão, em cima de algo na altura da cintura (um banco ou uma mesa). Notebook com a tampa aberta.",
                "Eles precisam continuar com internet: confira que o tablet e o notebook ainda pegam o Wi-Fi de casa (ou o cabo).",
                "Confira que os dois mostram \"Compartilhando\".",
                "Fique ao lado deles com este celular e toque em \"Verificar compartilhadores\".",
                "As distâncias contam a partir dos aparelhos. Ande sempre em linha reta, pela mesma calçada.",
            ),
            listOf(2, 5, 10, 15, 20, 30, 40, 50, 60, 80, 100)
        ),
        Stage(
            "janela", "Na janela", "da janela",
            listOf(
                "Leve os compartilhadores para a janela da frente (ou a porta), lado a lado, apoiados num móvel e virados para a rua.",
                "Confira que os dois continuam \"Compartilhando\".",
                "Vá para a calçada em frente à janela. As distâncias contam a partir da janela.",
            ),
            listOf(2, 5, 10, 15, 20, 25, 30, 40, 50, 60)
        ),
        Stage(
            "comodo", "Num cômodo de dentro", "da frente da casa",
            listOf(
                "Leve os compartilhadores para um cômodo de dentro da casa, longe da frente (quarto ou sala dos fundos), em cima de uma mesa. Deixe as portas como costumam ficar.",
                "Confira que os dois continuam \"Compartilhando\".",
                "Escolha abaixo a que distância o cômodo fica da frente da casa.",
                "Vá para a calçada em frente à casa. As distâncias contam a partir da frente da casa.",
            ),
            listOf(0, 5, 10, 15, 20, 25, 30, 40)
        ),
    )

    // ------------------------------------------------------------------ estado

    private enum class Phase { PREP, POINT, STAGE_DONE, ALL_DONE }

    private var stageIdx = 0
    private var pointIdx = 0
    private var phase = Phase.PREP
    private var depth = 0 // metros do cômodo até a frente da casa (etapa 3)
    private val selected = mutableSetOf("Tablet", "Notebook")
    private val stopped = mutableMapOf<String, Int>() // compartilhador → último ponto com conexão
    private var running = false
    private val live = linkedMapOf<String, String>()
    private var pointResults = listOf<Measurement>()
    private lateinit var results: MutableList<Measurement>

    private lateinit var body: LinearLayout
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        Identity.init(applicationContext)
        Ledger.init(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        results = Measurer.load(this)
        val prefs = getSharedPreferences("teste", MODE_PRIVATE)
        stageIdx = prefs.getInt("stage", 0).coerceIn(0, stages.size - 1)
        depth = prefs.getInt("depth", 0)
        prefs.getStringSet("selected", null)?.let { selected.clear(); selected.addAll(it) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(body) })
        render()
    }

    private fun saveProgress() {
        getSharedPreferences("teste", MODE_PRIVATE).edit()
            .putInt("stage", stageIdx).putInt("depth", depth).putStringSet("selected", selected.toSet()).apply()
    }

    // --------------------------------------------------------------- componentes

    private fun text(t: String, size: Float = 14f, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, top, 0, 0)
        body.addView(this)
    }

    private fun button(t: String, enabled: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        isEnabled = enabled
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
        body.addView(this)
    }

    private fun row(vararg items: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            r.addView(Button(this).apply {
                text = label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { action() }
            })
        }
        body.addView(r)
    }

    private val stage get() = stages[stageIdx]
    private val activeSharers get() = sharers.filter { it.name in selected && it.name !in stopped }

    // ------------------------------------------------------------------ telas

    private fun render() {
        body.removeAllViews()
        text("Teste de alcance", 21f, bold = true)
        text("Compartilhadores (ficam parados, lado a lado): ${sharers.filter { it.name in selected }.joinToString(" e ") { it.name.lowercase() }.ifEmpty { "nenhum" }}\nMedidor (anda pela calçada): este celular", 13f, top = pad / 3)
        text(stages.mapIndexed { i, s -> (if (i == stageIdx) "▶ " else if (i < stageIdx) "✓ " else "   ") + "${i + 1}. ${s.title}" }.joinToString("\n"), 14f, top = pad / 2)

        if (ClientVpnService.running || HostService.running) {
            text("\nAntes do teste, pare \"Usar internet\" e \"Compartilhar\" neste celular, na tela principal. Durante o teste, o próprio teste conecta e desconecta.", 15f, bold = true)
            button("Voltar à tela principal") { finish() }
            return
        }
        when (phase) {
            Phase.PREP -> renderPrep()
            Phase.POINT -> renderPoint()
            Phase.STAGE_DONE -> renderStageDone()
            Phase.ALL_DONE -> renderAllDone()
        }
        renderResultsFooter()
    }

    private fun renderPrep() {
        text("Etapa ${stageIdx + 1} de ${stages.size} · ${stage.title}", 18f, bold = true, top = pad)
        if (stageIdx == 0) {
            text("Quais compartilhadores vão participar?", 13f, bold = true, top = pad / 2)
            row(*sharers.map { s -> ((if (s.name in selected) "☑ " else "☐ ") + s.name) to { toggle(s.name) } }.toTypedArray())
        }
        text(stage.prep.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n"), 14f, top = pad / 2)
        if (stage.key == "comodo") {
            text("O cômodo fica a quantos metros da frente da casa?", 13f, bold = true, top = pad / 2)
            row(*listOf(3, 6, 10, 15).map { m -> ((if (depth == m) "● " else "") + "$m m") to { depth = m; saveProgress(); render() } }.toTypedArray())
        }
        text("Na primeira conexão a cada compartilhador, o Android pergunta se pode conectar: toque em Conectar. Depois ele não pergunta mais.", 12f, top = pad / 2)
        val scan = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        button("Verificar compartilhadores") { scan.text = scanText() }
        body.addView(scan)
        val ready = selected.isNotEmpty() && (stage.key != "comodo" || depth > 0)
        button("Começar a etapa ${stageIdx + 1}", enabled = ready) {
            stopped.clear(); pointIdx = 0; pointResults = emptyList(); live.clear()
            phase = Phase.POINT; saveProgress(); render()
        }
    }

    private fun toggle(name: String) {
        if (name in selected) selected.remove(name) else selected.add(name)
        saveProgress(); render()
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun scanText(): String {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        try {
            wifi.startScan()
        } catch (ignored: Exception) {
        }
        val seen = try {
            wifi.scanResults.groupBy { it.SSID }.mapValues { e -> e.value.maxOf { it.level } }
        } catch (e: SecurityException) {
            return "Ligue a localização do celular para verificar."
        }
        return sharers.filter { it.name in selected }.joinToString("\n") { s ->
            val lvl = seen[s.ssid]
            if (lvl != null) "✓ ${s.name}: rede ${s.ssid} encontrada ($lvl dBm)"
            else "✗ ${s.name}: rede ${s.ssid} não encontrada. Ele está compartilhando? Toque de novo em alguns segundos."
        }
    }

    private fun pointText(): String {
        val d = stage.distances[pointIdx]
        if (d == 0) return "Fique na calçada, bem em frente à casa."
        if (pointIdx == 0) return "Fique a $d m ${stage.from}."
        val prev = stage.distances[pointIdx - 1]
        val steps = ((d - prev) / 0.75).roundToInt()
        return "Vá até $d m ${stage.from}: cerca de $steps passos a partir do ponto anterior."
    }

    private fun renderPoint() {
        text("Etapa ${stageIdx + 1} · ${stage.title}", 13f, top = pad)
        text("Ponto ${pointIdx + 1} de ${stage.distances.size}", 13f)
        text(pointText(), 19f, bold = true, top = pad / 3)
        text("Fique parado, segurando o celular como vai usá-lo. Cada compartilhador leva uns 20 segundos.", 13f)
        val measuring = activeSharers.joinToString(" e ") { it.name.lowercase() }
        if (stopped.isNotEmpty()) {
            text(stopped.entries.joinToString("\n") { "${it.key}: limite alcançado (último ponto com conexão: ${it.value} m)" }, 13f, top = pad / 3)
        }
        if (live.isNotEmpty()) text(live.entries.joinToString("\n") { "${it.key}: ${it.value}" }, 15f, top = pad / 2)
        if (running) return
        if (pointResults.isEmpty()) {
            button("Medir este ponto ($measuring)") { measurePoint() }
            button("Pular este ponto") { nextPoint() }
            button("Encerrar a etapa aqui") { finishStage() }
        } else {
            button("Próximo ponto") { nextPoint() }
            button("Medir de novo este ponto") { measurePoint() }
            button("Encerrar a etapa aqui") { finishStage() }
        }
    }

    private fun nextPoint() {
        pointIdx++
        pointResults = emptyList()
        live.clear()
        if (pointIdx >= stage.distances.size || activeSharers.isEmpty()) finishStage() else render()
    }

    private fun finishStage() {
        phase = if (stageIdx >= stages.size - 1) Phase.ALL_DONE else Phase.STAGE_DONE
        live.clear()
        render()
    }

    private fun renderStageDone() {
        text("Etapa ${stageIdx + 1} concluída: ${stage.title}", 18f, bold = true, top = pad)
        text(summaryFor(stage.key), 14f, top = pad / 2)
        button("Ir para a etapa ${stageIdx + 2}: ${stages[stageIdx + 1].title}") {
            stageIdx++; phase = Phase.PREP; saveProgress(); render()
        }
        button("Refazer esta etapa") { phase = Phase.PREP; render() }
    }

    private fun renderAllDone() {
        text("Teste concluído", 18f, bold = true, top = pad)
        text("Volte para dentro de casa, deixe o celular voltar ao Wi-Fi de casa e envie os resultados.", 14f)
        button("Enviar resultados ao Claude (abre o GitHub)") { sendResults() }
        button("Começar o teste de novo") {
            stageIdx = 0; phase = Phase.PREP; saveProgress(); render()
        }
    }

    private fun renderResultsFooter() {
        text("Resultados até agora", 15f, bold = true, top = pad * 2)
        text(stages.joinToString("\n\n") { "${it.title}\n" + summaryFor(it.key) }, 13f)
        button("Enviar resultados ao Claude (abre o GitHub)") { sendResults() }
        button("Copiar resultados") { copyResults() }
        button("Apagar todos os resultados") {
            results.clear(); Measurer.save(this, results); stageIdx = 0; phase = Phase.PREP; saveProgress(); render()
        }
        text("Para enviar, o celular precisa de internet. Os resultados ficam salvos mesmo se o app fechar.", 12f)
    }

    // ---------------------------------------------------------------- medição

    private class Link(val wifi: DirectWifi, val callback: ConnectivityManager.NetworkCallback)

    /** Conecta à rede do compartilhador como Wi-Fi comum (sem mexer na VPN). */
    private fun connect(ssid: String): Link? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(Config.PASSPHRASE).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val latch = CountDownLatch(1)
        var found: DirectWifi? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                if (found != null) return
                val gw = lp.routes.firstNotNullOfOrNull { r -> (r.gateway as? Inet4Address)?.takeIf { r.isDefaultRoute } } ?: return
                found = DirectWifi(network, gw.hostAddress ?: return, lp.interfaceName)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        cm.requestNetwork(req, cb, 40_000)
        latch.await(45, TimeUnit.SECONDS)
        val w = found
        if (w == null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (ignored: Exception) {
            }
            return null
        }
        return Link(w, cb)
    }

    private fun release(link: Link) {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(link.callback)
        } catch (ignored: Exception) {
        }
    }

    private fun scenarioLabel(): String = if (stage.key == "comodo") "comodo-${depth}m" else stage.key

    private fun measureOne(s: Sharer, distance: Int): Measurement {
        setLive(s.name, "conectando à rede ${s.ssid}…")
        val link = connect(s.ssid)
            ?: return Measurement(s.name, scenarioLabel(), distance, "", s.ssid, null, null, -1, 0.0, 0, 0, "—", false, "não conectou à rede ${s.ssid}")
        try {
            setLive(s.name, "medindo…")
            // Sessão de cobrança, para o compartilhador não pausar a medição por falta de pagamento.
            val billing = BillingClient(InetAddress.getByName(link.wifi.gateway), link.wifi.network) { Measurer.extraBytes.get() }
            billing.start()
            Thread.sleep(800)
            val m = Measurer.measure(applicationContext, link.wifi.gateway, link.wifi.network, s.name, scenarioLabel(), distance)
            Thread.sleep(1200)
            billing.stop()
            return m
        } finally {
            release(link)
        }
    }

    private fun setLive(name: String, msg: String) {
        runOnUiThread { live[name] = msg; render() }
    }

    private fun measurePoint() {
        val d = stage.distances[pointIdx]
        val targets = activeSharers
        running = true
        live.clear()
        targets.forEach { live[it.name] = "na fila" }
        render()
        Thread {
            val got = mutableListOf<Measurement>()
            for (s in targets) {
                var m = measureOne(s, d)
                if (!m.ok) {
                    setLive(s.name, "sem conexão; tentando mais uma vez…")
                    m = measureOne(s, d)
                }
                got += m
                val summary = if (m.ok) {
                    "${m.quality} · ${m.rssi?.let { "$it dBm" } ?: "—"} · ${m.latencyMs} ms · " +
                        String.format(Locale("pt", "BR"), "%.1f Mbps", m.mbps) + " · " + originText(m)
                } else "sem conexão"
                setLive(s.name, summary)
            }
            runOnUiThread {
                running = false
                pointResults = got
                results.addAll(got)
                Measurer.save(this, results)
                for (m in got) {
                    if (!m.ok && m.device !in stopped) {
                        val lastOk = results.filter { it.device == m.device && it.scenario == scenarioLabel() && it.ok }.maxOfOrNull { it.distance }
                        stopped[m.device] = lastOk ?: 0
                        AppLog.i("${m.device}: limite alcançado na etapa ${stage.title} (último ponto com conexão: ${lastOk ?: 0} m)")
                    }
                }
                if (activeSharers.isEmpty()) {
                    live["Etapa"] = "todos os compartilhadores chegaram ao limite"
                    finishStage()
                } else render()
            }
        }.start()
    }

    private fun originText(m: Measurement) = when {
        m.origin == "confirmada" -> "Rede P2P ✓"
        m.origin.startsWith("NÃO") -> "outra rede ✗ (não vale)"
        m.origin == "—" -> "—"
        else -> "sem verificação"
    }

    // ------------------------------------------------------------- resultados

    private fun summaryFor(key: String): String {
        val lines = sharers.mapNotNull { s ->
            val rs = results.filter { it.device == s.name && it.scenario.startsWith(key) && !it.origin.startsWith("NÃO") }
            if (rs.isEmpty()) return@mapNotNull null
            fun max(q: (Measurement) -> Boolean) = rs.filter(q).maxOfOrNull { it.distance }?.let { "$it m" } ?: "—"
            "${s.name}: vídeo até ${max { it.quality == "Vídeo" }} · útil até ${max { it.quality == "Vídeo" || it.quality == "Útil" }} · conecta até ${max { it.ok }}"
        }
        return if (lines.isEmpty()) "Ainda sem medições." else lines.joinToString("\n")
    }

    private fun fullSummary() = stages.joinToString("\n") { "${it.title}:\n" + summaryFor(it.key) } +
        (if (depth > 0) "\nCômodo a $depth m da frente da casa." else "")

    private fun copyResults() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Teste de alcance", fullSummary() + "\n\n" + Measurer.csv(results)))
        Toast.makeText(this, "Resultados copiados. Cole na conversa com o Claude.", Toast.LENGTH_LONG).show()
    }

    private fun sendResults() {
        var csv = Measurer.csv(results)
        if (csv.length > 5000) csv = "(início cortado)\n" + csv.takeLast(5000)
        val title = "Teste de alcance · ${Build.MANUFACTURER} ${Build.MODEL}"
        val body = "Versão: ${BuildInfo.VERSION}\n\nResumo:\n${fullSummary()}\n\n```csv\n$csv```"
        val url = "https://github.com/joanirj/rede-p2p/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body)
        copyResults()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.e("Não consegui abrir o navegador. Os resultados foram copiados: cole na conversa.", e)
        }
    }
}
