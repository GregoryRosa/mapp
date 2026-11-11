# CLAUDE.md — regras do projeto MApp

Documentação completa (pesquisa, requisitos, arquitetura, roadmap): **README.md**. Mantenha a documentação **só no README** (seção Status/Roadmap) — não crie outros .md.

## Regras inegociáveis
1. **Nunca automatizar cliques.** Proibido `performAction`, `dispatchGesture`, `canPerformGestures`. O app só lê e mostra.
2. `core/` é Kotlin puro: **nenhum import `android.*`**. Toda regra de negócio fica lá e tem teste em `app/src/test`.
3. Contratos em `core/model/Models.kt` e `core/repo/Repositories.kt` só mudam via subagente `arquiteto` (e atualizando este arquivo).
4. Dados ficam no aparelho (Room). Sem envio para servidores, exceto tiles do OSM e o Geocoder do sistema.
5. Textos de UI em pt-BR. Kotlin oficial, Compose + Material 3.

## Contratos entre módulos
| Quem | Expõe | Consumido por |
|---|---|---|
| `core/bus/OfferBus` | `publish(RawOffer)`, `raw`, `publishEvaluated()`, `evaluated`, `clear()` | reader → overlay |
| `core/engine/OfferEvaluator` | `evaluate(raw, profile, cost, zones, pickup, dropoff)` | overlay |
| `data/DataModule.create(context): Repositories` | implementações de `core/repo/*` | `MappApp.repos` |
| `overlay/OverlayService` | `companion fun start(ctx)`, `stop(ctx)` | ui/HomeScreen |
| `reader/OfferAccessibilityService` | serviço declarado no manifest | sistema |
| Telas `@Composable fun XScreen(onBack: () -> Unit)` | `finance/`: Finance, Reports, History, Profiles, Vehicle · `maps/`: ZoneEditor | ui/MainActivity |

## Fluxo da oferta
Reader lê tela → `OfferBus.publish(raw)` → `overlay/OfferPipeline` (geocodifica, carrega perfil ativo/custos/zonas, avalia, grava histórico) → `OfferBus.publishEvaluated` → overlay desenha cartão + mini mapa. Quando a oferta some: `OfferBus.clear()`.

## Subagentes (`.claude/agents/`)
- `arquiteto` — core/, Gradle, manifest, ui/, contratos.
- `leitor-ofertas` — reader/.
- `overlay-mapa` — overlay/ e maps/.
- `financeiro` — data/ e finance/.
- `qa-revisor` — revisão e testes; não implementa features.

Cada subagente **só edita a sua pasta**. Precisa mudar contrato? Pare e descreva a mudança para o `arquiteto`.

## Build
Android Studio ou `gradle :app:testDebugUnitTest :app:assembleDebug` (CI em `.github/workflows/android.yml`).
