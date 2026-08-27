package it.zawardo.treni.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        SearchHistoryEntity::class,
        SavedSearchEntity::class,
        StationEntity::class,
        FavoriteTrainEntity::class,
        RecentTrainEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class TreniDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun stationDao(): StationDao
    abstract fun favoriteTrainDao(): FavoriteTrainDao
    abstract fun recentTrainDao(): RecentTrainDao

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

        /**
         * v3 aggiunge i treni preferiti.
         *
         * Il CREATE TABLE e' copiato dallo schema esportato da Room in
         * `app/schemas/.../3.json`: scriverlo a mano porta a differenze
         * invisibili (un NOT NULL, un DEFAULT) che fanno fallire la validazione
         * all'avvio, non in compilazione.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_trains` (" +
                        "`number` TEXT NOT NULL, " +
                        "`label` TEXT, " +
                        "`origin_name` TEXT, " +
                        "`destination_name` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`number`))",
                )
            }
        }

        /** v4 aggiunge i treni aperti di recente, per l'autocompletamento. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_trains` (" +
                        "`number` TEXT NOT NULL, " +
                        "`label` TEXT, " +
                        "`origin_name` TEXT, " +
                        "`destination_name` TEXT, " +
                        "`opened_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`number`))",
                )
            }
        }

        @Volatile private var instance: TreniDatabase? = null

        fun get(context: Context): TreniDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TreniDatabase::class.java,
                "treni.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
