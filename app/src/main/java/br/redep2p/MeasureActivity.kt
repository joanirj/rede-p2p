package br.redep2p

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Medição de alcance guiada, no aparelho cliente. Mede o compartilhador escolhido
 * (tablet, notebook ou celular) em três cenários, ponto a ponto.
 */
class MeasureActivity : Activity() {

    private data class Scenario(val key: String, val title: (String) -> String, val from: (String) -> String, val prep: (String) -> List<String>, val distances: List<Int>)

    private val scenarios = listOf(
        Scenario(
            "janela", { d -> "1 · $d na janela" }, { d -> "do $d" },
            { d ->
                listOf(
                    if (d == "notebook") "No notebook, abra o app Rede P2P e clique em \"Compartilhar internet\"."
                    else "No $d, abra o app e toque em \"Compartilhar internet\". Espere aparecer \"Compartilhando\".",
                    "Coloque o $d na janela da frente (ou na porta), apoiado num móvel e virado para a rua" +
                        (if (d == "notebook") ", com a tampa aberta" else "") + ". Não mexa mais nele até terminar este cenário.",
                    "Neste celular, conecte ao $d na tela principal" +
                        (if (d == "notebook") " (\"Usar internet de um notebook\")" else " (\"Usar internet de outro aparelho\")") +
                        " e volte para esta tela.",
                    "Vá para a calçada em frente à janela. Ande sempre pela mesma calçada e na mesma direção.",
                )
            },
            listOf(1, 5, 10, 15, 20, 25, 30, 40, 50, 60)
        ),
        Scenario(
            "comodo", { d -> "2 · $d num cômodo de dentro" }, { _ -> "da frente da casa" },
            { d ->
                listOf(
                    "Leve o $d para um cômodo de dentro da casa, longe da frente (quarto ou sala dos fundos), em cima de uma mesa. Deixe as portas como costumam ficar.",
                    "Confira se ele continua compartilhando.",
                    "Vá para a calçada em frente à casa. A distância agora conta a partir da frente da casa.",
                )
            },
            listOf(0, 5, 10, 15, 20, 25, 30, 40, 50, 60)
        ),
        Scenario(
            "ar livre", { _ -> "3 · Ao ar livre (referência)" }, { d -> "do $d" },
            { d ->
                listOf(
                    "Leve o $d para fora, na calçada ou no portão, em cima de algo na altura da cintura, ainda compartilhando.",
                    "Comece com este celular ao lado dele, com caminho livre entre os dois.",
                    "Ande em linha reta pela calçada, na mesma direção.",
                )
            },
            listOf(1, 5, 10, 15, 20, 25, 30, 40, 50, 60)
        ),
    )
    private val devices = listOf("tablet", "notebook", "celular")

    private var device = "notebook"
    private var scenario = scenarios[0]
    private var step = 0
    private var last: Measurement? = null
    private var measuring = false
    private lateinit var results: MutableList<Measurement>

