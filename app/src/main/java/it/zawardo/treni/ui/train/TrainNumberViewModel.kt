package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.local.FavoriteTrainEntity
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.trainNumberOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrainNumberUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<TrainRef> = emptyList(),
    val message: String? = null,
)

class TrainNumberViewModel : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository
    private val favorites = ServiceLocator.trainFavorites

    /** I preferiti si aggiungono dal dettaglio corsa; qui servono per ripescarli. */
    val favorite: StateFlow<List<FavoriteTrainEntity>> = favorites.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(TrainNumberUiState())
    val state: StateFlow<TrainNumberUiState> = _state.asStateFlow()

    fun onQueryChange(text: String) {
        /*
         * Il campo accetta anche l'etichetta intera, perche' e' quella che si
         * legge nei risultati e nei tabelloni: "RE 2874", "RE_8 2828", "REG
         * 2618". Toglierle gli spazi le trasformava in "RE82828", che non
         * esiste da nessuna parte.
         *
         * Si normalizza in maiuscolo perche' e' cosi' che ViaggiaTreno indicizza
         * i suffissi di lettera.
         */
        val cleaned = text
            .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '/' }
            .take(24)
            .uppercase()
        _state.update { it.copy(query = cleaned, message = null) }
    }

    /** Un preferito e' solo un numero gia' scritto: da li' la ricerca e' la solita. */
    fun searchFavorite(number: String) {
        _state.update { it.copy(query = number, message = null) }
        resolve()
    }

    fun removeFavorite(number: String) {
        viewModelScope.launch { favorites.toggle(number, favorite = false, status = null, now = 0) }
    }

    fun resolve() {
        val number = trainNumberOf(_state.value.query) ?: return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, results = emptyList()) }
            val refs = runCatching { trains.resolve(number) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    results = refs,
                    message = if (refs.isEmpty()) {
                        "Nessun treno $number in circolazione oggi."
                    } else {
                        null
                    },
                )
            }
        }
    }
}
