package br.com.mapp.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import br.com.mapp.core.status.PhoneBrand
import br.com.mapp.core.status.PhoneSetup

/**
 * Ajustes do celular pedidos no cartão *Permissões* da tela inicial além das permissões do Android:
 * **início automático** (fabricante) e **"Pausar atividade do app quando sem uso"** desmarcado.
 * As regras e textos ficam em `core/status/PhoneSetup`; aqui só abrir as telas e guardar a confirmação.
 */
object PhoneSettings {

    private const val TAG = "MApp/PhoneSettings"
    private const val PREFS = "mapp_setup"
    private const val KEY_AUTO_LAUNCH_DONE = "auto_launch_done"
    private const val KEY_UNUSED_DONE = "unused_pause_done"

    val brand: PhoneBrand get() = PhoneBrand.of(Build.MANUFACTURER, Build.BRAND)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** O Android não informa o início automático: vale a confirmação do motorista ("Já fiz"). */
    fun autoLaunchConfirmed(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_LAUNCH_DONE, false)

    fun setAutoLaunchConfirmed(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_LAUNCH_DONE, done).apply()
    }

    /**
     * "Pausar atividade do app quando sem uso" desmarcado? Android 11+ informa ([isAutoRevokeWhitelisted]);
     * alguns celulares deixam a chave cinza e desligada sem informar — por isso também vale o "Já fiz".
     */
    fun unusedPauseOff(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true // não existe antes do Android 11
        if (prefs(ctx).getBoolean(KEY_UNUSED_DONE, false)) return true
        return runCatching { ctx.packageManager.isAutoRevokeWhitelisted() }.getOrDefault(false)
    }

    fun setUnusedPauseConfirmed(ctx: Context, done: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_UNUSED_DONE, done).apply()
    }

    /** O item "Pausar atividade…" existe neste Android? */
    val hasUnusedPause: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Abre a lista de início automático do fabricante; sem nenhuma conhecida, as informações do app. */
    fun openAutoLaunch(ctx: Context) {
        for (screen in PhoneSetup.autoLaunchScreens(brand)) {
            val intent = Intent()
                .setComponent(ComponentName(screen.pkg, screen.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                // Tela não existe nesta versão do sistema (ou não pode ser aberta por outro app): tenta a próxima.
                Log.d(TAG, "Início automático: ${screen.cls} indisponível (${e.javaClass.simpleName})")
            }
        }
        openAppInfo(ctx)
    }

    /** Informações do app: é onde fica "Pausar atividade do app quando sem uso" (e Bateria/Início automático em vários celulares). */
    fun openAppInfo(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Não abriu as informações do app", e)
        }
    }
}
