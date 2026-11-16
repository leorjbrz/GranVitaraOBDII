package com.vitaraobd.app

import java.util.Locale

/** Tipo de grandeza: define quais unidades o usuário pode escolher. */
enum class Kind { SPEED, TEMP, PRESSURE, ECONOMY, DISTANCE, VOLUME, FLOW, TIME, MONEY, PLAIN }

/**
 * Catálogo de tudo o que o app sabe mostrar. Valores sempre guardados na unidade base
 * (km/h, °C, kPa, km/L, km, L, L/h, s) e convertidos só na hora de exibir.
 *
 * Grupos:
 *  - "Veículo": lido da ECU pelo bloco 21 00 ou do adaptador.
 *  - "Consumo", "Viagem", "Totais", "Diagnóstico": calculados a partir dos anteriores.
 *  - "Provável": interpretação ainda não confirmada; só aparece se habilitado nas configurações.
 *
 * Este motor não tem sensor MAP, por isso não há pressão do coletor, vácuo nem eficiência volumétrica.
 */
enum class Sensor(
    val title: String,
    val short: String,
    val kind: Kind,
    val plainUnit: String,
    val min: Float,
    val max: Float,
    val decimals: Int,
    val group: String,
    val experimental: Boolean = false,
    /** Início da faixa vermelha no mostrador (unidade base); NaN = sem faixa. */
    val redFrom: Float = Float.NaN,
) {
    // ---- lidos do veículo
    SPEED("Velocidade", "VELOCIDADE", Kind.SPEED, "", 0f, 180f, 0, "Veículo"),
    RPM("Rotação do motor", "RPM", Kind.PLAIN, "rpm", 0f, 7000f, 0, "Veículo", redFrom = 6000f),
    COOLANT("Temperatura do motor", "MOTOR", Kind.TEMP, "", 0f, 130f, 0, "Veículo", redFrom = 105f),
    INTAKE("Temperatura do ar admitido", "AR ADMITIDO", Kind.TEMP, "", -10f, 80f, 0, "Veículo"),
    MAF("Vazão de ar (MAF)", "MAF", Kind.PLAIN, "g/s", 0f, 120f, 1, "Veículo"),
    THROTTLE("Posição da borboleta", "BORBOLETA", Kind.PLAIN, "%", 0f, 100f, 0, "Veículo"),
    BATTERY("Tensão da bateria (no conector)", "BATERIA", Kind.PLAIN, "V", 10f, 16f, 1, "Veículo"),
    ECU_BATTERY("Tensão vista pela ECU", "TENSÃO ECU", Kind.PLAIN, "V", 10f, 16f, 1, "Veículo"),
    LOAD("Carga do motor", "CARGA", Kind.PLAIN, "%", 0f, 100f, 0, "Veículo"),
    TIMING("Avanço de ignição", "AVANÇO", Kind.PLAIN, "°", -10f, 50f, 1, "Veículo"),
    STFT("Correção de combustível curta", "CORR. CURTA", Kind.PLAIN, "%", -25f, 25f, 1, "Veículo"),
    LTFT("Correção de combustível longa", "CORR. LONGA", Kind.PLAIN, "%", -25f, 25f, 1, "Veículo"),
    TRIM_TOTAL("Correção de combustível total", "CORR. TOTAL", Kind.PLAIN, "%", -25f, 25f, 1, "Veículo"),
    O2("Sonda lambda (antes do catalisador)", "SONDA", Kind.PLAIN, "V", 0f, 1.1f, 2, "Veículo"),
    IDLE_TARGET("Rotação-alvo de marcha lenta", "LENTA ALVO", Kind.PLAIN, "rpm", 0f, 2000f, 0, "Veículo"),
    INJ_PULSE("Tempo de injeção", "INJEÇÃO", Kind.PLAIN, "ms", 0f, 20f, 2, "Veículo"),

    // ---- consumo
    FUEL_RATE("Consumo por hora", "CONSUMO/H", Kind.FLOW, "", 0f, 30f, 1, "Consumo"),
    ECON_INST("Consumo instantâneo", "INSTANTÂNEO", Kind.ECONOMY, "", 0f, 30f, 1, "Consumo"),
    ECON_TRIP("Consumo médio da viagem", "MÉDIA VIAGEM", Kind.ECONOMY, "", 0f, 30f, 1, "Consumo"),
    ECON_TOTAL("Consumo médio total", "MÉDIA TOTAL", Kind.ECONOMY, "", 0f, 30f, 1, "Consumo"),

    // ---- viagem
    TRIP_DIST("Distância da viagem", "DISTÂNCIA", Kind.DISTANCE, "", 0f, 1000f, 1, "Viagem"),
    TRIP_FUEL("Combustível da viagem", "COMBUSTÍVEL", Kind.VOLUME, "", 0f, 60f, 2, "Viagem"),
    TRIP_COST("Custo da viagem", "CUSTO", Kind.MONEY, "", 0f, 500f, 2, "Viagem"),
    TRIP_TIME("Tempo de viagem", "TEMPO", Kind.TIME, "", 0f, 36000f, 0, "Viagem"),
    IDLE_TIME("Tempo parado (motor ligado)", "PARADO", Kind.TIME, "", 0f, 36000f, 0, "Viagem"),
    IDLE_FUEL("Combustível gasto parado", "GASTO PARADO", Kind.VOLUME, "", 0f, 10f, 2, "Viagem"),
    AVG_SPEED("Velocidade média", "VEL. MÉDIA", Kind.SPEED, "", 0f, 180f, 0, "Viagem"),
    MAX_SPEED("Velocidade máxima", "VEL. MÁX.", Kind.SPEED, "", 0f, 180f, 0, "Viagem"),
    MAX_RPM("Rotação máxima", "RPM MÁX.", Kind.PLAIN, "rpm", 0f, 7000f, 0, "Viagem"),
    MAX_COOLANT("Temperatura máxima do motor", "MOTOR MÁX.", Kind.TEMP, "", 0f, 130f, 0, "Viagem"),
    CO2("CO₂ emitido na viagem", "CO₂", Kind.PLAIN, "kg", 0f, 100f, 2, "Viagem"),

    // ---- totais
    TOTAL_DIST("Distância total", "DIST. TOTAL", Kind.DISTANCE, "", 0f, 100000f, 1, "Totais"),
    TOTAL_FUEL("Combustível total", "COMB. TOTAL", Kind.VOLUME, "", 0f, 10000f, 1, "Totais"),
    TOTAL_COST("Custo total", "CUSTO TOTAL", Kind.MONEY, "", 0f, 100000f, 2, "Totais"),

    // ---- dinâmica e diagnóstico (calculados)
    ACCEL("Aceleração longitudinal", "ACELERAÇÃO", Kind.PLAIN, "g", -1f, 1f, 2, "Diagnóstico"),
    GEAR("Relação de marcha (km/h por 1000 rpm)", "MARCHA", Kind.PLAIN, "km/h/krpm", 0f, 50f, 1, "Diagnóstico"),
    INJ_DUTY("Ciclo de trabalho dos injetores", "INJ. %", Kind.PLAIN, "%", 0f, 100f, 1, "Diagnóstico"),
    POWER("Potência estimada (pela vazão de ar)", "POTÊNCIA", Kind.PLAIN, "cv", 0f, 150f, 0, "Diagnóstico"),
    // ---- prováveis (interpretação ainda não confirmada)
    IAC("Válvula de marcha lenta (provável)", "IAC", Kind.PLAIN, "%", 0f, 100f, 0, "Provável", true),
    TPS_VOLT("Tensão do sensor da borboleta (provável)", "TPS V", Kind.PLAIN, "V", 0f, 5f, 2, "Provável", true);

    companion object {
        /** Sensores que fazem sentido como mostrador circular. */
        val GAUGE_CHOICES: List<Sensor> = listOf(
            RPM, SPEED, COOLANT, INTAKE, MAF, THROTTLE, LOAD, TIMING, BATTERY, FUEL_RATE, ECON_INST, POWER,
        )
    }
}

