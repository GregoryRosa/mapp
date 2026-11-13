package br.com.mapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProfileEntity::class, ZoneEntity::class, OfferEntity::class, FinanceEntryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun zoneDao(): ZoneDao
    abstract fun offerDao(): OfferDao
    abstract fun financeDao(): FinanceDao

    companion object {
        private const val NAME = "mapp.db"

        /** v2: faixa verde de valor, nota e busca nos perfis (colunas novas vazias = como antes). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN goodPrice REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goodRating REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goodPickupKm REAL")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goodPickupMin INTEGER")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** Instância única. Consultas rodam nos executores do Room (nunca na main thread). */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
