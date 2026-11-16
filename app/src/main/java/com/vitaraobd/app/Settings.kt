package com.vitaraobd.app

import android.content.Context
import android.content.SharedPreferences

/** Preferências do app. Toda alteração incrementa [revision] para as telas se atualizarem. */
class Settings(ctx: Context) {

    private val p: SharedPreferences = ctx.getSharedPreferences("vitara", Context.MODE_PRIVATE)

    @Volatile
    var revision: Int = 0
        private set

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(block).apply()
        revision++
    }

    // ---------------------------------------------------------------- fonte de dados

    var btAddress: String
        get() = p.getString("bt_address", "") ?: ""
        set(v) = put { putString("bt_address", v) }

    var btName: String
        get() = p.getString("bt_name", "") ?: ""
        set(v) = put { putString("bt_name", v) }

    /** Mostra também os dados cuja interpretação ainda não foi confirmada. */
    var experimental: Boolean
        get() = p.getBoolean("experimental", false)
        set(v) = put { putBoolean("experimental", v) }

    // ---------------------------------------------------------------- unidades

    var distUnit: Int      // 0 km / km/h   1 milhas / mph
        get() = p.getInt("u_dist", 0)
        set(v) = put { putInt("u_dist", v) }
    var tempUnit: Int      // 0 °C  1 °F
        get() = p.getInt("u_temp", 0)
        set(v) = put { putInt("u_temp", v) }
    var pressUnit: Int     // 0 kPa  1 bar  2 psi
        get() = p.getInt("u_press", 0)
        set(v) = put { putInt("u_press", v) }
    var econUnit: Int      // 0 km/L  1 L/100km  2 mpg
        get() = p.getInt("u_econ", 0)
        set(v) = put { putInt("u_econ", v) }
    var volUnit: Int       // 0 litro  1 galão (US)
        get() = p.getInt("u_vol", 0)
        set(v) = put { putInt("u_vol", v) }

    // ---------------------------------------------------------------- combustível

    /** Índice em [FUELS]. */
    var fuelType: Int
        get() = p.getInt("fuel_type", 0)
        set(v) = put { putInt("fuel_type", v) }
    var fuelPrice: Float
        get() = p.getFloat("fuel_price", 6.00f)
        set(v) = put { putFloat("fuel_price", v) }
    var currency: String
        get() = p.getString("currency", "R$") ?: "R$"
        set(v) = put { putString("currency", v) }
    /** Multiplicadores de calibração (1.0 = sem correção). */
    var fuelCoef: Float
        get() = p.getFloat("fuel_coef", 1f)
        set(v) = put { putFloat("fuel_coef", v) }
    var distCoef: Float
        get() = p.getFloat("dist_coef", 1f)
        set(v) = put { putFloat("dist_coef", v) }
    /** Considera consumo zero em desaceleração com borboleta fechada. */
    var fuelCut: Boolean
        get() = p.getBoolean("fuel_cut", true)
        set(v) = put { putBoolean("fuel_cut", v) }

    // ---------------------------------------------------------------- painel

    var gauge1: Sensor
        get() = sensor("gauge1", Sensor.RPM)
        set(v) = put { putString("gauge1", v.name) }
    var gauge2: Sensor
        get() = sensor("gauge2", Sensor.SPEED)
        set(v) = put { putString("gauge2", v.name) }

    /** Sensores exibidos como blocos no painel, na ordem do catálogo. */
    var tiles: Set<Sensor>
        get() {
            val raw = p.getString("tiles", null) ?: return DEFAULT_TILES
            return raw.split(',').mapNotNull { n -> Sensor.entries.firstOrNull { it.name == n } }.toSet()
        }
        set(v) = put { putString("tiles", v.joinToString(",") { it.name }) }

    var showSparkline: Boolean
        get() = p.getBoolean("sparkline", true)
        set(v) = put { putBoolean("sparkline", v) }
    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", true)
        set(v) = put { putBoolean("keep_screen_on", v) }

    private fun sensor(key: String, def: Sensor): Sensor =
        Sensor.entries.firstOrNull { it.name == p.getString(key, def.name) } ?: def

    // ---------------------------------------------------------------- mapa

    /** Camada do mapa: 0 ruas, 1 terreno, 2 satélite. */
    var mapLayer: Int
        get() = p.getInt("map_layer", 0)
        set(v) = put { putInt("map_layer", v) }

    /** Orientação do mapa: 0 norte para cima, 1 giro livre com dois dedos, 2 sempre para a frente. */
    var mapOrientation: Int
        get() = p.getInt("map_orientation", 0)
        set(v) = put { putInt("map_orientation", v) }

    /** Sensor do mini-gráfico [n] (1 ou 2) sobre o mapa; null = não mostrar. */
    fun mapWidget(n: Int): Sensor? {
        val def = if (n == 1) Sensor.SPEED.name else Sensor.ECON_INST.name
        val name = p.getString("map_widget_$n", def)
        return Sensor.entries.firstOrNull { it.name == name }
    }
    fun setMapWidget(n: Int, s: Sensor?) = put { putString("map_widget_$n", s?.name ?: "") }

    /** Canto do mini-gráfico: 0 sup. esquerdo, 1 sup. direito, 2 inf. esquerdo, 3 inf. direito. */
    fun mapWidgetCorner(n: Int): Int = p.getInt("map_widget_corner_$n", if (n == 1) 2 else 3)
    fun setMapWidgetCorner(n: Int, corner: Int) = put { putInt("map_widget_corner_$n", corner) }

    /** Opacidade dos mini-gráficos sobre o mapa, de 20 a 100 %. */
    var mapWidgetAlpha: Int
        get() = p.getInt("map_widget_alpha", 80).coerceIn(20, 100)
        set(v) = put { putInt("map_widget_alpha", v.coerceIn(20, 100)) }

    // ---------------------------------------------------------------- alarmes

    fun alarmEnabled(a: AlarmDef): Boolean = p.getBoolean("al_on_${a.name}", a.defaultOn)
    fun setAlarmEnabled(a: AlarmDef, v: Boolean) = put { putBoolean("al_on_${a.name}", v) }
    /** Limite sempre na unidade base do sensor (°C, V, km/h, rpm). */
    fun alarmLimit(a: AlarmDef): Float = p.getFloat("al_lim_${a.name}", a.defaultLimit)
    fun setAlarmLimit(a: AlarmDef, v: Float) = put { putFloat("al_lim_${a.name}", v) }

    var alarmSound: Boolean
        get() = p.getBoolean("alarm_sound", true)
        set(v) = put { putBoolean("alarm_sound", v) }

    // ---------------------------------------------------------------- gravação

    var csvLogging: Boolean
        get() = p.getBoolean("csv", false)
        set(v) = put { putBoolean("csv", v) }

    // ---------------------------------------------------------------- acumuladores (não contam como "alteração")

    fun loadAcc(key: String): Double = java.lang.Double.longBitsToDouble(p.getLong("acc_$key", 0L))
    fun saveAcc(values: Map<String, Double>) {
        val e = p.edit()
        for ((k, v) in values) e.putLong("acc_$k", java.lang.Double.doubleToRawLongBits(v))
        e.apply()
    }

    companion object {
        val DEFAULT_TILES: Set<Sensor> = setOf(
            Sensor.COOLANT, Sensor.BATTERY, Sensor.ECON_INST, Sensor.ECON_TRIP,
            Sensor.LOAD, Sensor.MAF, Sensor.TRIP_DIST, Sensor.FUEL_RATE,
        )

        /** Propriedades usadas no cálculo de consumo e CO2. */
        class Fuel(val name: String, val afr: Float, val densityKgL: Float, val co2KgPerL: Float)

        val FUELS = listOf(
            Fuel("Gasolina comum (E27)", 13.2f, 0.75f, 2.10f),
            Fuel("Gasolina pura (E0)", 14.7f, 0.74f, 2.31f),
        )
    }
}

/** Alarmes disponíveis. O limite é guardado na unidade base do sensor. */
enum class AlarmDef(val title: String, val sensor: Sensor, val isMax: Boolean, val defaultLimit: Float, val defaultOn: Boolean) {
    COOLANT_HIGH("Motor superaquecendo (acima de)", Sensor.COOLANT, true, 105f, true),
    BATTERY_LOW("Bateria baixa (abaixo de)", Sensor.BATTERY, false, 11.8f, true),
    BATTERY_HIGH("Sobretensão do alternador (acima de)", Sensor.BATTERY, true, 15.0f, true),
    SPEED_HIGH("Excesso de velocidade (acima de)", Sensor.SPEED, true, 110f, false),
    RPM_HIGH("Rotação alta (acima de)", Sensor.RPM, true, 6000f, false),
}
