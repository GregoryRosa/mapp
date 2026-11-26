package br.com.mapp.core.earnings

import br.com.mapp.core.copilot.OnlineState
import br.com.mapp.core.model.Platform
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftMath
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Ganho do dia que o app de corrida mostra na tela inicial (publicado pelo leitor no `OfferBus`). */
data class DayEarnings(val platform: Platform, val amount: Double)

/**
 * Ganho de hoje na tela inicial da Uber: o valor grande no topo com "HOJE" embaixo. Pela árvore de
 * Acessibilidade chega como `"R$ 147,25", "HOJE"` (print de 25/11/2026; diagnóstico de 12/11 e 22/11).
 * Sem o rótulo "HOJE" não vale (o mesmo lugar pode mostrar outro período), nem valor com "+" (as bolhas
 * de dinâmica do mapa, "+R$ 1,50") nem valor escondido ("R$ ••••"). 99 e inDrive ainda sem print: null.
 */
object DayEarningsScreen {

    private val LABELS = setOf("hoje", "today")
    private val MONEY = Regex("""^r\$ ?(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})$""")
    private val MONEY_WITH_LABEL = Regex("""^r\$ ?(\d{1,3}(?:\.\d{3})+|\d+),(\d{2})[ ,·-]+(hoje|today)$""")

    /** Maior valor aceito (protege de leitura estranha). */
    const val MAX = 20_000.0

    fun read(platform: Platform, texts: List<String>): Double? {
        if (platform != Platform.UBER) return null
        val norm = texts.map(::normalize)
        norm.forEachIndexed { i, t ->
            MONEY_WITH_LABEL.matchEntire(t)?.let { return valueOf(it) }
            if (t in LABELS) {
                // Valor logo antes do rótulo (pode haver um ícone/descrição no meio); senão logo depois.
                for (j in listOf(i - 1, i - 2, i + 1)) {
                    val m = norm.getOrNull(j)?.let { MONEY.matchEntire(it) } ?: continue
                    return valueOf(m)
                }
            }
        }
        return null
    }

    private fun valueOf(m: MatchResult): Double? {
        val reais = m.groupValues[1].replace(".", "").toDoubleOrNull() ?: return null
        val cents = m.groupValues[2].toDoubleOrNull() ?: return null
        val v = reais + cents / 100.0
        return v.takeIf { it in 0.0..MAX }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ',', ';')
}

/**
 * **Ganho extra da Uber.** A Uber às vezes paga mais do que a oferta mostrou (percurso maior, passageiro
 * mudou o destino, gorjeta, taxa de cancelamento, promoção) e o "Hoje" dela fica acima do que o MApp
 * contou. Na tela inicial da Uber o MApp compara os dois e guarda a diferença num lançamento só por dia,
 * *Ganho extra* (Uber), que vai sendo atualizado — nunca soma duas vezes.
 *
 * Conta: extra = Hoje da Uber − corridas Uber do MApp no dia da Uber − outros ganhos Uber lançados à mão
 * (nunca negativo). Dia da Uber = das [DAY_HOUR]h às [DAY_HOUR]h (como a semana de ganhos dela).
 */
object UberExtra {

    /** Categoria do lançamento automático (o diálogo Lançar não cria esta categoria). */
    const val CATEGORY = "Ganho extra"

    /** O dia de ganhos da Uber vira às 4h (como a semana: segunda 4h → segunda 4h). */
    const val DAY_HOUR = 4

    /** Valor novo maior que o lançado: vale depois de visto igual por este tempo. */
    const val STABLE_UP_MS = 30_000L

    /**
     * Valor novo menor: espera mais — logo depois de uma corrida o MApp já soma a corrida e o "Hoje" da
     * Uber pode demorar a subir (o extra cairia e voltaria).
     */
    const val STABLE_DOWN_MS = 120_000L

    /** Intervalo [from]..[to] (inclusivo, em ms). */
    data class Window(val from: Long, val to: Long)

    /** Dia de ganhos da Uber que contém [now]: das 4h até as 4h do dia seguinte (antes das 4h, o anterior). */
    fun uberDay(now: Long, zone: ZoneId): Window {
        val shifted = Instant.ofEpochMilli(now).atZone(zone).minusHours(DAY_HOUR.toLong())
        val start = shifted.toLocalDate().atTime(DAY_HOUR, 0).atZone(zone)
        return Window(start.toInstant().toEpochMilli(), start.plusDays(1).toInstant().toEpochMilli() - 1)
    }

    fun isExtra(e: FinanceEntry, platform: Platform = Platform.UBER): Boolean =
        e.kind == EntryKind.INCOME && e.platform == platform && e.category == CATEGORY

