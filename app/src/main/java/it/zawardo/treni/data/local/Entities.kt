package it.zawardo.treni.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import it.zawardo.treni.domain.model.Station

/** Stazione denormalizzata dentro una ricerca: sopravvive anche se il BFF cambia gli id. */
data class StationRef(
    @ColumnInfo(name = "rfi_code") val rfiCode: String?,
    @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "name") val name: String,
) {
    fun toStation() = Station(rfiCode = rfiCode, locationId = locationId, name = name)

    companion object {
        fun of(s: Station) = StationRef(s.rfiCode, s.locationId, s.name)
    }
}

/**
 * Cronologia: le ultime 10 ricerche, deduplicate sulla coppia partenza/arrivo.
 * L'indice univoco fa il lavoro di deduplicazione al posto nostro.
 */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["from_location_id", "to_location_id"], unique = true)],
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded(prefix = "from_") val from: StationRef,
    @Embedded(prefix = "to_") val to: StationRef,
    @ColumnInfo(name = "searched_at") val searchedAt: Long,
)

/** Ricerche salvate a mano dall'utente: nessun limite, nessuna scadenza. */
@Entity(tableName = "saved_searches")
data class SavedSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @Embedded(prefix = "from_") val from: StationRef,
    @Embedded(prefix = "to_") val to: StationRef,
    val label: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Cache stazioni per l'autocompletamento offline.
 * [nameNormalized] e' minuscolo e senza accenti: permette il LIKE senza COLLATE.
 */
@Entity(
    tableName = "stations",
    indices = [Index(value = ["name_normalized"])],
)
data class StationEntity(
    @PrimaryKey @ColumnInfo(name = "location_id") val locationId: Long,
    @ColumnInfo(name = "rfi_code") val rfiCode: String?,
    val name: String,
    @ColumnInfo(name = "name_normalized") val nameNormalized: String,
    val latitude: Double,
    val longitude: Double,
    /** Ordinamento: le stazioni gia' usate salgono in cima ai suggerimenti. */
    @ColumnInfo(name = "use_count") val useCount: Int = 0,
)
