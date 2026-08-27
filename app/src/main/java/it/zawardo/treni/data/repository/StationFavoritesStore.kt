package it.zawardo.treni.data.repository

import it.zawardo.treni.data.local.FavoriteStationDao
import it.zawardo.treni.data.local.FavoriteStationEntity
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Le stazioni preferite: quelle di cui si guarda il tabellone tutti i giorni e
 * che non ha senso ridigitare ogni volta.
 */
class StationFavoritesStore(private val dao: FavoriteStationDao) {

    fun observe(): Flow<List<Station>> = dao.observe().map { righe ->
        righe.map { Station(rfiCode = it.rfiCode, locationId = it.locationId, name = it.name) }
    }

    /**
     * Aggiunge o toglie, a seconda di com'era.
     *
     * Senza codice RFI non c'e' niente da preferire: quella stazione un
     * tabellone non ce l'ha, e la stellina infatti non compare.
     */
    suspend fun toggle(station: Station, favorite: Boolean, now: Long) {
        val code = station.rfiCode?.takeIf { it.isNotBlank() } ?: return
        if (favorite) {
            dao.insert(
                FavoriteStationEntity(
                    rfiCode = code,
                    locationId = station.locationId,
                    name = station.name,
                    createdAt = now,
                ),
            )
        } else {
            dao.delete(code)
        }
    }

    suspend fun remove(rfiCode: String) = dao.delete(rfiCode)
}
