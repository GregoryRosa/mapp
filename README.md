# MApp — Copiloto pessoal para motoristas de app

App Android de uso pessoal, inspirado em **Rota Pro**, **GigU** (ex-StopClub) e **Rydex Pro**. Ele lê as ofertas de corrida da **Uber, 99 e inDrive**, mostra na hora se a corrida vale a pena (semáforo + mini mapa), avisa se a coleta/destino cai numa zona marcada por você e controla ganhos, gastos e relatórios.

> **Princípio de projeto:** o MApp **só lê e mostra informações**. Ele **nunca aceita nem recusa corridas sozinho** — isso protege a conta do motorista (Uber trata automação como fraude) e respeita a política do Google Play sobre Acessibilidade.

---

## 1. Pesquisa de mercado (nov/2026)

| | Rota Pro | GigU (ex-StopClub) | Rydex Pro |
|---|---|---|---|
| Pacote | `app.rotapro` | `co.gigu.app` | `br.com.rydex.app` |
| Empresa | FRM TECH (Betim-MG) | GigU (RJ), ~200 mil pagantes | Dev. individual |
| Plataformas | Android 9+ e iOS 17+ | Android (iOS em espera) | Android |
| Apps suportados | Uber, 99, inDrive (iFood/Keeta beta) | Uber, 99, inDrive (+Lyft/DoorDash EUA) | Uber, 99, inDrive |
| Preço | R$ 97/ano, 7 dias grátis | R$ 12,90/mês, 30 dias grátis | Não divulgado |
| Nota Play | 4,7 (50 mil+ downloads) | 3,9 (~880 mil instalações) | 4,9 (1 mil+) |

**Funcionalidades em comum:** semáforo verde/amarelo/vermelho sobre a oferta; R$/km, R$/hora, R$/min; lucro líquido descontando custo do carro; filtros por perfil; histórico de ofertas; controle financeiro e metas.

**Diferenciais observados**
- *Rota Pro:* até 10 perfis de filtro, custo/km completo (combustível, manutenção, depreciação), cronômetro de jornada (aguardando/buscando/em viagem), alerta de endereço de risco por palavra-chave, aviso por voz, exportação CSV, ranking de melhores horários.
- *GigU:* atalho para ver coleta/destino no Google Maps/Street View, aviso por voz, prints das ofertas. Teve “recusa automática” — motivo do processo da Uber.
- *Rydex Pro:* foco financeiro, rastreio de km por GPS, metas diária/mensal.

**Como funcionam (técnica):** `AccessibilityService` lê o texto da tela do app de corrida → parser extrai valor, distância, tempo e endereços → janela flutuante (`SYSTEM_ALERT_WINDOW`) desenha o cartão. Exige celular em pt-BR e fonte padrão; fabricantes (Xiaomi, Samsung) matam o serviço em segundo plano — por isso usam serviço em primeiro plano e pedem isenção de bateria.

**Descoberta no celular (12/11/2026):** a Uber **não entrega o cartão da corrida como texto** para a Acessibilidade — o leitor recebia só a tela inicial ("Página inicial", "R$ 0,00", "Você está online"). A ajuda do GigU pede fonte pequena e nenhum ícone flutuante "na frente", o que indica que esses apps leem a **imagem** da tela. O MApp faz o mesmo: captura a janela do app de corrida pelo serviço de Acessibilidade e reconhece o texto no aparelho (ver seção 3).

**Reclamações comuns:** acessibilidade desligando sozinha, leitura errada do valor, app morto pela economia de bateria.

**Riscos:** Uber processou a StopClub (liminar derrubada pelo TJ-SP; CADE reabriu investigação contra a Uber em jul/2026). Uber lista “apps não autorizados” como motivo de desativação. Google Play proíbe ações *autônomas* via Acessibilidade e exige aviso destacado + declaração no Console. Como o MApp é para uso pessoal (instalação direta/APK), o risco de loja é baixo, mas a regra de **não automatizar cliques** permanece.

Fontes: rotapro.app, play.google.com (app.rotapro, br.com.rydex.app), gigu.app/help, appgoblin.info/apps/co.gigu.app, therideshareguy.com, migalhas.com.br, timesbrasil.com.br, uber.com/br/pt-br/drive/safety/deactivations, support.google.com/googleplay/android-developer/answer/10964491.

