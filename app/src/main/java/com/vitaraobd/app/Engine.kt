package com.vitaraobd.app

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(File(getExternalFilesDir(null) ?: filesDir, "logs"))
        AppLog.i("APP", "app iniciado")
        Engine.init(this)
    }
}

/**
 * Núcleo do app: dono da fonte de dados, do computador de bordo, dos alarmes e da gravação.
 * Vive enquanto o processo viver, independente da tela aberta.
 */
object Engine {

    const val HISTORY = 60

    lateinit var settings: Settings
        private set
    private lateinit var appContext: Context
    private lateinit var trip: TripComputer
    private val ui = Handler(Looper.getMainLooper())

    private var source: DataSource? = null
    private val lock = Any()

    /** Valores atuais por Sensor.ordinal, unidade base; NaN = sem dado. Ler só na thread principal. */
    val values = FloatArray(Sensor.entries.size) { Float.NaN }
    /** Histórico curto por sensor para os mini-gráficos (anel circular). */
    val history = Array(Sensor.entries.size) { FloatArray(HISTORY) { Float.NaN } }
    var historyPos = 0
        private set

    @Volatile var state: ConnState = ConnState.STOPPED
        private set
    @Volatile var stateDetail: String = ""
        private set
    @Volatile var sourceName: String = ""
        private set
    /** Leituras por segundo. */
    @Volatile var sampleRate: Float = 0f
        private set

    /** Estados informados pela ECU na última leitura (ex.: "A/C · corte de combustível"). */
    var statusFlags: String = ""
        private set

    val activeAlarms = LinkedHashSet<AlarmDef>()

    /** Chamado na thread principal a cada leitura ou mudança de estado. */
    var listener: (() -> Unit)? = null

