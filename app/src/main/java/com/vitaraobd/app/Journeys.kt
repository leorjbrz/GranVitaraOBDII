package com.vitaraobd.app

import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

class Place(val name: String, val lat: Double?, val lon: Double?) {
    val hasCoords: Boolean get() = lat != null && lon != null
    fun toJson(): JSONObject = JSONObject().put("nome", name).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): Place = Place(
            o?.optString("nome").orEmpty(),
            o?.takeIf { !it.isNull("lat") }?.optDouble("lat"),
            o?.takeIf { !it.isNull("lon") }?.optDouble("lon"),
        )
    }
}

/** Marco na linha do tempo da viagem: início, pausa, retomada, fim. */
class JourneyEvent(
    val timeMs: Long,
    val type: String,
    val reason: String,
    val note: String,
    val liters: Double?,
    val cost: Double?,
    val lat: Double?,
    val lon: Double?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("hora", Journeys.iso(timeMs)).put("hora_ms", timeMs).put("tipo", type).put("motivo", reason).put("nota", note)
        .put("litros", liters ?: JSONObject.NULL).put("valor", cost ?: JSONObject.NULL)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): JourneyEvent = JourneyEvent(
            o.optLong("hora_ms"), o.optString("tipo"), o.optString("motivo"), o.optString("nota"),
            if (o.isNull("litros")) null else o.optDouble("litros"),
            if (o.isNull("valor")) null else o.optDouble("valor"),
            if (o.isNull("lat")) null else o.optDouble("lat"),
            if (o.isNull("lon")) null else o.optDouble("lon"),
        )
    }
}

/** Uma viagem registrada (em andamento ou concluída). */
class Journey(val id: String, val dir: File) {
    var name = ""
    var origin = Place("", null, null)
    var dest = Place("", null, null)
    var startMs = 0L
    var endMs = 0L
    var status = STATUS_ACTIVE
    val events = ArrayList<JourneyEvent>()

    // Acumulados (não contam o tempo em pausa).
    var movingS = 0.0
    var pausedS = 0.0
    var distKm = 0.0
    var gpsDistKm = 0.0
    var fuelL = 0.0
    var maxSpeed = 0.0
    var maxRpm = 0.0
    var maxCoolant = 0.0
    var samples = 0
    var trackPoints = 0
    /** Configurações em uso quando a viagem começou (unidades, combustível, calibração…). */
    var settingsJson = JSONObject()

    val paused: Boolean get() = status == STATUS_PAUSED
    val finished: Boolean get() = status == STATUS_FINISHED
    val refuelLiters: Double get() = events.sumOf { it.liters ?: 0.0 }
    val refuelCost: Double get() = events.sumOf { it.cost ?: 0.0 }
    val pauseCount: Int get() = events.count { it.type == "pausa" }
    val econKmL: Double get() = if (fuelL > 0.005) distKm / fuelL else Double.NaN
    val avgSpeed: Double get() = if (movingS > 5) distKm / (movingS / 3600.0) else Double.NaN

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("nome", name).put("status", status)
        .put("origem", origin.toJson()).put("destino", dest.toJson())
        .put("inicio", Journeys.iso(startMs)).put("inicio_ms", startMs)
        .put("fim", if (endMs > 0) Journeys.iso(endMs) else JSONObject.NULL).put("fim_ms", endMs)
        .put("resumo", JSONObject()
            .put("tempo_em_movimento_s", movingS.toLong()).put("tempo_em_pausa_s", pausedS.toLong())
            .put("distancia_km", r(distKm, 3)).put("distancia_gps_km", r(gpsDistKm, 3))
            .put("combustivel_l", r(fuelL, 3))
            .put("consumo_km_l", if (econKmL.isNaN()) JSONObject.NULL else r(econKmL, 2))
            .put("velocidade_media_kmh", if (avgSpeed.isNaN()) JSONObject.NULL else r(avgSpeed, 1))
            .put("velocidade_maxima_kmh", r(maxSpeed, 0)).put("rotacao_maxima_rpm", r(maxRpm, 0))
            .put("temperatura_maxima_c", r(maxCoolant, 0))
            .put("pausas", pauseCount).put("abastecido_l", r(refuelLiters, 2)).put("gasto_abastecimento", r(refuelCost, 2))
            .put("amostras_veiculo", samples).put("pontos_gps", trackPoints))
        .put("eventos", JSONArray(events.map { it.toJson() }))
        .put("configuracoes", settingsJson)

