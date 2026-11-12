package br.com.mapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY name COLLATE NOCASE, id")
    abstract fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE active = 1 ORDER BY id LIMIT 1")
    abstract fun active(): Flow<ProfileEntity?>

    @Insert
    abstract suspend fun insert(entity: ProfileEntity): Long

    @Update
    abstract suspend fun update(entity: ProfileEntity): Int

    @Query("UPDATE profiles SET active = 0")
    abstract suspend fun clearActive()

    @Query("UPDATE profiles SET active = 1 WHERE id = :id")
    abstract suspend fun markActive(id: Long)

    @Query("DELETE FROM profiles WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM profiles WHERE active = 1")
    abstract suspend fun activeCount(): Int

    @Query("SELECT id FROM profiles ORDER BY id LIMIT 1")
    abstract suspend fun firstId(): Long?

    /** Desmarca todos e ativa só [id]. */
    @Transaction
    open suspend fun setActive(id: Long) {
        clearActive()
        markActive(id)
    }

    /** Insere (id = 0) ou atualiza. Se o perfil vier ativo, ele passa a ser o único ativo. */
    @Transaction
    open suspend fun save(entity: ProfileEntity): Long {
        val id = if (entity.id == 0L) {
            insert(entity)
        } else {
            if (update(entity) == 0) insert(entity) else entity.id
        }
        if (entity.active) {
            clearActive()
            markActive(id)
        }
        return id
    }

    /** Exclui e, se não sobrar perfil ativo, ativa o primeiro restante. */
    @Transaction
    open suspend fun delete(id: Long) {
        deleteById(id)
        if (activeCount() == 0) firstId()?.let { markActive(it) }
    }
}

@Dao
interface ZoneDao {
    @Query("SELECT * FROM zones ORDER BY name COLLATE NOCASE, id")
    fun all(): Flow<List<ZoneEntity>>

    @Insert
    suspend fun insert(entity: ZoneEntity): Long

    @Update
    suspend fun update(entity: ZoneEntity): Int

    @Query("DELETE FROM zones WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OfferDao {
    @Insert
    suspend fun insert(entity: OfferEntity): Long

    @Query("SELECT * FROM offers ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<OfferEntity>>

    @Query("SELECT * FROM offers WHERE capturedAt BETWEEN :fromMillis AND :toMillis ORDER BY capturedAt DESC, id DESC")
    fun between(fromMillis: Long, toMillis: Long): Flow<List<OfferEntity>>

    @Query("UPDATE offers SET accepted = :accepted WHERE capturedAt = :capturedAt AND price = :price")
    suspend fun setAccepted(capturedAt: Long, price: Double, accepted: Boolean?): Int

    @Query("UPDATE offers SET accepted = :accepted WHERE id = :id")
    suspend fun setAcceptedById(id: Long, accepted: Boolean?)
}

@Dao
interface FinanceDao {
    @Query("SELECT * FROM finance_entries WHERE atMillis BETWEEN :fromMillis AND :toMillis ORDER BY atMillis DESC, id DESC")
    fun between(fromMillis: Long, toMillis: Long): Flow<List<FinanceEntryEntity>>

    @Insert
    suspend fun insert(entity: FinanceEntryEntity): Long

    @Update
    suspend fun update(entity: FinanceEntryEntity): Int

    @Query("DELETE FROM finance_entries WHERE id = :id")
    suspend fun delete(id: Long)
}
