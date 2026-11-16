package com.vitaraobd.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vitaraobd.app.ConnState
import com.vitaraobd.app.Dtc
import com.vitaraobd.app.DtcResult
import com.vitaraobd.app.Engine
import com.vitaraobd.app.SuzukiBlock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Tela de falhas (aberta em Config → Diagnóstico): lê os códigos armazenados na ECU. Somente leitura. */
class DtcScreen(private val ctx: Context) : Screen {

    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { addView(box) }
    override val view: View get() = scroll

    private val status = ctx.label("", 15f, Theme.TEXT, true)
    private val detail = ctx.label("", 12f, Theme.MUTED)
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val rawView = ctx.label("", 11f, Theme.MUTED).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
    private lateinit var readButton: Button
    private var reading = false

    init {
        val pad = Theme.dp(ctx, 12)
        box.setPadding(pad, pad, pad, pad * 2)
        box.addView(ctx.label("CÓDIGOS DE FALHA", 13f, Theme.ACCENT, true).apply { setPadding(0, pad / 2, 0, pad / 2) })

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.CARD); cornerRadius = Theme.dp(ctx, 10f) }
            setPadding(pad, pad, pad, pad)
        }
        status.text = "Ainda não lido nesta sessão."
        card.addView(status)
        card.addView(detail.apply { setPadding(0, pad / 3, 0, 0) })
        card.addView(list, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = pad / 2 })
        box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))

        readButton = ctx.flatButton("Ler códigos de falha") { read() }
        box.addView(readButton, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = pad })
        box.addView(ctx.flatButton("Ver um exemplo de como as falhas aparecem") { showExample() },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = pad / 2 })

        box.addView(ctx.label(
            "A leitura usa o serviço de diagnóstico da própria Suzuki e funciona com o motor ligado ou só com a ignição. " +
                "Este app apenas lê: não apaga códigos nem altera nada na ECU.\n\n" +
                "A exibição de códigos segue o formato padrão do protocolo e ainda não foi confirmada com uma falha real " +
                "neste modelo. Se aparecer algum código, confira a resposta crua abaixo. " +
                "As descrições são as definições genéricas OBD‑II, não a tabela da Suzuki.",
            12f, Theme.MUTED,
        ).apply { setPadding(0, pad, 0, pad) })
        box.addView(rawView)
    }

    private fun read() {
        if (reading) return
        reading = true
        readButton.isEnabled = false
        status.text = "Lendo…"
        status.setTextColor(Theme.TEXT)
        detail.text = ""
        list.removeAllViews()
        rawView.text = ""
        Engine.readDtcs { r -> show(r) }
    }

    /** Mostra dois códigos inventados, só para o usuário conhecer a apresentação. */
    private fun showExample() {
        show(DtcResult(
            true, "EXEMPLO — não é uma leitura do carro",
            listOf(Dtc("P0120", 0x0120, 0xE0), Dtc("P0300", 0x0300, 0x20)),
            ecuPart = "", raw = "",
        ))
        detail.text = "Códigos inventados, apenas para mostrar a apresentação."
    }

    private fun show(r: DtcResult) {
        reading = false
        readButton.isEnabled = true
        status.text = r.message
        status.setTextColor(when {
            !r.ok -> Theme.WARN
            r.codes.isEmpty() -> Theme.OK
            else -> Theme.DANGER
        })
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date())
        detail.text = buildString {
            append("Lido em $time")
            if (r.ecuPart.isNotEmpty()) append("\nECU: ${r.ecuPart}")
        }
        list.removeAllViews()
        val pad = Theme.dp(ctx, 8)
        for (c in r.codes) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad, 0, pad) }
            row.addView(ctx.label(c.code, 20f, Theme.DANGER, true))
            val description = SuzukiBlock.describe(c.code).ifEmpty { "Sem descrição genérica para este código" }
            row.addView(ctx.label(description, 13f))
            row.addView(ctx.label("Situação: " + SuzukiBlock.statusText(c.status), 13f, Theme.WARN))
            row.addView(ctx.label(String.format("bytes %04X · estado %02X", c.raw, c.status), 11f, Theme.MUTED))
            list.addView(row)
            // Linha separadora entre códigos.
            list.addView(View(ctx).apply { setBackgroundColor(Theme.TRACK) }, LinearLayout.LayoutParams(MATCH, Theme.dp(ctx, 1)))
        }
        rawView.text = if (r.raw.isEmpty()) "" else "Resposta crua da ECU:\n${r.raw}"
    }

    /** Ao abrir a tela já faz a leitura, se houver comunicação. */
    fun readIfConnected() {
        if (Engine.state == ConnState.RUNNING) read()
    }

    override fun onShown() = refresh()

    /** O botão só fica disponível enquanto há comunicação com a ECU. */
    override fun refresh() {
        if (reading) return
        val connected = Engine.state == ConnState.RUNNING
        readButton.isEnabled = connected
        readButton.text = if (connected) "Ler códigos de falha" else "Ler códigos de falha (sem comunicação com a ECU)"
    }
}
