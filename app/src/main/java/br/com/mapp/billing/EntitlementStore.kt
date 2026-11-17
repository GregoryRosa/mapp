package br.com.mapp.billing

import android.content.Context
import br.com.mapp.core.billing.Entitlement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache do último "sim" da Google Play, no aparelho (SharedPreferences).
 *
 * Serve para dois casos: abrir o app sem internet (a tolerância de [br.com.mapp.core.billing
 * .AccessRules.GRACE_DAYS] dias) e não piscar o paywall enquanto o Play responde. Não é
 * autorização: quem decide é o Play, em [PlayBilling].
 */
object EntitlementStore {

    private const val PREFS = "mapp_billing"
    private const val ACTIVE = "active"
    private const val CHECKED_AT = "checked_at"
    private const val TRIAL = "trial"
    private const val CANCELED = "canceled"

    private val _state = MutableStateFlow(Entitlement.NONE)

    /** Último estado conhecido. Começa em [Entitlement.NONE] até [load] rodar. */
    val state: StateFlow<Entitlement> = _state.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Lê do disco (chamado uma vez, no início do app). */
    fun load(ctx: Context): Entitlement {
        val p = prefs(ctx)
        val e = Entitlement(
            active = p.getBoolean(ACTIVE, false),
            checkedAt = p.getLong(CHECKED_AT, 0L),
            trial = p.getBoolean(TRIAL, false),
            canceled = p.getBoolean(CANCELED, false),
        )
        _state.value = e
        return e
    }

    /** Grava a resposta do Play. Só é chamado quando a consulta **deu certo**. */
    fun save(ctx: Context, e: Entitlement) {
        prefs(ctx).edit()
            .putBoolean(ACTIVE, e.active)
            .putLong(CHECKED_AT, e.checkedAt)
            .putBoolean(TRIAL, e.trial)
            .putBoolean(CANCELED, e.canceled)
            .apply()
        _state.value = e
    }
}
