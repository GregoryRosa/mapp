package br.com.mapp

/**
 * Os poucos valores que mudam de dono/loja. **Único lugar para editar** ao publicar.
 *
 * Nada aqui é segredo: o APK é público, então chave de API, senha e token nunca entram no código
 * (regra 4 do CLAUDE.md). O e-mail do dono aqui é só para o app saber qual conta abre o painel de
 * suporte; quem garante isso de verdade são as regras do Firestore (ver README → Firestore).
 */
object MappConfig {

    /**
     * ID da assinatura cadastrada no Play Console (Monetizar → Produtos → Assinaturas).
     * Precisa ser exatamente igual ao do Console.
     */
    const val SUBSCRIPTION_ID = "mapp_mensal"

    /** Conta que abre o painel de chamados. Trocar para o seu e-mail antes de publicar. */
    const val OWNER_EMAIL = "gregoryrosa@gmail.com"

    /** E-mail mostrado quando o chat não está disponível (sem internet / Firebase sem configurar). */
    const val SUPPORT_EMAIL = "gregoryrosa@gmail.com"

    /** Política de privacidade (obrigatória na ficha do Play). */
    const val PRIVACY_URL = "https://gregoryrosa.github.io/mapp/privacidade.html"

    /** Tela de assinaturas do Play, para cancelar ou trocar de plano. */
    const val MANAGE_SUBSCRIPTION_URL =
        "https://play.google.com/store/account/subscriptions?sku=$SUBSCRIPTION_ID&package=br.com.mapp"
}
