package br.com.mapp.core.billing

/**
 * Regras da assinatura mensal (Kotlin puro, sem Android).
 *
 * O MApp é pago: só abre para quem tem assinatura ativa na Google Play. Esta é a decisão de
 * liberar ou bloquear, separada da biblioteca do Play (`billing/PlayBilling`) para ter teste.
 *
 * O que o app guarda no aparelho é o **último "sim" do Play** ([Entitlement.active]) e **quando**
 * esse "sim" chegou ([Entitlement.checkedAt]). Isso evita duas coisas ruins:
 * - bloquear quem pagou só porque o celular está sem internet na hora (até [GRACE_DAYS] dias);
 * - liberar para sempre quem cancelou (passado o prazo, o app volta a exigir a conferência).
 */
object AccessRules {

    /** Dias que o último "sim" do Play continua valendo sem nova conferência (sem internet). */
    const val GRACE_DAYS = 7

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * O que fazer agora. [owner] = dono do app logado (ele não assina de si mesmo).
     */
    fun access(e: Entitlement, now: Long, owner: Boolean = false, graceDays: Int = GRACE_DAYS): Access {
        if (owner) return Access.OWNER
        val age = now - e.checkedAt
        return when {
            // Nunca deu certo conferir com o Play: não é "não pagou", é "ainda não sei".
            e.checkedAt <= 0L -> Access.CHECKING
            e.active && age <= graceDays * DAY_MS -> Access.ALLOWED
            // Pagou, mas o último "sim" é antigo demais: precisa reconectar para seguir usando.
            e.active -> Access.STALE
            else -> Access.BLOCKED
        }
    }

    /** Dias que ainda faltam para o último "sim" expirar (0 quando já expirou ou não se aplica). */
    fun graceLeft(e: Entitlement, now: Long, graceDays: Int = GRACE_DAYS): Int {
        if (!e.active || e.checkedAt <= 0L) return 0
        val left = graceDays * DAY_MS - (now - e.checkedAt)
        if (left <= 0L) return 0
        return ((left + DAY_MS - 1) / DAY_MS).toInt()
    }

    /** Verdadeiro quando o app pode ser usado (inclui o dono e a tolerância sem internet). */
    fun isOpen(access: Access): Boolean = access == Access.ALLOWED || access == Access.OWNER

    /** Texto para a tela de assinatura / aviso. */
    fun message(access: Access, e: Entitlement, now: Long): String = when (access) {
        Access.OWNER -> "Acesso de administrador."
        Access.ALLOWED -> if (e.trial) {
            "Teste grátis em andamento."
        } else {
            "Assinatura ativa."
        }
        Access.CHECKING -> "Conferindo sua assinatura com a Google Play. Conecte o celular à internet."
        Access.STALE ->
            "Não consigo conferir sua assinatura há ${GRACE_DAYS} dias. Conecte à internet uma vez para " +
                "continuar usando o MApp."
        Access.BLOCKED -> "O MApp é por assinatura mensal. Assine para liberar o copiloto."
    }

    /** Aviso curto para quem está na tolerância (volta a null quando tudo está normal). */
    fun graceNotice(e: Entitlement, now: Long, graceDays: Int = GRACE_DAYS): String? {
        val days = graceLeft(e, now, graceDays)
        // Só avisa quando falta pouco: 2 dias ou menos da tolerância.
        if (!e.active || days == 0 || days > 2) return null
        return "Sem conferir a assinatura há dias. Conecte à internet em até $days dia(s)."
    }
}

/** O que o app guarda sobre a assinatura. Nada aqui é segredo: é só o cache do que o Play respondeu. */
data class Entitlement(
    /** O Play respondeu que existe assinatura válida na última conferência que deu certo. */
    val active: Boolean = false,
    /** Quando essa resposta chegou (epoch ms). 0 = nunca conseguiu conferir. */
    val checkedAt: Long = 0L,
    /** A assinatura está no período de teste grátis. */
    val trial: Boolean = false,
    /** Assinatura cancelada mas ainda válida até o fim do ciclo pago. */
    val canceled: Boolean = false,
) {
    companion object {
        val NONE = Entitlement()
    }
}

/** Decisão do [AccessRules]. */
enum class Access {
    /** Dono do app (painel de suporte): sempre entra. */
    OWNER,

    /** Assinatura ativa (ou dentro da tolerância sem internet). */
    ALLOWED,

    /** Primeira abertura ainda sem resposta do Play: mostrar "conferindo", não paywall. */
    CHECKING,

    /** Pagou, mas faz tempo demais sem conferir: precisa de internet. */
    STALE,

    /** Sem assinatura: paywall. */
    BLOCKED,
}
