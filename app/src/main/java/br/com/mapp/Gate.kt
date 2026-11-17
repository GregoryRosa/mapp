package br.com.mapp

import android.content.Context
import android.os.Build
import br.com.mapp.billing.EntitlementStore
import br.com.mapp.core.billing.Access
import br.com.mapp.core.billing.AccessRules
import br.com.mapp.support.SupportRepository

/**
 * "Pode usar o MApp agora?" — junta o cache da assinatura ([EntitlementStore]), a sessão do dono
 * ([SupportRepository.owner]) e a origem da instalação, e aplica as regras do `core/billing/AccessRules`.
 *
 * Usado em dois lugares: na `MainActivity` (mostrar o app ou a tela de assinatura) e no
 * `OverlayService` (o copiloto não liga sem assinatura — senão bastava não abrir a tela).
 */
object Gate {

    private const val PLAY_STORE = "com.android.vending"

    fun access(ctx: Context): Access {
        val cached = EntitlementStore.state.value
        // Serviço iniciado pelo Android antes de alguém ler o disco: lê agora.
        val e = if (cached.checkedAt > 0L || cached.active) cached else EntitlementStore.load(ctx)
        return AccessRules.access(
            e,
            System.currentTimeMillis(),
            owner = SupportRepository.owner.value,
            fromPlay = fromPlay(ctx),
        )
    }

    fun isOpen(ctx: Context): Boolean = AccessRules.isOpen(access(ctx))

    /**
     * O app veio da Play Store? O APK de teste publicado nas Releases do GitHub não vem, e ali não
     * existe assinatura para conferir — ver [AccessRules.access]. Na dúvida (erro ao ler o
     * instalador) assume que **não** veio da loja: trancar o motorista fora do app por causa de um
     * erro de leitura é pior do que liberar um APK que já poderia ser modificado de todo jeito.
     */
    fun fromPlay(ctx: Context): Boolean = runCatching {
        val pm = ctx.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(ctx.packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)
}