---

## 2. Requisitos (minhas necessidades)

**Plataforma:** Android (minSdk 26). **Apps lidos:** Uber, 99, inDrive.

| # | Requisito | Prioridade |
|---|---|---|
| R1 | Ler ofertas e calcular R$/km, R$/hora, lucro líquido | MVP |
| R2 | Semáforo com filtros configuráveis e múltiplos perfis | MVP |
| R3 | **Mini mapa no cartão da oferta:** minha posição, coleta e destino | MVP |
| R4 | **Editor de zonas:** desenhar polígonos no mapa da cidade (zona perigosa, alta demanda, etc.) | MVP |
| R5 | Alerta quando coleta/destino cai dentro de uma zona (cor + voz) | MVP |
| R6 | Controle financeiro: ganhos, gastos, combustível, lucro, metas | MVP |
| R7 | Histórico de ofertas e corridas | v1 |
| R8 | Relatórios: melhores horários/dias, R$/km médio, mapa das melhores regiões | v1 |
| R9 | Backup/exportação CSV | v1 |

---

## 3. Arquitetura

**Stack:** Kotlin · Jetpack Compose (Material 3) · Room · Coroutines/Flow · osmdroid (mapa OpenStreetMap, sem chave de API) · `android.location.Geocoder` (endereço → coordenada, nativo e gratuito) · Fused Location · ML Kit Text Recognition com modelo embutido no APK (OCR no aparelho).

```
app/src/main/java/br/com/mapp/
├── core/        # Domínio puro (sem Android): modelos, motor de avaliação, geometria
├── reader/      # AccessibilityService: texto da tela + OCR da imagem (ScreenOcr) + parsers por plataforma
├── overlay/     # Serviço de janela flutuante: cartão semáforo + mini mapa
├── maps/        # Editor de zonas, geocodificação, utilidades osmdroid
├── data/        # Room: entidades, DAOs, repositórios
├── finance/     # Telas de ganhos/gastos/metas e relatórios
└── ui/          # MainActivity, navegação, tema, configurações, permissões
```

**Fluxo de uma oferta**
```
App Uber/99/inDrive  →  OfferAccessibilityService
        ├─ texto: árvore de Acessibilidade (a cada evento do app)
        └─ imagem: captura da janela do app + OCR no aparelho (a cada ~1,2 s, só com o copiloto ligado)
   →  OfferParser (por plataforma)  →  RawOffer  →  Geocoder (coleta/destino)  →  OfferEvaluator (perfil ativo + zonas)
   →  OfferBus (SharedFlow)  →  OverlayService (cartão + mini mapa)  →  Room (histórico)
```

**Decisões**
- `core/` não importa nada do Android → testável em JVM puro.
- Cada plataforma tem seu parser isolado; mudança de layout da Uber não quebra a 99.
- Comunicação reader → overlay por um `SharedFlow` em processo (`OfferBus`), sem broadcasts.
- Mapas com osmdroid para não depender de chave/fatura do Google Maps.
- Toda automação de clique é proibida no código (o serviço não declara `canPerformGestures`).
- **Leitura por imagem** (`reader/ScreenOcr`): `takeScreenshotOfWindow` (Android 14+, só a janela do app de corrida — janelas por cima não atrapalham) ou `takeScreenshot` recortado na área do app (Android 11–13). Enquanto nenhum modo provou que enxerga a oferta, o leitor usa os dois a cada ciclo; depois fica com o que funcionou (regras em `reader/OfferTracker`, Kotlin puro com testes: quem publicou a oferta decide que ela sumiu; na imagem os trechos tempo+km identificam a oferta, para um quadro mal lido não repetir voz/cartão). A imagem vive só na memória durante o reconhecimento: **nunca é salva nem enviada**. O canal de estatísticas do ML Kit (`TransportBackendDiscovery`) é removido no manifest.
- OCR erra de jeitos diferentes do texto: endereço quebrado em 2 linhas, "•" que some ou vira outro sinal, ícone lido como letra. O parser tem um modo `fromOcr` para isso, mais rígido (só é oferta com botão Aceitar ou com busca + viagem completos). Testes em `OcrParsingTest` usam a saída real de dois motores de OCR sobre os prints do cartão.
- A versão do app (`0.2.<nº do build>`) aparece no topo da tela inicial, no painel do ícone "M" e no título da release.

