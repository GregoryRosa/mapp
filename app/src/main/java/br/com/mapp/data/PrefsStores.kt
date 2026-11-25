package br.com.mapp.data

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.ExtraKmCostCodec
import br.com.mapp.core.model.FixedLedger
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.goal.WeekGoals
import br.com.mapp.core.model.KmBase
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
import java.time.LocalDate
import java.time.YearMonth

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

private fun SharedPreferences.getUnit(key: String): CostUnit? =
    getString(key, null)?.let { name -> CostUnit.entries.firstOrNull { it.name == name } }

private fun SharedPreferences.Editor.putDouble(key: String, value: Double?): SharedPreferences.Editor =
    if (value == null) remove(key) else putString(key, value.toString())

class PrefsVehicleRepository(context: Context) : VehicleRepository {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun config(): Flow<VehicleCostConfig> = observePrefs({ prefs }) {
        val d = VehicleCostConfig()
        VehicleCostConfig(
            fuelType = getString(FUEL_TYPE, null)
                ?.let { name -> FuelType.entries.firstOrNull { it.name == name } } ?: d.fuelType,
            fuelPricePerUnit = getDouble(FUEL) ?: d.fuelPricePerUnit,
            kmPerUnit = getDouble(KM_PER_LITER) ?: d.kmPerUnit,
            maintenance = getDouble(MAINTENANCE) ?: d.maintenance,
            depreciation = getDouble(DEPRECIATION) ?: d.depreciation,
            extraCosts = ExtraKmCostCodec.decode(getString(EXTRA_COSTS, null)),
            maintenanceUnit = getUnit(MAINTENANCE_UNIT) ?: d.maintenanceUnit,
            depreciationUnit = getUnit(DEPRECIATION_UNIT) ?: d.depreciationUnit,
            kmBase = KmBase(
                kmPerDay = getDouble(BASE_KM_PER_DAY) ?: d.kmBase.kmPerDay,
                daysPerMonth = getDouble(BASE_DAYS) ?: d.kmBase.daysPerMonth,
                auto = getBoolean(BASE_AUTO, d.kmBase.auto),
                ridesPerDay = getDouble(BASE_RIDES_PER_DAY) ?: d.kmBase.ridesPerDay,
            ),
            ledger = FixedLedger(
                today = getDouble(FIXED_TODAY),
                since = getString(FIXED_SINCE, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                settled = getString(FIXED_SETTLED, null)?.let { runCatching { YearMonth.parse(it) }.getOrNull() },
            ),
        )
    }

    override suspend fun save(config: VehicleCostConfig) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(FUEL_TYPE, config.fuelType.name)
                .putDouble(FUEL, config.fuelPricePerUnit)
                .putDouble(KM_PER_LITER, config.kmPerUnit)
                .putDouble(MAINTENANCE, config.maintenance)
                .putDouble(DEPRECIATION, config.depreciation)
                .putString(EXTRA_COSTS, ExtraKmCostCodec.encode(config.extraCosts))
                .putString(MAINTENANCE_UNIT, config.maintenanceUnit.name)
                .putString(DEPRECIATION_UNIT, config.depreciationUnit.name)
                .putDouble(BASE_KM_PER_DAY, config.kmBase.kmPerDay)
                .putDouble(BASE_DAYS, config.kmBase.daysPerMonth)
                .putBoolean(BASE_AUTO, config.kmBase.auto)
                .putDouble(BASE_RIDES_PER_DAY, config.kmBase.ridesPerDay)
                .commit()
        }
    }

    override suspend fun saveLedger(ledger: FixedLedger) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putDouble(FIXED_TODAY, ledger.today)
                .putString(FIXED_SINCE, ledger.since?.toString())
                .putString(FIXED_SETTLED, ledger.settled?.toString())
                .commit()
        }
    }

    private companion object {
        const val FILE = "vehicle_cost"
        const val FUEL_TYPE = "fuel_type"

        // Chaves antigas mantidas (valem por litro ou por kWh conforme o tipo; manutenção e
        // depreciação guardam o valor digitado, na unidade de MAINTENANCE_UNIT/DEPRECIATION_UNIT).
        const val FUEL = "fuel_price_per_liter"
        const val KM_PER_LITER = "km_per_liter"
        const val MAINTENANCE = "maintenance_per_km"
        const val DEPRECIATION = "depreciation_per_km"
        const val EXTRA_COSTS = "extra_costs_per_km"
        const val MAINTENANCE_UNIT = "maintenance_unit"
        const val DEPRECIATION_UNIT = "depreciation_unit"
        const val BASE_KM_PER_DAY = "base_km_per_day"
        const val BASE_DAYS = "base_days_per_month"
        const val BASE_AUTO = "base_auto"
        const val BASE_RIDES_PER_DAY = "base_rides_per_day"

        // Rateio dos custos fixos (core/shift/FixedPlan): fora do formulário, gravado por saveLedger.
        const val FIXED_TODAY = "fixed_per_ride_today"
        const val FIXED_SINCE = "fixed_since"
        const val FIXED_SETTLED = "fixed_settled_month"
    }
}

/**
 * Metas de ganhos (usado por [RoomFinanceRepository]): meta geral do dia, meta mensal e a meta de
 * cada dia da semana (`WeekGoals`). Valores <= 0 = sem meta.
 */
class GoalsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    fun goals(): Flow<Goals> = observePrefs({ prefs }) {
        Goals(
            daily = getDouble(DAILY),
            monthly = getDouble(MONTHLY),
            byWeekday = WeekGoals.decode(getString(BY_WEEKDAY, null)),
        )
    }

    suspend fun save(goals: Goals) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putDouble(DAILY, goals.daily?.takeIf { it > 0 })
                .putDouble(MONTHLY, goals.monthly?.takeIf { it > 0 })
                .putString(BY_WEEKDAY, WeekGoals.encode(goals.byWeekday))
                .commit()
        }
    }

    private companion object {
        const val FILE = "goals"
        const val DAILY = "daily"
        const val MONTHLY = "monthly"
        const val BY_WEEKDAY = "by_weekday"
    }
}
