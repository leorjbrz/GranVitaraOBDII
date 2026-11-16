# Cálculos

Como o app transforma as leituras da ECU nos valores de consumo, viagem e diagnóstico. A origem de cada leitura está em [parametros.md](parametros.md).

Internamente todos os valores ficam em unidades fixas (km/h, °C, g/s, L, km, s). A conversão para a unidade escolhida pelo usuário acontece só na exibição.

## Intervalo entre leituras

Cada leitura traz o instante em que foi recebida. O intervalo `Δt` usado nas integrações é o tempo real desde a leitura anterior, limitado a **2 s**. O limite evita que um buraco na comunicação (reconexão, por exemplo) seja contado como tempo de percurso.

O motor é considerado **em funcionamento** quando a rotação é maior que 300 rpm.

## Vazão de ar

É a base do cálculo de consumo.

```
ar [g/s] = MAF            se a rotação > 0
ar [g/s] = 0              se a rotação = 0
```

O motor J20A tem sensor MAF, então a vazão é lida diretamente.

O código mantém um cálculo de reserva para o caso de a ECU não fornecer o MAF mas fornecer a pressão do coletor (método *speed‑density*):

```
densidade [g/L] = 1,293 · (273 ÷ (T_ar + 273)) · (MAP ÷ 101,3)
ar [g/s]        = (rotação ÷ 120) · 2,0 · densidade
```

onde `rotação ÷ 120` é o número de ciclos de admissão por segundo de um motor de 4 tempos e `2,0` é a cilindrada em litros. Nesta ECU o MAP não é fornecido, então esse caminho não é usado.

## Vazão de combustível

```
combustível [L/h] = ar [g/s] ÷ AFR ÷ (densidade · 1000) · 3600 · coef_combustível
```

| Combustível | AFR (ar/combustível) | Densidade (kg/L) | CO₂ (kg/L) |
|---|---|---|---|
| Gasolina comum (E27) | 13,2 | 0,75 | 2,10 |
| Gasolina pura (E0) | 14,7 | 0,74 | 2,31 |

Casos especiais:

| Situação | Vazão de combustível |
|---|---|
| Motor parado | 0 |
| Corte de combustível informado pela ECU | 0 |
| Vazão de ar indisponível | Sem valor |

### Corte de combustível

Na desaceleração com marcha engatada a ECU interrompe a injeção, mas o ar continua passando pelo motor (4 a 8 g/s). Sem tratamento, o cálculo pela vazão de ar apontaria consumo onde não há nenhum.

O app usa o bit de corte informado pela ECU (`d65`, máscara `0x40`) e considera a vazão de combustível zero enquanto ele está ligado. Esse comportamento pode ser desativado nas configurações.

### Limites do método

- O cálculo supõe mistura estequiométrica. Em aceleração forte a ECU enriquece a mistura (estado de malha aberta) e o consumo real é maior que o calculado.
- A relação ar/combustível e a densidade são valores típicos, não medidos.
- Um sensor MAF sujo ou descalibrado desloca todo o resultado.

O coeficiente de combustível existe para absorver essas diferenças. A calibração com um abastecimento real está descrita no [manual do usuário](manual-do-usuario.html).

A ECU também informa o tempo de injeção, que permitiria calcular o consumo a partir da vazão dos injetores. Os dados sugerem cerca de 2,4 mg por milissegundo por injetor, mas esse valor é uma estimativa e o app não usa esse caminho.

## Distância

```
distância [km] += velocidade [km/h] · coef_distância ÷ 3600 · Δt
```

A velocidade é a informada pela ECU, com resolução de 1 km/h. O coeficiente de distância permite ajustar a diferença em relação ao hodômetro.

## Combustível acumulado

```
combustível [L] += vazão [L/h] ÷ 3600 · Δt
```

## Economia de combustível

| Valor | Fórmula | Regras |
|---|---|---|
| Instantânea | `velocidade ÷ vazão` (km/L) | Sem valor abaixo de 2 km/h. Com vazão menor que 0,05 L/h (corte), mostra o teto da escala (30 km/L). Limitada a 30 km/L |
| Média da viagem | `distância da viagem ÷ combustível da viagem` | Exibida depois de 50 m e 5 mL acumulados |
| Média total | `distância total ÷ combustível total` | Mesma regra |

A economia instantânea é suavizada: cada leitura contribui com 25 % e o valor anterior com 75 %. Sem isso o número oscilaria demais para ser lido.

A conversão para outras unidades é feita na exibição: `L/100 km = 100 ÷ (km/L)` e `mpg (EUA) = km/L · 2,352`.

## Tempos

| Acumulador | Conta quando |
|---|---|
| Tempo de viagem | O motor está em funcionamento |
| Tempo parado | O motor está em funcionamento e a velocidade é menor que 1 km/h |
| Combustível gasto parado | Nas mesmas condições do tempo parado |

