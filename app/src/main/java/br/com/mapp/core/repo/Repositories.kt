package br.com.mapp.core.repo

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.model.Zone
import kotlinx.coroutines.flow.Flow

/*
 * Contratos de dados. Implementados em br.com.mapp.data (subagente "financeiro").
 * Consumidos por overlay/, maps/, finance/ e ui/. Não altere sem atualizar CLAUDE.md.
 */

interface ProfileRepository {
    fun all(): Flow<List<FilterProfile>>
    /** Emite o perfil ativo; se não houver nenhum, emite FilterProfile() padrão. */
    fun active(): Flow<FilterProfile>
    suspend fun save(profile: FilterProfile): Long
    suspend fun setActive(id: Long)
    suspend fun delete(id: Long)
}

interface ZoneRepository {
    fun all(): Flow<List<Zone>>
    suspend fun save(zone: Zone): Long
    suspend fun delete(id: Long)
}

interface VehicleRepository {
    fun config(): Flow<VehicleCostConfig>
    suspend fun save(config: VehicleCostConfig)
}

/** Registro de oferta vista (histórico). */
data class OfferRecord(
    val id: Long,
    val evaluated: EvaluatedOffer,
    val accepted: Boolean?,
)

interface OfferHistoryRepository {
    suspend fun record(offer: EvaluatedOffer): Long
    fun recent(limit: Int = 200): Flow<List<OfferRecord>>
    fun between(fromMillis: Long, toMillis: Long): Flow<List<OfferRecord>>
    /**
     * Marca a oferta (identificada por raw.capturedAt + preço) como aceita ou não (null = sem informação).
     * Se ela ainda não foi gravada, a marca vale quando [record] for chamado.
     */
    suspend fun setAccepted(offer: EvaluatedOffer, accepted: Boolean?)
    /** Marca pelo id do registro (ajuste manual na tela de histórico). */
    suspend fun setAcceptedById(id: Long, accepted: Boolean?)
}

enum class EntryKind { INCOME, EXPENSE }

/** Lançamento financeiro. Ganho por plataforma ou gasto (combustível, manutenção, etc.). */
data class FinanceEntry(
    val id: Long = 0,
    val kind: EntryKind,
    val category: String,
    val amount: Double,
    val platform: Platform? = null,
    val km: Double? = null,
    val note: String? = null,
    val atMillis: Long = System.currentTimeMillis(),
)

data class Goals(val daily: Double? = null, val monthly: Double? = null)

interface FinanceRepository {
    fun entries(fromMillis: Long, toMillis: Long): Flow<List<FinanceEntry>>
    suspend fun save(entry: FinanceEntry): Long
    suspend fun delete(id: Long)
    fun goals(): Flow<Goals>
    suspend fun saveGoals(goals: Goals)
}

/** Agrupa todos os repositórios. Criado em br.com.mapp.data.DataModule.create(context). */
data class Repositories(
    val profiles: ProfileRepository,
    val zones: ZoneRepository,
    val vehicle: VehicleRepository,
    val offers: OfferHistoryRepository,
    val finance: FinanceRepository,
)
