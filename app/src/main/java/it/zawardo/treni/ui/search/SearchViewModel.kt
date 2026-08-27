package it.zawardo.treni.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.local.SavedSearchEntity
import it.zawardo.treni.data.local.SearchHistoryEntity
import it.zawardo.treni.domain.model.Station
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
    val dateTime: LocalDateTime = LocalDateTime.now(),
    val rememberLast: Boolean = true,
    val alreadySaved: Boolean = false,
    val error: String? = null,
) {
    /** Cercare una tratta verso se stessa non ha senso: il pulsante resta spento. */
    val canSearch: Boolean
        get() = from != null && to != null && from.locationId != to.locationId
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
            queries.debounce(250).distinctUntilChanged().collect { runSuggest(it) }
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
        refreshSavedFlag()
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
                SearchField.FROM -> it.copy(fromQuery = text, from = null, activeField = field)
                SearchField.TO -> it.copy(toQuery = text, to = null, activeField = field)
            }
        }
        queries.value = text
    }

    fun onFieldFocused(field: SearchField) {
        _state.update { it.copy(activeField = field, suggestions = emptyList()) }
    }

    private suspend fun runSuggest(query: String) {
        if (query.length < 2) {
            _state.update { it.copy(suggestions = emptyList(), loadingSuggestions = false) }
            return
        }
        // Prima la cache locale: la lista compare subito, poi si arricchisce dalla rete.
        val offline = runCatching { store.suggestOffline(query) }.getOrDefault(emptyList())
        _state.update { it.copy(suggestions = offline, loadingSuggestions = true) }

        val remote = runCatching { stations.search(query) }.getOrNull()
        if (remote != null) {
            runCatching { store.cacheAll(remote) }
            val merged = (remote + offline).distinctBy { it.locationId }
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
            }.copy(suggestions = emptyList(), activeField = null)
        }
        viewModelScope.launch {
            runCatching { store.cache(station) }
            refreshSavedFlag()
        }
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
                activeField = null,
            )
        }
        viewModelScope.launch { refreshSavedFlag() }
    }

    fun clearField(field: SearchField) {
        _state.update {
            when (field) {
                SearchField.FROM -> it.copy(from = null, fromQuery = "")
                SearchField.TO -> it.copy(to = null, toQuery = "")
            }.copy(suggestions = emptyList())
        }
    }

    private fun clearFields() {
        _state.update {
            it.copy(from = null, to = null, fromQuery = "", toQuery = "", suggestions = emptyList())
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
                activeField = null,
            )
        }
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

    /** Salva stazioni e orario impostato. La data no: domani sarebbe gia' vecchia. */
    fun saveCurrent() {
        val s = _state.value
        val from = s.from ?: return
        val to = s.to ?: return
        val minutes = s.dateTime.toLocalTime().let { it.hour * 60 + it.minute }
        viewModelScope.launch {
            store.save(from, to, timeMinutes = minutes)
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
}
