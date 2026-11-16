package com.vitaraobd.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.vitaraobd.app.AppLog
import com.vitaraobd.app.ConnState
import com.vitaraobd.app.Engine
import com.vitaraobd.app.Sensor
import com.vitaraobd.app.Settings
import com.vitaraobd.app.Units

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal fun Context.label(text: String, sizeSp: Float = 14f, color: Int = Theme.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

internal fun Context.flatButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 13f
    setTextColor(Theme.TEXT)
    background = GradientDrawable().apply { setColor(Theme.CARD_ALT); cornerRadius = Theme.dp(context, 8f) }
    setOnClickListener { onClick() }
}

internal fun Context.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): Spinner = Spinner(this).apply {
    adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
    setSelection(selected.coerceIn(0, maxOf(0, items.size - 1)), false)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        // O Spinner dispara uma seleção ao ser montado; ignora o que não for mudança real.
        private var last = selectedItemPosition
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (position != last) { last = position; onSelect(position) }
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
}

/** Contrato comum das quatro telas. */
interface Screen {
    val view: View
    /** Chamado a cada leitura enquanto a tela está visível. */
    fun refresh()
    fun onShown() {}
    fun onHidden() {}
}

// =====================================================================================
// PAINEL
// =====================================================================================

class DashboardScreen(private val ctx: Context) : Screen {

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root

    private val statusDot = View(ctx)
    private val statusText = ctx.label("", 13f)
    private val rateText = ctx.label("", 11f, Theme.MUTED)
    private val alarmBanner = ctx.label("", 14f, Theme.TEXT, true)
    /** Recebe o conteúdo montado conforme a orientação (retrato ou paisagem). */
    private val container = android.widget.FrameLayout(ctx)
    private val liveViews = ArrayList<View>()
    private var builtRevision = -1
    private var builtLandscape = false

    init {
        val pad = Theme.dp(ctx, 10)
        val status = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, Theme.dp(ctx, 6))
        }
        status.addView(statusDot, LinearLayout.LayoutParams(Theme.dp(ctx, 10), Theme.dp(ctx, 10)).apply { rightMargin = Theme.dp(ctx, 8) })
        status.addView(statusText, LinearLayout.LayoutParams(0, WRAP, 1f))
        status.addView(rateText)
        root.addView(status)

        alarmBanner.apply {
            setBackgroundColor(Theme.DANGER)
            setPadding(pad, Theme.dp(ctx, 8), pad, Theme.dp(ctx, 8))
            visibility = View.GONE
        }
        root.addView(alarmBanner, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    /**
     * Remonta mostradores e blocos conforme as configurações e a orientação da tela.
     *  - Retrato: mostradores em cima, blocos em 2 colunas abaixo, tudo rolando junto.
     *  - Paisagem: mostradores fixos à esquerda, blocos em 3 colunas rolando à direita.
     */
    private fun rebuild() {
        val st = Engine.settings
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        builtRevision = st.revision
        builtLandscape = landscape
        container.removeAllViews()
        liveViews.clear()
        val gap = Theme.dp(ctx, 8)

        val gauges = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gaugeViews = listOf(st.gauge1, st.gauge2).map { s -> GaugeView(ctx).apply { sensor = s } }
        liveViews += gaugeViews

        val tiles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chosen = Sensor.entries.filter { it in st.tiles && (!it.experimental || st.experimental) }
        if (chosen.isEmpty()) {
            tiles.addView(ctx.label("Nenhum sensor selecionado. Escolha em Config → Sensores no painel.", 13f, Theme.MUTED).apply {
                setPadding(gap, gap * 2, gap, gap)
            })
        }
        val columns = if (landscape) 3 else 2
        val tileHeight = Theme.dp(ctx, if (st.showSparkline) (if (landscape) 92 else 104) else 78)
        for (rowItems in chosen.chunked(columns)) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                val lp = LinearLayout.LayoutParams(0, tileHeight, 1f).apply { setMargins(gap / 2, gap / 2, gap / 2, gap / 2) }
                val s = rowItems.getOrNull(i)
                if (s == null) row.addView(View(ctx), lp)
                else {
                    val t = TileView(ctx, s)
                    row.addView(t, lp)
                    liveViews += t
                }
            }
            tiles.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (chosen.any { it.experimental }) {
            tiles.addView(ctx.label("* provável: interpretação ainda não confirmada.", 11f, Theme.MUTED).apply {
                setPadding(gap, gap, gap, 0)
            })
        }

        if (landscape) {
            // Os mostradores ocupam toda a altura disponível; o GaugeView se ajusta ao menor lado.
            for (g in gaugeViews) gauges.addView(g, LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(gap / 2, 0, gap / 2, gap) })
            tiles.setPadding(0, 0, gap, gap)
            val split = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(gap, 0, 0, 0) }
            split.addView(gauges, LinearLayout.LayoutParams(0, MATCH, 0.95f))
            split.addView(ScrollView(ctx).apply { addView(tiles) }, LinearLayout.LayoutParams(0, MATCH, 1.05f))
            container.addView(split)
        } else {
            val gaugeSize = (ctx.resources.displayMetrics.widthPixels - gap * 3) / 2
            for (g in gaugeViews) gauges.addView(g, LinearLayout.LayoutParams(gaugeSize, gaugeSize).apply { leftMargin = gap / 2; rightMargin = gap / 2 })
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(gap, 0, gap, gap) }
            column.addView(gauges, LinearLayout.LayoutParams(MATCH, WRAP))
            column.addView(tiles, LinearLayout.LayoutParams(MATCH, WRAP))
            container.addView(ScrollView(ctx).apply { addView(column) })
        }
    }

    override fun onShown() = refresh()

    override fun refresh() {
        val st = Engine.settings
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (builtRevision != st.revision || builtLandscape != landscape) rebuild()

        val state = Engine.state
        val color = when (state) {
            ConnState.RUNNING -> Theme.OK
            ConnState.ERROR -> Theme.DANGER
            ConnState.STOPPED -> Theme.MUTED
            else -> Theme.WARN
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        val detail = Engine.stateDetail
        statusText.text = buildString {
            append(state.label)
            if (detail.isNotEmpty() && state != ConnState.RUNNING) append(" · ").append(detail)
        }
        rateText.text = when {
            state != ConnState.RUNNING -> ""
            Engine.statusFlags.isNotEmpty() -> Engine.statusFlags
            else -> String.format("%.1f leituras/s", Engine.sampleRate)
        }

        if (Engine.activeAlarms.isEmpty()) {
            alarmBanner.visibility = View.GONE
        } else {
            alarmBanner.visibility = View.VISIBLE
            alarmBanner.text = Engine.activeAlarms.joinToString("\n") { a ->
                "⚠ ${a.sensor.title}: ${Units.format(a.sensor, Engine.values[a.sensor.ordinal], st)} ${Units.unit(a.sensor, st)}"
            }
        }
        for (v in liveViews) v.invalidate()
    }
}