## Custo e emissões

```
custo        = combustível [L] · preço por litro
CO₂ [kg]     = combustível da viagem [L] · fator do combustível
```

## Médias e máximos

| Valor | Cálculo |
|---|---|
| Velocidade média | `distância da viagem ÷ tempo de viagem`, depois de 5 s de viagem. Inclui o tempo parado com o motor ligado |
| Velocidade, rotação e temperatura máximas | Maior valor lido desde que a viagem foi zerada |

## Diagnóstico

| Valor | Fórmula | Observações |
|---|---|---|
| Aceleração longitudinal | `(Δvelocidade ÷ 3,6 ÷ Δt) ÷ 9,81` (g) | Suavizada: 30 % da leitura nova, 70 % do valor anterior. Como a velocidade tem resolução de 1 km/h, o valor é aproximado |
| Relação de marcha | `velocidade ÷ rotação · 1000` (km/h por 1000 rpm) | Calculada acima de 5 km/h e 500 rpm. O valor é constante em cada marcha |
| Ciclo de trabalho dos injetores | `tempo de injeção [ms] · rotação ÷ 1200` (%) | Supõe injeção sequencial: uma abertura por injetor a cada duas voltas, ou seja, a cada `120 000 ÷ rotação` milissegundos |
| Potência estimada | `ar [g/s] · 1,27` (cv) | Regra prática para motores aspirados a gasolina. Serve só como indicação |

## Viagem atual e totais

O app mantém dois conjuntos de acumuladores:

| Conjunto | Conteúdo | Zerado por |
|---|---|---|
| Viagem | Distância, combustível, tempo, tempo parado, combustível parado e máximos | Botão "Zerar viagem" |
| Totais | Distância e combustível | Botão "Zerar totais" |

Os dois são gravados a cada 10 s e ao sair do app, e restaurados na abertura seguinte.

## Viagens registradas

A viagem registrada na aba Mapa tem contabilidade própria:

- **Distância e combustível:** somam as variações dos acumuladores totais entre leituras. Assim valem os mesmos coeficientes e o mesmo tratamento do corte de combustível.
- **Tempo em movimento:** conta quando o motor está em funcionamento e a viagem não está em pausa.
- **Tempo em pausa:** conta enquanto a viagem está pausada.
- **Distância pelo GPS:** soma das distâncias entre posições consecutivas (fórmula de haversine).

Durante uma pausa nada é somado à distância, ao combustível ou ao tempo em movimento. Ao retomar, o deslocamento feito durante a pausa é desconsiderado.

Filtros aplicados às posições do GPS:

| Condição | Tratamento |
|---|---|
| Precisão pior que 50 m | Posição descartada |
| Deslocamento menor que 3 m | Posição descartada (evita ruído com o carro parado) |
| Salto maior que 2 km entre duas posições | Gravado no trajeto, mas não somado à distância |

## Alarmes

Cada alarme compara um valor com um limite, a cada leitura:

| Alarme | Condição | Limite inicial |
|---|---|---|
| Motor superaquecendo | Temperatura do líquido acima do limite | 105 °C |
| Bateria baixa | Tensão no conector abaixo do limite | 11,8 V |
| Sobretensão | Tensão no conector acima do limite | 15,0 V |
| Excesso de velocidade | Velocidade acima do limite | 110 km/h |
| Rotação alta | Rotação acima do limite | 6000 rpm |

Sem leitura do valor, o alarme não dispara. Os limites são guardados em °C, V, km/h e rpm, e convertidos para a unidade escolhida na tela de configurações. O aviso sonoro se repete a cada 4 s enquanto houver algum alarme ativo.

## Rotas

| Valor | Cálculo |
|---|---|
| Distância restante | Comprimento do traçado, do ponto mais próximo da posição atual até o destino |
| Tempo restante | Tempo total informado pelo serviço de rotas, proporcional à distância restante |
| Distância até a próxima manobra | Comprimento do traçado, da posição atual até o início da manobra seguinte |
| Fora da rota | Posição a mais de 120 m do traçado em 3 leituras seguidas do GPS |

Ao sair da rota, o app pede uma nova, com intervalo mínimo de 20 s entre pedidos.

## Bússola

A direção vem do sensor de orientação do aparelho, corrigida do norte magnético para o norte verdadeiro pela declinação do local. Em aparelhos sem esse sensor, a direção é o rumo do deslocamento informado pelo GPS, válido acima de 3 km/h.

## Histórico dos mini‑gráficos

Para cada dado o app guarda os últimos 60 pontos, um a cada 0,5 s (30 s no total). A escala vertical se ajusta ao mínimo e ao máximo do período, com amplitude mínima de 2 % da faixa do sensor para não ampliar ruído.
