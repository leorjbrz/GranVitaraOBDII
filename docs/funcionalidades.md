# Funcionalidades

Descrição técnica do que o app faz e de como está organizado. O uso passo a passo está no [manual do usuário](manual-do-usuario.html).

## Visão geral

| Aba | Função |
|---|---|
| Painel | Dois mostradores circulares e blocos configuráveis com os dados em tempo real |
| Mapa | Posição, bússola, camadas de mapa, rotas e registro de viagens |
| Viagem | Acumulados da viagem atual, máximos e totais |
| Logs | Registro do funcionamento do app em tempo real |
| Config | Adaptador, diagnóstico, unidades, combustível, painel, sensores, alarmes e gravação |

O app tem uma única tela (Activity) que troca o conteúdo conforme a aba. A interface é construída em código, sem bibliotecas de interface além do SDK do Android.

## Catálogo de dados

São 40 dados, agrupados assim:

| Grupo | Quantidade | Dados |
|---|---|---|
| Veículo | 16 | Velocidade, rotação, temperatura do motor, temperatura do ar, vazão de ar, borboleta, tensão no conector, tensão na ECU, carga, avanço, correções curta, longa e total, sonda lambda, rotação‑alvo de lenta, tempo de injeção |
| Consumo | 4 | Por hora, instantâneo, média da viagem, média total |
| Viagem | 11 | Distância, combustível, custo, tempo, tempo parado, combustível parado, velocidade média e máxima, rotação máxima, temperatura máxima, CO₂ |
| Totais | 3 | Distância, combustível, custo |
| Diagnóstico | 4 | Aceleração, relação de marcha, ciclo dos injetores, potência estimada |
| Provável | 2 | Válvula de marcha lenta, tensão do sensor da borboleta |

Os dados do grupo "Provável" ficam ocultos até serem habilitados e aparecem marcados com `*`.

Cada dado tem nome, rótulo curto, tipo de grandeza, faixa para o mostrador, número de casas decimais e, quando aplicável, o início da faixa vermelha (6000 rpm para a rotação, 105 °C para a temperatura do motor).

A origem das leituras está em [parametros.md](parametros.md) e as fórmulas dos calculados em [calculos.md](calculos.md).

## Painel

- **Linha de estado:** situação da conexão, motivo da falha quando há, e à direita a taxa de leituras por segundo ou os estados informados pela ECU (`A/C`, `corte de combustível`, `malha aberta`).
- **Mostradores:** dois arcos de 270° com marcas a cada 10 % da escala e faixa vermelha opcional. O ponteiro é suavizado entre leituras. Os dados disponíveis para os mostradores são rotação, velocidade, temperaturas, vazão de ar, borboleta, carga, avanço, tensão, consumo por hora, consumo instantâneo e potência.
- **Blocos:** um por dado selecionado, na ordem do catálogo, com valor, unidade e mini‑gráfico dos últimos 30 s.
- **Alarmes:** o bloco muda de cor e uma faixa no topo lista os alarmes ativos.

Layout:

| Orientação | Disposição |
|---|---|
| Retrato | Mostradores em cima, blocos em 2 colunas, tudo rola junto |
| Paisagem | Mostradores fixos à esquerda, blocos em 3 colunas com rolagem à direita |

## Mapa

Usa a biblioteca osmdroid, com blocos de mapa baixados da internet e guardados em cache no aparelho.

| Recurso | Descrição |
|---|---|
| Camadas | Ruas (OpenStreetMap), terreno (OpenTopoMap) e satélite (Esri World Imagery) |
| Posição | Seta que indica a direção do carro |
| Cartão de informações | Latitude, longitude, altitude, velocidade do GPS, precisão, bússola e orientação do mapa; com rota ou viagem ativas, também a próxima manobra e os números da viagem |
| Zoom | Pinça, toque duplo e botões `+` e `−` |
| Orientação | Norte para cima, giro livre com dois dedos ou sempre para a frente; alternada pelo toque na bússola |
| Seguir posição | Ligado ao tocar em Centralizar; desligado ao arrastar o mapa |
| Mini‑gráficos | Até dois dados do carro sobrepostos ao mapa, com escolha do dado, do canto e da opacidade (20 a 100 %) |
| Crédito | O provedor dos dados do mapa é exibido conforme a camada |

## Rotas

O cálculo usa o servidor público do projeto OSRM, com perfil de carro.