/** Conversão e formatação conforme as unidades escolhidas. */
object Units {
    private const val KM_PER_MILE = 1.609344f
    private const val L_PER_GAL = 3.785412f

    val DIST_NAMES = listOf("Quilômetros (km, km/h)", "Milhas (mi, mph)")
    val TEMP_NAMES = listOf("Celsius (°C)", "Fahrenheit (°F)")
    val PRESS_NAMES = listOf("kPa", "bar", "psi")
    val ECON_NAMES = listOf("km/L", "L/100 km", "mpg (EUA)")
    val VOL_NAMES = listOf("Litros", "Galões (EUA)")

    /** Converte da unidade base para a unidade de exibição. */
    fun convert(s: Sensor, v: Float, st: Settings): Float {
        if (v.isNaN()) return v
        return when (s.kind) {
            Kind.SPEED, Kind.DISTANCE -> if (st.distUnit == 1) v / KM_PER_MILE else v
            Kind.TEMP -> if (st.tempUnit == 1) v * 9f / 5f + 32f else v
            Kind.PRESSURE -> when (st.pressUnit) { 1 -> v / 100f; 2 -> v * 0.1450377f; else -> v }
            Kind.ECONOMY -> when (st.econUnit) {
                1 -> if (v > 0.01f) 100f / v else Float.NaN
                2 -> v * 2.352146f
                else -> v
            }
            Kind.VOLUME, Kind.FLOW -> if (st.volUnit == 1) v / L_PER_GAL else v
            else -> v
        }
    }

