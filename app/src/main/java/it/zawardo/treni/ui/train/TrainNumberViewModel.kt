package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.local.FavoriteTrainEntity
import it.zawardo.treni.data.repository.TrainSuggestion
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.trainNumberOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TrainNumberUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<TrainRef> = emptyList(),
    val suggestions: List<TrainSuggestion> = emptyList(),
    val suggestionsOpen: Boolean = false,
    val message: String? = null,
    /**
     * Corsa da aprire senza passare da un elenco.
     *
     * La ricerca per numero e' puntuale: quando la risposta e' una sola, farla
     * toccare sarebbe un passaggio a vuoto. Vale solo per la ricerca chiesta,
     * mai per quella che parte da sola mentre si digita, perche' a meta' numero
     * si finirebbe dentro il treno sbagliato.
     */
    val openDirectly: TrainRef? = null,
)

@OptIn(FlowPreview::class)
class TrainNumberViewModel : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository
    private val memory = ServiceLocator.trainMemory

    /** I preferiti si aggiungono dal dettaglio corsa; qui servono per ripescarli. */
    val favorite: StateFlow<List<FavoriteTrainEntity>> = memory.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(TrainNumberUiState())
    val state: StateFlow<TrainNumberUiState> = _state.asStateFlow()

    private val digitato = MutableStateFlow("")

    init {
        viewModelScope.launch {
            digitato.debounce(250).distinctUntilChanged().collect { suggerisci(it) }
        }
    }

    fun onQueryChange(text: String) {
        /*
         * Nel campo restano solo cifre, perche' su ViaggiaTreno i numeri di
         * treno sono interi: le lettere che si vedono in giro sono sigle di
         * linea ("RE8", "S8") o bus sostitutivi, e nessuna delle due si cerca
         * qui.
         *
         * Incollare l'etichetta intera continua pero' a funzionare, ed e' il
         * motivo per cui si passa dall'estrazione invece di filtrare e basta:
         * da "RE_8 2828" le sole cifre darebbero "82828", che e' un altro treno
         * o nessuno.
         */
        val numero = trainNumberOf(text)?.filter { it.isDigit() }.orEmpty().take(6)
        _state.update { it.copy(query = numero, message = null, suggestionsOpen = true) }
        digitato.value = numero
    }

    /**
     * Autocompletamento.
     *
     * ViaggiaTreno cerca solo per numero esatto - "282" non restituisce nulla -
     * quindi i suggerimenti non possono venire da li'. Vengono da quello che
     * l'app ha gia' visto: preferiti e corse aperte di recente, che poi sono i
     * treni che una persona cerca davvero.
     *
     * Appena il numero e' completo la ricerca parte da sola, in silenzio: se non
     * trova niente non lo dice, perche' a meta' digitazione "non esiste" e'
     * quasi sempre falso.
     */
    private suspend fun suggerisci(text: String) {
        val numero = trainNumberOf(text)
        if (numero.isNullOrBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        val trovati = runCatching { memory.suggest(numero, favorite.value) }.getOrDefault(emptyList())
        _state.update { it.copy(suggestions = trovati) }
        if (numero.length >= MIN_LOOKUP) cerca(numero, esplicita = false)
    }

    /** Un preferito o un suggerimento e' solo un numero gia' scritto. */
    fun pick(number: String) {
        _state.update {
            it.copy(query = number, message = null, suggestionsOpen = false, suggestions = emptyList())
        }
        digitato.value = number
        cerca(number, esplicita = true)
    }

    fun removeFavorite(number: String) {
        viewModelScope.launch {
            memory.toggleFavorite(number, favorite = false, status = null, now = 0)
        }
    }

    /** Svuota il campo e tutto quello che ne dipendeva. */
    fun clearQuery() {
        _state.update {
            TrainNumberUiState(suggestionsOpen = false)
        }
        digitato.value = ""
    }

    fun consumeOpen() {
        _state.update { it.copy(openDirectly = null) }
    }

    fun closeSuggestions() {
        _state.update { it.copy(suggestionsOpen = false) }
    }

    fun resolve() {
        val numero = trainNumberOf(_state.value.query) ?: return
        _state.update { it.copy(suggestionsOpen = false) }
        cerca(numero, esplicita = true)
    }

    private fun cerca(numero: String, esplicita: Boolean) {
        viewModelScope.launch {
            if (esplicita) {
                _state.update { it.copy(loading = true, message = null, results = emptyList()) }
            }
            val refs = runCatching { trains.resolve(numero) }.getOrDefault(emptyList())

            // Nel frattempo l'utente puo' aver scritto altro: una risposta in
            // ritardo non deve riportare a galla una ricerca abbandonata.
            if (trainNumberOf(_state.value.query) != numero) return@launch

            _state.update {
                it.copy(
                    loading = false,
                    results = refs,
                    openDirectly = refs.singleOrNull()?.takeIf { _ -> esplicita },
                    suggestionsOpen = it.suggestionsOpen && refs.isEmpty(),
                    message = when {
                        refs.isNotEmpty() -> null
                        esplicita -> "Nessun treno $numero in circolazione oggi."
                        else -> it.message
                    },
                )
            }
        }
    }

    private companion object {
        /**
         * Sotto le tre cifre la ricerca automatica e' quasi sempre un buco nel
         * vuoto: i treni a una o due cifre esistono, ma li si trova col tasto.
         */
        const val MIN_LOOKUP = 3
    }
}
