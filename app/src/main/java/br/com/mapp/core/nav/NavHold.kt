package br.com.mapp.core.nav

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripStage
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Link de navegação que um app pediu: do Waze (`waze://?ll=lat,lon&navigate=yes`) ou do Google Maps
 * (`google.navigation:q=lat,lon`, `geo:lat,lon?q=…`). [origin] = app a que o link se destinava;
 * [rawQuery] = parte depois do "?" (no Waze é repassada igual); [link] = o link inteiro, como chegou;
 * [test] = link do botão Testar (`mapp_test=1`).
 */
data class NavRequest(
    val lat: Double? = null,
    val lon: Double? = null,
    val query: String? = null,
    val navigate: Boolean = false,
    val test: Boolean = false,
    val rawQuery: String = "",
    val origin: NavApp = NavApp.WAZE,
    val link: String = "",
)

/**
 * App que recebe a rota no fim da espera (tela inicial → cartão 🧭 → *Abrir a rota no*). A Uber pode
 * estar com o Waze ou o Google Maps como app de navegação: o MApp recebe os dois links e, se o app
 * escolhido for o outro, converte o destino ([NavHold.target]).
 */
enum class NavApp(val id: String, val label: String, val packageName: String) {
    WAZE("waze", "Waze", "com.waze"),
    GOOGLE_MAPS("gmaps", "Google Maps", "com.google.android.apps.maps");

    companion object {
        /** Valor salvo → app; desconhecido ou nulo = [WAZE] (o de sempre). */
        fun fromId(id: String?): NavApp = entries.firstOrNull { it.id == id } ?: WAZE
    }
}

/**
 * Quanto segurar o Waze e por quê (para o Registro). 0 = repassa na hora. [bring] = app de corrida
 * que não está na tela (o motorista estava no Waze): o MApp abre esse app para ler a corrida e só
 * depois manda a rota ao Waze.
 */
data class HoldPlan(
    val holdMs: Long,
    val reason: String,
    val bring: Platform? = null,
    /**
     * App a trazer se ele sumir da tela no começo da espera (a janela de aceite da Uber fechou logo
     * depois de pedir o Waze, com o Waze por baixo) — ver [lateBring]. null = não trazer.
     */
    val fallback: Platform? = null,
    /**
     * Pedido que não veio de um app de corrida (outro app ou o próprio MApp): vai para o app a que o
     * link se destinava, não para o escolhido no cartão 🧭 — o MApp só mexe na navegação da corrida.
     */
    val passThrough: Boolean = false,
)

/** Por que o Waze foi liberado. */
enum class ReleaseReason(val label: String) {
    /** O leitor reconheceu a tela da corrida (aceite ou embarque): não há mais o que esperar. */
    STAGE("tela da corrida lida"),
    /** O leitor já leu a tela algumas vezes sem novidade (ex.: motorista tocou em Navegar na Uber). */
    FRAMES("tela lida sem novidade"),
    TIMEOUT("tempo esgotado"),
    /** Motorista voltou, apagou a tela ou saiu: abre a navegação na hora. */
    LEFT("motorista saiu da tela"),
}

/** Situação do atalho dos links de um app de navegação no Android (cartão da tela inicial). */
enum class NavLinkState {
    /** Desligado no MApp. */
    OFF,
    /** O app de navegação não está instalado: nada para segurar. */
    NOT_INSTALLED,
    /** Ligado, mas o Android ainda pergunta qual app abre os links (ou nunca perguntou). */
    ASK,
    /** O próprio app de navegação está marcado como padrão para os links dele: o MApp não recebe nada. */
    APP_DEFAULT,
    /** MApp é o padrão: a Uber abre a navegação passando pelo MApp. */
    READY,
}