    /**
     * Comparação de uma leitura. [rides] = corridas finalizadas do app no dia (jornadas, pelo horário da
     * corrida) + aceitas fora de jornada; [manual] = ganhos do app lançados à mão (sem o extra);
     * [extras] = lançamentos de ganho extra do dia (normalmente 0 ou 1; o 1º é o que fica).
     */
    data class Check(
        val shown: Double,
        val rides: Double,
        val rideCount: Int,
        val manual: Double,
        val extras: List<FinanceEntry>,
    ) {
        /** Extra que deveria estar lançado. */
        val target: Double = round2(max(0.0, shown - rides - manual))

        /** Extra lançado agora. */
        val saved: Double = round2(extras.sumOf { it.amount })

        /** Lançamento que fica (os outros, se houver, são duplicados e saem). */
        val kept: FinanceEntry? = extras.minByOrNull { it.atMillis }

        /** O MApp conta mais que a Uber mostra (corrida contada que a Uber não pagou, ou Uber atrasada). */
        val mappAhead: Double = round2(max(0.0, rides + manual - shown))

        fun differs(value: Double): Boolean = abs(value - saved) >= 0.005 || extras.size > 1
    }

    fun check(
        shown: Double,
        window: Window,
        current: Shift?,
        history: List<Shift>,
        offers: List<OfferRecord>,
        entries: List<FinanceEntry>,
        now: Long,
        platform: Platform = Platform.UBER,
    ): Check {
        val shiftTrips = (listOfNotNull(current) + history)
            .flatMap { it.trips }
            .filter { it.finished && it.platform == platform && it.atMillis in window.from..window.to }
        val loose = PeriodMath.looseOffers(window.from, window.to, current, history, offers, now)
            .map { it.evaluated.raw }
            .filter { it.platform == platform }
        val inDay = entries.filter { it.atMillis in window.from..window.to }
        val manual = inDay.filter { it.kind == EntryKind.INCOME && it.platform == platform && !isExtra(it, platform) }
        return Check(
            shown = round2(shown),
            rides = round2(shiftTrips.sumOf { it.price } + loose.sumOf { it.price }),
            rideCount = shiftTrips.size + loose.size,
            manual = round2(manual.sumOf { it.amount }),
            extras = inDay.filter { isExtra(it, platform) },
        )
    }

    /** O lançamento a gravar com [amount] (null = apagar o que existe: não há extra). */
    fun entry(check: Check, amount: Double, now: Long, platform: Platform = Platform.UBER): FinanceEntry? {
        if (amount < 0.005) return null
        val kept = check.kept
        return FinanceEntry(
            id = kept?.id ?: 0,
            kind = EntryKind.INCOME,
            category = CATEGORY,
            amount = round2(amount),
            platform = platform,
            km = null,
            note = note(check, platform),
            atMillis = kept?.atMillis ?: now,
        )
    }

    /** "Uber hoje R$ 147,25 · corridas no MApp R$ 140,05 (12)" (+ lançados à mão). */
    fun note(check: Check, platform: Platform = Platform.UBER): String = buildString {
        append("${platform.label} hoje ${ShiftMath.money(check.shown)} · corridas no MApp ${ShiftMath.money(check.rides)} (${check.rideCount})")
        if (check.manual > 0) append(" · lançado à mão ${ShiftMath.money(check.manual)}")
    }

    fun round2(v: Double): Double = round(v * 100.0) / 100.0
}

/**
 * Só muda o lançamento quando o valor fica igual por um tempo ([UberExtra.STABLE_UP_MS] para subir,
 * [UberExtra.STABLE_DOWN_MS] para descer): leituras no meio de uma corrida que termina (MApp e Uber
 * atualizando em momentos diferentes) não mexem no extra.
 *
 * **Offline na Uber** ([online] com [OnlineState.OFFLINE]): o dia parou — nenhuma corrida terminando —,
 * então o extra que **sobe** (ou fica igual) vale **na hora**, já na 1ª leitura do "Hoje", com o valor
 * completo do dia. Descer continua esperando [UberExtra.STABLE_DOWN_MS] (a Uber pode ainda não ter somado
 * a última corrida que o MApp já contou).
 */
class StableExtra(
    private val upMs: Long = UberExtra.STABLE_UP_MS,
    private val downMs: Long = UberExtra.STABLE_DOWN_MS,
) {
    private var candidate: Double? = null
    private var since = 0L
    private var day = -1L

    /** A tela inicial da Uber está dizendo "Você está offline". */
    var offline: Boolean = false
        private set

    /**
     * Estado online/offline lido na tela inicial da Uber. Troca de estado recomeça a espera. Devolve
     * true só quando o motorista **acabou de ficar offline** (para conferir o dia e avisar uma vez).
     */
    fun online(state: OnlineState): Boolean {
        val was = offline
        offline = state == OnlineState.OFFLINE
        if (offline != was) candidate = null
        return offline && !was
    }

    /**
     * Leitura com o extra calculado [target] (o lançado agora é [saved]), no dia da Uber [dayFrom].
     * Devolve o valor a gravar quando ele está firme; null = esperar.
     */
    fun offer(target: Double, saved: Double, dayFrom: Long, now: Long): Double? {
        if (dayFrom != day) { day = dayFrom; candidate = null }
        if (offline && target >= saved - 0.005) {
            candidate = target
            since = now
            return target
        }
        if (candidate == null || abs(candidate!! - target) >= 0.005 || now < since) {
            candidate = target
            since = now
            return null
        }
        val wait = if (target < saved) downMs else upMs
        return if (now - since >= wait) target else null
    }

    /** Corrida aberta (ou dado incompleto): a espera recomeça. */
    fun reset() {
        candidate = null
    }
}
