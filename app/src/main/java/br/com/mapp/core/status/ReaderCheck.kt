package br.com.mapp.core.status

/** Por que o leitor (Acessibilidade) não está lendo as corridas. */
enum class ReaderProblem {
    /** Tudo certo: o Android conectou o leitor. */
    NONE,

    /** O leitor "MApp" está desligado em Configurações → Acessibilidade. */
    ACCESSIBILITY_OFF,

    /**
     * Marcado como ligado nas Configurações, mas o Android não conectou o serviço (encerrado pela
     * economia de bateria, travado depois de atualizar o app, ou ainda reconectando).
     */
    NOT_CONNECTED,
}

/**
 * Explica o "!" cinza do ícone "M" e o que fazer. A tela inicial usa a **mesma** regra, para o app
 * nunca dizer "Tudo ativado" com o ícone avisando problema. Kotlin puro (sem Android).
 */
object ReaderCheck {

    /**
     * [settingOn] = o leitor MApp está marcado em Configurações → Acessibilidade;
     * [connected] = o Android conectou o serviço de verdade (`onServiceConnected`).
     */
    fun problem(settingOn: Boolean, connected: Boolean): ReaderProblem = when {
        connected -> ReaderProblem.NONE
        !settingOn -> ReaderProblem.ACCESSIBILITY_OFF
        else -> ReaderProblem.NOT_CONNECTED
    }

    /** Título curto (linha de estado na tela inicial). */
    fun title(problem: ReaderProblem): String = when (problem) {
        ReaderProblem.NONE -> "Leitor conectado"
        ReaderProblem.ACCESSIBILITY_OFF -> "Leitor desligado"
        ReaderProblem.NOT_CONNECTED -> "Leitor sem conexão"
    }

    /** O que está errado e como resolver, em uma frase para o aviso do ícone e da tela inicial. */
    fun message(problem: ReaderProblem): String = when (problem) {
        ReaderProblem.NONE -> "O leitor está conectado."
        ReaderProblem.ACCESSIBILITY_OFF ->
            "O MApp não está vendo as corridas: a Acessibilidade está desligada. " +
                "Ative o leitor MApp em Configurações → Acessibilidade."
        ReaderProblem.NOT_CONNECTED ->
            "O MApp não está vendo as corridas: a Acessibilidade aparece como ligada, mas o Android " +
                "não conectou o leitor (parou depois de uma atualização ou pela economia de bateria). " +
                "Desligue e ligue de novo o MApp em Configurações → Acessibilidade."
    }

    /** Aviso do ícone: o texto de [message] com o sinal de atenção na frente. */
    fun notice(problem: ReaderProblem): String? =
        if (problem == ReaderProblem.NONE) null else "⚠️ ${message(problem)}"
}
