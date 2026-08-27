package it.zawardo.treni.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [SearchHistoryEntity::class, SavedSearchEntity::class, StationEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TreniDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun stationDao(): StationDao

    companion object {
        /**
         * v2 aggiunge l'orario alle ricerche salvate.
         *
         * Migrazione vera e non `fallbackToDestructiveMigration`: l'app e' gia'
         * installata e cancellare cronologia e salvate di chi la usa sarebbe
         * un prezzo assurdo per una colonna in piu'.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                // Niente clausola DEFAULT: Room confronta anche i default con lo
                // schema atteso, e l'entita' non ne dichiara. Con "DEFAULT NULL"
                // la validazione fallirebbe all'avvio con un mismatch di schema.
                connection.execSQL(
                    "ALTER TABLE saved_searches ADD COLUMN time_minutes INTEGER",
                )
            }
        }

        @Volatile private var instance: TreniDatabase? = null

        fun get(context: Context): TreniDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TreniDatabase::class.java,
                "treni.db",
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
