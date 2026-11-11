package br.com.mapp.data

import android.content.Context
import br.com.mapp.core.repo.Repositories

/** Monta as implementações de core/repo. Chamado uma vez em MappApp.onCreate. */
object DataModule {
    fun create(context: Context): Repositories {
        val app = context.applicationContext
        val db = AppDatabase.get(app)
        return Repositories(
            profiles = RoomProfileRepository(db.profileDao()),
            zones = RoomZoneRepository(db.zoneDao()),
            vehicle = PrefsVehicleRepository(app),
            offers = RoomOfferHistoryRepository(db.offerDao()),
            finance = RoomFinanceRepository(db.financeDao(), GoalsStore(app)),
        )
    }
}
