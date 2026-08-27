package it.zawardo.treni.data.repository

import it.zawardo.treni.data.local.FavoriteTrainDao
import it.zawardo.treni.data.local.FavoriteTrainEntity
import it.zawardo.treni.data.local.RecentTrainDao
import it.zawardo.treni.data.local.RecentTrainEntity
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.flow.Flow

/** Un treno che l'app propone mentre si digita, con l'origine del suggerimento. */
data class TrainSuggestion(
    val number: String,
    val label: String?,
    val originName: String?,
    val destinationName: String?,
    val favorite: Boolean,
) {
    val description: String?
        get() = listOfNotNull(originName, destinationName)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" → ")
}

/**
 * Quello che l'app ricorda dei treni: i preferiti scelti a mano e le corse
 * aperte di recente.
 *
 * Stanno insieme perche' servono alla stessa cosa, non ridigitare un numero, e
 * perche' l'autocompletamento li mescola: un preferito e' solo un recente che
 * non scade.
 */
class TrainMemoryStore(
    private val favorites: FavoriteTrainDao,
    private val recents: RecentTrainDao,
) {

    fun observeFavorites(): Flow<List<FavoriteTrainEntity>> = favorites.observe()

    fun isFavorite(number: String): Flow<Boolean> = favorites.isFavorite(number)

    /**
     * Aggiunge o toglie, a seconda di com'era. La schermata non deve sapere in
     * che stato si trova: preme la stellina e basta.
     */
    suspend fun toggleFavorite(
        number: String,
        favorite: Boolean,
        status: TrainStatus?,
        now: Long,
    ) {
        if (favorite) {
            favorites.insert(
                FavoriteTrainEntity(
                    number = number,
                    label = status?.label,
                    originName = status?.origin,
                    destinationName = status?.destination,
                    createdAt = now,
                ),
            )
        } else {
            favorites.delete(number)
        }
    }

    /** Chiamata all'apertura di una corsa: e' la sola cosa che alimenta i recenti. */
    suspend fun recordOpened(number: String, status: TrainStatus?, now: Long) {
        recents.record(
            RecentTrainEntity(
                number = number,
                label = status?.label,
                originName = status?.origin,
                destinationName = status?.destination,
                openedAt = now,
            ),
        )
    }

    /**
     * Suggerimenti per un prefisso.
     *
     * I preferiti vengono prima e vincono sui recenti a parita' di numero: sono
     * una scelta esplicita, il recente e' solo un passaggio.
     */
    suspend fun suggest(prefix: String, favoriti: List<FavoriteTrainEntity>): List<TrainSuggestion> {
        val stellati = favoriti
            .filter { it.number.startsWith(prefix) }
            .map {
                TrainSuggestion(it.number, it.label, it.originName, it.destinationName, favorite = true)
            }
        val visti = recents.startingWith(prefix).map {
            TrainSuggestion(it.number, it.label, it.originName, it.destinationName, favorite = false)
        }
        return (stellati + visti).distinctBy { it.number }
    }
}
