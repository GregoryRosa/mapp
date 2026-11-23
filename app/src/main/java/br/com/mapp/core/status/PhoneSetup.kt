package br.com.mapp.core.status

/**
 * Família do fabricante pelo `Build.MANUFACTURER`/`Build.BRAND`: decide se o celular tem o ajuste de
 * **início automático** (sem ele, o sistema mata o MApp em segundo plano e o copiloto/leitor não voltam).
 */
enum class PhoneBrand {
    XIAOMI, // Xiaomi, Redmi, POCO (MIUI/HyperOS)
    OPPO, // OPPO, realme, OnePlus (ColorOS / realme UI / OxygenOS novo)
    VIVO, // vivo, iQOO
    HUAWEI,
    HONOR,
    ASUS,
    LETV,
    OTHER; // Samsung, Motorola, Pixel...: não têm a lista de início automático

    companion object {
        fun of(manufacturer: String?, brand: String? = null): PhoneBrand {
            val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
            fun any(vararg keys: String) = names.any { n -> keys.any { n.contains(it) } }
            return when {
                any("xiaomi", "redmi", "poco") -> XIAOMI
                any("oppo", "realme", "oneplus", "oplus") -> OPPO
                any("vivo", "iqoo") -> VIVO
                any("huawei") -> HUAWEI
                any("honor") -> HONOR
                any("asus") -> ASUS
                any("letv", "leeco") -> LETV
                else -> OTHER
            }
        }
    }
}

/** Tela de configurações de outro app (pacote + classe), aberta pelo nome. */
data class SettingsTarget(val pkg: String, val cls: String)

/**
 * Ajustes do celular que a tela inicial pede além das permissões do Android (cartão *Permissões*).
 * O Android não informa se o início automático está ligado: o motorista abre a tela e confirma.
 */
object PhoneSetup {

    fun needsAutoLaunch(brand: PhoneBrand): Boolean = brand != PhoneBrand.OTHER

    /** Telas de início automático conhecidas, da mais nova para a mais antiga: abre a 1ª que existir. */
    fun autoLaunchScreens(brand: PhoneBrand): List<SettingsTarget> = when (brand) {
        PhoneBrand.XIAOMI -> listOf(
            SettingsTarget("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        PhoneBrand.OPPO -> listOf(
            SettingsTarget("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
            SettingsTarget("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            SettingsTarget("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            SettingsTarget("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            SettingsTarget("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        )
        PhoneBrand.VIVO -> listOf(
            SettingsTarget("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            SettingsTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            SettingsTarget("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        )
        PhoneBrand.HUAWEI -> listOf(
            SettingsTarget("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            SettingsTarget("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        PhoneBrand.HONOR -> listOf(
            SettingsTarget("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
        PhoneBrand.ASUS -> listOf(
            SettingsTarget("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"),
        )
        PhoneBrand.LETV -> listOf(
            SettingsTarget("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        )
        PhoneBrand.OTHER -> emptyList()
    }

    /** Onde fica, em palavras (se nenhuma tela conhecida abrir, o botão abre as informações do app). */
    fun autoLaunchPath(brand: PhoneBrand): String = when (brand) {
        PhoneBrand.XIAOMI -> "Configurações → Apps → Gerenciar apps → MApp → ligue \"Início automático\"."
        PhoneBrand.OPPO -> "Configurações → Apps → Início automático (\"Início automático em segundo plano\") → ligue o MApp."
        PhoneBrand.VIVO -> "Configurações → Apps → Início automático (ou i Manager → Início automático) → ligue o MApp."
        PhoneBrand.HUAWEI, PhoneBrand.HONOR ->
            "Configurações → Bateria → Inicialização de apps → MApp → \"Gerenciar manualmente\" com as 3 opções ligadas."
        PhoneBrand.ASUS -> "Mobile Manager → Gerenciador de inicialização automática → permita o MApp."
        PhoneBrand.LETV -> "Configurações → Gerenciador de inicialização automática → permita o MApp."
        PhoneBrand.OTHER -> ""
    }

    /** Por que pedir: aparece embaixo do item enquanto ele não está feito. */
    const val AUTO_LAUNCH_WHY =
        "Sem isso o celular fecha o MApp em segundo plano e o copiloto não liga sozinho com a Uber online. " +
            "Trave também o MApp na tela de apps recentes (cadeado): fechar ele ali desliga a Acessibilidade."

    const val UNUSED_PATH =
        "Em Informações do app (botão Abrir) → Permissões, deixe desmarcado \"Pausar atividade do app quando sem uso\". " +
            "Marcado, o Android tira as permissões do MApp e para o leitor se ele ficar uns dias sem ser aberto."
}
