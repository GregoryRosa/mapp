package br.com.mapp

import android.content.Context
import br.com.mapp.billing.EntitlementStore
import br.com.mapp.core.billing.Access
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.support.SupportRepository

/**
 * "Pode usar o MApp agora?" — junta o cache da assinatura ([EntitlementStore]) com a sessão do dono
 * ([SupportRepository.owner]) e aplica as regras do `core/billing/AccessRules`.
 *
 * Usado em dois lugares: na `MainActivity` (mostrar o app ou a tela de assinatura) e no
 * `OverlayService` (o copiloto não liga sem assinatura — senão bastava não abrir a tela).
 */
object Gate {

    fun access(ctx: Context): Access {
        val cached = EntitlementStore.state.value
        // Serviço iniciado pelo Android antes de alguém ler o disco: lê agora.
        val e = if (cached.checkedAt > 0L || cached.active) cached else EntitlementStore.load(ctx)
        return AccessRules.access(e, System.currentTimeMillis(), owner = SupportRepository.owner.value)
    }

    fun isOpen(ctx: Context): Boolean = AccessRules.isOpen(access(ctx))
}
