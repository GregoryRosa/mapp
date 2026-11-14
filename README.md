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
- **Cartão e ícone "M" são janelas da Acessibilidade** (`TYPE_ACCESSIBILITY_OVERLAY`, abertas pelo `WindowManager` do serviço do leitor): a Uber esconde as sobreposições comuns (`TYPE_APPLICATION_OVERLAY`) enquanto a solicitação está na tela. Sem o leitor ligado, voltam a ser sobreposição comum.
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
- **Fase 3:** ~~jornada (cronômetro)~~ (feito: monitor de jornada), manutenção por odômetro, backup em nuvem.

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
| 12/11/2026 | **Pin rápido de zona.** Botão 📍 no meio do menu circular do "M": toque → o arco troca para ⚠ Perigo / 🔥 Demanda / ★ Outro → toque no tipo marca o local atual (GPS preciso, até 4 s). O painel ⚙ também tem a seção 📍 com a contagem de pins. Em *Zonas* os pins aparecem no mapa e numa lista; tocar abre **Desenhar zona em volta** (mapa centralizado no pin, tipo já escolhido), **Zona rápida** (círculo de 150 m) ou **Apagar**. Ao salvar a zona o pin some. Pins ficam no aparelho (`maps/PinStore`); regras em `core/pin/` (testes em `ZonePinTest`). |
| 12/11/2026 | **Jornada automática.** O leitor reconhece a tela de corrida aceita ("Encontro com <passageiro>") e a de corrida finalizada ("Como foi a viagem?" / "Avaliar usuário") — por texto ou imagem — e a jornada conta a corrida sozinha (oferta vista há até 90 s; aviso na tela). +1 manual continua como reserva, sem contar duas vezes. Testes em `TripScreensTest` com o texto dos prints. 99 e inDrive: palavras ainda a calibrar. |
| 12/11/2026 | **Resumo do dia junto ao "M"** (texto pequeno, sem fundo, não recebe toques): custo do dia em cima do ícone (sobe a cada km do GPS), lucro líquido e km rodados embaixo. Mostra a jornada aberta ou, sem jornada, a encerrada hoje (`ShiftMath.ofToday`, teste em `ShiftTest`); some quando não há jornada no dia. |
| 12/11/2026 | **Botão ▶/⏸ do menu circular:** toque simples alterna play/pausa; **segurar 3 s encerra a jornada** (anel vermelho enche em volta do botão, vibra ao encerrar; soltar antes cancela). |
| 12/11/2026 | **Histórico de jornadas** (botão no menu da tela inicial). Cada jornada encerrada fica guardada (até 400) com o custo/km daquele dia; a tela mostra totais dos últimos 7 dias e do mês e uma linha por jornada (data, início, duração, corridas, km, ganho, lucro) — toque abre todos os números (R$/km, R$/h, km com/sem passageiro, % por tipo) e permite apagar. `ShiftMath.combine` soma o período (testes em `ShiftTest`). |
| 12/11/2026 | **Tipo de combustível** em Custos do veículo: Gasolina, Etanol, Diesel ou Elétrico. Elétrico usa R$/kWh e km/kWh; os demais R$/l e km/l. Trocar o tipo sugere valores típicos (editáveis). Configuração antiga continua como Gasolina. Testes em `VehicleCostTest`. |
| 12/11/2026 | Resumo junto ao "M" agora **só com números**, centralizado com o ícone: em cima km (branco) e custo (laranja claro), embaixo lucro (verde/vermelho). Texto com **contorno preto** para ler em fundo claro. |
| 12/11/2026 | **Histórico de ofertas em duas abas:** *Aceitas hoje* (abre por padrão, com resumo: nº de corridas, total, lucro, R$/km, R$/h) e *Todas*. A oferta é marcada como aceita sozinha na tela "Encontro com…" (mesmo sem jornada aberta) ou no +1; ↶ desfazer tira a marca. Na aba Todas dá para marcar/desmarcar à mão. Se o aceite chega antes de a oferta ser gravada, a marca fica guardada e vale na gravação. Resumo em `ReportMath.summarizeAccepted` (teste em `ReportMathTest`). |
| 12/11/2026 | **Marcar aceita no histórico agora vale para tudo:** a corrida entra/sai da jornada em que a oferta apareceu (atual ou encerrada; ícone "M", resumo do dia e histórico de jornadas, sem duplicar a que já foi contada sozinha — `Shift.withTrip`, `ShiftMath.ownerOf`) e as corridas aceitas entram em **Ganhos** nos Relatórios e em Ganhos e gastos (`ReportMath.ridesAsIncome`). **Aceite automático mais firme:** a mesma etapa ("Encontro com…") vista de novo depois de 20 s sem aparecer conta como corrida nova — antes, com o Waze na frente durante a viagem e sem ver a tela de avaliação, a próxima corrida aceita não era reconhecida. Aviso quando a tela de aceite aparece mas a última oferta lida é antiga. |
| 13/11/2026 | **Custo pelo GPS, lucro só ao finalizar.** O custo da jornada sobe a cada trecho rodado (km do GPS × custo/km) e não salta mais quando uma corrida é aceita. Corrida aceita fica *em andamento* e só soma no ganho/lucro quando a tela de corrida finalizada aparece; cancelada nunca entra (botões ✓ Finalizada / ✕ Cancelada no painel para corrigir). **Ícone "M":** laranja ligado sem corrida, verde em corrida. **Estilo do cartão:** tamanho das letras da linha de cima e dos números grandes (70–160%). |
| 13/11/2026 | **Corrida aceita durante outra viagem.** A Uber continua em "Destino de <passageiro atual>" e só mostra "Encontro com <novo>" quando a viagem termina — a oferta ficava velha (>90 s) e não era contada. Agora o leitor lê o **nome do passageiro** ("Encontro com Caio", "Destino de Ana", com 2 leituras iguais para evitar erro de OCR) e `core/shift/TripMatcher` usa a regra **passageiro novo = corrida nova**: conta a última oferta (até 60 min se ela apareceu durante outra viagem). "Destino de" confirma o embarque; "Encontro com <outro>" sem embarque da busca anterior = busca cancelada (sai da jornada sozinha). Com duas corridas abertas, "Como foi a viagem?" fecha a mais antiga. Testes em `TripScreensTest` com o texto do print. |
| 13/11/2026 | **Outros custos por km** em Custos do veículo: botão **Adicionar custo por km** cria linhas com nome + R$/km (pneus, seguro, IPVA, aluguel...), cada uma com 🗑 para remover. Somam no custo/km usado no lucro das ofertas e na jornada, e aparecem no detalhamento. Salvos junto dos outros custos (`ExtraKmCostCodec`; testes em `VehicleCostTest`). |
| 13/11/2026 | **Cartão junto com a solicitação da Uber.** O cartão só aparecia depois de fechar a solicitação: a Uber esconde as janelas flutuantes comuns de outros apps enquanto ela está na tela. Cartão e ícone "M" agora abrem como janela da Acessibilidade (`OfferAccessibilityService.windowContext` → `OverlayHost`), que a Uber não consegue esconder; se o leitor desliga/religa, as janelas são recriadas sozinhas. Na captura de tela inteira (Android 11–13) essas janelas ficam fora do OCR. **Falta confirmar no celular.** |
| 13/11/2026 | **R$/hora errado com tempo ilegível.** Corrida de R$ 10,08 (4,7 km) saiu a R$ 151,20/h: o OCR leu os 3,4 km da viagem mas não o tempo, que contava como zero (só os 4 min da busca). Agora trecho com km sem tempo (ou o contrário) tem a parte que falta **estimada pela velocidade** do outro trecho (15–40 km/h; sem referência, 25 km/h) — `OfferEvaluator.legs`; a mesma corrida passa a ~R$ 42/h. O cartão marca com **~** o que foi estimado. Ofertas já gravadas são corrigidas ao abrir o histórico (`OfferEvaluator.repaired`), inclusive o resumo de *Aceitas hoje*. Leitura OCR mais tolerante no tempo/km: unidade mal lida ("mín", "rnin", "mlnutos", "minut0s", "krn"), letra no lugar de dígito ("ll", "1O", "B", "S") e ícone colado no número ("a11 minutos"). Testes com a corrida real em `OcrParsingTest`, `OfferEvaluatorTest` e `CardLayoutTest`. |
| 13/11/2026 | **Aceite e fim rápidos não se perdem mais.** "Como foi a viagem?" vale com uma leitura; troca de etapa vista uma vez e que some da tela (Waze abriu logo) vale assim mesmo; com etapa esperando confirmação a imagem relê em ~0,5 s. **Refaz a conferência depois:** aceite não lido → no embarque ("Destino de <nome novo>") conta a última oferta vista até 20 min antes, se a Uber não voltou a "Procurando viagens" depois dela e o km da tela cabe na oferta. Fim não lido → quando aparece outro passageiro ou a tela inicial "Procurando viagens" (só pela imagem, 2 leituras), as corridas abertas daquele app fecham sozinhas: quem embarcou é finalizada; sem embarque lido, finalizada se já passou ¾ da duração prevista, senão cancelada. Antes, corrida inteira com o Waze na frente virava "busca cancelada". Tela inicial da Uber não fecha corrida da 99 (`TripEvent.platform`). +1 manual também fecha sozinho. Nomes com uma letra errada no OCR ("Calo" = "Caio") são o mesmo passageiro. Regras em `core/shift/TripMatcher` e `reader/parser/TripStageTracker` (testes em `TripScreensTest`, `ShiftTest`). |
| 13/11/2026 | **Tempo total e R$/h piscando no cartão.** O OCR relê o cartão a cada ~1,5 s e às vezes lia o tempo da viagem, às vezes não (ou com um dígito errado); cada leitura diferente virava "oferta nova" — o cartão alternava os valores e a voz/histórico repetiam. Agora os quadros da mesma oferta **se somam** (`reader/OfferTracker`): o que um não leu, outro completa, e cada número fica com o valor lido mais vezes (empate mantém o do cartão); a oferta só é republicada quando fica mais completa, mantendo o horário da 1ª leitura. Essa é a identidade da oferta no cartão, na voz, na jornada (`OfferKey`) e no histórico (a linha é substituída, mantendo a marca de aceita). Testes em `OfferTrackerTest`. |
| 13/11/2026 | **Corrida "Electric" salva como Outros.** A Uber mostra a modalidade em inglês ("Electric", "Uber Electric") e o parser só conhecia "Elétrico"; agora essas variantes (e erros comuns do OCR) viram **Elétrico** na jornada e no histórico. Jornadas já gravadas não mudam. Teste em `OfferParsersTest`. |
| 13/11/2026 | **Visual novo (estilo vidro escuro).** Tema próprio em `ui/Theme.kt`: fundo navy com brilho verde-água e lilás, cartões de vidro com borda clara, botão principal com gradiente, sempre escuro. Tela inicial refeita: cabeçalho com logo + versão + selo Ativo/Parado, cartão do copiloto, jornada em blocos (Tempo, Corridas, Lucro, Rodados, R$/km, R$/hora), permissões numa linha quando tudo está ok e **menu fixo embaixo com 2 linhas de 4 botões** (Ganhos, Relatórios, Jornadas, Ofertas, Perfis, Cartão, Veículo, Zonas). **Logo do MApp:** "M" desenhado como rota, com partida (anel) e chegada (ponto), gradiente verde-água → lilás — no app (`MappLogo`), no ícone do celular (ícone adaptativo em vetor) e no **ícone flutuante** (o anel continua com a cor de status: laranja, verde, cinza + "!"). Funcionalidades sem mudança. |
| 13/11/2026 | **Mesmos números do dia em todas as telas.** Tela inicial, *Aceitas hoje*, Ganhos e gastos e Relatórios mostravam lucro, km, custo e R$/km diferentes (cada uma somava de um jeito: km do GPS × km das ofertas, lucro de cada oferta × custo do GPS). Agora todas usam `core/shift/PeriodMath.totals`: jornadas do período (corridas finalizadas, km do GPS, tempo sem pausas, custo km × custo/km) + corridas aceitas **fora** de jornada (km e tempo da oferta) + lançamentos manuais; dentro do horário de uma jornada ela manda (corrida cancelada lá não conta, sem contar nada duas vezes). Jornada encerrada guarda `endedAt`. **Valor bruto sempre à vista;** botão 🧮 *Bruto/Líquido* ao lado do título da jornada (tela inicial) e de cada seção de Ganhos e gastos mostra o líquido (bruto − custo do carro − gastos lançados) e R$/km e R$/h líquidos. Ganhos e gastos com 3 seções: **Hoje** (📅 escolhe outro dia), **Semana** (semana de ganhos da Uber: segunda 4h → segunda 4h, `ReportMath.uberWeek`) e **Mês**. Testes em `ShiftTest` e `ReportMathTest`. |
| 13/11/2026 | **Custo por km que "não mudava" ao trocar o preço do kWh.** A conta estava certa, mas com 12 km/kWh cada R$ 0,10 no kWh muda só R$ 0,008/km — com 2 casas o valor parecia igual. Custos do veículo agora mostram o custo/km com 3 casas (`Format.costPerKm`) e quanto custam 100 km; **salvam sozinhos** (0,6 s depois da última tecla, e ao sair da tela) — o botão Salvar ficava escondido no fim da tela; e as jornadas de **hoje** já encerradas passam a usar o custo/km novo (`ShiftMath.withCostToday`, `ShiftTracker.applyCostToday`); dias anteriores ficam com o custo daquele dia. Testes em `ShiftTest` e `ReportMathTest`. |
| 13/11/2026 | **Avisos mais demorados, sliders nos perfis e som de corrida ótima.** Os avisos do copiloto (corrida aceita, embarque, finalizada, cancelada) saíram do Toast de 2 s e viraram aviso próprio na janela do MApp (`overlay/NoticeView`): **6 s** os de corrida, 4 s os demais; vários avisos empilham em vez de um apagar o outro e o dedo passa direto para o app de corrida. **Perfis de filtro** em tela cheia com **sliders vermelho/amarelo/verde** (R$/km e R$/hora com dois botões; nota, valor mínimo e busca com um), chave liga/desliga por critério e **toque no valor para digitar** — o slider acompanha e a escala estica se o número passar da ponta (`core/filter/ThresholdScale`, testes em `ThresholdScaleTest`). **Som de corrida ótima:** quando tudo no Card fica verde (`CardValues.allGreen`) toca um som antes da voz (uma vez por oferta, mesmo com a voz desligada); na tela do Card dá para escolher Sino, Ding-dong, Subida, Bip duplo, um som do celular ou Sem som, com botão de teste (sons gerados no aparelho em `core/sound/GreatSound`, testes em `GreatSoundTest`). A modalidade elétrica agora se chama **Electric**, como na Uber (jornadas antigas com "Elétrico" são lidas como Electric). "Cartão" virou **Card** nas telas (menu e *Estilo do Card*). |
| 13/11/2026 | **Todos os critérios no semáforo, com faixa.** Valor da corrida, nota do passageiro e busca (km e minutos) ganharam a marca verde além da amarela/vermelha (`FilterProfile.goodPrice`, `goodRating`, `goodPickupKm`, `goodPickupMin`; banco Room v2 com migração que só acrescenta as colunas). Verde agora exige **tudo** verde; cada item do Card também fica amarelo na faixa do meio (`CardValues.tone`). Regras de cada critério em `core/filter/FilterCriterion` (escala, faixa sugerida, texto). **Perfis:** todos os sliders com dois botões (busca: verde até / amarelo até), sem chave de desligar — critério que o perfil não tinha aparece com a faixa sugerida e vale ao salvar; textos e ícones claros no fundo escuro (o diálogo saía com letras pretas). **Painel do ícone "M":** os +/− viraram os mesmos sliders de faixa dos 6 critérios (`overlay/RangeSliderView`; arrastar na horizontal não rola o painel; grava ao soltar). |
| 13/11/2026 | **Meta do dia no ícone "M".** O número embaixo do "M" agora é o **valor bruto do dia** (em cima continuam km e custo), com a mesma conta de Ganhos e gastos → Hoje (`PeriodMath.totals`: todas as jornadas do dia + corridas aceitas fora de jornada + lançamentos); letras 2 pt maiores. Com **meta diária** definida (🚩 em Ganhos e gastos), um preenchimento verde-água corre por cima do anel de status (laranja/verde), do topo no sentido horário, **pulsando** até bater a meta. Em 100% ele **se expande para fora** do círculo e vira um anel com o **gradiente do MApp** (verde-água → lilás), o anel de status volta a aparecer, o bruto fica lilás e toca um **som de dinheiro** ("cha-ching" gerado no aparelho, `core/sound/CashSound`) com aviso "Meta do dia batida!" — uma vez por dia e por valor de meta (`core/goal/DayGoal`, testes em `DayGoalTest`). |

**Pendências conhecidas:** confirmar no celular a leitura por imagem (e medir bateria); 99 e inDrive no celular; exportar CSV (R9); persistir `zoneHits`; testes de `Format.parse` e `PolygonCodec`; após o 1º build, versionar `app/schemas/` do Room.