// =====================================================================================
// VIAGEM
// =====================================================================================

class TripScreen(private val ctx: Context) : Screen {

    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { addView(box) }
    override val view: View get() = scroll
    private val rows = ArrayList<Pair<Sensor, TextView>>()
    private var builtRevision = -1
    private var builtLandscape = false

    private fun isLandscape(): Boolean = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun section(parent: LinearLayout, title: String, sensors: List<Sensor>) {
        val pad = Theme.dp(ctx, 12)
        parent.addView(ctx.label(title, 13f, Theme.ACCENT, true).apply { setPadding(pad, pad + pad / 2, pad, pad / 2) })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.CARD); cornerRadius = Theme.dp(ctx, 10f) }
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        for (s in sensors) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad / 2, 0, pad / 2) }
            row.addView(ctx.label(s.title, 14f, Theme.MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
            val value = ctx.label("--", 16f, Theme.TEXT, true)
            row.addView(value)
            rows += s to value
            card.addView(row)
        }
        parent.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = pad; rightMargin = pad })
    }

    private fun rebuild() {
        builtRevision = Engine.settings.revision
        builtLandscape = isLandscape()
        box.removeAllViews()
        rows.clear()

        // Paisagem: viagem atual à esquerda; máximos e totais à direita.
        val left: LinearLayout
        val right: LinearLayout
        if (builtLandscape) {
            left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val columns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            columns.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            columns.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f))
            box.addView(columns, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            left = box
            right = box
        }
        section(left, "VIAGEM ATUAL", listOf(
            Sensor.TRIP_DIST, Sensor.TRIP_TIME, Sensor.AVG_SPEED, Sensor.ECON_TRIP, Sensor.TRIP_FUEL,
            Sensor.TRIP_COST, Sensor.CO2, Sensor.IDLE_TIME, Sensor.IDLE_FUEL,
        ))
        section(right, "MÁXIMOS DA VIAGEM", listOf(Sensor.MAX_SPEED, Sensor.MAX_RPM, Sensor.MAX_COOLANT))
        section(right, "TOTAIS", listOf(Sensor.TOTAL_DIST, Sensor.TOTAL_FUEL, Sensor.ECON_TOTAL, Sensor.TOTAL_COST))

        val pad = Theme.dp(ctx, 12)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(pad, pad * 2, pad, pad * 2) }
        buttons.addView(ctx.flatButton("Zerar viagem") {
            confirm("Zerar a viagem atual?") { Engine.resetTrip() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = pad / 2 })
        buttons.addView(ctx.flatButton("Zerar totais") {
            confirm("Zerar os totais acumulados? Não pode ser desfeito.") { Engine.resetTotals() }
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = pad / 2 })
        box.addView(buttons)
        box.addView(ctx.label(
            "Consumo calculado pela vazão de ar do motor. Para aproximar do consumo real, ajuste os coeficientes em Config → Combustível depois de comparar com um abastecimento.",
            12f, Theme.MUTED,
        ).apply { setPadding(pad, 0, pad, pad * 2) })
    }

    private fun confirm(message: String, action: () -> Unit) {
        AlertDialog.Builder(ctx).setMessage(message)
            .setPositiveButton("Zerar") { _, _ -> action() }
            .setNegativeButton("Cancelar", null).show()
    }

    override fun onShown() = refresh()

    override fun refresh() {
        val st = Engine.settings
        if (builtRevision != st.revision || builtLandscape != isLandscape()) rebuild()
        for ((s, tv) in rows) {
            val unit = Units.unit(s, st)
            val text = Units.format(s, Engine.values[s.ordinal], st)
            tv.text = when {
                text == "--" || unit.isEmpty() -> text
                s.kind == com.vitaraobd.app.Kind.MONEY -> "$unit $text"
                else -> "$text $unit"
            }
        }
    }
}

