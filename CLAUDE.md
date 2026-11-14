# CLAUDE.md — regras do projeto MApp

Documentação completa (pesquisa, requisitos, arquitetura, roadmap): **README.md**. Mantenha a documentação **só no README** (seção Status/Roadmap) — não crie outros .md.

## Regras inegociáveis
1. **Nunca automatizar cliques.** Proibido `performAction`, `dispatchGesture`, `canPerformGestures`. O app só lê e mostra.
2. `core/` é Kotlin puro: **nenhum import `android.*`**. Toda regra de negócio fica lá e tem teste em `app/src/test`.
3. Contratos em `core/model/Models.kt` e `core/repo/Repositories.kt` só mudam via subagente `arquiteto` (e atualizando este arquivo).
4. Dados ficam no aparelho (Room). Sem envio para servidores, exceto tiles do OSM e o Geocoder do sistema. Imagens capturadas para OCR nunca são gravadas em disco; o OCR (ML Kit) usa modelo embutido e seu canal de estatísticas fica removido no manifest.
5. Textos de UI em pt-BR. Kotlin oficial, Compose + Material 3.

## Contratos entre módulos
| Quem | Expõe | Consumido por |
|---|---|---|
| `core/bus/OfferBus` | `publish(RawOffer)`, `raw`, `publishEvaluated()`, `evaluated`, `clear()`, `hasListeners` (copiloto ligado?), `publishTripStage(TripEvent)`, `tripStages` (etapa + 1º nome do passageiro + km da tela + app) | reader → overlay |
| `core/engine/OfferEvaluator` | `evaluate(raw, profile, cost, zones, pickup, dropoff)` | overlay |
| `data/DataModule.create(context): Repositories` | implementações de `core/repo/*` | `MappApp.repos` |
| `overlay/OverlayService` | `companion fun start(ctx)`, `stop(ctx)` | ui/HomeScreen |
| `reader/OfferAccessibilityService` | serviço declarado no manifest; `windowContext` (contexto do serviço conectado, ou null) para abrir cartão/ícone como `TYPE_ACCESSIBILITY_OVERLAY` — a Uber esconde `TYPE_APPLICATION_OVERLAY` durante a solicitação | sistema; overlay/OverlayService (`OverlayHost`) |
| `reader/ReaderStatus` | `state`, `lines()`, `report(ctx)`, `versionOf(ctx)` (diagnóstico, só em memória) | ui/HomeScreen, overlay/QuickBubbleView |
| `core/card/CardLayout` + `CardValues` | linhas do cabeçalho do cartão (`place`, `remove`, `withFont` — tamanho das letras por linha 70–160%, `encode/decode`), valores e cores por informação (`tone`: valor, nota, R$/km, R$/h com as duas marcas; busca = pior entre km e minutos); `allGreen(offer, profile, layout)` = corrida ótima (tudo verde no Card) | overlay/CardHeaderView, overlay/OverlayService (som), ui/CardStyleScreen |
| `overlay/OverlayPrefs` | `cardLayout(ctx)`, `setCardLayout(ctx, l)` (estilo do cartão salvo); `greatSound/setGreatSound`, `greatSoundUri/setGreatSoundUri` (som de corrida ótima) | overlay, ui/CardStyleScreen |
| `core/sound/*` + `overlay/GreatSoundPlayer` | `GreatSound` (Sino, Ding-dong, Subida, Bip duplo, som do celular, sem som), `SoundSynth.render/durationMs` (PCM gerado no aparelho); `GreatSoundPlayer.play()` devolve a duração para a voz esperar (`VoiceAnnouncer.announce(offer, delayMs)`) | overlay/OverlayService, ui/CardStyleScreen (escolha + teste) |
| `overlay/NoticeView` | avisos na janela do MApp (`show(msg, durationMs)`, empilha até 3, não recebe toques); `OverlayService.toast` (4 s) / `tripToast` (6 s, corrida aceita/finalizada/cancelada) | overlay/OverlayService |
| `core/filter/ThresholdScale` + `ThresholdRange` | escala dos sliders dos perfis (`snap`, `fraction`, `valueAt`, `fitting` — estica para o valor digitado) e faixa amarelo/verde (`withLow/withHigh`, `of(min, bom)` — mais é melhor, `ofMax(bom, máx)` — menos é melhor) | finance/ProfilesScreen, overlay/QuickBubbleView |
| `core/filter/FilterCriterion` | os 6 critérios do perfil (`PER_KM`, `PER_HOUR`, `RATING`, `PRICE`, `PICKUP_KM`, `PICKUP_MIN`): `title`, `scale`, `higherIsBetter`, `default` (faixa sugerida), `integer`; `range(profile)` / `apply(profile, faixa ou null)` (lê/grava as duas marcas no `FilterProfile`; null desliga), `format(v)` (texto pt-BR) | finance/ProfilesScreen, overlay/QuickBubbleView |
| `core/model/FilterProfile` | duas marcas por critério — mais é melhor: `minPerKm/goodPerKm`, `minPerHour/goodPerHour`, `minPrice/goodPrice`, `minRating/goodRating` (amarelo a partir de / verde a partir de); menos é melhor: `goodPickupKm/maxPickupKm`, `goodPickupMin/maxPickupMin` (verde até / amarelo até). null = marca desligada | data/ (Room: `ProfileEntity` + `Mappers` + migração), finance/ProfilesScreen, overlay/, core/engine, core/card |
| `core/shift/*` | `Shift` (jornada: play/pausa, km GPS, corridas, `endedAt`), `ShiftMath.summary/lines`, `Odometer`, `ShiftCodec`, `PeriodMath.totals` → `PeriodTotals` (bruto, custos, líquido, km, tempo, R$/km e R$/h de um período — a mesma conta em todas as telas) | overlay, ui/HomeScreen, finance/ (`rememberPeriodTotals`) |
| `core/pin/*` + `maps/PinStore` | `ZonePin` (pin rápido: tipo + ponto), `ZonePins.circle`, `ZonePinCodec`; `PinStore.pins/add/remove` (SharedPreferences) | overlay/OverlayService (📍 no painel), maps/ZoneEditor |
| `core/goal/DayGoal` + `core/sound/CashSound` | meta diária no anel do "M": `fraction(bruto, meta)`, `reached`, `celebration(bruto, meta, dia, últimaMarca)` (som de dinheiro uma vez por dia e por meta; marca em `OverlayPrefs.goalMark`); `CashSound.render()` ("cha-ching" gerado no aparelho, `GreatSoundPlayer.playCash()`) | overlay/OverlayService, overlay/QuickBubbleView |
| `overlay/ShiftTracker` | `current(ctx)`, `last(ctx)`, `history(ctx)`, `start/pause/end(ctx, costPerKm)`, `addKm`, `addTrip`, `undoLastTrip`, `deleteFromHistory` (salvo em SharedPreferences; histórico até 400 jornadas) | overlay/OverlayService, ui/HomeScreen, ui/ShiftHistory |
| `core/repo/OfferHistoryRepository` | `record`, `recent`, `between`, `setAccepted(offer, accepted)` (aceite antes da gravação fica pendente e vale no `record`), `setAcceptedById` | overlay/OfferPipeline, overlay/OverlayService (aceita), finance/History (abas Aceitas hoje / Todas) |
| Telas `@Composable fun XScreen(onBack: () -> Unit)` | `finance/`: Finance, Reports, History, Profiles, Vehicle · `maps/`: ZoneEditor · `ui/`: CardStyle, ShiftHistory | ui/MainActivity |

