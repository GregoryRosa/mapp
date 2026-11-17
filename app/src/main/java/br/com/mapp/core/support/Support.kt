package br.com.mapp.core.support

/**
 * Chamados de suporte e report de bug (Kotlin puro, sem Android).
 *
 * Um **chamado** é uma conversa: o motorista abre (bug ou dúvida), o suporte responde. As regras de
 * ordem, status, contagem de não lidas e validação do texto ficam aqui, com teste; o envio e a
 * escuta em tempo real ficam em `support/SupportRepository` (Firestore).
 */
object SupportRules {

    /** Tamanho máximo de uma mensagem (protege o banco e a tela). */
    const val MAX_MESSAGE = 2000

    /** Tamanho máximo do assunto mostrado na lista do painel. */
    const val MAX_SUBJECT = 80

    /** Mensagem pronta para enviar? Devolve o texto limpo ou null. */
    fun clean(text: String): String? {
        val t = text.trim()
        if (t.length < 2) return null
        return if (t.length > MAX_MESSAGE) t.take(MAX_MESSAGE) else t
    }

    /** Assunto do chamado a partir da primeira mensagem (uma linha, curta). */
    fun subjectOf(firstMessage: String): String {
        val line = firstMessage.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val short = if (line.length > MAX_SUBJECT) line.take(MAX_SUBJECT - 1).trimEnd() + "…" else line
        return short.ifBlank { "Chamado sem assunto" }
    }

    /**
     * Ordem do painel: primeiro o que espera resposta do suporte (mais antigo no topo — quem esperou
     * mais é atendido primeiro), depois os já respondidos, por fim os resolvidos.
     */
    fun forAdmin(tickets: List<Ticket>): List<Ticket> =
        tickets.sortedWith(
            compareBy<Ticket> { adminRank(it) }
                .thenBy { if (adminRank(it) == 0) it.updatedAt else -it.updatedAt },
        )

    private fun adminRank(t: Ticket): Int = when {
        t.status == TicketStatus.RESOLVED -> 2
        t.waitingSupport -> 0
        else -> 1
    }

    /** Ordem para o motorista: conversa mexida mais recentemente em cima. */
    fun forUser(tickets: List<Ticket>): List<Ticket> = tickets.sortedByDescending { it.updatedAt }

    /** Nº de chamados com resposta nova do suporte que o motorista ainda não abriu. */
    fun unreadForUser(tickets: List<Ticket>): Int = tickets.count { it.unreadForUser }

    /** Nº de chamados esperando resposta do suporte (bolinha do painel). */
    fun waitingForAdmin(tickets: List<Ticket>): Int =
        tickets.count { it.status != TicketStatus.RESOLVED && it.waitingSupport }

    fun label(status: TicketStatus): String = when (status) {
        TicketStatus.OPEN -> "Aberto"
        TicketStatus.ANSWERED -> "Respondido"
        TicketStatus.RESOLVED -> "Resolvido"
    }

    fun label(kind: TicketKind): String = when (kind) {
        TicketKind.BUG -> "🐞 Bug"
        TicketKind.HELP -> "💬 Suporte"
    }

    /** Linha de resumo de um chamado na lista. */
    fun summary(t: Ticket): String {
        val who = if (t.lastFromSupport) "Suporte: " else ""
        val last = t.lastMessage.trim().replace('\n', ' ')
        val short = if (last.length > 90) last.take(89).trimEnd() + "…" else last
        return who + short.ifBlank { "(sem mensagens)" }
    }
}

/** Um chamado. Os campos vêm do Firestore; `id` é o documento. */
data class Ticket(
    val id: String = "",
    /** Quem abriu (uid anônimo do Firebase). */
    val uid: String = "",
    val kind: TicketKind = TicketKind.HELP,
    val subject: String = "",
    val status: TicketStatus = TicketStatus.OPEN,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastMessage: String = "",
    /** A última mensagem foi do suporte (e não do motorista). */
    val lastFromSupport: Boolean = false,
    /** Tem resposta do suporte que o motorista ainda não leu. */
    val unreadForUser: Boolean = false,
    /** Está na fila do suporte (última palavra foi do motorista). */
    val waitingSupport: Boolean = true,
    /** Diagnóstico do aparelho, anexado quando o chamado é aberto (versão, celular, Android). */
    val device: String = "",
    /** Tela (e aba) em que o motorista tocou no botão de suporte — ver [ScreenNames]. */
    val screen: String = "",
)

/** Uma mensagem do chamado. */
data class SupportMessage(
    val id: String = "",
    val text: String = "",
    /** Escrita pelo suporte (você), e não pelo motorista. */
    val fromSupport: Boolean = false,
    val at: Long = 0L,
)

enum class TicketStatus { OPEN, ANSWERED, RESOLVED }

enum class TicketKind { BUG, HELP }

/**
 * Bloco de diagnóstico que vai junto do chamado — é o que evita a ida e volta de "qual versão você
 * usa?". Nenhum dado de corrida, endereço ou valor entra aqui.
 */
object DeviceReport {
    fun text(
        appVersion: String,
        androidVersion: String,
        model: String,
        readerConnected: Boolean,
        copilotRunning: Boolean,
        subscription: String,
        screen: String = "",
    ): String = buildString {
        if (screen.isNotBlank()) append("Tela: ").append(screen).append('\n')
        append("MApp $appVersion · Android $androidVersion · $model")
        append("\nLeitor: ").append(if (readerConnected) "conectado" else "sem conexão")
        append(" · Copiloto: ").append(if (copilotRunning) "ligado" else "parado")
        append(" · Assinatura: ").append(subscription)
    }
}

/**
 * Nome, em português, da tela em que o motorista abriu o suporte — vai junto do chamado para eu
 * saber onde o problema aconteceu sem precisar perguntar. [route] é a rota de navegação
 * (`ui/Routes`); [tab] é a aba aberta dentro dela, quando a tela tem abas.
 */
object ScreenNames {

    /** Fora do app principal (sem rota): aberto na tela de assinatura. */
    const val PAYWALL = "paywall"

    private val names = mapOf(
        "home" to "Tela inicial",
        "finance" to "Financeiro",
        "history" to "Ofertas",
        "profiles" to "Perfis",
        "zones" to "Zonas",
        "card_style" to "Card",
        "shifts" to "Jornadas",
        PAYWALL to "Tela de assinatura",
    )

    /** "Financeiro → Relatórios"; rota desconhecida aparece como veio (melhor que nada). */
    fun of(route: String?, tab: String? = null): String {
        val base = route?.let { names[it] ?: it }?.takeIf { it.isNotBlank() } ?: return "Desconhecida"
        val t = tab?.trim().orEmpty()
        return if (t.isEmpty()) base else "$base → $t"
    }
}
