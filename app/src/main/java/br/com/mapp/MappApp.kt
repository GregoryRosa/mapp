package br.com.mapp

import android.app.Application
import br.com.mapp.billing.PlayBilling
import br.com.mapp.core.repo.Repositories
import br.com.mapp.data.DataModule
import br.com.mapp.finance.FixedCostsSync
import br.com.mapp.overlay.CopilotAutoStart
import org.osmdroid.config.Configuration

class MappApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        repos = DataModule.create(this)
        // Assinatura: lê o cache do disco e já conecta na Google Play (o resultado chega em segundos).
        PlayBilling.start(this)
        // Uber aberta e online → liga o copiloto sozinho (escuta o leitor desde a abertura do processo).
        CopilotAutoStart.watch(this)
        // Custos fixos: fecha o mês que acabou e acerta o R$ por corrida do dia (o que falta ÷ corridas que faltam).
        FixedCostsSync.refresh(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid-tiles")
        }
    }

    companion object {
        lateinit var instance: MappApp
            private set

        /** Acesso global aos repositórios (service locator simples, app pessoal). */
        lateinit var repos: Repositories
            private set
    }
}
