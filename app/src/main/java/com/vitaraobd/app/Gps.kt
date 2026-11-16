package com.vitaraobd.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor as HwSensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Uma posição. Velocidade em km/h; rumo em graus a partir do norte, sentido horário. */
class Fix(
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val speedKmh: Float,
    val bearing: Float,
    val accuracyM: Float,
    val timeMs: Long,
)

/**
 * Posição (GPS) e direção (bússola) do telefone. Tudo é entregue na thread principal.
 */
object Gps {

    private lateinit var ctx: Context
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var realStarted = false

    @Volatile var fix: Fix? = null
        private set
    /** Para onde o telefone/carro aponta, em graus (norte verdadeiro); NaN se desconhecido. */
    @Volatile var heading: Float = Float.NaN
        private set
    /** true = veio da bússola do aparelho; false = deduzido do deslocamento. */
    @Volatile var headingFromCompass: Boolean = false
        private set

    /** Tela do mapa: chamado a cada nova posição ou mudança de direção perceptível. */
    var uiListener: (() -> Unit)? = null
    /** Gravador de viagens. */
    var fixListener: ((Fix) -> Unit)? = null

    private var lastHeadingNotified = Float.NaN

    fun init(context: Context) {
        ctx = context.applicationContext
        locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- GPS real

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            publish(Fix(
                l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN,
                if (l.hasSpeed()) l.speed * 3.6f else Float.NaN,
                if (l.hasBearing()) l.bearing else Float.NaN,
                if (l.hasAccuracy()) l.accuracy else Float.NaN,
                System.currentTimeMillis(),
            ))
        }
        @Deprecated("exigido em APIs antigas")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) { AppLog.i("GPS", "provedor ligado: $provider") }
        override fun onProviderDisabled(provider: String) { AppLog.w("GPS", "provedor desligado: $provider") }
    }

    private val compassListener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, e.values)
            SensorManager.getOrientation(rotation, orientation)
            var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
            // Norte magnético → norte verdadeiro, quando já sabemos onde estamos.
            fix?.let { f ->
                deg += GeomagneticField(f.lat.toFloat(), f.lon.toFloat(), if (f.altM.isNaN()) 0f else f.altM.toFloat(), f.timeMs).declination
            }
            setHeading((deg + 360f) % 360f, true)
        }
        override fun onAccuracyChanged(sensor: HwSensor?, accuracy: Int) {}
    }

    /** Liga GPS e bússola do aparelho. Não faz nada sem a permissão de localização. */
    @SuppressLint("MissingPermission")
    fun startReal() {
        if (realStarted || !hasPermission()) return
        val lm = locationManager ?: return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                    lm.getLastKnownLocation(provider)?.let { if (fix == null) locationListener.onLocationChanged(it) }
                }
            }
            sensorManager?.getDefaultSensor(HwSensor.TYPE_ROTATION_VECTOR)?.let {
                sensorManager?.registerListener(compassListener, it, SensorManager.SENSOR_DELAY_UI)
            } ?: AppLog.w("GPS", "aparelho sem sensor de orientação; a direção virá do deslocamento")
            realStarted = true
            AppLog.i("GPS", "localização iniciada")
        } catch (e: Exception) {
            AppLog.e("GPS", "não foi possível iniciar a localização", e)
        }
    }

    fun stopReal() {
        if (!realStarted) return
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        sensorManager?.unregisterListener(compassListener)
        realStarted = false
        AppLog.i("GPS", "localização parada")
    }

    // ---------------------------------------------------------------- entrada comum

    private fun publish(f: Fix) {
        fix = f
        // Sem bússola no aparelho, a direção é o rumo do deslocamento.
        if (!headingFromCompass && !f.bearing.isNaN() && (f.speedKmh.isNaN() || f.speedKmh > 3f)) {
            setHeading(f.bearing, false)
        }
        Router.onFix(f)
        fixListener?.invoke(f)
        uiListener?.invoke()
    }

    private fun setHeading(deg: Float, fromCompass: Boolean) {
        heading = deg
        headingFromCompass = fromCompass
        // A bússola dispara dezenas de vezes por segundo; só avisa a tela a cada 2° de mudança.
        if (lastHeadingNotified.isNaN() || angleDiff(deg, lastHeadingNotified) >= 2f) {
            lastHeadingNotified = deg
            if (fromCompass) uiListener?.invoke()
        }
    }

    // ---------------------------------------------------------------- geometria

    private fun angleDiff(a: Float, b: Float): Float {
        val d = Math.abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Distância em metros entre dois pontos (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Rumo inicial de um ponto a outro, em graus (0 = norte). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** Ponto a [meters] de distância no rumo [bearing]. */
    fun move(lat: Double, lon: Double, bearing: Float, meters: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val b = Math.toRadians(bearing.toDouble())
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val d = meters / r
        val p2 = Math.asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    private val CARDINALS = listOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")

    /** Ponto cardeal em português (L = leste, O = oeste). */
    fun cardinal(deg: Float): String = if (deg.isNaN()) "--" else CARDINALS[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
}