    private fun r(v: Double, d: Int): Double {
        val m = Math.pow(10.0, d.toDouble())
        return Math.round(v * m) / m
    }

    companion object {
        const val STATUS_ACTIVE = "em_andamento"
        const val STATUS_PAUSED = "pausada"
        const val STATUS_FINISHED = "finalizada"

        fun load(dir: File): Journey? = try {
            val o = JSONObject(File(dir, "viagem.json").readText())
            Journey(o.getString("id"), dir).apply {
                name = o.optString("nome")
                status = o.optString("status", STATUS_FINISHED)
                origin = Place.fromJson(o.optJSONObject("origem"))
                dest = Place.fromJson(o.optJSONObject("destino"))
                startMs = o.optLong("inicio_ms")
                endMs = o.optLong("fim_ms")
                o.optJSONObject("resumo")?.let { s ->
                    movingS = s.optDouble("tempo_em_movimento_s", 0.0)
                    pausedS = s.optDouble("tempo_em_pausa_s", 0.0)
                    distKm = s.optDouble("distancia_km", 0.0)
                    gpsDistKm = s.optDouble("distancia_gps_km", 0.0)
                    fuelL = s.optDouble("combustivel_l", 0.0)
                    maxSpeed = s.optDouble("velocidade_maxima_kmh", 0.0)
                    maxRpm = s.optDouble("rotacao_maxima_rpm", 0.0)
                    maxCoolant = s.optDouble("temperatura_maxima_c", 0.0)
                    samples = s.optInt("amostras_veiculo")
                    trackPoints = s.optInt("pontos_gps")
                }
                o.optJSONArray("eventos")?.let { a -> for (i in 0 until a.length()) events += JourneyEvent.fromJson(a.getJSONObject(i)) }
                settingsJson = o.optJSONObject("configuracoes") ?: JSONObject()
            }
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "não foi possível ler ${dir.name}", e)
            null
        }
    }
}

/**
 * Gravador de viagens. Uma viagem por vez. Tudo roda na thread principal.
 *
 * Cada viagem é uma pasta em .../files/viagens_registradas/<id>/ com:
 *   viagem.json          dados gerais, resumo, eventos e as configurações usadas
 *   dados_veiculo.csv    todos os sensores, 1 linha por segundo
 *   trajeto_gps.csv      posições do GPS
 *   trajeto.gpx          o mesmo trajeto em GPX (gerado ao finalizar)
 *   eventos.csv          início, pausas, retomadas e fim
 *   resumo.txt           resumo legível (gerado ao finalizar)
 */
object Journeys {

    val PAUSE_REASONS = listOf("Abastecimento", "Refeição", "Descanso", "Pedágio / parada rápida", "Outro")

    private lateinit var ctx: Context
    private lateinit var baseDir: File
    private val ui = Handler(Looper.getMainLooper())

    var active: Journey? = null
        private set
    /** Trajeto da viagem em andamento, para desenhar no mapa: pares (lat, lon). */
    val track = ArrayList<DoubleArray>()
    /** Muda quando a viagem ou o trajeto mudam; o mapa usa para saber se precisa redesenhar. */
    var revision = 0
        private set