    private lateinit var status: TextView
    private lateinit var deviceRow: LinearLayout
    private lateinit var scenarioRow: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var resultsView: TextView
    private lateinit var summaryView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1500)
        }
    }

    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        results = Measurer.load(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Medição de alcance"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        root.addView(label("Qual aparelho está compartilhando?"))
        deviceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(deviceRow)
        root.addView(label("Cenário"))
        scenarioRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scenarioRow)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad / 2, 0, 0) }
        root.addView(body)
        root.addView(label("Resultados"))
        summaryView = TextView(this).apply { textSize = 14f }
        root.addView(summaryView)
        resultsView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        root.addView(resultsView)
        root.addView(button("Enviar resultados ao Claude (abre o GitHub)") { sendResults() })
        root.addView(button("Copiar resultados") { copyResults() })
        root.addView(button("Apagar resultados") {
            results.clear(); Measurer.save(this, results); renderResults()
        })
        root.addView(TextView(this).apply {
            text = "Para enviar, o celular precisa de internet: desconecte da Rede P2P antes. Os resultados ficam salvos."
            textSize = 12f
        })
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(root) })
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, pad, 0, pad / 4)
    }

    private fun button(t: String, action: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { action() }
    }

    private fun refreshStatus() {
        val host = ClientVpnService.currentHost
        status.text = when {
            !ClientVpnService.running || host == null ->
                "Desconectado. Volte à tela principal e toque em \"Usar internet de um notebook\" ou \"Usar internet de outro aparelho\"."
            else -> "Conectado ao compartilhador $host por ${ClientP2p.path ?: "Wi-Fi"}.\n" +
                (ClientVpnService.originMsg?.let { (if (ClientVpnService.originOk) "✓ " else "⚠ ") + it } ?: "Verificando a origem da navegação…")
        }
        if (!measuring && last == null) renderStep()
    }

    private fun render() {
        deviceRow.removeAllViews()
        for (d in devices) {
            deviceRow.addView(Button(this).apply {
                text = (if (d == device) "● " else "") + d.replaceFirstChar { it.uppercase() }
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { device = d; step = 0; last = null; render() }
            })
        }
        scenarioRow.removeAllViews()
        for (sc in scenarios) {
            scenarioRow.addView(button((if (sc == scenario) "● " else "") + sc.title(device)) {
                scenario = sc; step = 0; last = null; render()
            })
        }
        renderStep()
        renderResults()
    }

    private fun stepText(): String {
        val d = scenario.distances[step]
        val from = scenario.from(device)
        if (d == 0) return "Fique na calçada, bem em frente à casa."
        if (step == 0) return "Fique a $d metro $from, do lado de fora."
        val prev = scenario.distances[step - 1]
        val steps = ((d - prev) / 0.75).roundToInt()
        val total = (d / 0.75).roundToInt()
        return "Afaste-se pela calçada até $d m $from: cerca de $steps passos a partir do ponto anterior ($total passos no total)."
    }

    private fun renderStep() {
        body.removeAllViews()
        val prep = scenario.prep(device)
        body.addView(TextView(this).apply {
            text = prep.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n")
            textSize = 14f
        })
        if (step >= scenario.distances.size) {
            body.addView(TextView(this).apply {
                text = "\nCenário concluído. Escolha o próximo cenário acima ou envie os resultados."
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(button("Refazer este cenário") { step = 0; last = null; render() })
            return
        }
        body.addView(TextView(this).apply {
            text = "\nPonto ${step + 1} de ${scenario.distances.size}"
            textSize = 13f
        })
        body.addView(TextView(this).apply {
            text = stepText()
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        body.addView(TextView(this).apply { text = "Fique parado, segurando o celular como vai usá-lo."; textSize = 13f })
        val res = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        body.addView(res)
        val connected = ClientVpnService.running && ClientVpnService.currentHost != null
        val d = scenario.distances[step]
        if (measuring) {
            res.text = "Medindo… fique parado (uns 15 segundos)."
            return
        }
        val m = last
        if (m == null) {
            body.addView(button("Medir aqui") { measure(d) }.apply { isEnabled = connected })
            body.addView(button("Pular este ponto") { step++; render() })
            if (!connected) res.text = "Conecte ao compartilhador antes de medir."
            return
        }
        res.text = if (m.ok) {
            "${m.quality} · sinal ${m.rssi?.let { "$it dBm" } ?: "—"} · latência ${m.latencyMs} ms · " +
                String.format(Locale("pt", "BR"), "%.1f Mbps", m.mbps) + " · origem: " + originText(m) +
                (if (m.origin.startsWith("NÃO")) "\n${m.origin}. Esta medição não vale: reconecte e meça de novo." else "")
        } else {
            "Sem conexão neste ponto. Meça de novo; se continuar sem conexão, este é o limite."
        }
        if (m.ok) {
            body.addView(button("Próximo ponto") { step++; last = null; render() })
            body.addView(button("Medir de novo") { measure(d) }.apply { isEnabled = connected })
        } else {
            body.addView(button("Medir de novo") { measure(d) })
            body.addView(button("Encerrar este cenário (limite alcançado)") { step = scenario.distances.size; last = null; render() })
            body.addView(button("Próximo ponto mesmo assim") { step++; last = null; render() })
        }
    }

    private fun originText(m: Measurement) = when {
        m.origin == "confirmada" -> "Rede P2P ✓"
        m.origin.startsWith("NÃO") -> "outra rede ✗"
        m.origin == "—" -> "—"
        else -> "sem verificação"
    }

    private fun measure(distance: Int) {
        val host = ClientVpnService.currentHost ?: return
        val network = ClientVpnService.currentNetwork
        measuring = true
        last = null
        renderStep()
        val app = applicationContext
        val dev = device
        val sc = scenario.key
        Thread {
            val m = try {
                Measurer.measure(app, host, network, dev, sc, distance)
            } catch (e: Exception) {
                AppLog.e("Medição falhou", e)
                Measurement(dev, sc, distance, "", "", null, null, -1, 0.0, 0, 0, "—", false, e.message)
            }
            runOnUiThread {
                measuring = false
                last = m
                results.add(m)
                Measurer.save(this, results)
                render()
            }
        }.start()
    }

    private fun summaryText(): String {
        val lines = mutableListOf<String>()
        for (d in devices) for (sc in scenarios) {
            val rs = results.filter { it.device == d && it.scenario == sc.key }
            if (rs.isEmpty()) continue
            fun max(q: (Measurement) -> Boolean) = rs.filter(q).maxOfOrNull { it.distance }?.let { "$it m" } ?: "—"
            val v = max { it.quality == "Vídeo" }
            val u = max { it.quality == "Vídeo" || it.quality == "Útil" }
            val c = max { it.ok }
            lines += "${sc.title(d)}: vídeo até $v · útil até $u · conecta até $c"
        }
        return if (lines.isEmpty()) "Nenhuma medição ainda." else lines.joinToString("\n")
    }

    private fun renderResults() {
        summaryView.text = summaryText()
        resultsView.text = results.joinToString("\n") { m ->
            String.format(
                Locale("pt", "BR"), "%-9s %-8s %3d m  %5s  %5s  %5s  %-11s %s",
                m.device, m.scenario, m.distance, m.rssi?.let { "${it}dBm" } ?: "—",
                if (m.latencyMs >= 0) "${m.latencyMs}ms" else "—",
                if (m.ok) String.format(Locale.US, "%.1f", m.mbps) else "—", m.quality, originText(m)
            )
        }
    }

    private fun copyResults() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Medições Rede P2P", summaryText() + "\n\n" + Measurer.csv(results)))
        Toast.makeText(this, "Resultados copiados. Cole na conversa com o Claude.", Toast.LENGTH_LONG).show()
    }

    private fun sendResults() {
        var csv = Measurer.csv(results)
        if (csv.length > 5000) csv = "(início cortado)\n" + csv.takeLast(5000)
        val title = "Medições de alcance · ${Build.MANUFACTURER} ${Build.MODEL}"
        val body = "Versão: ${BuildInfo.VERSION}\n\nResumo:\n${summaryText()}\n\n```csv\n$csv```"
        val url = "https://github.com/joanirj/rede-p2p/issues/new?title=" + Uri.encode(title) + "&body=" + Uri.encode(body)
        copyResults()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            AppLog.e("Não consegui abrir o navegador. Os resultados foram copiados: cole na conversa.", e)
        }
    }
}
