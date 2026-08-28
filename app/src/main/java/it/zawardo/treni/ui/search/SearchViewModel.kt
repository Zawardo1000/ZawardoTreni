package it.zawardo.treni.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.local.SavedSearchEntity
import it.zawardo.treni.data.local.SearchHistoryEntity
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.NearbyStation
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.sortedByName
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class SearchField { FROM, TO }

data class SearchUiState(
    val from: Station? = null,
    val to: Station? = null,
    val fromQuery: String = "",
    val toQuery: String = "",
    val activeField: SearchField? = null,
    val suggestions: List<Station> = emptyList(),
    val loadingSuggestions: Boolean = false,
    /** Le stazioni proposte dal mirino: si mostrano al posto dei suggerimenti. */
    val nearby: List<NearbyStation> = emptyList(),
    val dateTime: LocalDateTime = LocalDateTime.now(),
    val rememberLast: Boolean = true,
    val directOnly: Boolean = false,
    val alreadySaved: Boolean = false,
    val locating: Boolean = false,
    val error: String? = null,
) {
    /** Cercare una tratta verso se stessa non ha senso: il pulsante resta spento. */
    val canSearch: Boolean
        get() = from != null && to != null && from.locationId != to.locationId

    /** C'e' una lista aperta sotto il campo attivo: si sta scegliendo una stazione. */
    val choosing: Boolean
        get() = activeField != null &&
            (suggestions.isNotEmpty() || nearby.isNotEmpty() || loadingSuggestions)
}

@OptIn(FlowPreview::class)
class SearchViewModel : ViewModel() {