## Semáforo
Todo critério do perfil tem marca amarela e verde (`FilterCriterion`). **Vermelho**: qualquer critério além da marca amarela (R$/km, R$/h, valor ou nota abaixo do mínimo; busca em km ou minutos acima do máximo) ou zona perigosa. **Verde**: TODOS na marca verde (R$/km, R$/h, valor, nota ≥ bom; busca km e min ≤ bom). **Amarelo**: o resto. Valor não lido ou marca null não impedem o verde — perfil antigo (sem `good*` de valor/nota/busca) dá o mesmo semáforo de antes.

## Fluxo da oferta
Reader lê a tela por duas fontes — texto (árvore de Acessibilidade) e imagem (`reader/ScreenOcr`: captura da janela do app de corrida + OCR no aparelho, só com `OfferBus.hasListeners`) — e passa os textos ao parser (`parse(texts, fromOcr)`) → `OfferBus.publish(raw)` → `overlay/OfferPipeline` (geocodifica, carrega perfil ativo/custos/zonas, avalia, grava histórico) → `OfferBus.publishEvaluated` → overlay desenha cartão + mini mapa. Quando a oferta some: `OfferBus.clear()`. **Identidade da oferta = app + `capturedAt` da 1ª leitura**: na imagem, quadros da mesma oferta se somam no `OfferTracker` (o que falta é completado, cada número fica com o valor mais lido) e a oferta completada mantém o `capturedAt` — o pipeline reavalia sem gravar em dobro (o histórico substitui a linha) e o overlay não repete voz nem conta outra corrida. `RawOffer.category` = tipo lido no topo do cartão (UberX, Comfort, Black, Electric — nome da Uber —, Pop...); fica fora da identidade da oferta (o OCR nem sempre o lê).