// =====================================================================================
// LOGS EM TEMPO REAL
// =====================================================================================

class LogsScreen(private val ctx: Context) : Screen {

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View get() = root

    private val text = TextView(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10.5f
        setTextColor(Theme.TEXT)
        setTextIsSelectable(true)
        val p = Theme.dp(ctx, 8)
        setPadding(p, p, p, p)
    }
    private val scroll = ScrollView(ctx).apply { addView(text); setBackgroundColor(android.graphics.Color.BLACK) }
    private val handler = Handler(Looper.getMainLooper())
    private var shownRevision = -1L
    private var minLevel = AppLog.Level.DEBUG
    private var paused = false
    private var visible = false
    private lateinit var pauseButton: Button

    private val poll = object : Runnable {
        override fun run() {
            if (!visible) return
            if (!paused && AppLog.revision != shownRevision) render()
            handler.postDelayed(this, 300)
        }
    }

    init {
        val pad = Theme.dp(ctx, 8)
        // Filtro em linha própria: ao lado dos botões o texto ficava cortado.
        root.addView(ctx.spinner(listOf("Mostrar tudo (inclui TX/RX com o adaptador)", "Info, avisos e erros", "Só avisos e erros"), 0) { i ->
            minLevel = when (i) { 1 -> AppLog.Level.INFO; 2 -> AppLog.Level.WARN; else -> AppLog.Level.DEBUG }
            render()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = pad; rightMargin = pad; topMargin = pad / 2 })
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, pad / 2, pad, pad) }
        pauseButton = ctx.flatButton("Pausar") {
            paused = !paused
            pauseButton.text = if (paused) "Continuar" else "Pausar"
            if (!paused) render()
        }
        bar.addView(pauseButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(ctx.flatButton("Limpar") { AppLog.clear(); render() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = pad })
        bar.addView(ctx.flatButton("Enviar") { share() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = pad })
        root.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun render() {
        shownRevision = AppLog.revision
        val entries = AppLog.snapshot(minLevel).takeLast(400)
        val sb = android.text.SpannableStringBuilder()
        for (e in entries) {
            val start = sb.length
            sb.append(e.line()).append('\n')
            val color = when (e.level) {
                AppLog.Level.ERROR -> Theme.DANGER
                AppLog.Level.WARN -> Theme.WARN
                AppLog.Level.INFO -> Theme.TEXT
                AppLog.Level.DEBUG -> Theme.MUTED
            }
            sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, 0)
        }
        text.text = sb
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Envia o que está na memória como texto (e-mail, WhatsApp, Drive…). */
    private fun share() {
        val body = AppLog.snapshot(AppLog.Level.DEBUG).joinToString("\n") { it.line() }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Vitara OBD - log")
            putExtra(Intent.EXTRA_TEXT, body.takeLast(90_000))
        }
        try {
            ctx.startActivity(Intent.createChooser(intent, "Enviar log"))
        } catch (e: Exception) {
            AppLog.e("LOGS", "não foi possível compartilhar", e)
        }
    }

    override fun onShown() {
        visible = true
        render()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 300)
    }

    override fun onHidden() {
        visible = false
        handler.removeCallbacks(poll)
    }

    /** A atualização é por temporizador próprio, não por leitura do veículo. */
    override fun refresh() {}
}
