package com.vitaraobd.app

/** Uma leitura do veículo. Campos nulos = não fornecido nesta leitura. Unidades base. */
class Sample(val timeMs: Long) {
    // Lidos do bloco de dados da ECU.
    var rpm: Float? = null
    var speedKmh: Float? = null
    var coolantC: Float? = null
    var intakeC: Float? = null
    var mafGps: Float? = null
    var throttlePct: Float? = null
    var loadPct: Float? = null
    var timingDeg: Float? = null
    var stftPct: Float? = null
    var ltftPct: Float? = null
    var trimTotalPct: Float? = null
    var o2V: Float? = null
    var idleTargetRpm: Float? = null
    var injPulseMs: Float? = null
    var ecuBatteryV: Float? = null
    /** Tensão medida pelo adaptador no conector (AT RV). */
    var batteryV: Float? = null

    /** Este motor não tem sensor MAP: a ECU devolve "não fornecido". Mantido só para o cálculo de reserva. */
    var mapKpa: Float? = null

    // Estados informados pela ECU (bits de d11 e d65..d67).
    var engineRunning: Boolean = false
    /** Injeção cortada em desaceleração. */
    var fuelCut: Boolean = false
    var closedThrottle: Boolean = false
    var acOn: Boolean = false
    /** Malha aberta: motor parado, carga alta ou corte (d11 = 01 ou 04). */
    var openLoop: Boolean = false

    // Prováveis: comportamento coerente, nome ainda não confirmado.
    var iacPct: Float? = null
    var tpsVolt: Float? = null
}

enum class ConnState(val label: String) {
    STOPPED("Parado"),
    CONNECTING("Conectando ao adaptador…"),
    INITIALIZING("Iniciando comunicação com a ECU…"),
    RUNNING("Recebendo dados"),
    ERROR("Sem comunicação"),
}

/** Um código de falha armazenado na ECU. */
class Dtc(val code: String, val raw: Int, val status: Int)

/** Resultado de uma leitura de códigos de falha. */
class DtcResult(
    val ok: Boolean,
    val message: String,
    val codes: List<Dtc> = emptyList(),
    /** Número de peça informado pela ECU, se obtido. */
    val ecuPart: String = "",
    /** Respostas cruas, para conferência. */
    val raw: String = "",
)

/** Quem recebe os dados e o estado da fonte (o Engine). Chamado na thread da fonte. */
interface SourceSink {
    fun onState(state: ConnState, detail: String)
    fun onSample(sample: Sample)
}

/** Origem dos dados do veículo. Hoje a única implementação é [ElmSource] (ELM327 por Bluetooth). */
interface DataSource {
    val name: String
    fun start(sink: SourceSink)
    fun stop()
    /** Lê os códigos de falha entre duas leituras de dados. A resposta chega na thread da fonte. */
    fun readDtcs(callback: (DtcResult) -> Unit)
}
