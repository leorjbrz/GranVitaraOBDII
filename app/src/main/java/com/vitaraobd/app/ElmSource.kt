package com.vitaraobd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Fonte de dados: ELM327 por Bluetooth clássico, protocolo SUZUKI K-LINE 2.
 *
 * Comportamentos da ECU e do adaptador que moldam esta classe:
 *  - o socket "inseguro" é tentado primeiro; o "seguro" fica de reserva;
 *  - o primeiro 21 00 leva cerca de 4 s (init de 5 baud); os seguintes ~260 ms com o sufixo " 1";
 *  - a ligação Bluetooth cai com ~15 s sem tráfego: o laço de leitura nunca fica parado;
 *  - logo após encerrar uma sessão a ECU recusa novo init: por isso a espera antes de reconectar.
 */
class ElmSource(
    private val ctx: Context,
    private val address: String,
    private val withUnconfirmed: Boolean,
) : DataSource {

    override val name = "ELM327 Bluetooth"

    @Volatile private var running = false
    private var thread: Thread? = null
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Pedido de leitura de falhas aguardando a vez no laço de leitura. */
    @Volatile private var pendingDtc: ((DtcResult) -> Unit)? = null

    override fun start(sink: SourceSink) {
        running = true
        thread = Thread({ loop(sink) }, "elm327").also { it.isDaemon = true; it.start() }
    }

    override fun stop() {
        running = false
        failPendingDtc("A leitura foi interrompida.")
        close()                 // destrava leituras pendentes
        thread?.interrupt()
        thread = null
    }

    override fun readDtcs(callback: (DtcResult) -> Unit) {
        pendingDtc = callback
    }

    private fun failPendingDtc(message: String) {
        val cb = pendingDtc ?: return
        pendingDtc = null
        cb(DtcResult(false, message))
    }

    /** Conecta, inicializa e lê; em qualquer falha espera e tenta de novo, indefinidamente. */
    private fun loop(sink: SourceSink) {
        var attempt = 0
        while (running) {
            attempt++
            var wait = RETRY_DELAY_MS
            try {
                if (address.isEmpty()) {
                    // Sem adaptador escolhido não adianta insistir: só avisa e espera a configuração mudar.
                    sink.onState(ConnState.ERROR, "Escolha o adaptador em Config")
                    wait = 3_000L
                } else {
                    sink.onState(ConnState.CONNECTING, if (attempt > 1) "Tentativa $attempt" else "")
                    connect()
                    sink.onState(ConnState.INITIALIZING, "Adaptador conectado")
                    initialize()
                    attempt = 0
                    poll(sink)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                AppLog.e("ELM", "falha na comunicação", e)
                sink.onState(ConnState.ERROR, e.message ?: e.javaClass.simpleName)
                failPendingDtc("Sem comunicação com a ECU.")
            } finally {
                close()
            }
            if (running) {
                try { Thread.sleep(wait) } catch (_: InterruptedException) { break }
            }
        }
        sink.onState(ConnState.STOPPED, "")
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw IOException("este aparelho não tem Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth desligado")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: SecurityException) {
            throw IOException("permissão de Bluetooth negada")
        }
        // O socket "inseguro" é o que costuma funcionar com adaptadores ELM327; o "seguro" fica de reserva.
        var last: Exception? = null
        for (insecure in listOf(true, false)) {
            try {
                AppLog.i("ELM", "abrindo socket ${if (insecure) "inseguro" else "seguro"} para $address")
                val s = if (insecure) device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                else device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                AppLog.i("ELM", "socket conectado")
                return
            } catch (e: SecurityException) {
                throw IOException("permissão de Bluetooth negada")
            } catch (e: Exception) {
                last = e
                AppLog.w("ELM", "socket falhou: ${e.message}")
            }
        }
        throw IOException("não foi possível conectar ao adaptador (ele está ligado no carro?)", last)
    }

    private fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    /** Envia a sequência AT do SUZUKI K-LINE 2. */
    private fun initialize() {
        val id = command("AT Z", 6_000).joinToString(" ")
        AppLog.i("ELM", "adaptador: $id")
        for (cmd in INIT_COMMANDS) {
            val reply = command(cmd, 5_000)
            if (reply.none { it.contains("OK") }) {
                // Para e informa qual comando o adaptador recusou, em vez de seguir com a inicialização incompleta.
                throw IOException("o adaptador recusou '$cmd' (resposta: ${reply.joinToString(" | ").ifEmpty { "nada" }})")
            }
        }
    }

    private fun poll(sink: SourceSink) {
        var cycle = 0
        var failures = 0
        var battery: Float? = null
        while (running) {
            // O primeiro pedido dispara o init de 5 baud do barramento (cerca de 4 s).
            val lines = command(REQUEST, if (cycle == 0) 15_000L else 2_500L)
            val sample = SuzukiBlock.parseData(lines, withUnconfirmed)
            if (sample != null) {
                if (failures > 0 || cycle == 0) sink.onState(ConnState.RUNNING, "SUZUKI K-LINE 2")
                failures = 0
                sample.batteryV = battery
                sink.onSample(sample)
            } else {
                failures++
                val text = lines.joinToString(" | ")
                AppLog.w("ELM", "resposta não reconhecida ($failures): $text")
                if (text.contains("BUS INIT") && text.contains("ERROR")) {
                    throw IOException("a ECU não aceitou o início da comunicação (ignição ligada?). Nova tentativa em instantes.")
                }
                if (failures >= MAX_FAILURES) throw IOException("a ECU parou de responder (${text.ifEmpty { "sem resposta" }})")
            }

            if (cycle % BATTERY_EVERY == 0) {
                battery = command("AT RV", 2_000).firstNotNullOfOrNull {
                    it.substringBefore('V', "").trim().toFloatOrNull()
                } ?: battery
            }
            pendingDtc?.let { cb ->
                pendingDtc = null
                cb(readDtcsNow())
            }
            cycle++
            Thread.sleep(MIN_INTERVAL_MS)
        }
    }

    /** Leitura de falhas: somente leitura (serviço 18). Este app nunca apaga códigos. */
    private fun readDtcsNow(): DtcResult {
        AppLog.i("ELM", "lendo códigos de falha")
        val result = SuzukiBlock.parseDtcs(command(DTC_REQUEST, 4_000))
        val part = SuzukiBlock.parseAscii(command(PART_NUMBER_REQUEST, 3_000))
        AppLog.i("ELM", "falhas: ${result.message} ${result.codes.joinToString { it.code }}  ECU: $part")
        return DtcResult(result.ok, result.message, result.codes, part, result.raw)
    }

    /** Envia um comando e devolve as linhas recebidas até o prompt '>'. */
    private fun command(cmd: String, timeoutMs: Long): List<String> {
        val inp = input ?: throw IOException("não conectado")
        val out = output ?: throw IOException("não conectado")
        while (inp.available() > 0) inp.read()          // descarta sobras de respostas atrasadas

        AppLog.d("ELM", "TX $cmd")
        val t0 = SystemClock.elapsedRealtime()
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()

        val raw = StringBuilder()
        var prompt = false
        while (running) {
            if (SystemClock.elapsedRealtime() - t0 > timeoutMs) break
            if (inp.available() <= 0) { Thread.sleep(5); continue }
            val c = inp.read()
            if (c < 0) throw IOException("conexão encerrada pelo adaptador")
            if (c == '>'.code) { prompt = true; break }
            raw.append(c.toChar())
        }
        val lines = raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val ms = SystemClock.elapsedRealtime() - t0
        AppLog.d("ELM", "RX (${ms} ms${if (prompt) "" else ", TIMEOUT"}) ${lines.joinToString(" | ")}")
        return lines
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Sequência de inicialização do protocolo SUZUKI K-LINE 2 (KWP2000, init de 5 baud, ECU 0x11). */
        private val INIT_COMMANDS = listOf(
            "AT PC", "AT D", "AT E0", "AT SP 4", "AT H1", "AT KW0", "AT ST FF", "AT IIA 11", "AT SH 80 11 F1",
        )
        /** O sufixo " 1" manda o adaptador devolver a resposta assim que ela chega, sem esperar o tempo limite. */
        private const val REQUEST = "21 00 1"
        /** Formato de leitura de falhas aceito por esta ECU. */
        private const val DTC_REQUEST = "18 00 00 00"
        /** Devolve o número de peça da ECU em ASCII. */
        private const val PART_NUMBER_REQUEST = "1A 8A"
        private const val MIN_INTERVAL_MS = 40L
        private const val BATTERY_EVERY = 20         // lê AT RV a cada 20 blocos (~5 s)
        private const val MAX_FAILURES = 3
        /** A ECU precisa de alguns segundos de barramento em repouso antes de aceitar um novo init. */
        private const val RETRY_DELAY_MS = 8_000L
    }
}
