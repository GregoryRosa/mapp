package br.com.mapp.data

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.Goals
import br.com.mapp.core.repo.VehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/*
 * Configurações pequenas (custos do veículo e metas) em SharedPreferences.
 * Doubles gravados como String para não perder precisão.
 * O arquivo é aberto (leitura de disco) só dentro de Dispatchers.IO.
 */

/** Emite [read] ao assinar e a cada mudança. [open] é chamado já em IO. */
private fun <T> observePrefs(
    open: () -> SharedPreferences,
    read: SharedPreferences.() -> T,
): Flow<T> = callbackFlow {
    val prefs = open()
    // Referência forte mantida até awaitClose (o SharedPreferences guarda listeners em WeakHashMap).
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> trySend(p.read()) }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    send(prefs.read())
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.flowOn(Dispatchers.IO).distinctUntilChanged()

private fun SharedPreferences.getDouble(key: String): Double? = getString(key, null)?.toDoubleOrNull()

private fun SharedPreferences.Editor.putDouble(key: String, value: Double?): SharedPreferences.Editor =
    if (value == null) remove(key) else putString(key, value.toString())

class PrefsVehicleRepository(context: Context) : VehicleRepository {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun config(): Flow<VehicleCostConfig> = observePrefs({ prefs }) {
        val d = VehicleCostConfig()
        VehicleCostConfig(
            fuelPricePerLiter = getDouble(FUEL) ?: d.fuelPricePerLiter,
            kmPerLiter = getDouble(KM_PER_LITER) ?: d.kmPerLiter,
            maintenancePerKm = getDouble(MAINTENANCE) ?: d.maintenancePerKm,
            depreciationPerKm = getDouble(DEPRECIATION) ?: d.depreciationPerKm,
        )
    }

    override suspend fun save(config: VehicleCostConfig) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putDouble(FUEL, config.fuelPricePerLiter)
                .putDouble(KM_PER_LITER, config.kmPerLiter)
                .putDouble(MAINTENANCE, config.maintenancePerKm)
                .putDouble(DEPRECIATION, config.depreciationPerKm)
                .commit()
        }
    }

    private companion object {
        const val FILE = "vehicle_cost"
        const val FUEL = "fuel_price_per_liter"
        const val KM_PER_LITER = "km_per_liter"
        const val MAINTENANCE = "maintenance_per_km"
        const val DEPRECIATION = "depreciation_per_km"
    }
}

/** Metas diária/mensal de ganhos (usado por [RoomFinanceRepository]). Valores <= 0 = sem meta. */
class GoalsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    fun goals(): Flow<Goals> = observePrefs({ prefs }) {
        Goals(daily = getDouble(DAILY), monthly = getDouble(MONTHLY))
    }

    suspend fun save(goals: Goals) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putDouble(DAILY, goals.daily?.takeIf { it > 0 })
                .putDouble(MONTHLY, goals.monthly?.takeIf { it > 0 })
                .commit()
        }
    }

    private companion object {
        const val FILE = "goals"
        const val DAILY = "daily"
        const val MONTHLY = "monthly"
    }
}