- **Pedido:** toque longo em um ponto do mapa, ou automaticamente quando uma viagem recebe destino com coordenadas.
- **Resultado:** traçado pelas ruas, distância, tempo estimado e lista de manobras.
- **Instruções:** geradas em português a partir do tipo e da direção de cada manobra (virar, continuar, entrar em via, acesso, saída, bifurcação, rotatória, retorno, chegada), com o nome da via quando existe.
- **Acompanhamento:** a cada posição do GPS o app localiza o ponto mais próximo do traçado, atualiza a próxima manobra e o que falta, e recalcula ao sair da rota.
- **Outros apps:** o destino pode ser enviado a um app de navegação instalado.

Não há instruções por voz nem informação de trânsito.

## Registro de viagens

Uma viagem por vez. Estados: em andamento, pausada e finalizada.

| Ação | Efeito |
|---|---|
| Iniciar | Cria a viagem com nome, origem e destino. A origem usa a posição atual. O destino digitado é convertido em coordenadas pelo serviço de localização do Android; se falhar, pode ser marcado no mapa |
| Pausar | Registra um evento com motivo (abastecimento, refeição, descanso, pedágio/parada rápida, outro), observação e, no abastecimento, litros e valor |
| Retomar | Volta a contar tempo e distância |
| Finalizar | Fecha os arquivos, gera o trajeto em GPX e o resumo |

Durante a viagem são gravados, uma vez por segundo, todos os dados do catálogo e as posições do GPS, inclusive nas pausas (marcadas como tal).

Se o app for fechado com uma viagem em andamento, ela é retomada na abertura seguinte, com o trajeto já gravado recarregado.

### Arquivos de uma viagem

Cada viagem ocupa uma pasta em `Android/data/com.vitaraobd.app/files/viagens_registradas/<data_hora>/`:

| Arquivo | Conteúdo |
|---|---|
| `viagem.json` | Nome, origem, destino, horários, resumo, eventos e configurações em uso |
| `dados_veiculo.csv` | Hora, estado (andando ou pausa) e os 40 dados do catálogo, uma linha por segundo |
| `trajeto_gps.csv` | Hora, latitude, longitude, altitude, velocidade, rumo, precisão e estado |
| `trajeto.gpx` | Trajeto percorrido, com origem, destino e pausas como pontos |
| `eventos.csv` | Início, pausas, retomadas e fim, com motivo, observação, litros, valor e posição |
| `resumo.txt` | Resumo legível |
| `rota_calculada.gpx`, `rota_calculada_passos.txt` | Rota planejada e suas manobras, quando houve cálculo de rota |

Os arquivos CSV usam ponto e vírgula como separador, ponto como separador decimal e as unidades fixas (km/h, °C, g/s, L, km, s), independentemente das unidades escolhidas na tela.

As configurações gravadas em `viagem.json` incluem adaptador, combustível, relação ar/combustível, densidade, preço, moeda, coeficientes, tratamento do corte de combustível, cilindrada, unidades de exibição, alarmes, modelo do aparelho e versão do app.

A exportação gera um ZIP com a pasta da viagem, salvo no local escolhido pelo usuário no seletor de arquivos do Android.

## Aba Viagem

Mostra os acumuladores da viagem atual, os máximos e os totais, com botões para zerar cada conjunto. Em paisagem, a viagem atual fica à esquerda e os máximos e totais à direita. Esses acumuladores são independentes das viagens registradas no mapa.

## Códigos de falha

Em Config → Diagnóstico. A leitura é intercalada entre duas leituras de dados, sem interromper o painel, e só é possível com a comunicação ativa.

Para cada código são exibidos o código, uma descrição genérica (cerca de 40 códigos comuns do padrão OBD‑II), a situação decodificada do byte de estado e os bytes crus. A tela mostra também o número de peça da ECU e a resposta completa recebida.

Há um modo de exemplo, com dois códigos fictícios identificados como tal, para demonstrar a apresentação.

O app não apaga códigos.

## Alarmes

Cinco alarmes, cada um com ativação e limite próprios: superaquecimento, bateria baixa, sobretensão, excesso de velocidade e rotação alta. O aviso é visual (bloco e faixa no topo) e, opcionalmente, sonoro.

## Configurações

