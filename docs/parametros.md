# Parâmetros lidos da ECU

Conteúdo do bloco de dados devolvido pela ECU do motor em resposta ao pedido `21 00`. O formato das mensagens está em [protocolo.md](protocolo.md).

Os valores foram observados em um Suzuki Grand Vitara 2.0 (J20A), nas seguintes condições: ignição ligada com motor parado, marcha lenta a frio e a quente, rotação constante sem carga, ar‑condicionado ligado, acelerador no fundo e um trecho em movimento com aceleração forte e freio‑motor.

## Convenções

- A resposta tem 77 bytes: `80 F1 11 48 61 00`, 70 bytes de dados e o checksum.
- `d0` é o primeiro byte depois de `61 00`. O byte `dN` está na posição `N + 6` da linha.
- `A` é o byte indicado; em campos de 2 bytes, `A` é o mais significativo e `B` o seguinte.
- **`FF` significa "não fornecido"**, e `FF FF` nos campos de 2 bytes. As exceções estão indicadas.

## Dados confirmados

| Byte | Grandeza | Fórmula | Unidade | Observações |
|---|---|---|---|---|
| d11 | Estado do sistema de combustível | código | — | Ver [abaixo](#estado-do-sistema-de-combustível-d11) |
| d13 | Carga calculada do motor | `A · 100 ÷ 255` | % | `FF` é 100 %, não "ausente" |
| d14 | Temperatura do líquido de arrefecimento | `A − 40` | °C | |
| d15 | Correção de combustível curta (STFT) | `(A − 128) · 100 ÷ 128` | % | |
| d16 | Correção de combustível longa (LTFT) | `(A − 128) · 100 ÷ 128` | % | Varia conforme o regime |
| d20–d21 | Rotação | `(A · 256 + B) ÷ 4` | rpm | |
| d22 | Velocidade | `A` | km/h | |
| d23 | Avanço de ignição | `A ÷ 2 − 64` | ° | |
| d24 | Temperatura do ar admitido | `A − 40` | °C | |
| d25–d26 | Vazão de ar (MAF) | `(A · 256 + B) ÷ 100` | g/s | |
| d27 | Posição da borboleta | `A · 100 ÷ 255` | % | `FF` é 100 %, não "ausente" |
| d29 | Sonda lambda antes do catalisador | `A ÷ 200` | V | |
| d35 | Rotação‑alvo de marcha lenta | `A · 8` | rpm | |
| d37–d38 | Tempo de injeção | `A · 256 + B` | µs | Zero durante o corte de combustível |
| d47 | Correção de combustível total | `(A − 128) · 100 ÷ 128` | % | Igual a `d15 + d16 − 0x80` |
| d49 | Tensão da bateria vista pela ECU | `A · 0,08` | V | |

Os campos de `d11` a `d27` seguem a ordem, o tamanho e a escala dos PIDs `03` a `11` do modo 01 do OBD‑II (SAE J1979), com o PID `0A` omitido.

### Carga e borboleta chegam a `FF`

Com o pedal no fundo, o byte da borboleta vale `FF`. O mesmo ocorre com a carga em aceleração forte a baixa rotação. Nesses dois campos `FF` é o valor máximo e deve ser lido como 100 %. Tratá‑lo como "ausente" faz a leitura sumir exatamente na condição de carga total.

### Valores de referência

| Grandeza | Motor parado | Lenta a frio | Lenta quente | Lenta com A/C | ~2300 rpm sem carga | Pé no fundo, em movimento |
|---|---|---|---|---|---|---|
| Rotação (rpm) | 0 | 1070–1170 | 750–790 | 810–860 | 2150–2500 | 2500–2800 |
| Carga (%) | 0 | 18–20 | 15–16 | 20 | 15–17 | 79–100 |
| MAF (g/s) | 0 | 4,2–4,4 | 2,3–2,5 | 3,3–3,4 | 6,7–8,1 | 39–46 |
| Avanço (°) | 5 | 22–26 | 13–15 | 14–15 | 36–38 | 13–14 |
| Borboleta (%) | 0 | 0 | 0 | 0 | 10–11 | 100 |
| Tempo de injeção (ms) | 0 | 3,1–3,3 | 2,6–2,9 | 3,3–3,4 | 2,7–3,0 | 15,7–16,0 |
| Rotação‑alvo de lenta (rpm) | — | 1136–1152 | 768 | 816 | 768–776 | 768 |
| Tensão na ECU (V) | 12,4–13,1 | 13,9–14,3 | 13,9–14,3 | 13,9–14,1 | 14,1 | 13,8–14,2 |

Em marcha lenta com o motor quente, a sonda lambda oscila entre cerca de 0,1 V e 0,85 V e a correção curta entre −4 % e +4 %.

## Dados não fornecidos

| Byte | PID equivalente | Situação |
|---|---|---|
| d17, d18 | Correções de combustível do banco 2 | Sempre `FF` (motor de 4 cilindros em linha) |
| d19 | Pressão absoluta do coletor (MAP) | Sempre `FF`: o motor usa sensor MAF e não tem sensor MAP |

Por isso o app não oferece pressão do coletor, vácuo nem eficiência volumétrica.

## Bits de estado

| Byte | Bit | Máscara | Significado | Como se manifesta |
|---|---|---|---|---|
| d65 | 1 | `0x02` | Motor em funcionamento | Desligado com o motor parado |
| d65 | 6 | `0x40` | Corte de combustível | Ligado na desaceleração com marcha engatada, junto com tempo de injeção zero |
| d66 | 4 | `0x10` | Borboleta fechada | Ligado sempre que a borboleta está em 0 % com o motor ligado |
| d66 | 2 | `0x04` | Ar‑condicionado ligado | |
| d67 | 2 | `0x04` | Acompanha o ar‑condicionado | Provavelmente compressor ou ventilador do condensador |
| d67 | 1 | `0x02` | Desconhecido | Desligado logo após a partida a frio; ligado nas demais condições |
| d68 | 0 | `0x01` | Desconhecido | Ligado na maior parte do tempo |

Os interruptores de freio, direção hidráulica, carga elétrica e posição do câmbio não foram identificados.

## Estado do sistema de combustível (d11)

| Valor | Quando aparece | Significado na tabela OBD‑II |
|---|---|---|
| `01` | Motor parado | Malha aberta |
| `04` | Pé no fundo; durante o corte de combustível | Malha aberta por carga do motor ou desaceleração |
| `10` | Marcha lenta e rotação constante | Malha fechada, com falha em pelo menos uma sonda |

Os valores `01` e `04` aparecem nas situações que a tabela padrão descreve. O valor `10` aparece no funcionamento normal em malha fechada, com a sonda antes do catalisador oscilando e sem código de falha armazenado. Uma explicação possível é a ausência de sinal da segunda sonda (depois do catalisador). O app trata `01` e `04` como "malha aberta" e não tira conclusões do valor `10`.

## Dados prováveis

Comportamento coerente com a interpretação, mas sem confirmação por outra fonte. No app ficam ocultos até serem habilitados.

| Byte | Interpretação | Fórmula | Evidência |
|---|---|---|---|
| d42 | Abertura da válvula de marcha lenta (IAC) | `A · 100 ÷ 255` % | 16–18 % em lenta quente, 24–25 % com A/C, 33–35 % a frio; `FF` com o motor parado |
| d36 | Tensão do sensor de posição da borboleta | `A · 5 ÷ 255` V | 0,67 V fechada, 1,0 V a 2300 rpm, 4,24 V com o pedal no fundo; a variação é proporcional à de `d27`. A referência de 5 V é uma suposição. Neste campo `FF` também não deve ser tratado como "ausente" |

## Bytes sem interpretação

| Byte | Comportamento |
|---|---|
| d0–d6 | Sempre `00`. Pela posição, podem corresponder ao estado da luz de falha e à contagem de códigos |
| d44 | 118–132 em lenta quente, 168–173 com A/C, 221–232 a frio; varia amplamente em movimento. Relacionado ao controle de marcha lenta |
| d43, d54 | Zero com o carro parado; diferentes de zero em movimento |
| d55 | Contador que avança com o motor ligado e para com ele desligado |
| d50 | Sempre `00` |
| Demais | Sempre `FF` |

## Tensão medida pelo adaptador

Além dos dados da ECU, o app lê a tensão no conector com o comando `AT RV` do ELM327. Ela difere da tensão vista pela ECU (`d49`) em até 0,2 V.

## Exemplos

Marcha lenta com o motor quente:

```
80 F1 11 48 61 00 00 00 00 00 00 00 00 FF FF FF FF 10 FF 29 6F 82 8B FF FF FF 0C 5F 00 9C 43 00 FE 00 FF 20
FF FF FF FF FF 64 22 0B 44 FF FF FF 31 00 86 FF FF 8D FF B1 00 FF FF FF 00 0C FF FF FF FF FF FF FF FF FF 02 10 00 01 FF 10
```

| Campo | Bytes | Valor |
|---|---|---|
| Carga | `29` | 16,1 % |
| Temperatura do líquido | `6F` | 71 °C |
| Correção curta / longa / total | `82` / `8B` / `8D` | +1,6 % / +8,6 % / +10,2 % |
| Rotação | `0C 5F` | 792 rpm |
| Velocidade | `00` | 0 km/h |
| Avanço | `9C` | 14° |
| Temperatura do ar | `43` | 27 °C |
| MAF | `00 FE` | 2,54 g/s |
| Borboleta | `00` | 0 % |
| Sonda | `20` | 0,16 V |
| Rotação‑alvo de lenta | `64` | 800 rpm |
| Tempo de injeção | `0B 44` | 2,884 ms |
| Tensão na ECU | `B1` | 14,2 V |
| Estado | `02 10 00 01` | Motor ligado, borboleta fechada |

Desaceleração com marcha engatada (corte de combustível):

```
80 F1 11 48 61 00 00 00 00 00 00 00 00 FF FF FF FF 04 FF 1E 7C 80 8F FF FF FF 32 4B 30 CA 47 02 F5 00 FF BB
FF FF FF FF FF 60 22 00 00 FF FF FF 91 01 7D FF FF 8F FF AF 00 FF FF FF 00 13 FF FF FF FF FF FF FF FF FF 42 10 02 01 FF 5E
```

Rotação 3219 rpm, velocidade 48 km/h, borboleta 0 %, tempo de injeção 0 ms, MAF 7,57 g/s, `d11` = `04`, `d65` = `42` (motor ligado + corte). O ar continua passando pelo motor; apenas a injeção para.
