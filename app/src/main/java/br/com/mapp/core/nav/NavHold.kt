package br.com.mapp.core.nav

import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.TripStage
import java.net.URLDecoder
import java.util.Locale

/**
 * Link que um app pediu para o Waze abrir (`waze://?ll=lat,lon&navigate=yes`). [rawQuery] é a parte
 * depois do "?", repassada igual ao Waze; [test] = link do botão Testar (`mapp_test=1`).
 */
data class NavRequest(
    val lat: Double? = null,
    val lon: Double? = null,
    val query: String? = null,
    val navigate: Boolean = false,
    val test: Boolean = false,
    val rawQuery: String = "",
)

/** Quanto segurar o Waze e por quê (para o Registro). 0 = repassa na hora. */
data class HoldPlan(val holdMs: Long, val reason: String)

/** Por que o Waze foi liberado. */
enum class ReleaseReason(val label: String) {
    /** O leitor reconheceu a tela da corrida (aceite ou embarque): não há mais o que esperar. */
    STAGE("tela da corrida lida"),
    /** O leitor já leu a tela algumas vezes sem novidade (ex.: motorista tocou em Navegar na Uber). */
    FRAMES("tela lida sem novidade"),
    TIMEOUT("tempo esgotado"),
    /** Motorista voltou, apagou a tela ou saiu: abre o Waze na hora. */
    LEFT("motorista saiu da tela"),
}

/** Situação do atalho dos links do Waze no Android (cartão da tela inicial). */
enum class NavLinkState {
    /** Desligado no MApp. */
    OFF,
    /** Waze não instalado: nada para segurar. */
    NO_WAZE,
    /** Ligado, mas o Android ainda pergunta qual app abre os links (ou nunca perguntou). */
    ASK,
    /** O Waze está marcado como padrão para os próprios links: o MApp não recebe nada. */
    WAZE_DEFAULT,
    /** MApp é o padrão: a Uber abre o Waze passando pelo MApp. */
    READY,
}

/**
 * Atraso do Waze: quando a Uber abre o Waze (aceite, embarque ou fim de corrida com outra na fila), a
 * tela da Uber some em menos de 1 s e o leitor não consegue ler "Encontro com <novo>". Com o MApp
 * escolhido como app dos links `waze://`, uma tela invisível recebe o link, segura o Waze alguns
 * segundos com a Uber ainda à vista (o leitor captura a tela em rajada) e então o repassa ao Waze, igual.
 *
 * Só funciona se a Uber abrir o Waze por link (`waze://`) sem fixar o pacote — o Registro diz se o
 * pedido passou pelo MApp. O MApp não toca em nada: só atrasa a abertura que a Uber pediu.
 */
object NavHold {
    const val WAZE_PACKAGE = "com.waze"
    const val SCHEME = "waze"
    const val TEST_PARAM = "mapp_test"

    /** Link do botão Testar: faz o Android perguntar quem abre links do Waze (escolha MApp → Sempre). */
    const val TEST_URI = "waze://?$TEST_PARAM=1"

    /** Link usado só para perguntar ao Android qual app é o padrão. */
    const val PROBE_URI = "waze://?ll=0,0"

    /** Tempos que o motorista pode escolher (máximo de espera; normalmente libera antes). */
    val DELAYS_MS: List<Long> = listOf(1_000L, 1_500L, 2_000L, 3_000L, 4_000L, 5_000L)
    const val DEFAULT_DELAY_MS = 2_000L

    /**
     * Leituras da tela da Uber durante a espera que bastam: a etapa precisa de 2 leituras iguais para
     * ser confirmada; com 3 sem novidade não há tela nova para pegar.
     */
    const val FRAMES_ENOUGH = 3

    /** Tempo de espera válido (valor salvo antigo ou estranho vira o padrão). */
    fun validDelay(ms: Long): Long = if (ms in DELAYS_MS) ms else DEFAULT_DELAY_MS

    /** "1 s", "1,5 s", "2 s". */
    fun label(ms: Long): String {
        val s = ms / 1000.0
        return if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(PT_BR, "%.1f s", s)
    }