    private val stations = ServiceLocator.stationRepository
    private val store = ServiceLocator.searchStore
    private val settings = ServiceLocator.settings

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    val history: StateFlow<List<SearchHistoryEntity>> =
        store.recentSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val saved: StateFlow<List<SavedSearchEntity>> =
        store.savedSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Le reti accese, per la schermata delle fonti. */
    val enabledSources: StateFlow<Set<DataSource>> = settings.enabledSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataSource.defaultEnabled)

    fun setSourceEnabled(source: DataSource, enabled: Boolean) {
        viewModelScope.launch { settings.setSourceEnabled(source, enabled) }
    }

    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            settings.rememberLastSearch.collect { remember ->
                _state.update { it.copy(rememberLast = remember) }
                if (remember && _state.value.from == null && _state.value.to == null) {
                    prefillFromLastSearch()
                }
            }
        }
        viewModelScope.launch {
            settings.directOnly.collect { only -> _state.update { it.copy(directOnly = only) } }
        }
        /*
         * Nessun `distinctUntilChanged` dopo il debounce.
         *
         * Un MutableStateFlow non ripete due volte lo stesso valore, quindi non
         * servirebbe; ma dopo il debounce filtrerebbe anche i ritorni al punto
         * di partenza — scrivo "mi", cancello la "i", la riscrivo — che al
         * collector arrivano come un solo "mi" uguale al precedente. La ricerca
         * non ripartirebbe, e il campo resterebbe in attesa per sempre.
         */
        viewModelScope.launch {
            queries.debounce(250).collect { runSuggest(it) }
        }
    }

    /**
     * Ripropone le stazioni dell'ultima ricerca ma non il suo orario: un orario
     * del passato produrrebbe risultati gia' scaduti.
     */
    private suspend fun prefillFromLastSearch() {
        val last = store.lastSearch() ?: return
        _state.update {
            it.copy(
                from = last.first,
                to = last.second,
                fromQuery = last.first.name,
                toQuery = last.second.name,
                dateTime = LocalDateTime.now(),
            )
        }
        ServiceLocator.currentDeparture.value = last.first
        refreshSavedFlag()
    }

    fun setDirectOnly(enabled: Boolean) {
        viewModelScope.launch { settings.setDirectOnly(enabled) }
    }

    fun setRememberLast(enabled: Boolean) {
        viewModelScope.launch {
            settings.setRememberLastSearch(enabled)
            if (!enabled) clearFields()
        }
    }

    fun onQueryChange(field: SearchField, text: String) {
        _state.update {
            when (field) {
                SearchField.FROM -> it.copy(fromQuery = text, from = null)
                SearchField.TO -> it.copy(toQuery = text, to = null)
            }.copy(
                activeField = field,
                nearby = emptyList(),
                /*
                 * I suggerimenti di prima restano finche' non arrivano i nuovi.
                 * Svuotarli a ogni lettera faceva sparire e ricomparire la lista
                 * dentro la scheda, che si apriva e si richiudeva mentre si
                 * scriveva; il debounce di 250 ms basta a renderlo visibile.
                 */
                loadingSuggestions = text.length >= MIN_QUERY,
            )
        }
        queries.value = text
    }

    /** Cambiando campo i suggerimenti di prima non c'entrano piu' nulla. */
    fun onFieldFocused(field: SearchField) {
        _state.update {
            if (it.activeField == field) it
            else it.copy(activeField = field, suggestions = emptyList(), nearby = emptyList())
        }
    }

    private suspend fun runSuggest(query: String) {
        if (query.length < MIN_QUERY) {
            _state.update { it.copy(suggestions = emptyList(), loadingSuggestions = false) }
            return
        }
        // Prima la cache locale: la lista compare subito, poi si arricchisce dalla rete.
        val offline = runCatching { store.suggestOffline(query) }.getOrDefault(emptyList())
        _state.update { it.copy(suggestions = offline.sortedByName(), loadingSuggestions = true) }

        val remote = runCatching { stations.search(query) }.getOrNull()
        if (remote != null) {
            runCatching { store.cacheAll(remote) }
            val merged = (remote + offline).distinctBy { it.locationId }.sortedByName()
            _state.update { it.copy(suggestions = merged, loadingSuggestions = false, error = null) }
        } else {
            _state.update {
                it.copy(
                    loadingSuggestions = false,
                    error = if (offline.isEmpty()) "Nessuna connessione: solo stazioni gia' usate" else null,
                )
            }
        }
    }

    fun select(field: SearchField, station: Station) {
        _state.update {
            when (field) {
                SearchField.FROM -> it.copy(from = station, fromQuery = station.name)
                SearchField.TO -> it.copy(to = station, toQuery = station.name)
            }.copy(suggestions = emptyList(), nearby = emptyList(), activeField = null)
        }
        if (field == SearchField.FROM) ServiceLocator.currentDeparture.value = station
        viewModelScope.launch {
            runCatching { store.cache(station) }
            refreshSavedFlag()
        }
    }

    /**
     * Propone le stazioni piu' vicine, senza sceglierne nessuna.
     *
     * La piu' vicina in linea d'aria spesso non e' quella da cui conviene
     * partire: a Milano il mirino cadeva su una fermata suburbana mentre
     * Centrale era cinquecento metri piu' in la'. Scegliere resta all'utente;
     * l'app si limita a mettergli davanti le tre candidate, in ordine.
     */
    fun proposeNearest(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _state.update { it.copy(locating = true, error = null) }
            val vicine = runCatching { stations.nearest(latitude, longitude) }.getOrDefault(emptyList())
            when {
                vicine.isEmpty() -> _state.update {
                    it.copy(locating = false, error = "Nessuna stazione trovata nei dintorni.")
                }
                // Con una sola candidata non c'e' niente da scegliere.
                vicine.size == 1 -> {
                    _state.update { it.copy(locating = false) }
                    select(SearchField.FROM, vicine.first().station)
                }
                else -> _state.update {
                    it.copy(
                        locating = false,
                        activeField = SearchField.FROM,
                        nearby = vicine,
                        suggestions = emptyList(),
                        loadingSuggestions = false,
                    )
                }
            }
        }
    }

    fun setLocating(active: Boolean) {
        _state.update { it.copy(locating = active) }
    }

    fun reportLocationProblem(message: String) {
        _state.update { it.copy(locating = false, error = message) }
    }

    /** Inverti: pensato per il ritorno, quindi riporta anche l'orario ad adesso. */
    fun swap() {
        _state.update {
            it.copy(
                from = it.to,
                to = it.from,
                fromQuery = it.toQuery,
                toQuery = it.fromQuery,
                dateTime = LocalDateTime.now(),
                suggestions = emptyList(),
                nearby = emptyList(),
                activeField = null,
            )
        }
        ServiceLocator.currentDeparture.value = _state.value.from
        viewModelScope.launch { refreshSavedFlag() }
    }

    fun clearField(field: SearchField) {
        _state.update {
            when (field) {
                SearchField.FROM -> it.copy(from = null, fromQuery = "")
                SearchField.TO -> it.copy(to = null, toQuery = "")
            }.copy(suggestions = emptyList(), nearby = emptyList())
        }
    }

    private fun clearFields() {
        _state.update {
            it.copy(
                from = null,
                to = null,
                fromQuery = "",
                toQuery = "",
                suggestions = emptyList(),
                nearby = emptyList(),
            )
        }
    }

    fun setDate(date: LocalDate) {
        _state.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }
    }

    fun setTime(time: LocalTime) {
        _state.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }
    }

    fun setNow() {
        _state.update { it.copy(dateTime = LocalDateTime.now()) }
    }

    /**
     * Riapre una tratta da cronologia o salvate.
     *
     * [timeMinutes] arriva solo dalle salvate, che conservano l'orario abituale.
     * La data e' sempre oggi: salvare una data significherebbe riproporre un
     * giorno ormai passato.
     */
    fun applyPair(from: Station, to: Station, timeMinutes: Int? = null) {
        val now = LocalDateTime.now()
        val target = timeMinutes
            ?.let { now.toLocalDate().atTime(LocalTime.ofSecondOfDay(it * 60L)) }
            ?: now
        _state.update {
            it.copy(
                from = from,
                to = to,
                fromQuery = from.name,
                toQuery = to.name,
                dateTime = target,
                suggestions = emptyList(),
                nearby = emptyList(),
                activeField = null,
            )
        }
        ServiceLocator.currentDeparture.value = from
        viewModelScope.launch { refreshSavedFlag() }
    }

    private suspend fun refreshSavedFlag() {
        val s = _state.value
        val from = s.from
        val to = s.to
        val isSaved = if (from != null && to != null) {
            runCatching { store.isSaved(from, to) }.getOrDefault(false)
        } else {
            false
        }
        _state.update { it.copy(alreadySaved = isSaved) }
    }

    /**
     * Salva **solo le stazioni**.
     *
     * Salvare anche l'orario rendeva la funzione scomoda: la stessa tratta si
     * ripete a ore diverse, e riaprirla su un orario fisso costringeva a
     * correggerlo ogni volta.
     */
    fun saveCurrent() {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        viewModelScope.launch {
            store.save(from, to, timeMinutes = null)
            refreshSavedFlag()
        }
    }

    fun deleteSaved(id: Long) = viewModelScope.launch {
        store.deleteSaved(id)
        refreshSavedFlag()
    }

    fun deleteHistory(id: Long) = viewModelScope.launch { store.deleteHistory(id) }

    fun clearHistory() = viewModelScope.launch { store.clearHistory() }

    /** Registrata solo quando la ricerca parte davvero, non a ogni tocco dei campi. */
    fun recordSearch() {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        viewModelScope.launch { store.record(from, to) }
    }

    private companion object {
        /** Sotto due lettere i suggerimenti sarebbero mezza rete ferroviaria. */
        const val MIN_QUERY = 2
    }
}