/**
 * Atraso da navegação: quando a Uber abre o Waze ou o Google Maps (aceite, embarque ou fim de corrida
 * com outra na fila), a tela da Uber some em menos de 1 s e o leitor não consegue ler "Encontro com
 * <novo>". Com o MApp escolhido como app dos links `waze://` e `google.navigation:`/`geo:`, uma tela
 * invisível recebe o link, segura a navegação alguns segundos com a Uber ainda à vista (o leitor captura
 * a tela em rajada) e então abre o app escolhido no mesmo destino.
 *
 * Só funciona se a Uber abrir a navegação por link sem fixar o pacote do app — o Registro diz se o
 * pedido passou pelo MApp. O MApp não toca em nada: só atrasa a abertura que a Uber pediu.
 */
object NavHold {
    const val WAZE_PACKAGE = "com.waze"
    const val SCHEME = "waze"
    const val MAPS_SCHEME = "google.navigation"
    const val GEO_SCHEME = "geo"
    const val TEST_PARAM = "mapp_test"

    /** Link do botão Testar do Waze: faz o Android perguntar quem abre links do Waze (MApp → Sempre). */
    const val TEST_URI = "waze://?$TEST_PARAM=1"

    /** Link do botão Testar do Google Maps (sem destino: no fim só abre o app). */
    const val MAPS_TEST_URI = "$MAPS_SCHEME:$TEST_PARAM=1"

    /** Link usado só para perguntar ao Android qual app é o padrão dos links do Waze. */
    const val PROBE_URI = "waze://?ll=0,0"

    /** Idem, para os links de navegação do Google Maps. */
    const val MAPS_PROBE_URI = "$MAPS_SCHEME:q=0,0"

    /** Link do botão Testar de cada app. */
    fun testUri(app: NavApp): String = if (app == NavApp.GOOGLE_MAPS) MAPS_TEST_URI else TEST_URI

    /** Link que pergunta ao Android quem abre os links de cada app. */
    fun probeUri(app: NavApp): String = if (app == NavApp.GOOGLE_MAPS) MAPS_PROBE_URI else PROBE_URI

    /** Tempos que o motorista pode escolher (máximo de espera; normalmente libera antes). */
    val DELAYS_MS: List<Long> = listOf(1_000L, 1_500L, 2_000L, 3_000L, 4_000L, 5_000L)
    const val DEFAULT_DELAY_MS = 2_000L

    /**
     * Leituras da tela da Uber durante a espera que bastam: a etapa precisa de 2 leituras iguais para
     * ser confirmada; com 3 sem novidade não há tela nova para pegar.
     */
    const val FRAMES_ENOUGH = 3

    /** Trazendo o app de corrida para a frente: tempo a mais para ele aparecer na tela. */
    const val BRING_EXTRA_MS = 1_500L

    /**
     * Depois de trazer o app de corrida, outro pedido nesse intervalo não traz de novo (se o app
     * reenviar a rota ao voltar para a frente, não vira vaivém).
     */
    const val BRING_COOLDOWN_MS = 15_000L

    /** Confere se o app de corrida continua na tela este tanto depois de começar a segurar. */
    const val LATE_CHECK_MS = 700L

    /** Tempo de espera válido (valor salvo antigo ou estranho vira o padrão). */
    fun validDelay(ms: Long): Long = if (ms in DELAYS_MS) ms else DEFAULT_DELAY_MS

    /** "1 s", "1,5 s", "2 s". */
    fun label(ms: Long): String {
        val s = ms / 1000.0
        return if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(PT_BR, "%.1f s", s)
    }

    /**
     * Lê um link de navegação. Waze: `waze://?…`, `waze://ul?…` ou `https://(www.)waze.com/ul?…`.
     * Google Maps: `google.navigation:q=…` (sempre navegar) e `geo:lat,lon`, `geo:0,0?q=lat,lon(nome)` ou
     * `geo:…?q=endereço` (mostra o ponto). null = não é link de navegação. Coordenada fora da faixa é
     * ignorada; o resto do link continua valendo.
     */
    fun parse(uri: String?): NavRequest? {
        val text = uri?.trim() ?: return null
        val lower = text.lowercase(Locale.ROOT)
        return when {
            isWaze(lower) -> parseWaze(text)
            lower.startsWith("$MAPS_SCHEME:") -> parseMapsNavigation(text)
            lower.startsWith("$GEO_SCHEME:") -> parseGeo(text)
            else -> null
        }
    }

