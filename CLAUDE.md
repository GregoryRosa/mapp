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
| `core/bus/OfferBus` | `publish(RawOffer)`, `raw`, `publishEvaluated()`, `evaluated`, `clear()`, `hasListeners` (copiloto ligado?), `publishTripStage()`, `tripStages` (corrida aceita/finalizada) | reader → overlay |
| `core/engine/OfferEvaluator` | `evaluate(raw, profile, cost, zones, pickup, dropoff)` | overlay |
| `data/DataModule.create(context): Repositories` | implementações de `core/repo/*` | `MappApp.repos` |
| `overlay/OverlayService` | `companion fun start(ctx)`, `stop(ctx)` | ui/HomeScreen |
| `reader/OfferAccessibilityService` | serviço declarado no manifest | sistema |
| `reader/ReaderStatus` | `state`, `lines()`, `report(ctx)`, `versionOf(ctx)` (diagnóstico, só em memória) | ui/HomeScreen, overlay/QuickBubbleView |
| `core/card/CardLayout` + `CardValues` | linhas do cabeçalho do cartão (`place`, `remove`, `encode/decode`), valores e cores por informação | overlay/CardHeaderView, ui/CardStyleScreen |
| `overlay/OverlayPrefs` | `cardLayout(ctx)`, `setCardLayout(ctx, l)` (estilo do cartão salvo) | overlay, ui/CardStyleScreen |
| `core/shift/*` | `Shift` (jornada: play/pausa, km GPS, corridas), `ShiftMath.summary/lines`, `Odometer`, `ShiftCodec` | overlay, ui/HomeScreen |
| `core/pin/*` + `maps/PinStore` | `ZonePin` (pin rápido: tipo + ponto), `ZonePins.circle`, `ZonePinCodec`; `PinStore.pins/add/remove` (SharedPreferences) | overlay/OverlayService (📍 no painel), maps/ZoneEditor |
| `overlay/ShiftTracker` | `current(ctx)`, `last(ctx)`, `start/pause/end`, `addKm`, `addTrip`, `undoLastTrip` (salvo em SharedPreferences) | overlay/OverlayService, ui/HomeScreen |
| Telas `@Composable fun XScreen(onBack: () -> Unit)` | `finance/`: Finance, Reports, History, Profiles, Vehicle · `maps/`: ZoneEditor · `ui/`: CardStyle | ui/MainActivity |

## Fluxo da oferta
Reader lê a tela por duas fontes — texto (árvore de Acessibilidade) e imagem (`reader/ScreenOcr`: captura da janela do app de corrida + OCR no aparelho, só com `OfferBus.hasListeners`) — e passa os textos ao parser (`parse(texts, fromOcr)`) → `OfferBus.publish(raw)` → `overlay/OfferPipeline` (geocodifica, carrega perfil ativo/custos/zonas, avalia, grava histórico) → `OfferBus.publishEvaluated` → overlay desenha cartão + mini mapa. Quando a oferta some: `OfferBus.clear()`. `RawOffer.category` = tipo lido no topo do cartão (UberX, Comfort, Black, Elétrico, Pop...); fica fora da identidade da oferta (o OCR nem sempre o lê).

## Jornada
Painel do ícone "M" → ▶ Iniciar/⏸ Pausar/⏹ Encerrar. Com a jornada rodando o `OverlayService` liga o GPS preciso (`LocationProvider.fixes`) e soma km pelo `Odometer`. Corrida pega é contada **sozinha**: o leitor reconhece as telas depois da oferta (`reader/parser/TripScreens` — Uber: "Encontro com <nome>" = aceita, "Como foi a viagem?"/"Avaliar usuário" = finalizada; `TripStageTracker` avisa só na troca de etapa) e publica `TripStage` no `OfferBus`; o `OverlayService` conta a última oferta vista há até 90 s. "+1" no cartão / "+1 Contar última" no painel continuam como reserva (sem contar em dobro). Parar o copiloto pausa a jornada.

## Subagentes (`.claude/agents/`)
- `arquiteto` — core/, Gradle, manifest, ui/, contratos.
- `leitor-ofertas` — reader/.
- `overlay-mapa` — overlay/ e maps/.
- `financeiro` — data/ e finance/.
- `qa-revisor` — revisão e testes; não implementa features.

Cada subagente **só edita a sua pasta**. Precisa mudar contrato? Pare e descreva a mudança para o `arquiteto`.

## Build
Android Studio ou `gradle :app:testDebugUnitTest :app:assembleDebug` (CI em `.github/workflows/android.yml`).
