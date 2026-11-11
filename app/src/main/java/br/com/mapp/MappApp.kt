package br.com.mapp

import android.app.Application
import br.com.mapp.core.repo.Repositories
import br.com.mapp.data.DataModule
import org.osmdroid.config.Configuration

class MappApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        repos = DataModule.create(this)
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
