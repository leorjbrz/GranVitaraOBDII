package com.vitaraobd.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.vitaraobd.app.AlarmDef
import com.vitaraobd.app.AppLog
import com.vitaraobd.app.Engine
import com.vitaraobd.app.Sensor
import com.vitaraobd.app.Settings
import com.vitaraobd.app.Units
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** O que a tela de configurações precisa pedir à Activity. */
interface SettingsHost {
    /** Abre a lista de adaptadores pareados (pede a permissão se necessário). */
    fun chooseBluetoothDevice(onChosen: () -> Unit)
    fun applyKeepScreenOn()
}

class SettingsScreen(private val ctx: Context, private val host: SettingsHost) : Screen {

    private val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply { addView(box) }
    override val view: View get() = scroll
    private val st: Settings get() = Engine.settings
    private var built = false

    override fun onShown() { if (!built) rebuild() }
    override fun refresh() {}

    // ---------------------------------------------------------------- blocos de montagem

    private fun section(title: String, hint: String? = null): LinearLayout {
        val pad = Theme.dp(ctx, 12)
        box.addView(ctx.label(title, 13f, Theme.ACCENT, true).apply { setPadding(pad, pad + pad / 2, pad, pad / 2) })
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Theme.CARD); cornerRadius = Theme.dp(ctx, 10f) }
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        if (hint != null) card.addView(ctx.label(hint, 12f, Theme.MUTED).apply { setPadding(0, pad / 3, 0, pad / 2) })
        box.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { leftMargin = pad; rightMargin = pad })
        return card
    }

    private fun LinearLayout.choice(title: String, items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        addView(ctx.label(title, 12f, Theme.MUTED).apply { setPadding(0, Theme.dp(ctx, 8), 0, 0) })
        addView(ctx.spinner(items, selected, onSelect), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun LinearLayout.check(title: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(ctx).apply {
            text = title
            textSize = 14f
            setTextColor(Theme.TEXT)
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        addView(cb, LinearLayout.LayoutParams(MATCH, WRAP))
        return cb
    }

    /** Campo numérico que grava a cada alteração válida (aceita vírgula ou ponto). */
    private fun numberField(value: Float, decimals: Int, onValid: (Float) -> Unit): EditText = EditText(ctx).apply {
        setText(String.format(Locale.US, "%.${decimals}f", value))
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        textSize = 14f
        setTextColor(Theme.TEXT)
        gravity = Gravity.END
        setSingleLine()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                s?.toString()?.replace(',', '.')?.toFloatOrNull()?.let { if (it.isFinite()) onValid(it) }
            }
        })
    }

    private fun LinearLayout.number(title: String, value: Float, decimals: Int, onValid: (Float) -> Unit) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ctx.label(title, 14f), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(numberField(value, decimals, onValid), LinearLayout.LayoutParams(Theme.dp(ctx, 96), WRAP))
        addView(row)
    }

    // ---------------------------------------------------------------- conteúdo

    private fun rebuild() {
        built = true
        box.removeAllViews()
        val pad = Theme.dp(ctx, 12)

        // ---- Adaptador
        section("ADAPTADOR", "ELM327 Bluetooth, protocolo SUZUKI K-LINE 2. Pareie o adaptador antes, nas configurações de Bluetooth do Android.").apply {
            val name = if (st.btAddress.isEmpty()) "nenhum escolhido" else "${st.btName.ifEmpty { "?" }}  ${st.btAddress}"
            addView(ctx.label("Adaptador: $name", 14f).apply { setPadding(0, pad / 2, 0, pad / 2) })
            addView(ctx.flatButton("Escolher adaptador pareado") {
                host.chooseBluetoothDevice { Engine.restart(); rebuild() }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            check("Mostrar dados prováveis (válvula de marcha lenta, tensão do sensor da borboleta)", st.experimental) { v ->
                st.experimental = v
                Engine.restart()
                rebuild()
            }
        }

        // ---- Diagnóstico
        section("DIAGNÓSTICO", "Lê os códigos de falha armazenados na ECU. Somente leitura: o app não apaga códigos.").apply {
            addView(ctx.flatButton("Ler códigos de falha") {
                val screen = DtcScreen(ctx)
                android.app.AlertDialog.Builder(ctx)
                    .setView(screen.view)
                    .setPositiveButton("Fechar", null)
                    .show()
                screen.onShown()
                screen.readIfConnected()
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        // ---- Unidades
        section("UNIDADES").apply {
            choice("Distância e velocidade", Units.DIST_NAMES, st.distUnit) { st.distUnit = it; rebuild() }
            choice("Temperatura", Units.TEMP_NAMES, st.tempUnit) { st.tempUnit = it; rebuild() }
            choice("Pressão", Units.PRESS_NAMES, st.pressUnit) { st.pressUnit = it }
            choice("Consumo", Units.ECON_NAMES, st.econUnit) { st.econUnit = it }
            choice("Volume de combustível", Units.VOL_NAMES, st.volUnit) { st.volUnit = it }
        }

        // ---- Combustível
        section("COMBUSTÍVEL E CONSUMO").apply {
            choice("Combustível", Settings.FUELS.map { it.name }, st.fuelType) { st.fuelType = it }
            number("Preço por litro", st.fuelPrice, 2) { st.fuelPrice = it }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ctx.label("Símbolo da moeda", 14f), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(EditText(ctx).apply {
                setText(st.currency)
                textSize = 14f
                setTextColor(Theme.TEXT)
                gravity = Gravity.END
                setSingleLine()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { st.currency = s?.toString()?.trim().orEmpty() }
                })
            }, LinearLayout.LayoutParams(Theme.dp(ctx, 96), WRAP))
            addView(row)
            number("Coeficiente de combustível", st.fuelCoef, 3) { if (it in 0.5f..2f) st.fuelCoef = it }
            number("Coeficiente de distância", st.distCoef, 3) { if (it in 0.5f..2f) st.distCoef = it }
            addView(ctx.label("Calibração: coeficiente = valor real ÷ valor medido pelo app (litros da bomba, km do hodômetro). 1,000 = sem correção.", 12f, Theme.MUTED))
            check("Zerar o consumo durante o corte de combustível informado pela ECU", st.fuelCut) { st.fuelCut = it }
        }

        // ---- Painel
        val gaugeNames = Sensor.GAUGE_CHOICES.map { it.title }
        section("PAINEL").apply {
            choice("Mostrador esquerdo", gaugeNames, Sensor.GAUGE_CHOICES.indexOf(st.gauge1)) { st.gauge1 = Sensor.GAUGE_CHOICES[it] }
            choice("Mostrador direito", gaugeNames, Sensor.GAUGE_CHOICES.indexOf(st.gauge2)) { st.gauge2 = Sensor.GAUGE_CHOICES[it] }
            check("Mostrar mini-gráfico (últimos 30 s) nos blocos", st.showSparkline) { st.showSparkline = it }
            check("Manter a tela sempre ligada", st.keepScreenOn) { st.keepScreenOn = it; host.applyKeepScreenOn() }
        }

        // ---- Sensores no painel
        val tiles = st.tiles.toMutableSet()
        val visible = Sensor.entries.filter { !it.experimental || st.experimental }
        section("SENSORES NO PAINEL", "Marque o que deve aparecer como bloco na tela inicial.").apply {
            val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, pad / 2) }
            buttons.addView(ctx.flatButton("Marcar todos") { st.tiles = visible.toSet(); rebuild() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = pad / 3 })
            buttons.addView(ctx.flatButton("Padrão") { st.tiles = Settings.DEFAULT_TILES; rebuild() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = pad / 3; rightMargin = pad / 3 })
            buttons.addView(ctx.flatButton("Limpar") { st.tiles = emptySet(); rebuild() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = pad / 3 })
            addView(buttons)
            for ((group, sensors) in visible.groupBy { it.group }) {
                addView(ctx.label(group.uppercase(), 11f, Theme.MUTED, true).apply { setPadding(0, pad / 2, 0, 0) })
                for (s in sensors) {
                    check("${s.title}  (${Units.unit(s, st).ifEmpty { "h:mm:ss" }})", s in tiles) { on ->
                        if (on) tiles += s else tiles -= s
                        st.tiles = tiles.toSet()
                    }
                }
            }
        }

        // ---- Alarmes
        section("ALARMES", "Quando disparar: o bloco fica vermelho, aparece um aviso no topo do painel e, se ativado, toca um som.").apply {
            for (a in AlarmDef.entries) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val cb = CheckBox(ctx).apply {
                    text = "${a.title}  [${Units.unit(a.sensor, st)}]"
                    textSize = 14f
                    setTextColor(Theme.TEXT)
                    isChecked = st.alarmEnabled(a)
                    setOnCheckedChangeListener { _, v -> st.setAlarmEnabled(a, v) }
                }
                row.addView(cb, LinearLayout.LayoutParams(0, WRAP, 1f))
                // O limite é guardado na unidade base e mostrado na unidade escolhida.
                val shown = Units.convert(a.sensor, st.alarmLimit(a), st)
                row.addView(numberField(shown, a.sensor.decimals) { st.setAlarmLimit(a, Units.toBase(a.sensor, it, st)) },
                    LinearLayout.LayoutParams(Theme.dp(ctx, 84), WRAP))
                addView(row)
            }
            check("Tocar som de alarme", st.alarmSound) { st.alarmSound = it }
        }

        // ---- Gravação
        section("GRAVAÇÃO").apply {
            check("Gravar os dados em CSV (1 linha por segundo)", st.csvLogging) { st.csvLogging = it }
            addView(ctx.label("Arquivos em:\n${Engine.csvDir().absolutePath}\n\nLog do app em:\n${AppLog.logFile()?.parent ?: "-"}", 12f, Theme.MUTED).apply {
                setTextIsSelectable(true)
                setPadding(0, pad / 2, 0, pad / 2)
            })
        }

        // ---- Sobre
        section("SOBRE").apply {
            addView(ctx.label(
                "Vitara OBD. Suzuki (Grand) Vitara 2.0 (J20A), SUZUKI K-LINE 2, ELM327 Bluetooth.\n\n" +
                    "Somente leitura: o app não apaga falhas nem grava nada na ECU.\n\n" +
                    "A velocidade mostrada é a informada pela central do motor e pode diferir um pouco do velocímetro.",
                12f, Theme.MUTED,
            ).apply { setPadding(0, pad / 2, 0, pad / 2) })
        }
        box.addView(View(ctx), LinearLayout.LayoutParams(MATCH, pad * 2))
    }
}