---

## 4. Desenvolvimento com subagentes

O projeto é desenvolvido com **subagentes do Claude Code**, cada um com contexto próprio (definidos em `.claude/agents/`):

| Subagente | Responsável por |
|---|---|
| `arquiteto` | Contratos entre módulos, `core/`, Gradle, decisões |
| `leitor-ofertas` | `reader/` — Acessibilidade e parsers |
| `overlay-mapa` | `overlay/` e `maps/` — cartão, mini mapa, editor de zonas |
| `financeiro` | `data/` e `finance/` — Room, ganhos/gastos, relatórios |
| `qa-revisor` | Revisão, testes e consistência entre módulos |

As regras compartilhadas ficam em `CLAUDE.md`.

---

## 5. Como rodar

1. Abra a pasta no **Android Studio** (Ladybug ou mais novo) e deixe o Gradle sincronizar.
2. Rode no celular (cabo USB ou Wi-Fi debug).
3. No app: conceda **Sobrepor outros apps**, **Localização** e ative o **serviço de Acessibilidade “MApp”**. Desative a otimização de bateria para o MApp. A leitura por imagem precisa de Android 11+ (ideal: 14+).
4. Configure custos do carro e um perfil de filtro; desenhe suas zonas em *Configurações → Zonas*.

O GitHub Actions (`.github/workflows/android.yml`) compila e roda os testes a cada push e publica o APK em **Releases → ultima-versao** (https://github.com/GregoryRosa/mapp/releases/tag/ultima-versao).

---

## 6. Roadmap

- **Fase 1 – MVP:** R1–R6, testes de parser com textos reais de ofertas.
- **Fase 2:** histórico, relatórios, mapa de calor das melhores regiões, CSV.
- **Fase 3:** ~~jornada (cronômetro)~~ (feito: monitor de jornada), detectar corrida aceita sozinho (precisa de prints da Uber após aceitar), manutenção por odômetro, backup em nuvem.

**Calibração:** quando uma corrida não for reconhecida, mande print da tela; cada texto real vira um caso de teste antes de mexer no parser. (O botão "Copiar diagnóstico" foi retirado; o relatório `ReaderStatus.report` continua no código.) 99 e inDrive ainda não foram testados no celular.

---

## 7. Status

| Data | Marco |
|---|---|
| 11/11/2026 | Pesquisa, requisitos, arquitetura e esqueleto do projeto criados |
| 11/11/2026 | MVP implementado por subagentes: leitor (Uber/99/inDrive), overlay com semáforo + mini mapa + voz, editor de zonas, Room, finanças, relatórios, perfis, custos. 43 testes JVM passando. **Pendente:** calibrar parsers com ofertas reais (`OfferAccessibilityService.debugCapture = true`). |
| 12/11/2026 | Projeto no GitHub (GregoryRosa/mapp); 1º build no CI passou; APK publicado na release `ultima-versao` a cada push. |
| 12/11/2026 | 1º teste na rua. Corrigido: alerta falso na tela inicial da Uber (`R$ 0,00` + `1-3 min`); oferta real ignorada porque os textos da tela de fundo se misturavam ao cartão (Uber agora lê só o bloco preço → botão Aceitar; valor zero nunca é preço; exige par tempo+km ou endereço). Testes com textos reais da Uber. Tela inicial mostra se o copiloto está ativo e explica o que falta. Novo **ícone flutuante "M"** com ajustes rápidos (perfil, R$/km, R$/h, busca máx., valor mín., voz, parar). |
| 12/11/2026 | APK assinado com chave fixa (`app/mapp-debug.keystore`): atualizações instalam por cima. Quem tinha versão anterior desinstala uma única vez. |
| 12/11/2026 | Oferta UberX não lida no celular. Parser agora junta "R$" + valor e ignora separadores soltos ("•") quando a Uber entrega o texto em pedaços. Novo **diagnóstico do leitor** (`reader/ReaderStatus`, só em memória): tela inicial e ícone "M" mostram se a Acessibilidade está conectada, última leitura, se a tela com R$ virou oferta e botão **Copiar última leitura** para calibrar. Ícone fica cinza "M!" com o leitor desligado. |

| 12/11/2026 | Diagnóstico mostrou que a Uber não expõe o cartão da corrida como texto. Nova **leitura por imagem** (captura via Acessibilidade + OCR ML Kit no aparelho), parser com modo OCR e testes com saída real de OCR dos prints. Diagnóstico refeito (guarda a tela da corrida; botão **Copiar diagnóstico**). **Versão do app visível** na tela inicial (`0.2.<build>`), com aviso quando uma atualização é instalada. Ainda **sem teste no aparelho**. |
| 12/11/2026 | **Lentidão ao chegar corrida corrigida.** Semáforo sai na hora (perfil/custos/zonas ficam em memória; GPS e geocodificação viraram 2ª etapa que só atualiza mapa/zonas e avisa zona por voz depois). Leitura da árvore e interpretação do OCR saíram da main thread (a mesma do cartão) e a árvore é lida no máx. a cada 350 ms; com a oferta no cartão o OCR confere a cada 1,5 s e a animação da contagem não dispara capturas. Mini mapa não é redesenhado a cada GPS/republicação. Voz não repete quando o endereço é lido depois. |
| 12/11/2026 | 1ª oferta reconhecida no celular (v0.2.10, ML Kit, 167 ms/quadro). Km da busca saía vazio: o OCR leu "1.6 km" como "l6 km". Parser OCR agora troca l/I por 1 em números, recoloca o ponto quando a velocidade fica impossível (>120 km/h) e, se o km da busca continuar ilegível, calcula pela taxa da Uber ("Est. de R$1,96/km": preço ÷ taxa − viagem). Com isso R$/km, R$/h e lucro usam busca + viagem. Teste com o texto real do diagnóstico. |
| 12/11/2026 | **Cartão novo (estilo Rota PRO):** tudo no cabeçalho colorido — linha de cima (valor · tempo · nota) e linha de baixo com números grandes (R$/km, R$/hora, lucro) e barrinha de cor pelo perfil ativo. Saíram as linhas de endereço A/B e a linha "Total/Busca" (já aparecem na Uber). Nova tela **Estilo do cartão** (menu inicial): 3 espaços em cima, 4 embaixo, 11 informações; tocar ou segurar e arrastar para colocar/trocar, tocar para tirar, prévia ao vivo, restaurar padrão. Regras em `core/card/CardLayout.kt` (testes em `CardLayoutTest`). Retirado o botão **Copiar diagnóstico**. |
| 12/11/2026 | **Monitor de jornada.** Botão ▶ no painel do ícone "M" (anel âmbar no ícone com a jornada rodando; ⏸ pausa, ⏹ encerra com confirmação). Mostra ao vivo: tempo, nº de corridas, ganho, custo (km × custo/km do veículo), lucro, km total pelo GPS (com e sem passageiro), R$/km, R$/hora, km médio por corrida e % por tipo (UberX, Comfort, Black, Elétrico, Pop...). Corrida conta com **+1** no cartão da oferta ou **+1 Contar última** no painel (↶ desfaz). Parser lê o tipo da corrida (`RawOffer.category`). Jornada salva no aparelho; tela inicial mostra a atual ou a última. Regras em `core/shift/` (testes em `ShiftTest`). |
| 12/11/2026 | **Ícone "M" com menu circular.** Toque no "M" abre 6 botões redondos em meia-lua (estilo neon, fundo preto, ícone desenhado no código): ⚙ ajustes rápidos (painel completo), ▶/⏸ jornada, +1 contar corrida, 🔊 voz, 🏠 abrir MApp, ⏻ parar copiloto; ✕ no centro fecha. O arco abre virado para o meio da tela e o "M" desliza se estiver colado no topo/rodapé. |

**Pendências conhecidas:** confirmar no celular a leitura por imagem (e medir bateria); 99 e inDrive no celular; exportar CSV (R9); persistir `zoneHits` e marcar oferta aceita no histórico; testes de `Format.parse` e `PolygonCodec`; após o 1º build, versionar `app/schemas/` do Room.
