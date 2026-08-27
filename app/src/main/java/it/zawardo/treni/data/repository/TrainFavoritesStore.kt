package it.zawardo.treni.data.repository

import it.zawardo.treni.data.local.FavoriteTrainDao
import it.zawardo.treni.data.local.FavoriteTrainEntity
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.flow.Flow

/**
 * I treni preferiti: quelli che si prendono tutti i giorni e che non ha senso
 * ridigitare ogni volta.
 */
class TrainFavoritesStore(private val dao: FavoriteTrainDao) {

    fun observe(): Flow<List<FavoriteTrainEntity>> = dao.observe()

    fun isFavorite(number: String): Flow<Boolean> = dao.isFavorite(number)

    /**
     * Aggiunge o toglie, a seconda di com'era. La schermata non deve sapere in
     * che stato si trova: preme la stellina e basta.
     */
    suspend fun toggle(number: String, favorite: Boolean, status: TrainStatus?, now: Long) {
        if (favorite) {
            dao.insert(
                FavoriteTrainEntity(
                    number = number,
                    label = status?.label,
                    originName = status?.origin,
                    destinationName = status?.destination,
                    createdAt = now,
                ),
            )
        } else {
            dao.delete(number)
        }
    }
}