    /**
     * Lê um link do Waze (`waze://?…`, `waze://ul?…` ou `https://(www.)waze.com/ul?…`). null = não é
     * link do Waze. Coordenada fora da faixa é ignorada; o resto do link continua valendo.
     */
    fun parse(uri: String?): NavRequest? {
        val text = uri?.trim() ?: return null
        val lower = text.lowercase(Locale.ROOT)
        val isWaze = lower.startsWith("$SCHEME:") ||
            lower.startsWith("https://waze.com/ul") || lower.startsWith("https://www.waze.com/ul") ||
            lower.startsWith("http://waze.com/ul") || lower.startsWith("http://www.waze.com/ul")
        if (!isWaze) return null
        val raw = text.substringAfter('?', "").substringBefore('#')
        val params = HashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val key = decode(part.substringBefore('=')).lowercase(Locale.ROOT)
            val value = decode(part.substringAfter('=', ""))
            if (key.isNotEmpty() && key !in params) params[key] = value
        }
        var lat: Double? = null
        var lon: Double? = null
        params["ll"]?.split(',')?.takeIf { it.size == 2 }?.let { (a, b) ->
            val la = a.trim().toDoubleOrNull()
            val lo = b.trim().toDoubleOrNull()
            if (la != null && lo != null && la in -90.0..90.0 && lo in -180.0..180.0) {
                lat = la
                lon = lo
            }
        }
        return NavRequest(
            lat = lat,
            lon = lon,
            query = params["q"]?.trim()?.takeIf { it.isNotEmpty() },
            navigate = params["navigate"]?.lowercase(Locale.ROOT) in YES,
            test = params[TEST_PARAM] != null,
            rawQuery = raw,
        )
    }

    /**
     * Quanto segurar este pedido. Só segura com o atraso ligado, o copiloto rodando, o leitor conectado
     * e o pedido vindo de um app de corrida (ou de quem não se identificou). Pedido do próprio MApp e
     * o do botão Testar nunca esperam.
     */
    fun plan(
        enabled: Boolean,
        delayMs: Long,
        copilotOn: Boolean,
        readerOn: Boolean,
        referrer: String?,
        ownPackage: String,
        test: Boolean,
    ): HoldPlan = when {
        test -> HoldPlan(0, "teste")
        referrer == ownPackage -> HoldPlan(0, "pedido do próprio MApp")
        !enabled -> HoldPlan(0, "atraso desligado")
        !copilotOn -> HoldPlan(0, "copiloto parado")
        !readerOn -> HoldPlan(0, "leitor sem conexão")
        referrer != null && Platform.fromPackage(referrer) == null -> HoldPlan(0, "pedido de outro app")
        else -> HoldPlan(validDelay(delayMs), "esperando a leitura da tela")
    }

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
     * Situação no Android. [defaultHandler] = pacote que o Android usa hoje para `waze://` (o do
     * seletor "Abrir com" quando ninguém é padrão, ou null).
     */
    fun state(enabled: Boolean, wazeInstalled: Boolean, defaultHandler: String?, ownPackage: String): NavLinkState = when {
        !enabled -> NavLinkState.OFF
        !wazeInstalled -> NavLinkState.NO_WAZE
        defaultHandler == ownPackage -> NavLinkState.READY
        defaultHandler == WAZE_PACKAGE -> NavLinkState.WAZE_DEFAULT
        else -> NavLinkState.ASK
    }

    /** Texto do cartão para cada situação. */
    fun message(state: NavLinkState, delayMs: Long): String = when (state) {
        NavLinkState.OFF ->
            "Quando a Uber abre o Waze logo depois do aceite ou do fim da corrida, a tela some antes de o MApp " +
                "ler o próximo passageiro. Ligue para o MApp segurar o Waze por até ${label(validDelay(delayMs))}."
        NavLinkState.NO_WAZE -> "Waze não instalado: não há o que segurar."
        NavLinkState.ASK ->
            "Falta um passo: toque em Testar e, na pergunta \"Abrir com\", escolha MApp → Sempre. " +
                "É assim que o Android passa os links do Waze pelo MApp."
        NavLinkState.WAZE_DEFAULT ->
            "O Waze está como padrão para os próprios links e o MApp não recebe nada. Toque em Ajustar → " +
                "Abrir por padrão → Limpar padrões; depois toque em Testar e escolha MApp → Sempre."
        NavLinkState.READY ->
            "✅ Pronto: quando a Uber abrir o Waze, o MApp segura até ${label(validDelay(delayMs))} enquanto lê " +
                "a tela da corrida e então abre o Waze no mesmo destino. Se o Registro não mostrar \"🧭 Waze\" " +
                "nas próximas corridas, a Uber abre o Waze direto e o atraso não tem efeito."
    }

    /** Linha do Registro quando o pedido chega. */
    fun describe(request: NavRequest, referrer: String?, plan: HoldPlan): String = buildString {
        append("🧭 Waze pedido por ")
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
        append(if (plan.holdMs > 0) "segurando até ${label(plan.holdMs)}" else "repassado na hora (${plan.reason})")
    }

    /** Linha do Registro quando o Waze é liberado. */
    fun describeRelease(elapsedMs: Long, reason: ReleaseReason, frames: Int): String =
        "🧭 Waze aberto depois de ${String.format(PT_BR, "%.1f", elapsedMs / 1000.0)} s · ${reason.label}" +
            " ($frames ${if (frames == 1) "leitura" else "leituras"} da tela)"

    /** Mesmo destino no site do Waze, se o app do Waze sumir do celular. */
    fun webUrl(request: NavRequest): String =
        "https://waze.com/ul" + if (request.rawQuery.isNotEmpty()) "?${request.rawQuery}" else ""

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }

    private val YES = setOf("yes", "true", "1")
    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
}
