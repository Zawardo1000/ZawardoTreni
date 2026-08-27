package it.zawardo.treni.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT :limit")
    fun observe(limit: Int = HISTORY_LIMIT): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT 1")
    suspend fun mostRecent(): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchHistoryEntity)

    /** Taglia la coda oltre il limite; l'indice univoco ha gia' deduplicato. */
    @Query(
        """
        DELETE FROM search_history WHERE id NOT IN (
            SELECT id FROM search_history ORDER BY searched_at DESC LIMIT :limit
        )
        """
    )
    suspend fun trim(limit: Int = HISTORY_LIMIT)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    /**
     * REPLACE sull'indice univoco riusa la riga esistente aggiornando il timestamp,
     * quindi ricercare una tratta gia' fatta la riporta in cima senza duplicarla.
     */
    @Transaction
    suspend fun record(entity: SearchHistoryEntity) {
        insert(entity)
        trim()
    }

    companion object { const val HISTORY_LIMIT = 10 }
}

@Dao
interface SavedSearchDao {
    @Query("SELECT * FROM saved_searches ORDER BY created_at DESC")
    fun observe(): Flow<List<SavedSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SavedSearchEntity): Long

    @Query("DELETE FROM saved_searches WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE saved_searches SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("SELECT COUNT(*) FROM saved_searches WHERE from_location_id = :from AND to_location_id = :to")
    suspend fun countFor(from: Long, to: Long): Int
}

@Dao
interface StationDao {
    /**
     * Le stazioni gia' usate hanno priorita', poi conta dove cade il match:
     * "bologna" deve proporre "Bologna Centrale" prima di "Casalecchio di Bologna".
     */
    @Query(
        """
        SELECT * FROM stations
        WHERE name_normalized LIKE :prefix || '%' OR name_normalized LIKE '% ' || :prefix || '%'
        ORDER BY use_count DESC,
                 CASE WHEN name_normalized LIKE :prefix || '%' THEN 0 ELSE 1 END,
                 LENGTH(name)
        LIMIT :limit
        """
    )
    suspend fun search(prefix: String, limit: Int = 12): List<StationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<StationEntity>)

    @Query("UPDATE stations SET use_count = use_count + 1 WHERE location_id = :locationId")
    suspend fun markUsed(locationId: Long)

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int
}