    /** App a que um link se destinava, só pelo começo dele (null = não é link de navegação). */
    fun originOf(uri: String?): NavApp? = parse(uri)?.origin

    private fun isWaze(lower: String): Boolean =
        lower.startsWith("$SCHEME:") ||
            lower.startsWith("https://waze.com/ul") || lower.startsWith("https://www.waze.com/ul") ||
            lower.startsWith("http://waze.com/ul") || lower.startsWith("http://www.waze.com/ul")

    private fun parseWaze(text: String): NavRequest {
        val raw = text.substringAfter('?', "").substringBefore('#')
        val params = params(raw)
        val point = latLon(params["ll"])
        return NavRequest(
            lat = point?.first,
            lon = point?.second,
            query = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
            navigate = params["navigate"]?.lowercase(Locale.ROOT) in YES,
            test = params[TEST_PARAM] != null,
            rawQuery = raw,
            origin = NavApp.WAZE,
            link = text,
        )
    }

    /** `google.navigation:q=lat,lon&mode=d` (também `google.navigation:?q=` e `ll=`): já é a rota. */
    private fun parseMapsNavigation(text: String): NavRequest {
        val raw = text.substringAfter(':').substringBefore('#').removePrefix("//").removePrefix("?")
        val params = params(raw)
        val dest = (params["q"] ?: params["ll"] ?: params["daddr"])?.trim()?.takeIf { it.isNotEmpty() }
        val point = latLon(dest)
        return NavRequest(
            lat = point?.first,
            lon = point?.second,
            query = if (point == null) dest else null,
            navigate = true,
            test = params[TEST_PARAM] != null,
            rawQuery = raw,
            origin = NavApp.GOOGLE_MAPS,
            link = text,
        )
    }

