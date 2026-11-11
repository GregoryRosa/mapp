package br.com.mapp.data

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.Zone
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.FinanceRepository
import br.com.mapp.core.repo.Goals
import br.com.mapp.core.repo.OfferHistoryRepository
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.repo.ProfileRepository
import br.com.mapp.core.repo.ZoneRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Implementações Room dos contratos de core/repo. Flows do Room e funções suspend rodam
 * nos executores do próprio Room, nunca na main thread.
 */

class RoomProfileRepository(private val dao: ProfileDao) : ProfileRepository {
    override fun all(): Flow<List<FilterProfile>> =
        dao.all().map { list -> list.map { it.toModel() } }

    override fun active(): Flow<FilterProfile> =
        dao.active().map { it?.toModel() ?: FilterProfile() }.distinctUntilChanged()

    override suspend fun save(profile: FilterProfile): Long = dao.save(profile.toEntity())

    override suspend fun setActive(id: Long) = dao.setActive(id)

    override suspend fun delete(id: Long) = dao.delete(id)
}

class RoomZoneRepository(private val dao: ZoneDao) : ZoneRepository {
    override fun all(): Flow<List<Zone>> =
        dao.all().map { list -> list.map { it.toModel() } }

    override suspend fun save(zone: Zone): Long {
        val entity = zone.toEntity()
        if (entity.id == 0L) return dao.insert(entity)
        return if (dao.update(entity) > 0) entity.id else dao.insert(entity)
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

class RoomOfferHistoryRepository(private val dao: OfferDao) : OfferHistoryRepository {
    override suspend fun record(offer: EvaluatedOffer): Long = dao.insert(offer.toEntity())

    override fun recent(limit: Int): Flow<List<OfferRecord>> =
        dao.recent(limit).map { list -> list.map { it.toRecord() } }

    /** Intervalo inclusivo [fromMillis, toMillis]. */
    override fun between(fromMillis: Long, toMillis: Long): Flow<List<OfferRecord>> =
        dao.between(fromMillis, toMillis).map { list -> list.map { it.toRecord() } }
}

class RoomFinanceRepository(
    private val dao: FinanceDao,
    private val goalsStore: GoalsStore,
) : FinanceRepository {
    /** Intervalo inclusivo [fromMillis, toMillis], mais recentes primeiro. */
    override fun entries(fromMillis: Long, toMillis: Long): Flow<List<FinanceEntry>> =
        dao.between(fromMillis, toMillis).map { list -> list.map { it.toModel() } }

    override suspend fun save(entry: FinanceEntry): Long {
        val entity = entry.toEntity()
        if (entity.id == 0L) return dao.insert(entity)
        return if (dao.update(entity) > 0) entity.id else dao.insert(entity)
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    override fun goals(): Flow<Goals> = goalsStore.goals()

    override suspend fun saveGoals(goals: Goals) = goalsStore.save(goals)
}
