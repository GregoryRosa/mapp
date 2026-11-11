package br.com.mapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProfileEntity::class, ZoneEntity::class, OfferEntity::class, FinanceEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun zoneDao(): ZoneDao
    abstract fun offerDao(): OfferDao
    abstract fun financeDao(): FinanceDao

    companion object {
        private const val NAME = "mapp.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** Instância única. Consultas rodam nos executores do Room (nunca na main thread). */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .build()
                    .also { instance = it }
            }
    }
}
