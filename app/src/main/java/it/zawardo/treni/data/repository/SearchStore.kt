package it.zawardo.treni.data.repository

import it.zawardo.treni.data.local.SavedSearchDao
import it.zawardo.treni.data.local.SavedSearchEntity
import it.zawardo.treni.data.local.SearchHistoryDao
import it.zawardo.treni.data.local.SearchHistoryEntity
import it.zawardo.treni.data.local.StationDao
import it.zawardo.treni.data.local.StationEntity
import it.zawardo.treni.data.local.StationRef
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.flow.Flow
import java.text.Normalizer

/** Cronologia e ricerche salvate. */
class SearchStore(
    private val history: SearchHistoryDao,
    private val saved: SavedSearchDao,
    private val stations: StationDao,
) {
    val recentSearches: Flow<List<SearchHistoryEntity>> = history.observe()
    val savedSearches: Flow<List<SavedSearchEntity>> = saved.observe()

    suspend fun lastSearch(): Pair<Station, Station>? =
        history.mostRecent()?.let { it.from.toStation() to it.to.toStation() }

    suspend fun record(from: Station, to: Station) {
        history.record(
            SearchHistoryEntity(
                from = StationRef.of(from),
                to = StationRef.of(to),
                searchedAt = System.currentTimeMillis(),
            )
        )
        // Alimenta la cache locale: le stazioni usate risalgono nei suggerimenti.
        cache(from)
        cache(to)
    }

    suspend fun deleteHistory(id: Long) = history.delete(id)
    suspend fun clearHistory() = history.clear()

    suspend fun save(from: Station, to: Station, label: String? = null): Long =
        saved.insert(
            SavedSearchEntity(
                from = StationRef.of(from),
                to = StationRef.of(to),
                label = label?.takeIf { it.isNotBlank() } ?: "${from.name} → ${to.name}",
                createdAt = System.currentTimeMillis(),
            )
        )

    suspend fun isSaved(from: Station, to: Station): Boolean =
        saved.countFor(from.locationId, to.locationId) > 0

    suspend fun deleteSaved(id: Long) = saved.delete(id)
    suspend fun renameSaved(id: Long, label: String) = saved.rename(id, label)

    /** Suggerimenti offline, serviti prima della chiamata di rete. */
    suspend fun suggestOffline(query: String): List<Station> =
        stations.search(normalize(query)).map {
            Station(it.rfiCode, it.locationId, it.name, it.latitude, it.longitude)
        }

    suspend fun cache(station: Station) {
        stations.insertAll(
            listOf(
                StationEntity(
                    locationId = station.locationId,
                    rfiCode = station.rfiCode,
                    name = station.name,
                    nameNormalized = normalize(station.name),
                    latitude = station.latitude,
                    longitude = station.longitude,
                )
            )
        )
        stations.markUsed(station.locationId)
    }

    suspend fun cacheAll(list: List<Station>) {
        stations.insertAll(
            list.map {
                StationEntity(
                    locationId = it.locationId,
                    rfiCode = it.rfiCode,
                    name = it.name,
                    nameNormalized = normalize(it.name),
                    latitude = it.latitude,
                    longitude = it.longitude,
                )
            }
        )
    }

    private companion object {
        /** Segni diacritici lasciati indietro dalla decomposizione NFD. */
        private val NON_SPACING_MARKS = Regex("""\p{Mn}+""")

        /** "Firenze S. M. Novella" -> "firenze s. m. novella"; toglie anche gli accenti. */
        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace(NON_SPACING_MARKS, "")
                .trim()
    }
}
