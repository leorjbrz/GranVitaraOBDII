# Protocolo de comunicação

Como o app conversa com a central do motor (ECU) do Suzuki (Grand) Vitara 2.0 (J20A) por meio de um adaptador ELM327 Bluetooth.

Os valores de tempo e as respostas de exemplo foram observados em um veículo usando um ELM327 v1.5. Outras versões de ECU da mesma família podem diferir.

O mapa dos dados recebidos está em [parametros.md](parametros.md).

## Visão geral

| Camada | O que é usado |
|---|---|
| Ligação com o celular | Bluetooth clássico, perfil de porta serial (SPP) |
| Adaptador | ELM327, em modo texto (comandos AT e hexadecimal ASCII) |
| Linha física no carro | K‑Line, 10 400 baud |
| Protocolo | ISO 14230‑4 (KWP2000), com inicialização de 5 baud |
| Endereço da ECU do motor | `0x11` |
| Endereço do testador | `0xF1` |
| Serviço de leitura de dados | `0x21` ReadDataByLocalIdentifier |

Não é OBD‑II genérico. A ECU não responde aos serviços `01` e `03` nem ao endereço padrão `0x33`; o diagnóstico é o proprietário da Suzuki sobre KWP2000.

O app só faz leitura. Nenhum serviço de escrita, de acionamento de atuadores ou de apagamento de falhas é enviado.

## Conexão Bluetooth

- UUID do serviço: `00001101-0000-1000-8000-00805F9B34FB` (SPP).
- O adaptador precisa estar pareado previamente no Android; o app não faz busca de dispositivos.
- Primeira tentativa: socket RFCOMM "inseguro" (`createInsecureRfcommSocketToServiceRecord`). Se falhar, tenta o socket "seguro".
- A conexão costuma levar menos de 1,5 s.

**Queda por ociosidade:** a ligação Bluetooth é encerrada pelo adaptador depois de cerca de 15 s sem tráfego (com 13 s ainda se mantém). Como o app lê continuamente, isso não ocorre em uso normal; qualquer implementação que faça pausas precisa enviar um comando periódico.

## Formato das mensagens

Tudo trafega em texto ASCII.

**Envio:** o comando seguido de retorno de carro (`0x0D`). Exemplo: `21 00 1\r`.

**Recepção:** o adaptador devolve uma ou mais linhas terminadas em `\r` e, ao final, o caractere `>` indicando que está pronto para o próximo comando. O app lê até o `>` (ou até o tempo limite) e separa as linhas.

### Linha de resposta da ECU

Com os cabeçalhos ligados (`AT H1`), cada resposta vem como bytes em hexadecimal separados por espaço:

```
80 F1 11 LL SS dd dd dd ... CS
│  │  │  │  │  │            └─ checksum: soma de todos os bytes anteriores, módulo 256
│  │  │  │  │  └────────────── dados
│  │  │  │  └───────────────── serviço respondido
│  │  │  └──────────────────── comprimento: número de bytes de SS até antes de CS
│  │  └─────────────────────── origem: ECU (0x11)
│  └────────────────────────── destino: testador (0xF1)
└───────────────────────────── formato: 0x80 = endereçamento físico, comprimento em byte próprio
```

| Tipo de resposta | Como reconhecer |
|---|---|
| Positiva | `SS` = serviço pedido + `0x40` (ex.: `61` para o pedido `21`) |
| Negativa | `SS` = `7F`, seguido do serviço pedido e de um código de motivo |

Códigos de motivo observados: `11` (serviço não suportado) e `12` (subfunção não suportada).

O app confere o cabeçalho (`80`…`BF` no primeiro byte, `F1` no segundo) e o checksum de toda linha. Linhas corrompidas ou cortadas são descartadas.

## Inicialização

Enviados em ordem. Cada comando deve responder `OK`; se o adaptador recusar algum, o app interrompe e informa qual foi.

| Comando | Função |
|---|---|
| `AT Z` | Reinicia o adaptador |
| `AT PC` | Encerra qualquer protocolo ativo |
| `AT D` | Restaura os padrões |
| `AT E0` | Desliga o eco dos comandos |
| `AT SP 4` | Seleciona ISO 14230‑4 com init de 5 baud |
| `AT H1` | Mostra os cabeçalhos nas respostas |
| `AT KW0` | Não valida os bytes de chave do init |
| `AT ST FF` | Tempo limite de resposta no máximo (~1 s) |
| `AT IIA 11` | Endereço usado no init de 5 baud: `0x11` |
| `AT SH 80 11 F1` | Cabeçalho das mensagens enviadas |

Cada comando AT responde em 70 a 150 ms.

O comando `AT IIA` só existe a partir da versão 1.4 do ELM327. Adaptadores que não o implementam fazem o init no endereço padrão e não conseguem falar com esta ECU.

### Início da sessão

O init de 5 baud não acontece nos comandos AT, e sim no **primeiro pedido de dados**. O adaptador responde `BUS INIT: ...OK` seguido da linha de dados. Esse primeiro pedido leva cerca de **3,8 s**.