    /**
     * `geo:lat,lon` (o ponto), `geo:0,0?q=lat,lon(nome)` (o ponto marcado) ou `geo:lat,lon?q=endereço`
     * (procura o endereço perto do ponto — o destino é o endereço, não o ponto).
     */
    private fun parseGeo(text: String): NavRequest {
        val body = text.substringAfter(':').substringBefore('#').removePrefix("//")
        val raw = body.substringAfter('?', "")
        val params = params(raw)
        val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() }
        val qPoint = latLon(q)
        val pathPoint = latLon(body.substringBefore('?').substringBefore(';'))
            ?.takeIf { it.first != 0.0 || it.second != 0.0 }
        val point = qPoint ?: if (q == null) pathPoint else null
        return NavRequest(
            lat = point?.first,
            lon = point?.second,
            query = if (qPoint == null) q else null,
            navigate = false,
            test = params[TEST_PARAM] != null,
            rawQuery = raw,
            origin = NavApp.GOOGLE_MAPS,
            link = text,
        )
    }

    private fun params(raw: String): Map<String, String> {
        val params = HashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            val value = decode(part.substringAfter('=', ""))
            if (key.isNotEmpty() && key !in params) params[key] = value
        }
        return params
    }

    /** "lat,lon" (aceita "(nome)" depois, como no `geo:`); null = não é coordenada válida. */
    private fun latLon(text: String?): Pair<Double, Double>? {
        val m = LAT_LON.matchEntire(text ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    /**
     * Quanto segurar este pedido. Só segura com o atraso ligado, o copiloto rodando, o leitor conectado
     * e o pedido vindo de um app de corrida (ou de quem não se identificou). Pedido do próprio MApp e
     * o do botão Testar nunca esperam; o de outro app (ou do MApp) vai para o app do link ([HoldPlan.passThrough]).
     *
     * [rideAppOnScreen] = o leitor vê a janela de um app de corrida agora. Sem ela (motorista no Waze
     * ou no Google Maps quando a chamada chegou), segurar não adianta: com [bringOn] o plano manda trazer o app que pediu
     * (ou, se ele não se identificou, [lastRideApp] — o último app de corrida que o leitor viu) e
     * espera [BRING_EXTRA_MS] a mais; senão repassa na hora. [sinceLastBringMs] = tempo desde a última
     * vez que um app foi trazido (null = nunca).
     */
    fun plan(
        enabled: Boolean,
        delayMs: Long,
        copilotOn: Boolean,
        readerOn: Boolean,
        referrer: String?,
        ownPackage: String,
        test: Boolean,
        rideAppOnScreen: Boolean = true,
        bringOn: Boolean = false,
        lastRideApp: Platform? = null,
        sinceLastBringMs: Long? = null,
    ): HoldPlan = when {
        test -> HoldPlan(0, "teste")
        referrer == ownPackage -> HoldPlan(0, "pedido do próprio MApp", passThrough = true)
        referrer != null && Platform.fromPackage(referrer) == null -> HoldPlan(0, "pedido de outro app", passThrough = true)
        !enabled -> HoldPlan(0, "atraso desligado")
        !copilotOn -> HoldPlan(0, "copiloto parado")
        !readerOn -> HoldPlan(0, "leitor sem conexão")
        else -> {
            val app = referrer?.let { Platform.fromPackage(it) } ?: lastRideApp
            val cooled = sinceLastBringMs == null || sinceLastBringMs >= BRING_COOLDOWN_MS
            val bringable = app?.takeIf { bringOn && cooled }
            when {
                rideAppOnScreen -> HoldPlan(validDelay(delayMs), "esperando a leitura da tela", fallback = bringable)
                app == null -> HoldPlan(0, "app de corrida fora da tela")
                !bringOn -> HoldPlan(0, "${app.label} fora da tela")
                !cooled -> HoldPlan(0, "${app.label} trazido para a frente há pouco")
                else -> HoldPlan(validDelay(delayMs) + BRING_EXTRA_MS, "${app.label} fora da tela", bring = app)
            }
        }
    }

    /**
     * [LATE_CHECK_MS] depois de começar a segurar com o app de corrida na tela: se nenhuma leitura
     * aconteceu e ele já não está lá (era a janela de aceite por cima do Waze), vale trazer o app.
     */
    fun lateBring(reads: Int, rideAppOnScreen: Boolean): Boolean = reads == 0 && !rideAppOnScreen

    /**
     * Libera o Waze? [stage] = etapa que o leitor publicou desde o começo da espera (só aceite e
     * embarque liberam: depois de "Como foi a viagem?" a Uber ainda pode mostrar o próximo passageiro);
     * [frames] = leituras da tela da Uber feitas desde o começo. null = continua segurando.
     */
    fun release(elapsedMs: Long, holdMs: Long, stage: TripStage?, frames: Int): ReleaseReason? = when {
        stage == TripStage.ACCEPTED || stage == TripStage.ON_TRIP -> ReleaseReason.STAGE
        frames >= FRAMES_ENOUGH -> ReleaseReason.FRAMES
        elapsedMs >= holdMs -> ReleaseReason.TIMEOUT
        else -> null
    }

    /**
     * Situação no Android dos links de [app]. [defaultHandler] = pacote que o Android usa hoje para
     * esses links (o do seletor "Abrir com" quando ninguém é padrão, ou null).
     */
    fun state(
        enabled: Boolean,
        installed: Boolean,
        defaultHandler: String?,
        ownPackage: String,
        app: NavApp = NavApp.WAZE,
    ): NavLinkState = when {
        !enabled -> NavLinkState.OFF
        !installed -> NavLinkState.NOT_INSTALLED
        defaultHandler == ownPackage -> NavLinkState.READY
        defaultHandler == app.packageName -> NavLinkState.APP_DEFAULT
        else -> NavLinkState.ASK
    }

    /** Texto do cartão: resumo da situação dos dois apps ([states]) e do app que abre a rota ([app]). */
    fun message(states: Map<NavApp, NavLinkState>, delayMs: Long, app: NavApp = NavApp.WAZE): String {
        val ready = NavApp.entries.filter { states[it] == NavLinkState.READY }
        return when {
            states.values.all { it == NavLinkState.OFF } ->
                "Quando a Uber abre o Waze ou o Google Maps logo depois do aceite ou do fim da corrida, a tela some " +
                    "antes de o MApp ler o próximo passageiro. Ligue para o MApp segurar a navegação por até " +
                    "${label(validDelay(delayMs))}."
            states.values.all { it == NavLinkState.NOT_INSTALLED } ->
                "Nem o Waze nem o Google Maps estão instalados: não há o que segurar."
            ready.isNotEmpty() ->
                "✅ Pronto: quando a Uber abrir o ${ready.joinToString(" ou o ") { it.label }}, o MApp segura até " +
                    "${label(validDelay(delayMs))} enquanto lê a tela da corrida e então abre o ${app.label} no mesmo " +
                    "destino. Se o Registro não mostrar \"🧭 … pedido por Uber\" nas próximas corridas, a Uber abre o " +
                    "app direto e o atraso não tem efeito."
            else ->
                "Falta um passo: deixe o MApp receber os links do app de navegação que a Uber usa (abaixo: Testar → " +
                    "MApp → Sempre)."
        }
    }

    /** Linha de cada app no cartão (links do Waze / do Google Maps). */
    fun linkMessage(state: NavLinkState, app: NavApp): String = when (state) {
        NavLinkState.OFF -> ""
        NavLinkState.NOT_INSTALLED -> "${app.label} não instalado."
        NavLinkState.ASK -> "Toque em Testar e, na pergunta \"Abrir com\", escolha MApp → Sempre."
        NavLinkState.APP_DEFAULT ->
            "O ${app.label} está como padrão para os próprios links e o MApp não recebe nada. Toque em Ajustar → " +
                "Abrir por padrão → Limpar padrões; depois em Testar e escolha MApp → Sempre."
        NavLinkState.READY -> "✅ Passam pelo MApp."
    }

    /** Linha do Registro quando o pedido chega. */
    fun describe(request: NavRequest, referrer: String?, plan: HoldPlan, app: NavApp = request.origin): String = buildString {
        append("🧭 ").append(request.origin.label).append(" pedido por ")
        append(referrer?.let { Platform.fromPackage(it)?.label ?: it } ?: "app sem nome")
        append(": ")
        val lat = request.lat
        val lon = request.lon
        when {
            lat != null && lon != null -> append(String.format(Locale.ROOT, "%.5f,%.5f", lat, lon))
            request.query != null -> append("\"").append(request.query).append("\"")
            else -> append("sem destino")
        }
        if (request.navigate) append(" · navegar")
        append(" · ")
        val bring = plan.bring
        append(
            when {
                plan.holdMs <= 0 -> "repassado na hora (${plan.reason})"
                bring != null -> "${bring.label} fora da tela: trazendo o app para ler (até ${label(plan.holdMs)})"
                else -> "segurando até ${label(plan.holdMs)}"
            },
        )
        if (app != request.origin) append(" · rota no ${app.label}")
    }

    /** Linha do Registro quando a navegação é liberada. */
    fun describeRelease(elapsedMs: Long, reason: ReleaseReason, frames: Int, app: NavApp = NavApp.WAZE): String =
        "🧭 ${app.label} aberto depois de ${String.format(PT_BR, "%.1f", elapsedMs / 1000.0)} s · ${reason.label}" +
            " ($frames ${if (frames == 1) "leitura" else "leituras"} da tela)"

    /** Mesmo destino no site do [app], se o app sumir do celular no meio do caminho. */
    fun webUrl(request: NavRequest, app: NavApp = NavApp.WAZE): String {
        if (app == NavApp.GOOGLE_MAPS) return mapsWebUrl(request)
        if (request.origin == NavApp.WAZE) {
            return "https://waze.com/ul" + if (request.rawQuery.isNotEmpty()) "?${request.rawQuery}" else ""
        }
        return "https://waze.com/ul" + (wazeQuery(request)?.let { "?$it" } ?: "")
    }

    /**
     * Link que [app] entende com o mesmo destino. App do próprio link: o link como chegou (no Waze, o
     * [NavRequest.rawQuery] repassado igual). Waze a partir do Google Maps: `waze://?ll=…` ou `?q=…`
     * (com `navigate=yes` quando o pedido era a rota). Google Maps a partir do Waze: com "navegar",
     * `google.navigation:` (já abre a rota); sem, `geo:` (mostra o ponto). null = sem destino (ou botão
     * Testar): só abrir o app.
     */
    fun target(request: NavRequest, app: NavApp): String? {
        if (request.test) return null
        if (app == request.origin) {
            if (app == NavApp.WAZE) return "$SCHEME://" + if (request.rawQuery.isNotEmpty()) "?${request.rawQuery}" else ""
            if (request.link.isNotEmpty()) return request.link
        }
        if (app == NavApp.WAZE) return wazeQuery(request)?.let { "$SCHEME://?$it" }
        val point = pointOf(request)
        val query = request.query?.let { encode(it) }
        return when {
            point != null && request.navigate -> "$MAPS_SCHEME:q=$point&mode=d"
            point != null -> "$GEO_SCHEME:$point?q=$point"
            query != null && request.navigate -> "$MAPS_SCHEME:q=$query&mode=d"
            query != null -> "$GEO_SCHEME:0,0?q=$query"
            else -> null
        }
    }

    /** Parâmetros do link do Waze com o destino do pedido (null = sem destino). */
    private fun wazeQuery(request: NavRequest): String? {
        val dest = pointOf(request)?.let { "ll=$it" } ?: request.query?.let { "q=${encode(it)}" } ?: return null
        return dest + if (request.navigate) "&navigate=yes" else ""
    }

    /** Mesmo destino no site do Google Maps (Google Maps desinstalado no meio do caminho). */
    fun mapsWebUrl(request: NavRequest): String {
        val dest = pointOf(request) ?: request.query?.let { encode(it) }
        return when {
            dest == null -> "https://www.google.com/maps"
            request.navigate -> "https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=driving"
            else -> "https://www.google.com/maps/search/?api=1&query=$dest"
        }
    }

    /**
     * App que recebe a rota: o escolhido, se estiver instalado; senão o do próprio link ([origin]); senão
     * qualquer um instalado (a navegação nunca fica sem app por causa da escolha).
     */
    fun resolveApp(chosen: NavApp, installed: Set<NavApp>, origin: NavApp = NavApp.WAZE): NavApp = when {
        chosen in installed -> chosen
        origin in installed -> origin
        else -> installed.firstOrNull() ?: chosen
    }

    /**
     * App que abre este pedido: o escolhido no cartão 🧭 para os pedidos da corrida; o do próprio link para
     * os de outro app ([HoldPlan.passThrough]).
     */
    fun chooseApp(request: NavRequest, plan: HoldPlan, chosen: NavApp, installed: Set<NavApp>): NavApp =
        resolveApp(if (plan.passThrough) request.origin else chosen, installed, request.origin)

    private fun pointOf(request: NavRequest): String? {
        val lat = request.lat ?: return null
        val lon = request.lon ?: return null
        return String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }

    private val YES = setOf("yes", "true", "1")
    private val LAT_LON = Regex("""\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\(.*\))?\s*""", RegexOption.DOT_MATCHES_ALL)
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
}