    /** Converte um valor digitado pelo usuário de volta para a unidade base (usado nos limites de alarme). */
    fun toBase(s: Sensor, v: Float, st: Settings): Float = when (s.kind) {
        Kind.SPEED, Kind.DISTANCE -> if (st.distUnit == 1) v * KM_PER_MILE else v
        Kind.TEMP -> if (st.tempUnit == 1) (v - 32f) * 5f / 9f else v
        else -> v
    }

    fun unit(s: Sensor, st: Settings): String = when (s.kind) {
        Kind.SPEED -> if (st.distUnit == 1) "mph" else "km/h"
        Kind.DISTANCE -> if (st.distUnit == 1) "mi" else "km"
        Kind.TEMP -> if (st.tempUnit == 1) "°F" else "°C"
        Kind.PRESSURE -> PRESS_NAMES[st.pressUnit.coerceIn(0, 2)]
        Kind.ECONOMY -> when (st.econUnit) { 1 -> "L/100km"; 2 -> "mpg"; else -> "km/L" }
        Kind.VOLUME -> if (st.volUnit == 1) "gal" else "L"
        Kind.FLOW -> if (st.volUnit == 1) "gal/h" else "L/h"
        Kind.MONEY -> st.currency
        Kind.TIME -> ""
        Kind.PLAIN -> s.plainUnit
    }

    private fun decimals(s: Sensor, st: Settings): Int = when {
        s.kind == Kind.PRESSURE && st.pressUnit == 1 -> 2
        s.kind == Kind.PRESSURE && st.pressUnit == 2 -> 1
        else -> s.decimals
    }

    /** Texto do valor, sem a unidade. "--" quando não há dado. */
    fun format(s: Sensor, base: Float, st: Settings): String {
        if (base.isNaN()) return "--"
        if (s.kind == Kind.TIME) {
            val t = base.toLong()
            return String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
        }
        val v = convert(s, base, st)
        if (v.isNaN() || v.isInfinite()) return "--"
        return String.format(Locale("pt", "BR"), "%.${decimals(s, st)}f", v)
    }

    /** Faixa do mostrador já na unidade de exibição. */
    fun range(s: Sensor, st: Settings): Pair<Float, Float> {
        if (s.kind == Kind.ECONOMY && st.econUnit == 1) return 0f to 30f
        val a = convert(s, s.min, st)
        val b = convert(s, s.max, st)
        return minOf(a, b) to maxOf(a, b)
    }
}
