package com.vitaraobd.app

import kotlin.math.max

/**
 * Computador de bordo: transforma leituras em consumo, distância, tempos e médias.
 * As constantes do combustível (relação ar/combustível e densidade) são configuráveis.
 */
class TripComputer(private val st: Settings) {

    // Acumuladores da viagem (persistidos para sobreviver a paradas curtas).
    private var tripDistKm = st.loadAcc("trip_dist")
    private var tripFuelL = st.loadAcc("trip_fuel")
    private var tripTimeS = st.loadAcc("trip_time")
    private var idleTimeS = st.loadAcc("idle_time")
    private var idleFuelL = st.loadAcc("idle_fuel")
    private var maxSpeed = st.loadAcc("max_speed")
    private var maxRpm = st.loadAcc("max_rpm")
    private var maxCoolant = st.loadAcc("max_coolant")

    // Acumuladores totais.
    private var totalDistKm = st.loadAcc("total_dist")
    private var totalFuelL = st.loadAcc("total_fuel")

    private var lastTimeMs = 0L
    private var lastSpeed = Float.NaN
    private var accelG = 0f
    private var econSmooth = Float.NaN
    private var lastSaveMs = 0L

    fun resetTrip() {
        tripDistKm = 0.0; tripFuelL = 0.0; tripTimeS = 0.0; idleTimeS = 0.0; idleFuelL = 0.0
        maxSpeed = 0.0; maxRpm = 0.0; maxCoolant = 0.0
        save()
        AppLog.i("TRIP", "viagem zerada")
    }

    fun resetTotals() {
        totalDistKm = 0.0; totalFuelL = 0.0
        save()
        AppLog.i("TRIP", "totais zerados")
    }

    fun save() {
        st.saveAcc(mapOf(
            "trip_dist" to tripDistKm, "trip_fuel" to tripFuelL, "trip_time" to tripTimeS,
            "idle_time" to idleTimeS, "idle_fuel" to idleFuelL,
            "max_speed" to maxSpeed, "max_rpm" to maxRpm, "max_coolant" to maxCoolant,
            "total_dist" to totalDistKm, "total_fuel" to totalFuelL,
        ))
    }

    /** Vazão de ar em g/s: MAF quando existe; senão estimada por MAP + RPM + temperatura (speed-density). */
    private fun airFlow(s: Sample): Float? {
        val rpm = s.rpm ?: return null
        s.mafGps?.let { return if (rpm > 0f) it else 0f }
        val map = s.mapKpa ?: return null
        val iat = s.intakeC ?: return null
        return rpm / 120f * DISPLACEMENT_L * airDensity(map, iat)
    }

    private fun airDensity(mapKpa: Float, iatC: Float): Float = 1.293f * (273f / (iatC + 273f)) * (mapKpa / 101.3f)