Se a resposta for `BUS INIT: ...ERROR`, a ECU não aceitou o início. Causas usuais: ignição desligada, ou tentativa feita logo depois do fim de outra sessão. A ECU precisa de alguns segundos de barramento em silêncio antes de aceitar um novo init. O app fecha a conexão, espera 8 s e recomeça.

## Leitura de dados

| Item | Valor |
|---|---|
| Pedido | `21 00 1` |
| Resposta | 77 bytes: `80 F1 11 48 61 00` + 70 bytes de dados + checksum |
| Tempo por leitura | ~260 ms (cerca de 3,5 leituras por segundo) |
| Intervalo mínimo entre pedidos | 40 ms |

O `1` no final do pedido é um recurso do ELM327 (versão 1.3 ou superior): indica que só uma resposta é esperada, e o adaptador a devolve assim que chega, sem aguardar o tempo limite. Sem o sufixo, a leitura leva ~315 ms e os primeiros pedidos depois de uma pausa chegam a 1,3 s.

A sessão com a ECU se mantém por pelo menos 10 s sem pedidos, porque o adaptador envia mensagens de manutenção por conta própria.

Outros identificadores do serviço `21`:

| Pedido | Resposta |
|---|---|
| `21 00` | Bloco de dados (ver [parametros.md](parametros.md)) |
| `21 01` | 57 bytes, todos `FF` (bloco existente, sem conteúdo) |
| `21 02` … `21 FF` | Resposta negativa |

## Tensão da bateria

Lida do próprio adaptador, que mede a tensão no pino de alimentação do conector:

```
AT RV  →  12.4V
```

O app repete a leitura a cada 20 blocos de dados (cerca de 5 s). A ECU também informa a tensão que ela enxerga, dentro do bloco de dados.

## Códigos de falha

| Item | Valor |
|---|---|
| Pedido | `18 00 00 00` (ReadDTCByStatus, todos os estados, todos os grupos) |
| Resposta sem falhas | `80 F1 11 02 58 00 DC` |

Formato esperado da resposta, segundo o padrão KWP2000:

```
58 NN  HH LL SS  HH LL SS  ...
│  │   └──┴──┴─ um código: 2 bytes do código e 1 byte de estado
│  └─────────── quantidade de códigos
└────────────── resposta positiva ao serviço 18
```

- **Código:** os 2 bits mais altos escolhem a letra (`P`, `C`, `B`, `U`), os 2 seguintes o primeiro dígito e os 12 restantes os três dígitos hexadecimais. Exemplo: `01 20` → `P0120`.
- **Estado** (ISO 14230‑3): bits 5 e 6 indicam a situação (`11` presente agora, `10` intermitente, `01` armazenada e não presente); o bit 7 indica a luz de falha acesa.

A resposta com códigos presentes **não foi observada** em um veículo; a interpretação acima segue a norma.

Outros formatos do serviço `18` (`18 00 FF 00`, `18 02 FF 00`) e os serviços `13`, `17` e `12` recebem resposta negativa.

## Identificação da ECU

Serviço `1A` (ReadEcuIdentification):

| Pedido | Conteúdo |
|---|---|
| `1A 8A`, `1A 8B`, `1A 91` | Número de peça em ASCII (ex.: `12345-67AB*`) |
| `1A 9A` | Segundo número de peça (ex.: `33920-82D4`) |
| `1A 90` | Chassi; pode vir preenchido com `FF` |
| Demais de `80` a `9F` | Resposta negativa ou sem informação útil |

O app lê `1A 8A` junto com os códigos de falha, para exibir o número de peça.

## Outros serviços

| Pedido | Resposta |
|---|---|
| `3E` | Positiva (`7E`): TesterPresent |
| `10 81` | Positiva (`50 81`): sessão de diagnóstico padrão |
| `01 00`, `03` | Negativa: sem serviços OBD‑II |

O app não usa esses serviços.

## Tratamento de falhas

| Situação | Ação |
|---|---|
| Adaptador não escolhido | Avisa e aguarda a configuração |
| Falha ao abrir o socket | Informa o erro; nova tentativa em 8 s |
| Comando AT sem `OK` | Interrompe e informa o comando recusado; nova tentativa em 8 s |
| `BUS INIT: ...ERROR` | Fecha a conexão; nova tentativa em 8 s |
| 3 respostas seguidas não reconhecidas | Fecha a conexão; nova tentativa em 8 s |
| Erro de leitura ou escrita no socket | Fecha a conexão; nova tentativa em 8 s |

A reconexão é automática e indefinida.

## Tempos limite

| Operação | Limite |
|---|---|
| `AT Z` | 6 s |
| Demais comandos AT | 5 s |
| Primeiro pedido de dados | 15 s |
| Pedidos de dados seguintes | 2,5 s |
| `AT RV` | 2 s |
| Leitura de falhas | 4 s |