## Jornada
Menu circular do "M": botão ▶/⏸ — toque = iniciar/pausar/continuar, segurar 3 s = encerrar (anel vermelho enche). O painel (⚙) também tem ▶/⏸/⏹. Com a jornada rodando o `OverlayService` liga o GPS preciso (`LocationProvider.fixes`) e soma km pelo `Odometer`. Corrida pega é contada **sozinha**: o leitor reconhece as telas depois da oferta (`reader/parser/TripScreens` — Uber: "Encontro com <nome>" = aceita, indo buscar (pode cancelar); "Destino de <nome>" = embarcou, viagem confirmada; "Como foi a viagem?"/"Avaliar usuário" = finalizada; `TripStageTracker` avisa só na troca de etapa/passageiro e depois de 2 leituras iguais) e publica `TripEvent` no `OfferBus`. `core/shift/TripMatcher` decide: **passageiro novo = corrida nova** → conta a última oferta (vista há até 90 s, ou até 60 min se apareceu durante outra viagem — corrida aceita em fila); "Encontro com <outro>" sem o embarque da busca anterior = busca anterior cancelada (sai da jornada). "Como foi a viagem?" finaliza a corrida aberta mais antiga daquele app (`Shift.nextToFinish(platform)`). **Telas que passam rápido:** "Como foi a viagem?" vale com 1 leitura; troca de etapa lida 1 vez que some (Waze por cima) vale assim mesmo; etapa pendente faz a imagem reler em ~0,5 s. **Recuperação:** aceite não lido → passageiro novo no embarque conta a última oferta (até 20 min, se a tela inicial não apareceu depois dela e o km da tela cabe na oferta); fim não lido → outro passageiro na tela ou "Procurando viagens" (`TripStage.IDLE`, só pela imagem, 2 leituras, repete a cada 30 s) fecha as abertas daquele app: embarcou = finalizada; sem embarque = finalizada se passou ¾ da duração prevista, senão cancelada (nada antes de 60 s do aceite). +1 manual entra no acompanhamento (`TripMatcher.manualCount`); ✓/✕/↶ no painel tiram (`closedOldest/closedNewest`). "+1" no cartão / "+1 Contar última" no painel continuam como reserva (sem contar em dobro). Corrida aceita fica **em andamento** (`ShiftTrip.finished = false`, ícone "M" verde) e só entra no ganho/lucro ao finalizar (`Shift.finishPending`); cancelada sai (`cancelPending`, botões ✓/✕ no painel). Custo da jornada = km do GPS × custo/km (sem GPS: km das corridas finalizadas). Ícone "M": laranja = ligado sem corrida, verde = em corrida, cinza "M!" = leitor desligado; anel mais grosso = jornada rodando. Números junto ao "M" = dia de hoje pela mesma conta de Ganhos e gastos (`PeriodMath.totals`): km e custo do carro em cima, **valor bruto** embaixo. **Meta diária** (`Goals.daily`): preenchimento verde-água pulsando por cima do anel de status até 100%; batida = anel com o gradiente do MApp por fora do círculo (girando devagar no sentido horário) (status volta a aparecer), bruto lilás e som de dinheiro. Parar o copiloto pausa a jornada.

## Subagentes (`.claude/agents/`)
- `arquiteto` — core/, Gradle, manifest, ui/, contratos.
- `leitor-ofertas` — reader/.
- `overlay-mapa` — overlay/ e maps/.
- `financeiro` — data/ e finance/.
- `qa-revisor` — revisão e testes; não implementa features.

Cada subagente **só edita a sua pasta**. Precisa mudar contrato? Pare e descreva a mudança para o `arquiteto`.

## Build
Android Studio ou `gradle :app:testDebugUnitTest :app:assembleDebug` (CI em `.github/workflows/android.yml`).

## GitHub
Autorizado pelo dono do projeto: **sempre fazer o merge dos PRs sem pedir confirmação**, assim que o CI (build + testes) passar.