    private val work = FloatArray(Sensor.entries.size)
    private var lastHistoryMs = 0L
    private var rateCount = 0
    private var rateStartMs = 0L
    private var lastBeepMs = 0L
    private var tone: ToneGenerator? = null
    private var csvFile: File? = null
    private var lastCsvMs = 0L

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        settings = Settings(appContext)
        trip = TripComputer(settings)
        Gps.init(appContext)
        Journeys.init(appContext)
        start()
    }

    // ---------------------------------------------------------------- fonte

    private fun createSource(): DataSource = ElmSource(appContext, settings.btAddress, settings.experimental)

    fun start() {
        synchronized(lock) {
            if (source != null) return
            val s = createSource()
            source = s
            sourceName = s.name
            csvFile = null
            AppLog.i("ENGINE", "iniciando fonte: ${s.name}")
            val token = Any()
            currentToken = token
            s.start(Sink(token))
        }
    }

    fun stop() {
        synchronized(lock) {
            currentToken = null
            source?.stop()
            source = null
            trip.save()
        }
        post {
            state = ConnState.STOPPED
            stateDetail = ""
            values.fill(Float.NaN)
            for (h in history) h.fill(Float.NaN)      // mini-gráficos não misturam dados de fontes diferentes
            activeAlarms.clear()
            listener?.invoke()
        }
        AppLog.i("ENGINE", "fonte parada")
    }

    /** Aplica mudanças de fonte/cenário feitas nas configurações. */
    fun restart() {
        stop()
        start()
    }

    /** Lê os códigos de falha. O resultado chega na thread principal. */
    fun readDtcs(callback: (DtcResult) -> Unit) {
        val s = synchronized(lock) { source }
        if (s == null || state != ConnState.RUNNING) {
            callback(DtcResult(false, "Sem comunicação com a ECU. Ligue a ignição e aguarde a conexão."))
            return
        }
        s.readDtcs { result -> post { callback(result) } }
    }

    fun resetTrip() = synchronized(lock) { trip.resetTrip() }
    fun resetTotals() = synchronized(lock) { trip.resetTotals() }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else ui.post(block)
    }

    /** Só a fonte atual pode publicar: callbacks atrasados de uma fonte já parada são ignorados. */
    @Volatile private var currentToken: Any? = null

    private class Sink(private val token: Any) : SourceSink {
        override fun onState(state: ConnState, detail: String) {
            if (token !== currentToken) return
            AppLog.i("ENGINE", "estado: ${state.label}${if (detail.isEmpty()) "" else " ($detail)"}")
            post {
                if (token !== currentToken) return@post
                Engine.state = state
                stateDetail = detail
                if (state != ConnState.RUNNING) {
                    values.fill(Float.NaN)
                    activeAlarms.clear()
                }
                listener?.invoke()
            }
        }

        override fun onSample(sample: Sample) {
            if (token !== currentToken) return
            val snapshot: FloatArray
            synchronized(lock) {
                trip.update(sample, work)
                snapshot = work.copyOf()
            }
            writeCsv(sample.timeMs, snapshot)
            val flags = ArrayList<String>()
            if (sample.acOn) flags += "A/C"
            if (sample.fuelCut) flags += "corte de combustível"
            else if (sample.openLoop && sample.engineRunning) flags += "malha aberta"
            val text = flags.joinToString(" · ")
            post {
                if (token === currentToken) {
                    statusFlags = text
                    publish(sample.timeMs, snapshot)
                }
            }
        }
    }

    private fun publish(timeMs: Long, snapshot: FloatArray) {
        System.arraycopy(snapshot, 0, values, 0, values.size)

        // Histórico a 2 Hz: 60 pontos = últimos 30 s.
        if (timeMs - lastHistoryMs >= 500) {
            lastHistoryMs = timeMs
            historyPos = (historyPos + 1) % HISTORY
            for (i in values.indices) history[i][historyPos] = values[i]
        }

        rateCount++
        if (rateStartMs == 0L) rateStartMs = timeMs
        if (timeMs - rateStartMs >= 2000) {
            sampleRate = rateCount * 1000f / (timeMs - rateStartMs)
            rateCount = 0
            rateStartMs = timeMs
        }

        checkAlarms(timeMs)
        Journeys.onSnapshot(timeMs, values)
        listener?.invoke()
    }

    // ---------------------------------------------------------------- alarmes

    private fun checkAlarms(timeMs: Long) {
        for (a in AlarmDef.entries) {
            val v = values[a.sensor.ordinal]
            val limit = settings.alarmLimit(a)
            // Bateria só é avaliada com valor plausível (evita alarme com leitura ausente).
            val on = settings.alarmEnabled(a) && !v.isNaN() && (if (a.isMax) v > limit else v < limit)
            if (on && activeAlarms.add(a)) {
                AppLog.w("ALARME", "${a.title} $limit — valor atual ${"%.1f".format(Locale.US, v)}")
            } else if (!on && activeAlarms.remove(a)) {
                AppLog.i("ALARME", "normalizado: ${a.title}")
            }
        }
        if (activeAlarms.isNotEmpty() && settings.alarmSound && timeMs - lastBeepMs > 4000) {
            lastBeepMs = timeMs
            try {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { tone = it }
                t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
            } catch (e: Exception) {
                AppLog.e("ALARME", "não foi possível tocar o som", e)
            }
        }
    }

    fun isAlarming(s: Sensor): Boolean = activeAlarms.any { it.sensor == s }

    // ---------------------------------------------------------------- gravação CSV

    /** Uma linha por segundo com todos os sensores, na unidade base. */
    private fun writeCsv(timeMs: Long, v: FloatArray) {
        if (!settings.csvLogging) { csvFile = null; return }
        if (timeMs - lastCsvMs < 1000) return
        lastCsvMs = timeMs
        try {
            var f = csvFile
            if (f == null) {
                val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "viagens")
                dir.mkdirs()
                f = File(dir, "viagem_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
                f.writeText("hora;" + Sensor.entries.joinToString(";") { it.name } + "\n")
                csvFile = f
                AppLog.i("CSV", "gravando em ${f.absolutePath}")
            }
            val row = StringBuilder(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs)))
            for (x in v) row.append(';').append(if (x.isNaN()) "" else String.format(Locale.US, "%.3f", x))
            FileWriter(f, true).use { it.write(row.append('\n').toString()) }
        } catch (e: Exception) {
            AppLog.e("CSV", "falha ao gravar", e)
        }
    }

    fun csvDir(): File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "viagens")
}