| Seção | Ajustes |
|---|---|
| Adaptador | Escolha entre os dispositivos Bluetooth pareados; exibir dados prováveis |
| Diagnóstico | Leitura dos códigos de falha |
| Unidades | Distância e velocidade (km ou milhas), temperatura (°C ou °F), pressão (kPa, bar ou psi), consumo (km/L, L/100 km ou mpg), volume (litros ou galões) |
| Combustível e consumo | Tipo de combustível, preço, moeda, coeficientes de combustível e de distância, tratamento do corte de combustível |
| Painel | Dado de cada mostrador, mini‑gráficos nos blocos, tela sempre ligada |
| Sensores no painel | Seleção dos blocos exibidos |
| Alarmes | Ativação, limites e som |
| Gravação | Gravação contínua dos dados em CSV |

As alterações são salvas imediatamente e aplicadas sem reiniciar. Mudanças no adaptador reiniciam a comunicação.

## Gravação contínua

Independente das viagens registradas. Quando ligada, grava todos os dados do catálogo, uma linha por segundo, em `Android/data/com.vitaraobd.app/files/viagens/viagem_<data_hora>.csv`. Um arquivo novo é criado a cada início de comunicação.

## Logs

O app mantém um registro com quatro níveis (depuração, informação, aviso e erro):

- os últimos 1500 registros ficam em memória e alimentam a aba Logs, atualizada a cada 0,3 s;
- tudo é gravado em um arquivo por dia em `Android/data/com.vitaraobd.app/files/logs/`, mantidos os 7 mais recentes.

No nível de depuração aparecem todos os comandos enviados ao adaptador e as respostas recebidas, com o tempo de cada troca. A aba permite filtrar por nível, pausar, limpar e compartilhar o conteúdo.

## Navegação e ciclo de vida

| Situação | Comportamento |
|---|---|
| Botão voltar fora do Painel | Vai para o Painel |
| Botão voltar duas vezes no Painel (em até 2,5 s) | Grava os acumuladores e a viagem em andamento, encerra a comunicação e fecha o app |
| Rotação do aparelho | O layout se adapta; a comunicação e as gravações continuam |
| App em segundo plano | A leitura continua enquanto o processo existir. Não há serviço em primeiro plano, então o Android pode encerrar o app depois de algum tempo |
| GPS | Ligado com o app visível; permanece ligado em segundo plano apenas quando há viagem sendo registrada |

## Permissões

| Permissão | Uso |
|---|---|
| Bluetooth (dispositivos próximos) | Comunicação com o adaptador |
| Localização | Posição no mapa, bússola, rotas e trajeto das viagens |
| Internet e estado da rede | Blocos de mapa e cálculo de rotas |

Os arquivos são gravados na área de armazenamento do próprio app, o que dispensa permissão de armazenamento.

## Organização do código

| Arquivo | Responsabilidade |
|---|---|
| `ElmSource.kt` | Conexão Bluetooth, inicialização, laço de leitura, leitura de falhas e reconexão |
| `SuzukiBlock.kt` | Interpretação das respostas da ECU: bloco de dados, códigos de falha e número de peça |
| `DataSource.kt` | Interface da fonte de dados e os tipos trocados com ela |
| `TripComputer.kt` | Consumo, distância, tempos, médias e valores de diagnóstico |
| `Engine.kt` | Núcleo: fonte ativa, valores atuais, histórico, alarmes e gravação contínua |
| `Sensors.kt` | Catálogo de dados e conversão de unidades |
| `Settings.kt` | Preferências e definição dos alarmes |
| `Gps.kt` | Posição e bússola do aparelho |
| `Router.kt` | Cálculo de rotas, instruções e acompanhamento |
| `Journeys.kt` | Registro de viagens, arquivos e exportação |
| `AppLog.kt` | Registro em memória e em arquivo |
| `MainActivity.kt` | Abas, permissões, botão voltar e exportação |
| `ui/Widgets.kt` | Mostrador circular e bloco com mini‑gráfico |
| `ui/Screens.kt` | Telas Painel, Viagem e Logs |
| `ui/MapScreen.kt` | Tela Mapa |
| `ui/SettingsScreen.kt` | Tela Config |
| `ui/DtcScreen.kt` | Tela de códigos de falha |

A comunicação roda em uma thread própria. As leituras são processadas e publicadas para a interface na thread principal, onde também rodam o registro de viagens, o GPS e o acompanhamento de rotas.

Dependência externa: `osmdroid`, para o mapa.
