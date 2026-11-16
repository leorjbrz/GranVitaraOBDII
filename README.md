# GranVitaraOBDII

O Vitara OBD transforma o celular em um painel de bordo para o Suzuki (Grand) Vitara 2.0. Ele lê as informações do motor por um adaptador Bluetooth ligado ao conector de diagnóstico do carro e mostra rotação, temperatura, consumo, mapa, rotas e o histórico das suas viagens.

O app **apenas lê** informações: não altera nada no carro e não apaga códigos de falha.

> **Não mexa no celular enquanto dirige.** Use um suporte fixo e configure tudo com o carro parado.

## Manual do usuário

O guia completo de uso está em [docs/manual-do-usuario.html](docs/manual-do-usuario.html). Baixe o arquivo e abra no navegador (o GitHub mostra apenas o código‑fonte de arquivos HTML).

## Documentação técnica

Para quem quer entender ou modificar o app:

| Documento | Conteúdo |
|---|---|
| [docs/protocolo.md](docs/protocolo.md) | Comunicação com a central do motor: Bluetooth, comandos do adaptador, inicialização, formato das mensagens, leitura de falhas e tempos |
| [docs/parametros.md](docs/parametros.md) | Mapa do bloco de dados da ECU: posição, fórmula e valores de referência de cada parâmetro, bits de estado e o que ainda não foi identificado |
| [docs/calculos.md](docs/calculos.md) | Fórmulas de consumo, distância, tempos, diagnóstico, alarmes e rotas |
| [docs/funcionalidades.md](docs/funcionalidades.md) | O que cada parte do app faz, arquivos gerados, permissões e organização do código |

## O que o app faz

- **Painel** com dois mostradores e blocos configuráveis, cada um com um mini‑gráfico dos últimos 30 segundos.
- **Dados do motor:** velocidade, rotação, temperatura do motor e do ar, vazão de ar, borboleta, carga, avanço de ignição, correções de combustível, sonda lambda, rotação‑alvo de marcha lenta, tempo de injeção e tensão da bateria.
- **Consumo:** instantâneo, por hora e médio, com custo, distância, tempos e emissão estimada de CO₂.
- **Alarmes** de superaquecimento, bateria baixa, sobretensão, velocidade e rotação.
- **Mapa** com posição, bússola, três tipos de mapa, giro livre ou "sempre para a frente" e dois dados do carro sobrepostos com transparência.
- **Rotas** pelas ruas, com a próxima manobra e a distância restante.
- **Registro de viagens** com pausas (abastecimento, refeição, descanso) e exportação de tudo em um arquivo ZIP.
- **Leitura dos códigos de falha** armazenados na central do motor.
- Funciona com o celular em pé ou deitado.

## Compatibilidade

| Item | Requisito |
|---|---|
| Carro | Suzuki (Grand) Vitara 2.0 a gasolina, motor J20A, com diagnóstico pelo protocolo Suzuki K‑Line (anterior ao OBD‑II genérico) |
| Adaptador | ELM327 Bluetooth clássico. Desenvolvido com um ELM327 v1.5 |
| Celular | Android 6.0 ou mais recente |
| Internet | Necessária para o mapa e o cálculo de rotas. Os dados do carro funcionam sem internet |

O app **não funciona em outros carros**: a forma de conversar com a central do motor é específica deste modelo. Adaptadores Wi‑Fi ou Bluetooth LE não são compatíveis, e alguns ELM327 de baixo custo não têm todas as funções necessárias.

## Instalação

Este repositório contém o código‑fonte. Para instalar no celular é preciso gerar o arquivo de instalação (APK).

### Gerar o APK

Requisitos: JDK 17 e Android SDK (plataforma 34). O jeito mais simples é abrir a pasta no [Android Studio](https://developer.android.com/studio), que instala o que faltar.

Pela linha de comando:

```
./gradlew assembleDebug
```

O arquivo é gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Se o Gradle não encontrar o Android SDK, crie um arquivo `local.properties` na raiz com o caminho do SDK:

```
sdk.dir=C:/Users/SEU_USUARIO/AppData/Local/Android/Sdk
```

### Instalar no celular

Copie o APK para o celular e toque nele (é preciso permitir a instalação de apps de fontes desconhecidas), ou, com o celular ligado por USB:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Primeiro uso

1. Encaixe o adaptador no conector de diagnóstico (sob o painel, lado do motorista) e ligue a ignição.
2. Pareie o adaptador nas configurações de Bluetooth do Android. A senha costuma ser `1234` ou `0000`.
3. Abra o app e aceite a permissão de dispositivos próximos.
4. Em **Config → Adaptador**, toque em **Escolher adaptador pareado**.
5. Volte ao **Painel**. Em cerca de 5 segundos os dados aparecem.

O restante está no [manual](docs/manual-do-usuario.html).

## Limitações

- O **consumo é uma estimativa**, calculada a partir do ar aspirado pelo motor. O manual explica como calibrá‑lo com um abastecimento.
- A **velocidade** é a informada pela central do motor e pode diferir um pouco do velocímetro.
- As **rotas** não têm voz nem informação de trânsito.
- A **exibição de códigos de falha** segue o formato padrão do protocolo, mas ainda não foi confirmada com uma falha real; as descrições dos códigos são genéricas.
- O app só conversa com a central do **motor**. Não acessa ABS, airbag nem câmbio.
- Para continuar lendo e gravando, o app precisa ficar **aberto na tela**.

## Privacidade

O app não tem contas, anúncios nem coleta de dados. Tudo o que ele grava (viagens, trajetos, registros) fica no próprio celular e só sai dele quando você exporta.

Duas funções usam a internet e, ao fazer isso, enviam coordenadas a serviços de terceiros:

- **Mapa:** os blocos de imagem da região exibida são baixados dos servidores do OpenStreetMap, do OpenTopoMap ou da Esri, conforme o tipo de mapa escolhido.
- **Rotas:** os pontos de partida e de chegada são enviados ao servidor público do projeto OSRM.

A busca do destino pelo nome usa o serviço de localização do próprio Android.

## Créditos

- Mapas de ruas: © colaboradores do [OpenStreetMap](https://www.openstreetmap.org/copyright).
- Mapa de terreno: [OpenTopoMap](https://opentopomap.org/) (CC‑BY‑SA), com dados do OpenStreetMap e SRTM.
- Imagens de satélite: Esri World Imagery.
- Cálculo de rotas: [Project OSRM](https://project-osrm.org/), com dados do OpenStreetMap.
- Biblioteca de mapas: [osmdroid](https://github.com/osmdroid/osmdroid) (Apache 2.0).

Os servidores de mapas e de rotas são serviços públicos gratuitos, sem garantia de disponibilidade e com políticas de uso próprias. Eles servem ao uso pessoal e moderado.

## Aviso

Este projeto é independente e não tem vínculo com a Suzuki. É distribuído como está, sem garantias. As informações mostradas servem de apoio e não substituem o painel do carro nem a avaliação de um mecânico.

## Licença

[MIT](LICENSE)
