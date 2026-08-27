package it.zawardo.treni.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BoardMode { DEPARTURES, ARRIVALS }

data class BoardUiState(
    val station: Station? = null,
    val query: String = "",
    val suggestions: List<Station> = emptyList(),
    /** Il campo stazione resta sempre visibile: si cambia senza tornare indietro. */
    val suggestionsOpen: Boolean = false,
    val mode: BoardMode = BoardMode.DEPARTURES,
    val loading: Boolean = false,
    val entries: List<BoardEntry> = emptyList(),
    val message: String? = null,
    val locatingNearest: Boolean = false,
)

@OptIn(FlowPreview::class)
class BoardViewModel : ViewModel() {

    private val stationsRepo = ServiceLocator.stationRepository
    private val trains = ServiceLocator.trainStatusRepository
    private val store = ServiceLocator.searchStore

    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queries.debounce(250).distinctUntilChanged().collect { suggest(it) }
        }
        // Se nella scheda Tratta e' gia' stata scelta una partenza, il tabellone
        // si apre su quella e carica subito: aprire un campo vuoto sarebbe un
        // passaggio in piu' per un'informazione gia' nota.
        ServiceLocator.currentDeparture.value?.let { select(it) }
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text, suggestionsOpen = true) }
        queries.value = text
    }

    private suspend fun suggest(query: String) {
        if (query.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        val offline = runCatching { store.suggestOffline(query) }.getOrDefault(emptyList())
        _state.update { it.copy(suggestions = offline) }
        val remote = runCatching { stationsRepo.search(query) }.getOrNull() ?: return
        runCatching { store.cacheAll(remote) }
        _state.update {
            it.copy(suggestions = (remote + offline).distinctBy { s -> s.locationId })
        }
    }

    fun select(station: Station) {
        if (!station.trackable) {
            // Senza codice RFI non esiste tabellone: meglio dirlo che mostrare una lista vuota.
            _state.update {
                it.copy(
                    station = station,
                    query = station.name,
                    suggestionsOpen = false,
                    suggestions = emptyList(),
                    entries = emptyList(),
                    message = "Per questa fermata non esiste un tabellone in tempo reale.",
                )
            }
            return
        }
        _state.update {
            it.copy(
                station = station,
                query = station.name,
                suggestionsOpen = false,
                suggestions = emptyList(),
            )
        }
        viewModelScope.launch { runCatching { store.cache(station) } }
        load()
    }

    /** Svuota il campo per digitare un'altra stazione, senza perdere il tabellone. */
    fun clearQuery() {
        _state.update { it.copy(query = "", suggestions = emptyList(), suggestionsOpen = true) }
    }

    fun closeSuggestions() {
        _state.update { it.copy(suggestionsOpen = false, suggestions = emptyList()) }
    }

    fun setMode(mode: BoardMode) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode) }
        load()
    }

    fun load() {
        val station = _state.value.station ?: return
        val code = station.rfiCode ?: return

        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val entries = when (_state.value.mode) {
                BoardMode.DEPARTURES -> trains.departures(code)
                BoardMode.ARRIVALS -> trains.arrivals(code)
            }
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    /*
                     * Un tabellone vuoto ha due cause indistinguibili dalla
                     * risposta: nessun treno adesso, oppure stazione che
                     * ViaggiaTreno non copre affatto. Succede sulle fermate del
                     * Passante milanese servite da Trenord, che hanno un codice
                     * RFI ma nessun dato: dirlo evita di far cercare un guasto
                     * che non c'e'.
                     */
                    message = if (entries.isEmpty()) {
                        "Nessun treno in arrivo nel prossimo intervallo.\n\n" +
                            "Alcune fermate urbane e regionali non sono coperte da " +
                            "ViaggiaTreno: per quelle il tabellone resta vuoto anche " +
                            "quando i treni ci sono."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Chiamata dopo che il permesso e' stato concesso: la posizione arriva dalla UI. */
    fun useNearest(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _state.update { it.copy(locatingNearest = true, message = null) }
            val nearest = runCatching { stationsRepo.closest(latitude, longitude) }.getOrNull()
            _state.update { it.copy(locatingNearest = false) }
            if (nearest == null) {
                _state.update { it.copy(message = "Nessuna stazione trovata nei dintorni.") }
            } else {
                select(nearest)
            }
        }
    }

    fun onLocationUnavailable(reason: String) {
        _state.update { it.copy(locatingNearest = false, message = reason) }
    }

    fun setLocating(active: Boolean) {
        _state.update { it.copy(locatingNearest = active) }
    }
}