    private var vehicleCsv: BufferedWriter? = null
    private var trackCsv: BufferedWriter? = null
    private var lastSampleMs = 0L
    private var lastTickMs = 0L
    private var lastTotalDist = Float.NaN
    private var lastTotalFuel = Float.NaN
    private var lastFix: Fix? = null
    private var lastSaveMs = 0L

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))
    private fun clock(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun init(context: Context) {
        ctx = context.applicationContext
        baseDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "viagens_registradas")
        baseDir.mkdirs()
        Gps.fixListener = { onFix(it) }
        resumeUnfinished()
    }

    /** Se o app foi fechado no meio de uma viagem, continua de onde parou. */
    private fun resumeUnfinished() {
        val j = baseDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { d -> Journey.load(d)?.takeIf { !it.finished } } ?: return
        active = j
        track.clear()
        try {
            File(j.dir, "trajeto_gps.csv").takeIf { it.exists() }?.useLines { lines ->
                lines.drop(1).forEach { l ->
                    val p = l.split(';')
                    val la = p.getOrNull(1)?.toDoubleOrNull()
                    val lo = p.getOrNull(2)?.toDoubleOrNull()
                    if (la != null && lo != null) track += doubleArrayOf(la, lo)
                }
            }
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "falha ao recarregar o trajeto", e)
        }
        openWriters(j, append = true)
        revision++
        AppLog.i("VIAGEM", "viagem '${j.name}' retomada após reinício do app (${track.size} pontos de trajeto)")
    }

    private fun openWriters(j: Journey, append: Boolean) {
        closeWriters()
        try {
            val v = File(j.dir, "dados_veiculo.csv")
            val t = File(j.dir, "trajeto_gps.csv")
            val newV = !append || !v.exists()
            val newT = !append || !t.exists()
            vehicleCsv = BufferedWriter(FileWriter(v, append)).also {
                if (newV) it.write("hora;estado;" + Sensor.entries.joinToString(";") { s -> s.name } + "\n")
            }
            trackCsv = BufferedWriter(FileWriter(t, append)).also {
                if (newT) it.write("hora;lat;lon;altitude_m;velocidade_kmh;rumo_graus;precisao_m;estado\n")
            }
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "não foi possível abrir os arquivos da viagem", e)
        }
    }

    private fun closeWriters() {
        try { vehicleCsv?.close() } catch (_: Exception) {}
        try { trackCsv?.close() } catch (_: Exception) {}
        vehicleCsv = null
        trackCsv = null
    }

    private fun save(j: Journey) {
        try {
            vehicleCsv?.flush()
            trackCsv?.flush()
            File(j.dir, "viagem.json").writeText(j.toJson().toString(2))
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "falha ao salvar a viagem", e)
        }
    }

    /** Grava em disco o estado atual da viagem em andamento (usado ao sair do app). */
    fun flush() {
        active?.let { save(it) }
    }

    private fun addEvent(j: Journey, type: String, reason: String = "", note: String = "", liters: Double? = null, cost: Double? = null) {
        val f = Gps.fix
        val e = JourneyEvent(System.currentTimeMillis(), type, reason, note, liters, cost, f?.lat, f?.lon)
        j.events += e
        try {
            val file = File(j.dir, "eventos.csv")
            val header = if (file.exists()) "" else "hora;tipo;motivo;nota;litros;valor;lat;lon\n"
            FileWriter(file, true).use {
                it.write(header + listOf(clock(e.timeMs), type, reason, note.replace(';', ','), liters ?: "", cost ?: "", e.lat ?: "", e.lon ?: "").joinToString(";") + "\n")
            }
        } catch (ex: Exception) {
            AppLog.e("VIAGEM", "falha ao gravar evento", ex)
        }
    }

    // ---------------------------------------------------------------- ações do usuário

    /**
     * Começa uma viagem. A origem sem coordenadas usa a posição atual; os nomes são
     * convertidos em coordenadas em segundo plano quando o aparelho tem geocodificador e internet.
     */
    fun start(name: String, originName: String, destName: String) {
        if (active != null) return
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val j = Journey(id, File(baseDir, id))
        j.dir.mkdirs()
        j.name = name.ifBlank { "Viagem $id" }
        j.startMs = now
        val f = Gps.fix
        j.origin = Place(originName.ifBlank { "Posição atual" }, f?.lat, f?.lon)
        j.dest = Place(destName, null, null)
        j.settingsJson = settingsSnapshot()
        active = j
        track.clear()
        lastSampleMs = 0; lastTickMs = 0; lastFix = null
        lastTotalDist = Float.NaN; lastTotalFuel = Float.NaN
        openWriters(j, append = false)
        addEvent(j, "inicio", note = "${j.origin.name} → ${j.dest.name.ifBlank { "(sem destino)" }}")
        save(j)
        revision++
        AppLog.i("VIAGEM", "viagem iniciada: '${j.name}' de '${j.origin.name}' para '${j.dest.name}'")
        geocode(j, originName, destName)
    }

    private fun geocode(j: Journey, originName: String, destName: String) {
        if (!Geocoder.isPresent()) {
            AppLog.w("VIAGEM", "aparelho sem geocodificador; defina o destino tocando e segurando no mapa")
            return
        }
        Thread {
            fun find(q: String): Pair<Double, Double>? = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("pt", "BR")).getFromLocationName(q, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
            } catch (e: Exception) {
                AppLog.w("VIAGEM", "não foi possível localizar '$q': ${e.message}")
                null
            }
            val o = if (originName.isNotBlank()) find(originName) else null
            val d = if (destName.isNotBlank()) find(destName) else null
            ui.post {
                if (active !== j) return@post
                // A origem digitada só é usada se não havia posição do GPS no início.
                if (o != null && !j.origin.hasCoords) j.origin = Place(j.origin.name, o.first, o.second)
                if (d != null && !j.dest.hasCoords) {
                    j.dest = Place(j.dest.name, d.first, d.second)
                    AppLog.i("VIAGEM", "destino localizado: ${d.first}, ${d.second}")
                } else if (destName.isNotBlank() && d == null) {
                    AppLog.w("VIAGEM", "destino '$destName' não localizado; toque e segure no mapa para marcá-lo")
                }
                save(j)
                revision++
            }
        }.start()
    }

    fun setDestination(lat: Double, lon: Double) {
        val j = active ?: return
        j.dest = Place(j.dest.name.ifBlank { "Destino marcado no mapa" }, lat, lon)
        save(j)
        revision++
        AppLog.i("VIAGEM", "destino definido no mapa: $lat, $lon")
    }

    fun pause(reason: String, note: String, liters: Double?, cost: Double?) {
        val j = active ?: return
        if (j.paused) return
        j.status = Journey.STATUS_PAUSED
        addEvent(j, "pausa", reason, note, liters, cost)
        save(j)
        revision++
        AppLog.i("VIAGEM", "pausa: $reason ${if (note.isBlank()) "" else "($note)"}")
    }

    fun resume() {
        val j = active ?: return
        if (!j.paused) return
        j.status = Journey.STATUS_ACTIVE
        // Não soma o deslocamento feito durante a pausa (manobra no posto etc.).
        lastTotalDist = Float.NaN; lastTotalFuel = Float.NaN; lastFix = null
        addEvent(j, "retomada")
        save(j)
        revision++
        AppLog.i("VIAGEM", "viagem retomada")
    }

    fun finish() {
        val j = active ?: return
        j.status = Journey.STATUS_FINISHED
        j.endMs = System.currentTimeMillis()
        if (!j.dest.hasCoords) Gps.fix?.let { j.dest = Place(j.dest.name.ifBlank { "Ponto final" }, it.lat, it.lon) }
        addEvent(j, "fim")
        save(j)
        closeWriters()
        writeGpx(j)
        writeSummary(j)
        active = null
        track.clear()
        revision++
        AppLog.i("VIAGEM", "viagem finalizada: '${j.name}' — ${"%.1f".format(Locale.US, j.distKm)} km, ${"%.2f".format(Locale.US, j.fuelL)} L")
    }

    // ---------------------------------------------------------------- coleta

    /** Chamado pelo Engine a cada leitura do veículo. */
    fun onSnapshot(timeMs: Long, values: FloatArray) {
        val j = active ?: return
        val dt = if (lastTickMs == 0L) 0.0 else ((timeMs - lastTickMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTickMs = timeMs

        if (j.paused) {
            j.pausedS += dt
        } else {
            val rpm = values[Sensor.RPM.ordinal]
            if (!rpm.isNaN() && rpm > 300f) j.movingS += dt
            // Distância e combustível vêm dos acumuladores do computador de bordo: assim a
            // calibração e o corte de combustível valem igual nos dois lugares.
            val td = values[Sensor.TOTAL_DIST.ordinal]
            val tf = values[Sensor.TOTAL_FUEL.ordinal]
            if (!td.isNaN() && !lastTotalDist.isNaN() && td >= lastTotalDist && td - lastTotalDist < 0.2f) j.distKm += td - lastTotalDist
            if (!tf.isNaN() && !lastTotalFuel.isNaN() && tf >= lastTotalFuel && tf - lastTotalFuel < 0.05f) j.fuelL += tf - lastTotalFuel
            lastTotalDist = td
            lastTotalFuel = tf
            values[Sensor.SPEED.ordinal].let { if (!it.isNaN()) j.maxSpeed = max(j.maxSpeed, it.toDouble()) }
            if (!rpm.isNaN()) j.maxRpm = max(j.maxRpm, rpm.toDouble())
            values[Sensor.COOLANT.ordinal].let { if (!it.isNaN()) j.maxCoolant = max(j.maxCoolant, it.toDouble()) }
        }

        // Uma linha por segundo com todos os sensores, inclusive durante as pausas (marcadas).
        if (timeMs - lastSampleMs >= 1000) {
            lastSampleMs = timeMs
            try {
                val row = StringBuilder(clock(timeMs)).append(';').append(if (j.paused) "pausa" else "andando")
                for (x in values) row.append(';').append(if (x.isNaN()) "" else String.format(Locale.US, "%.3f", x))
                vehicleCsv?.write(row.append('\n').toString())
                j.samples++
            } catch (e: Exception) {
                AppLog.e("VIAGEM", "falha ao gravar dados do veículo", e)
            }
        }
        if (timeMs - lastSaveMs > 15_000) {
            lastSaveMs = timeMs
            save(j)
        }
    }

    private fun onFix(f: Fix) {
        val j = active ?: return
        val prev = lastFix
        // Ignora posições imprecisas e tremidas paradas (menos de 3 m).
        if (!f.accuracyM.isNaN() && f.accuracyM > 50f) return
        val step = if (prev == null) Double.MAX_VALUE else Gps.distanceM(prev.lat, prev.lon, f.lat, f.lon)
        if (prev != null && step < 3.0) return
        if (prev != null && !j.paused && step < 2000.0) j.gpsDistKm += step / 1000.0
        lastFix = f
        try {
            fun n(v: Number, d: Int) = if (v.toDouble().isNaN()) "" else String.format(Locale.US, "%.${d}f", v.toDouble())
            trackCsv?.write(listOf(
                clock(f.timeMs), n(f.lat, 6), n(f.lon, 6), n(f.altM, 1), n(f.speedKmh, 1), n(f.bearing, 0), n(f.accuracyM, 0),
                if (j.paused) "pausa" else "andando",
            ).joinToString(";") + "\n")
            j.trackPoints++
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "falha ao gravar o trajeto", e)
        }
        track += doubleArrayOf(f.lat, f.lon)
        revision++
    }

    // ---------------------------------------------------------------- arquivos finais

    private fun settingsSnapshot(): JSONObject {
        val st = Engine.settings
        val fuel = Settings.FUELS[st.fuelType.coerceIn(0, Settings.FUELS.size - 1)]
        val alarms = JSONObject()
        for (a in AlarmDef.entries) alarms.put(a.name, JSONObject().put("ativo", st.alarmEnabled(a)).put("limite", st.alarmLimit(a).toDouble()))
        return JSONObject()
            .put("fonte_de_dados", "veiculo_bluetooth")
            .put("adaptador", "${st.btName} ${st.btAddress}".trim())
            .put("sensores_experimentais", st.experimental)
            .put("combustivel", fuel.name).put("afr", fuel.afr.toDouble()).put("densidade_kg_l", fuel.densityKgL.toDouble())
            .put("preco_por_litro", st.fuelPrice.toDouble()).put("moeda", st.currency)
            .put("coeficiente_combustivel", st.fuelCoef.toDouble()).put("coeficiente_distancia", st.distCoef.toDouble())
            .put("corte_de_combustivel", st.fuelCut)
            .put("cilindrada_l", TripComputer.DISPLACEMENT_L.toDouble())
            .put("unidades_na_tela", JSONObject()
                .put("distancia", Units.DIST_NAMES[st.distUnit.coerceIn(0, 1)]).put("temperatura", Units.TEMP_NAMES[st.tempUnit.coerceIn(0, 1)])
                .put("pressao", Units.PRESS_NAMES[st.pressUnit.coerceIn(0, 2)]).put("consumo", Units.ECON_NAMES[st.econUnit.coerceIn(0, 2)])
                .put("volume", Units.VOL_NAMES[st.volUnit.coerceIn(0, 1)]))
            .put("unidades_dos_arquivos", "km/h, rpm, °C, kPa, g/s, %, V, L/h, km/L, km, L, s")
            .put("alarmes", alarms)
            .put("aparelho", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            .put("app", "Vitara OBD " + try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" })
    }

    /** Guarda na pasta da viagem a rota calculada (a planejada, não a percorrida). */
    fun saveRoute(r: Route) {
        val j = active ?: return
        try {
            BufferedWriter(FileWriter(File(j.dir, "rota_calculada.gpx"))).use { w ->
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Vitara OBD\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                w.write(String.format(Locale.US, "  <rte><name>Rota calculada (%.1f km, %.0f min)</name>\n", r.distanceM / 1000, r.durationS / 60))
                for (p in r.points) w.write(String.format(Locale.US, "    <rtept lat=\"%.6f\" lon=\"%.6f\"/>\n", p[0], p[1]))
                w.write("  </rte>\n</gpx>\n")
            }
            File(j.dir, "rota_calculada_passos.txt").writeText(
                r.steps.joinToString("\n") { String.format(Locale.US, "%s (%.0f m)", it.text, it.lengthM) } + "\n")
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "falha ao gravar a rota calculada", e)
        }
    }

    private fun writeGpx(j: Journey) {
        try {
            val src = File(j.dir, "trajeto_gps.csv")
            if (!src.exists()) return
            BufferedWriter(FileWriter(File(j.dir, "trajeto.gpx"))).use { w ->
                fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"Vitara OBD\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                if (j.origin.hasCoords) w.write("  <wpt lat=\"${j.origin.lat}\" lon=\"${j.origin.lon}\"><name>Origem: ${esc(j.origin.name)}</name></wpt>\n")
                if (j.dest.hasCoords) w.write("  <wpt lat=\"${j.dest.lat}\" lon=\"${j.dest.lon}\"><name>Destino: ${esc(j.dest.name)}</name></wpt>\n")
                for (e in j.events) if (e.type == "pausa" && e.lat != null && e.lon != null) {
                    w.write("  <wpt lat=\"${e.lat}\" lon=\"${e.lon}\"><name>Pausa: ${esc(e.reason)}</name></wpt>\n")
                }
                w.write("  <trk><name>${esc(j.name)}</name><trkseg>\n")
                val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val outFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                src.useLines { lines ->
                    lines.drop(1).forEach { l ->
                        val p = l.split(';')
                        if (p.size < 3 || p[1].isEmpty() || p[2].isEmpty()) return@forEach
                        val time = try { outFmt.format(inFmt.parse(p[0])!!) } catch (_: Exception) { "" }
                        w.write("    <trkpt lat=\"${p[1]}\" lon=\"${p[2]}\">")
                        if (p.getOrNull(3)?.isNotEmpty() == true) w.write("<ele>${p[3]}</ele>")
                        if (time.isNotEmpty()) w.write("<time>$time</time>")
                        w.write("</trkpt>\n")
                    }
                }
                w.write("  </trkseg></trk>\n</gpx>\n")
            }
        } catch (e: Exception) {
            AppLog.e("VIAGEM", "falha ao gerar o GPX", e)
        }
    }

    fun hms(seconds: Double): String {
        val t = seconds.toLong()
        return String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    }

    /** Resumo legível, usado no arquivo resumo.txt e na tela de viagens salvas. */
    fun summaryText(j: Journey): String {
        fun f(v: Double, d: Int) = if (v.isNaN()) "--" else String.format(Locale("pt", "BR"), "%.${d}f", v)
        val currency = j.settingsJson.optString("moeda", "R$")
        val price = j.settingsJson.optDouble("preco_por_litro", 0.0)
        return buildString {
            append("${j.name}\n")
            append("De: ${j.origin.name}\nPara: ${j.dest.name.ifBlank { "--" }}\n")
            append("Início: ${clock(j.startMs)}\n")
            append("Fim: ${if (j.endMs > 0) clock(j.endMs) else "em andamento"}\n\n")
            append("Tempo em movimento: ${hms(j.movingS)}\n")
            append("Tempo em pausa: ${hms(j.pausedS)} (${j.pauseCount} pausa(s))\n")
            append("Distância (veículo): ${f(j.distKm, 1)} km\n")
            append("Distância (GPS): ${f(j.gpsDistKm, 1)} km\n")
            append("Combustível estimado: ${f(j.fuelL, 2)} L\n")
            append("Consumo médio: ${f(j.econKmL, 1)} km/L\n")
            append("Custo estimado: $currency ${f(j.fuelL * price, 2)}\n")
            append("Velocidade média: ${f(j.avgSpeed, 0)} km/h\n")
            append("Velocidade máxima: ${f(j.maxSpeed, 0)} km/h\n")
            append("Rotação máxima: ${f(j.maxRpm, 0)} rpm\n")
            append("Temperatura máxima do motor: ${f(j.maxCoolant, 0)} °C\n")
            if (j.refuelLiters > 0 || j.refuelCost > 0) append("Abastecido: ${f(j.refuelLiters, 2)} L, $currency ${f(j.refuelCost, 2)}\n")
            if (j.events.any { it.type == "pausa" }) {
                append("\nPausas:\n")
                for (e in j.events.filter { it.type == "pausa" }) {
                    append("  ${clock(e.timeMs).substring(11)}  ${e.reason}")
                    if (e.note.isNotBlank()) append(" — ${e.note}")
                    if (e.liters != null) append(" — ${f(e.liters, 2)} L")
                    if (e.cost != null) append(" — $currency ${f(e.cost, 2)}")
                    append('\n')
                }
            }
            append("\nFonte dos dados: ${j.settingsJson.optString("fonte_de_dados")}\n")
        }
    }

    private fun writeSummary(j: Journey) {
        try { File(j.dir, "resumo.txt").writeText(summaryText(j)) } catch (e: Exception) { AppLog.e("VIAGEM", "falha ao gravar o resumo", e) }
    }

    // ---------------------------------------------------------------- viagens salvas

    /** Viagens concluídas, da mais recente para a mais antiga. */
    fun saved(): List<Journey> =
        baseDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.mapNotNull { Journey.load(it) }?.filter { it.finished }.orEmpty()

    fun delete(j: Journey) {
        j.dir.deleteRecursively()
        AppLog.i("VIAGEM", "viagem excluída: ${j.name}")
    }

    /** Compacta a pasta da viagem. Chamar fora da thread principal. */
    fun zip(j: Journey, out: OutputStream): Int {
        var count = 0
        ZipOutputStream(out).use { zip ->
            j.dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry("${j.id}/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }
}
