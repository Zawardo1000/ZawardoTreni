package it.zawardo.treni.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SearchHistoryEntity::class, SavedSearchEntity::class, StationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TreniDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedSearchDao(): SavedSearchDao
    abstract fun stationDao(): StationDao

    companion object {
        @Volatile private var instance: TreniDatabase? = null

        fun get(context: Context): TreniDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TreniDatabase::class.java,
                "treni.db",
            ).build().also { instance = it }
        }
    }
}
