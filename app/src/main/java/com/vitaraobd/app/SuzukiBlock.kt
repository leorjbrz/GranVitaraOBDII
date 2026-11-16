package com.vitaraobd.app

/**
 * Interpretação das respostas da ECU Suzuki (KWP2000 pelo ELM327 com cabeçalhos ligados).
 * Código puro, sem dependência do Android.
 *
 * Formato de uma linha:  80 F1 11 LL  SS ..dados..  CS
 *   80 F1 11 = cabeçalho (formato, testador, ECU)   LL = nº de bytes de SS até antes de CS
 *   SS = serviço respondido (pedido + 0x40) ou 7F em resposta negativa   CS = soma de tudo, módulo 256
 *
 */
object SuzukiBlock {

    /**
     * Procura, da última linha para a primeira, uma resposta KWP íntegra e devolve os bytes
     * a partir do serviço (sem cabeçalho e sem checksum). null se não houver nenhuma.
     */
    fun payload(lines: List<String>): IntArray? {
        for (line in lines.asReversed()) {
            val parts = line.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size < 5 || parts.any { it.length != 2 }) continue
            val parsed = parts.map { it.toIntOrNull(16) }
            if (parsed.any { it == null }) continue
            val b = IntArray(parsed.size) { parsed[it] ?: 0 }
            // Só aceita linhas com o cabeçalho esperado (resposta de um módulo para o testador F1).
            if (b[0] !in 0x80..0xBF || b[1] != 0xF1) continue
            // Checksum: descarta linhas corrompidas ou cortadas.
            var sum = 0
            for (i in 0 until b.size - 1) sum += b[i]
            if (sum and 0xFF != b[b.size - 1]) continue
            val start = if (b[0] and 0x3F == 0) 4 else 3        // comprimento em byte próprio ou embutido
            if (start >= b.size - 1) continue
            return b.copyOfRange(start, b.size - 1)
        }
        return null
    }

    /** Interpreta a resposta a 21 00. null se as linhas não contêm um bloco válido. */
    fun parseData(lines: List<String>, withUnconfirmed: Boolean): Sample? {
        val p = payload(lines) ?: return null
        if (p.size < 30 || p[0] != 0x61 || p[1] != 0x00) return null

        // d(n) = dado de índice n depois de "61 00". FF (ou FF FF) = não fornecido pela ECU.
        fun u8(d: Int): Int? = p.getOrNull(d + 2)?.takeIf { it != 0xFF }
        fun u16(d: Int): Int? {
            val hi = p.getOrNull(d + 2) ?: return null
            val lo = p.getOrNull(d + 3) ?: return null
            return if (hi == 0xFF && lo == 0xFF) null else (hi shl 8) or lo
        }
        fun trim(d: Int): Float? = u8(d)?.let { (it - 128) * 100f / 128f }

        val s = Sample(System.currentTimeMillis())
        // Carga e borboleta chegam a FF de verdade (100 %); nesses dois campos FF não significa "ausente".
        s.loadPct = p.getOrNull(15)?.let { it * 100f / 255f }
        s.coolantC = u8(14)?.let { it - 40f }
        s.stftPct = trim(15)
        s.ltftPct = trim(16)
        s.mapKpa = u8(19)?.toFloat()                       // FF neste motor (sem sensor MAP)
        s.rpm = u16(20)?.let { it / 4f }
        s.speedKmh = u8(22)?.toFloat()
        s.timingDeg = u8(23)?.let { it / 2f - 64f }
        s.intakeC = u8(24)?.let { it - 40f }
        s.mafGps = u16(25)?.let { it / 100f }
        s.throttlePct = p.getOrNull(29)?.let { it * 100f / 255f }   // 0 em marcha lenta, FF com o pedal no fundo
        s.o2V = u8(29)?.let { it / 200f }
        s.idleTargetRpm = u8(35)?.let { it * 8f }
        s.injPulseMs = u16(37)?.let { it / 1000f }
        s.trimTotalPct = trim(47)
        s.ecuBatteryV = u8(49)?.let { it * 0.08f }
        // Bits de estado.
        val fuelSystem = p.getOrNull(13) ?: 0
        val f65 = p.getOrNull(67) ?: 0
        val f66 = p.getOrNull(68) ?: 0
        s.engineRunning = f65 and 0x02 != 0
        s.fuelCut = f65 and 0x40 != 0
        s.closedThrottle = f66 and 0x10 != 0
        s.acOn = f66 and 0x04 != 0
        s.openLoop = fuelSystem == 0x01 || fuelSystem == 0x04
        if (withUnconfirmed) {
            // Sobe com motor frio e com o A/C ligado: coerente com a válvula de marcha lenta.
            s.iacPct = u8(42)?.let { it * 100f / 255f }
            // 0,67 V com borboleta fechada e 4,2 V com pé no fundo, supondo referência de 5 V.
            s.tpsVolt = p.getOrNull(38)?.let { it * 5f / 255f }
        }
        return s
    }

    /**
     * Interpreta a resposta a 18 00 00 00 (ReadDTCByStatus).
     * Sem falhas a resposta é "58 00". Com falhas, o formato esperado é o padrão KWP2000
     * (58 NN + 3 bytes por código: 2 do código, 1 de estado), ainda não confirmado com uma falha real.
     */
    fun parseDtcs(lines: List<String>): DtcResult {
        val raw = lines.joinToString(" | ")
        val p = payload(lines) ?: return DtcResult(false, "A ECU não respondeu à leitura de falhas.", raw = raw)
        if (p[0] == 0x7F) {
            return DtcResult(false, String.format("A ECU recusou a leitura de falhas (código %02X).", p.getOrElse(2) { 0 }), raw = raw)
        }
        if (p[0] != 0x58 || p.size < 2) return DtcResult(false, "Resposta inesperada da ECU.", raw = raw)
        val codes = ArrayList<Dtc>()
        var i = 2
        while (i + 2 < p.size + 0 && codes.size < p[1]) {
            val value = (p[i] shl 8) or p[i + 1]
            codes += Dtc(dtcText(value), value, p[i + 2])
            i += 3
        }
        val msg = when {
            p[1] == 0 -> "Nenhum código de falha armazenado."
            codes.size == p[1] -> "${codes.size} código(s) de falha armazenado(s)."
            else -> "A ECU informou ${p[1]} código(s), mas só ${codes.size} puderam ser lidos."
        }
        return DtcResult(true, msg, codes, raw = raw)
    }

    /** Dois bytes → código no formato padrão (P0120, C1234…). */
    fun dtcText(value: Int): String {
        val letter = "PCBU"[(value shr 14) and 3]
        return String.format("%c%d%03X", letter, (value shr 12) and 3, value and 0x0FFF)
    }

    /**
     * Significado do byte de estado de um código, pelo padrão ISO 14230-3 (KWP2000):
     * bits 5-6 = situação da falha, bit 7 = luz de falha. Interpretação padrão, não confirmada com uma falha real.
     */
    fun statusText(status: Int): String {
        val state = when ((status shr 5) and 3) {
            3 -> "presente agora"
            2 -> "intermitente (em confirmação)"
            1 -> "armazenada, não presente agora"
            else -> "sem registro ativo"
        }
        return if (status and 0x80 != 0) "$state · luz de falha acesa" else state
    }

    /** Texto ASCII de uma resposta a 1A xx (ex.: número de peça). "" se não houver. */
    fun parseAscii(lines: List<String>): String {
        val p = payload(lines) ?: return ""
        if (p[0] != 0x5A || p.size < 3) return ""
        return p.drop(2).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
    }

    /** Descrição genérica dos códigos mais comuns. É a definição padrão OBD-II, não a tabela da Suzuki. */
    fun describe(code: String): String = when (code) {
        "P0100", "P0101", "P0102", "P0103" -> "Sensor de vazão de ar (MAF)"
        "P0110", "P0112", "P0113" -> "Sensor de temperatura do ar admitido"
        "P0115", "P0117", "P0118" -> "Sensor de temperatura do líquido de arrefecimento"
        "P0120", "P0121", "P0122", "P0123" -> "Sensor de posição da borboleta"
        "P0130", "P0131", "P0132", "P0133", "P0134" -> "Sonda lambda antes do catalisador"
        "P0135" -> "Aquecedor da sonda lambda antes do catalisador"
        "P0136", "P0137", "P0138", "P0140" -> "Sonda lambda depois do catalisador"
        "P0141" -> "Aquecedor da sonda lambda depois do catalisador"
        "P0171" -> "Mistura pobre"
        "P0172" -> "Mistura rica"
        "P0300" -> "Falhas de ignição em vários cilindros"
        "P0301", "P0302", "P0303", "P0304" -> "Falha de ignição no cilindro ${code.last()}"
        "P0325" -> "Sensor de detonação"
        "P0335" -> "Sensor de rotação (virabrequim)"
        "P0340" -> "Sensor de fase (comando)"
        "P0400", "P0401" -> "Recirculação de gases (EGR)"
        "P0420" -> "Eficiência do catalisador"
        "P0443" -> "Válvula de purga do canister (EVAP)"
        "P0500" -> "Sensor de velocidade do veículo"
        "P0505" -> "Controle de marcha lenta"
        else -> ""
    }
}