    /** Processa uma leitura e preenche [out] (indexado por Sensor.ordinal; NaN = sem dado). */
    fun update(s: Sample, out: FloatArray) {
        out.fill(Float.NaN)
        val fuel = Settings.FUELS[st.fuelType.coerceIn(0, Settings.FUELS.size - 1)]

        // Intervalo real desde a leitura anterior; ignora buracos longos (reconexão).
        val dt = if (lastTimeMs == 0L) 0.0 else ((s.timeMs - lastTimeMs) / 1000.0).coerceIn(0.0, 2.0)
        lastTimeMs = s.timeMs

        val rpm = s.rpm
        val speed = s.speedKmh
        val running = rpm != null && rpm > 300f

        val thr = s.throttlePct

        // Corte de combustível em desaceleração: informado pela própria ECU (o tempo de injeção vai a zero).
        // A vazão de ar continua alta nesse momento, então sem isto o consumo seria superestimado.
        val cut = st.fuelCut && s.fuelCut

        val air = airFlow(s)
        // g/s de ar → g/s de combustível (÷ AFR) → L/h (÷ densidade em g/L × 3600)
        val fuelLh: Float? = when {
            air == null -> null
            !running || cut -> 0f
            else -> air / fuel.afr / (fuel.densityKgL * 1000f) * 3600f * st.fuelCoef
        }

        if (dt > 0.0) {
            if (running) {
                tripTimeS += dt
                if (speed != null && speed < 1f) {
                    idleTimeS += dt
                    if (fuelLh != null) idleFuelL += fuelLh / 3600.0 * dt
                }
            }
            if (speed != null) {
                val d = speed * st.distCoef / 3600.0 * dt
                tripDistKm += d
                totalDistKm += d
            }
            if (fuelLh != null) {
                val f = fuelLh / 3600.0 * dt
                tripFuelL += f
                totalFuelL += f
            }
            if (speed != null && !lastSpeed.isNaN()) {
                val a = ((speed - lastSpeed) / 3.6f / dt.toFloat()) / 9.81f
                accelG += (a - accelG) * 0.3f
            }
        }
        if (speed != null) lastSpeed = speed
        if (speed != null) maxSpeed = max(maxSpeed, speed.toDouble())
        if (rpm != null) maxRpm = max(maxRpm, rpm.toDouble())
        s.coolantC?.let { maxCoolant = max(maxCoolant, it.toDouble()) }

        // ---- leituras diretas
        fun set(sensor: Sensor, v: Float?) { if (v != null) out[sensor.ordinal] = v }
        set(Sensor.SPEED, speed)
        set(Sensor.RPM, rpm)
        set(Sensor.COOLANT, s.coolantC)
        set(Sensor.INTAKE, s.intakeC)
        set(Sensor.MAF, s.mafGps)
        set(Sensor.THROTTLE, thr)
        set(Sensor.BATTERY, s.batteryV)
        set(Sensor.ECU_BATTERY, s.ecuBatteryV)
        set(Sensor.LOAD, s.loadPct)
        set(Sensor.TIMING, s.timingDeg)
        set(Sensor.STFT, s.stftPct)
        set(Sensor.LTFT, s.ltftPct)
        set(Sensor.TRIM_TOTAL, s.trimTotalPct)
        set(Sensor.O2, s.o2V)
        set(Sensor.IDLE_TARGET, s.idleTargetRpm)
        set(Sensor.INJ_PULSE, s.injPulseMs)
        set(Sensor.IAC, s.iacPct)
        set(Sensor.TPS_VOLT, s.tpsVolt)

        // ---- consumo
        set(Sensor.FUEL_RATE, fuelLh)
        if (fuelLh != null && speed != null) {
            // Parado ou em corte não existe km/L finito: mostra o teto da escala em corte, "--" parado.
            val inst = when {
                speed < 2f -> Float.NaN
                fuelLh < 0.05f -> Sensor.ECON_INST.max
                else -> (speed / fuelLh).coerceAtMost(Sensor.ECON_INST.max)
            }
            econSmooth = when {
                inst.isNaN() -> Float.NaN
                econSmooth.isNaN() -> inst
                else -> econSmooth + (inst - econSmooth) * 0.25f
            }
            out[Sensor.ECON_INST.ordinal] = econSmooth
        }
        if (tripFuelL > 0.005 && tripDistKm > 0.05) out[Sensor.ECON_TRIP.ordinal] = (tripDistKm / tripFuelL).toFloat()
        if (totalFuelL > 0.005 && totalDistKm > 0.05) out[Sensor.ECON_TOTAL.ordinal] = (totalDistKm / totalFuelL).toFloat()

        // ---- viagem e totais
        out[Sensor.TRIP_DIST.ordinal] = tripDistKm.toFloat()
        out[Sensor.TRIP_FUEL.ordinal] = tripFuelL.toFloat()
        out[Sensor.TRIP_COST.ordinal] = (tripFuelL * st.fuelPrice).toFloat()
        out[Sensor.TRIP_TIME.ordinal] = tripTimeS.toFloat()
        out[Sensor.IDLE_TIME.ordinal] = idleTimeS.toFloat()
        out[Sensor.IDLE_FUEL.ordinal] = idleFuelL.toFloat()
        if (tripTimeS > 5.0) out[Sensor.AVG_SPEED.ordinal] = (tripDistKm / (tripTimeS / 3600.0)).toFloat()
        out[Sensor.MAX_SPEED.ordinal] = maxSpeed.toFloat()
        out[Sensor.MAX_RPM.ordinal] = maxRpm.toFloat()
        if (maxCoolant > 0.0) out[Sensor.MAX_COOLANT.ordinal] = maxCoolant.toFloat()
        out[Sensor.CO2.ordinal] = (tripFuelL * fuel.co2KgPerL).toFloat()
        out[Sensor.TOTAL_DIST.ordinal] = totalDistKm.toFloat()
        out[Sensor.TOTAL_FUEL.ordinal] = totalFuelL.toFloat()
        out[Sensor.TOTAL_COST.ordinal] = (totalFuelL * st.fuelPrice).toFloat()

        // ---- diagnóstico
        if (speed != null) out[Sensor.ACCEL.ordinal] = accelG
        if (speed != null && rpm != null && speed > 5f && rpm > 500f) out[Sensor.GEAR.ordinal] = speed / rpm * 1000f
        // Injeção sequencial: cada injetor abre uma vez a cada 2 voltas (120 000 / rpm milissegundos).
        val pulse = s.injPulseMs
        if (pulse != null && rpm != null && rpm > 300f) out[Sensor.INJ_DUTY.ordinal] = (pulse * rpm / 1200f).coerceIn(0f, 100f)
        // Regra prática: ~1,25 hp por g/s de ar em motor a gasolina aspirado. Só uma estimativa.
        if (air != null && running) out[Sensor.POWER.ordinal] = air * 1.27f

        // Persiste a cada 10 s para não perder a viagem se o app for fechado.
        if (s.timeMs - lastSaveMs > 10_000) {
            lastSaveMs = s.timeMs
            save()
        }
    }

    companion object {
        /** Cilindrada do J20A em litros. */
        const val DISPLACEMENT_L = 2.0f
    }
}
