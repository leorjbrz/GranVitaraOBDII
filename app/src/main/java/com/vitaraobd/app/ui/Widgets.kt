package com.vitaraobd.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.vitaraobd.app.Engine
import com.vitaraobd.app.Sensor
import com.vitaraobd.app.Units
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Cores e medidas compartilhadas pelas telas. */
object Theme {
    val BG = Color.rgb(0x0D, 0x11, 0x17)
    val CARD = Color.rgb(0x18, 0x1E, 0x27)
    val CARD_ALT = Color.rgb(0x22, 0x2A, 0x36)
    val TEXT = Color.rgb(0xEA, 0xEE, 0xF3)
    val MUTED = Color.rgb(0x8B, 0x96, 0xA6)
    val ACCENT = Color.rgb(0x2E, 0xA8, 0xFF)
    val OK = Color.rgb(0x3D, 0xD6, 0x8C)
    val WARN = Color.rgb(0xFF, 0xB0, 0x20)
    val DANGER = Color.rgb(0xFF, 0x4D, 0x4D)
    val DANGER_BG = Color.rgb(0x4A, 0x16, 0x16)
    val TRACK = Color.rgb(0x2A, 0x33, 0x40)

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}

/** Mostrador circular em arco de 270°, com faixa vermelha opcional. */
class GaugeView(ctx: Context) : View(ctx) {

    var sensor: Sensor = Sensor.RPM

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Theme.TRACK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Theme.DANGER; alpha = 150 }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED; textAlign = Paint.Align.CENTER }
    private val box = RectF()

    /** Valor mostrado, suavizado entre leituras para o ponteiro não "pular". */
    private var shown = Float.NaN

    override fun onDraw(c: Canvas) {
        val st = Engine.settings
        val base = Engine.values[sensor.ordinal]
        val (lo, hi) = Units.range(sensor, st)
        val v = Units.convert(sensor, base, st)

        shown = when {
            v.isNaN() -> Float.NaN
            shown.isNaN() -> v
            else -> shown + (v - shown) * 0.45f
        }

        val size = min(width, height).toFloat()
        val stroke = size * 0.075f
        val cx = width / 2f
        val cy = height / 2f + size * 0.04f
        val r = size / 2f - stroke
        box.set(cx - r, cy - r, cx + r, cy + r)

        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        c.drawArc(box, START, SWEEP, false, track)

        // Faixa vermelha (limite convertido para a unidade de exibição).
        if (!sensor.redFrom.isNaN()) {
            val from = ((Units.convert(sensor, sensor.redFrom, st) - lo) / (hi - lo)).coerceIn(0f, 1f)
            red.strokeWidth = stroke * 0.35f
            val inner = RectF(box).apply { inset(stroke * 0.85f, stroke * 0.85f) }
            c.drawArc(inner, START + SWEEP * from, SWEEP * (1f - from), false, red)
        }

        // Marcas a cada 10 % da escala.
        tick.strokeWidth = size * 0.006f
        for (i in 0..10) {
            val a = Math.toRadians((START + SWEEP * i / 10f).toDouble())
            val r1 = r + stroke * 0.75f
            val r2 = r + stroke * 1.0f
            c.drawLine(cx + (r1 * cos(a)).toFloat(), cy + (r1 * sin(a)).toFloat(), cx + (r2 * cos(a)).toFloat(), cy + (r2 * sin(a)).toFloat(), tick)
        }

        val alarming = Engine.isAlarming(sensor)
        if (!shown.isNaN()) {
            val frac = ((shown - lo) / (hi - lo)).coerceIn(0f, 1f)
            val inRed = !sensor.redFrom.isNaN() && base >= sensor.redFrom
            arc.color = if (alarming || inRed) Theme.DANGER else Theme.ACCENT
            if (frac > 0.002f) c.drawArc(box, START, SWEEP * frac, false, arc)
        }

        valueText.color = if (alarming) Theme.DANGER else Theme.TEXT
        valueText.textSize = size * 0.21f
        c.drawText(Units.format(sensor, base, st), cx, cy + size * 0.05f, valueText)
        smallText.textSize = size * 0.075f
        c.drawText(Units.unit(sensor, st), cx, cy + size * 0.16f, smallText)
        c.drawText(sensor.short, cx, cy + r * 0.78f, smallText)

        // Continua animando enquanto o ponteiro não alcançou o valor.
        if (!shown.isNaN() && !v.isNaN() && kotlin.math.abs(v - shown) > (hi - lo) * 0.002f) postInvalidateOnAnimation()
    }

    companion object {
        private const val START = 135f
        private const val SWEEP = 270f
    }
}

/** Bloco de um sensor: nome, valor grande, unidade e mini-gráfico dos últimos 30 s. */
class TileView(ctx: Context, val sensor: Sensor) : View(ctx) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.MUTED }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val st = Engine.settings
        val base = Engine.values[sensor.ordinal]
        val alarming = Engine.isAlarming(sensor)
        val pad = Theme.dp(context, 10f)
        val radius = Theme.dp(context, 10f)

        bg.color = if (alarming) Theme.DANGER_BG else Theme.CARD
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, radius, radius, bg)

        label.textSize = Theme.dp(context, 11f)
        c.drawText(sensor.short + if (sensor.experimental) " *" else "", pad, pad + label.textSize * 0.8f, label)

        value.textSize = Theme.dp(context, 28f)
        value.color = if (alarming) Theme.DANGER else Theme.TEXT
        val text = Units.format(sensor, base, st)
        val baseline = pad + label.textSize + value.textSize * 1.02f
        c.drawText(text, pad, baseline, value)

        unit.textSize = Theme.dp(context, 12f)
        c.drawText(Units.unit(sensor, st), pad + value.measureText(text) + Theme.dp(context, 5f), baseline, unit)

        if (st.showSparkline) drawSparkline(c, pad, baseline + Theme.dp(context, 6f), width - pad, height - pad * 0.6f, alarming)
    }

    private fun drawSparkline(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, alarming: Boolean) {
        if (bottom - top < Theme.dp(context, 8f)) return
        val h = Engine.history[sensor.ordinal]
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var n = 0
        for (x in h) if (!x.isNaN()) { lo = min(lo, x); hi = maxOf(hi, x); n++ }
        if (n < 2) return
        // Evita ampliar ruído: a escala mínima é 2 % da faixa do sensor.
        val minSpan = (sensor.max - sensor.min) * 0.02f
        if (hi - lo < minSpan) { val mid = (hi + lo) / 2f; lo = mid - minSpan / 2f; hi = mid + minSpan / 2f }

        path.reset()
        var started = false
        for (i in 0 until Engine.HISTORY) {
            val x = h[(Engine.historyPos + 1 + i) % Engine.HISTORY]       // do mais antigo para o mais novo
            if (x.isNaN()) { started = false; continue }
            val px = left + (right - left) * i / (Engine.HISTORY - 1)
            val py = bottom - (bottom - top) * (x - lo) / (hi - lo)
            if (started) path.lineTo(px, py) else { path.moveTo(px, py); started = true }
        }
        line.strokeWidth = Theme.dp(context, 1.6f)
        line.color = if (alarming) Theme.DANGER else Theme.ACCENT
        line.alpha = 190
        c.drawPath(path, line)
    }
}
