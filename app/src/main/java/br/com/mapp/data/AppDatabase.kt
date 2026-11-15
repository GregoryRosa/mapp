package br.com.mapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.com.mapp.core.model.Platform
import br.com.mapp.reader.parser.OfferParsers

@Database(
    entities = [ProfileEntity::class, ZoneEntity::class, OfferEntity::class, FinanceEntryEntity::class],
    version = 3,
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

        /**
         * v3: tipo da corrida (UberX, Comfort...) gravado com a oferta. As ofertas antigas recuperam o
         * tipo pelo texto lido da tela (rawText), com a mesma regra do leitor.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offers ADD COLUMN category TEXT")
                val found = mutableListOf<Pair<Long, String>>()
                db.query("SELECT id, platform, rawText FROM offers").use { c ->
                    while (c.moveToNext()) {
                        val category = runCatching {
                            OfferParsers.forPlatform(Platform.valueOf(c.getString(1))).categoryIn(c.getString(2) ?: "")
                        }.getOrNull()
                        if (category != null) found += c.getLong(0) to category
                    }
                }
                found.forEach { (id, category) ->
                    db.execSQL("UPDATE offers SET category = ? WHERE id = ?", arrayOf<Any>(category, id))
                }
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /** Instância única. Consultas rodam nos executores do Room (nunca na main thread). */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
