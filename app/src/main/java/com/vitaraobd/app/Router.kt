package com.vitaraobd.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Uma manobra da rota: onde acontece (índice no traçado) e o texto da instrução. */
class RouteStep(val pointIndex: Int, val text: String, val lengthM: Double)

class Route(
    /** Traçado completo: pares (lat, lon). */
    val points: List<DoubleArray>,
    val steps: List<RouteStep>,
    val distanceM: Double,
    val durationS: Double,
    val destLat: Double,
    val destLon: Double,
) {
    /** Distância acumulada do início até cada ponto do traçado, em metros. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) {
            c[i] = c[i - 1] + Gps.distanceM(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
        }
    }
}

/**
 * Cálculo de rotas por ruas usando o servidor público de demonstração do OSRM
 * (dados do OpenStreetMap, sem chave de API). Precisa de internet.
 *
 * O servidor é mantido por voluntários, sem garantia de disponibilidade e com política de
 * uso moderado. Serve para uso pessoal; um app distribuído precisaria de um serviço contratado.
 */
object Router {

    private const val SERVER = "https://router.project-osrm.org"
    private const val OFF_ROUTE_M = 120.0
    private const val RECALC_MIN_INTERVAL_MS = 20_000L

    private val ui = Handler(Looper.getMainLooper())

    var route: Route? = null
        private set
    /** Texto de estado para a tela: "", "Calculando rota…" ou a mensagem de erro. */
    var status: String = ""
        private set
    /** Muda quando a rota muda; o mapa usa para saber se precisa redesenhar. */
    var revision = 0
        private set
    var listener: (() -> Unit)? = null

    /** Ponto do traçado mais próximo da posição atual. */
    var nearestIndex = 0
        private set
    private var offRouteCount = 0
    private var lastRequestMs = 0L
    private var requestId = 0

    val isBusy: Boolean get() = status.startsWith("Calculando")

    fun clear() {
        requestId++
        route = null
        status = ""
        nearestIndex = 0
        revision++
        listener?.invoke()
        AppLog.i("ROTA", "rota removida")
    }

    /** Pede uma rota de carro entre dois pontos. A resposta chega depois, na thread principal. */
    fun request(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, reason: String = "pedido do usuário") {
        val id = ++requestId
        lastRequestMs = SystemClock.elapsedRealtime()
        status = "Calculando rota…"
        listener?.invoke()
        AppLog.i("ROTA", "calculando rota ($reason): $fromLat,$fromLon → $toLat,$toLon")
        Thread {
            val result = try {
                fetch(fromLat, fromLon, toLat, toLon)
            } catch (e: Exception) {
                AppLog.e("ROTA", "falha ao calcular a rota", e)
                null
            }
            ui.post {
                if (id != requestId) return@post          // já houve outro pedido ou a rota foi limpa
                if (result == null) {
                    status = "Não foi possível calcular a rota (sem internet ou serviço indisponível)"
                } else {
                    route = result
                    status = ""
                    nearestIndex = 0
                    offRouteCount = 0
                    AppLog.i("ROTA", String.format(Locale.US, "rota pronta: %.1f km, %.0f min, %d manobras, %d pontos",
                        result.distanceM / 1000, result.durationS / 60, result.steps.size, result.points.size))
                    Journeys.saveRoute(result)
                }
                revision++
                listener?.invoke()
            }
        }.start()
    }

    private fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route {
        // O OSRM recebe longitude antes de latitude.
        val url = URL(String.format(Locale.US,
            "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false&steps=true&geometries=polyline",
            SERVER, fromLon, fromLat, toLon, toLat))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "VitaraOBD/0.1 (app pessoal)")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("servidor respondeu $code: ${body.take(120)}")
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") throw IllegalStateException("resposta: ${json.optString("code")} ${json.optString("message")}")
            val r = json.getJSONArray("routes").getJSONObject(0)

            // O traçado é montado juntando a geometria de cada manobra; assim sabemos em que
            // ponto do traçado cada manobra começa.
            val points = ArrayList<DoubleArray>()
            val steps = ArrayList<RouteStep>()
            val legs = r.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val arr = legs.getJSONObject(l).getJSONArray("steps")
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val geometry = decodePolyline(s.optString("geometry"))
                    val start = if (points.isEmpty()) 0 else points.size - 1
                    for ((k, p) in geometry.withIndex()) {
                        if (k == 0 && points.isNotEmpty()) continue     // o primeiro ponto repete o último da manobra anterior
                        points += p
                    }
                    steps += RouteStep(start, instruction(s), s.optDouble("distance", 0.0))
                }
            }
            if (points.size < 2) throw IllegalStateException("rota sem traçado")
            return Route(points, steps, r.optDouble("distance", 0.0), r.optDouble("duration", 0.0), toLat, toLon)
        } finally {
            conn.disconnect()
        }
    }

    /** Formato "encoded polyline" do Google, precisão de 5 casas. */
    private fun decodePolyline(encoded: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1F) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            if (index >= encoded.length) break
            do { b = encoded[index++].code - 63; result = result or ((b and 0x1F) shl shift); shift += 5 } while (b >= 0x20 && index < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1
            out += doubleArrayOf(lat / 1e5, lon / 1e5)
        }
        return out
    }

    /** Converte a manobra do OSRM em uma instrução em português. */
    private fun instruction(step: JSONObject): String {
        val m = step.optJSONObject("maneuver") ?: JSONObject()
        val type = m.optString("type")
        val name = step.optString("name").ifBlank { step.optString("ref") }
        val dir = when (m.optString("modifier")) {
            "left" -> "à esquerda"
            "right" -> "à direita"
            "slight left" -> "levemente à esquerda"
            "slight right" -> "levemente à direita"
            "sharp left" -> "acentuadamente à esquerda"
            "sharp right" -> "acentuadamente à direita"
            "uturn" -> "em retorno"
            else -> "em frente"
        }
        val onto = if (name.isBlank()) "" else " em $name"
        return when (type) {
            "depart" -> if (name.isBlank()) "Inicie o percurso" else "Siga por $name"
            "arrive" -> "Você chegou ao destino"
            "turn" -> if (m.optString("modifier") == "uturn") "Faça o retorno$onto" else "Vire $dir$onto"
            "new name", "continue" -> "Continue $dir$onto"
            "merge" -> "Entre na via $dir$onto"
            "on ramp" -> "Pegue o acesso $dir$onto"
            "off ramp" -> "Pegue a saída $dir$onto"
            "fork" -> "Mantenha-se $dir$onto"
            "end of road" -> "No fim da via, vire $dir$onto"
            "roundabout", "rotary" -> {
                val exit = m.optInt("exit", 0)
                if (exit > 0) "Na rotatória, pegue a ${exit}ª saída$onto" else "Entre na rotatória$onto"
            }
            "roundabout turn" -> "Na rotatória, vire $dir$onto"
            "exit roundabout", "exit rotary" -> "Saia da rotatória$onto"
            else -> "Siga $dir$onto"
        }
    }

    // ---------------------------------------------------------------- acompanhamento

    /** Chamado a cada posição: atualiza o progresso e recalcula se o carro saiu da rota. */
    fun onFix(f: Fix) {
        val r = route ?: return
        // Procura o ponto mais próximo um pouco para trás e bastante para a frente do último conhecido.
        val from = (nearestIndex - 20).coerceAtLeast(0)
        val to = (nearestIndex + 400).coerceAtMost(r.points.size - 1)
        var best = nearestIndex.coerceIn(0, r.points.size - 1)
        var bestD = Double.MAX_VALUE
        for (i in from..to) {
            val d = Gps.distanceM(f.lat, f.lon, r.points[i][0], r.points[i][1])
            if (d < bestD) { bestD = d; best = i }
        }
        // Se ficou longe, pode ter pulado para outro trecho: procura no traçado inteiro.
        if (bestD > OFF_ROUTE_M) {
            for (i in r.points.indices) {
                val d = Gps.distanceM(f.lat, f.lon, r.points[i][0], r.points[i][1])
                if (d < bestD) { bestD = d; best = i }
            }
        }
        nearestIndex = best

        if (bestD > OFF_ROUTE_M) {
            offRouteCount++
            if (offRouteCount >= 3 && !isBusy && SystemClock.elapsedRealtime() - lastRequestMs > RECALC_MIN_INTERVAL_MS) {
                offRouteCount = 0
                request(f.lat, f.lon, r.destLat, r.destLon, "fora da rota")
            }
        } else {
            offRouteCount = 0
        }
    }

    /** Distância que falta até o destino, pela rota, em metros. */
    fun remainingM(): Double {
        val r = route ?: return Double.NaN
        return (r.cumulative.last() - r.cumulative[nearestIndex.coerceIn(0, r.cumulative.size - 1)]).coerceAtLeast(0.0)
    }

    /** Tempo estimado restante, proporcional à distância que falta. */
    fun remainingS(): Double {
        val r = route ?: return Double.NaN
        return if (r.distanceM > 0) r.durationS * remainingM() / r.distanceM else 0.0
    }

    /** Próxima manobra e a distância até ela, em metros. */
    fun nextStep(): Pair<RouteStep, Double>? {
        val r = route ?: return null
        val here = nearestIndex.coerceIn(0, r.cumulative.size - 1)
        val next = r.steps.firstOrNull { it.pointIndex > here } ?: return r.steps.lastOrNull()?.let { it to remainingM() }
        return next to (r.cumulative[next.pointIndex.coerceIn(0, r.cumulative.size - 1)] - r.cumulative[here]).coerceAtLeast(0.0)
    }
}
