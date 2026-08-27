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
import java.time.ZonedDateTime

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
    val loadingMore: Boolean = false,
    val noMore: Boolean = false,
)

@OptIn(FlowPreview::class)
class BoardViewModel : ViewModel() {

    private val stationsRepo = ServiceLocator.stationRepository
    private val trains = ServiceLocator.trainStatusRepository
    private val trenord = ServiceLocator.trenordRepository
    private val store = ServiceLocator.searchStore

    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    /**
     * Da che ora chiedere il prossimo blocco.
     *
     * ViaggiaTreno non pagina: `/partenze` restituisce una finestra di circa due
     * ore attorno all'orario richiesto. Per vedere piu' avanti si rifa' la
     * chiamata spostando l'orario, e si concatenano i blocchi.
     */
    private var nextFrom: ZonedDateTime = ZonedDateTime.now()

    /** Il tabellone Trenord non accetta un orario: si chiede solo al primo giro. */
    private var firstWindow: Boolean = true

    init {
        viewModelScope.launch {
            queries.debounce(250).distinctUntilChanged().collect { suggest(it) }
        }
        // Se nella scheda Tratta e' gia' stata scelta una partenza, il tabellone
        // si apre su quella e carica subito: aprire un campo vuoto sarebbe un
        // passaggio in piu' per un'informazione gia' nota.
        ServiceLocator.currentDeparture.value?.let { select(it) }
    }

    /**
     * Apre il tabellone su una stazione decisa da fuori, oggi arrivando da una
     * fermata toccata nel dettaglio corsa. Si ignora se e' gia' quella mostrata,
     * altrimenti ogni ricomposizione ricaricherebbe.
     */
    fun preselect(station: Station) {
        if (_state.value.station?.rfiCode == station.rfiCode) return
        select(station)
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

        nextFrom = ZonedDateTime.now()
        firstWindow = true
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, noMore = false) }
            val entries = fetch(code, nextFrom)
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    /*
                     * Vuoto puo' voler dire due cose diverse e l'utente ha
                     * diritto di distinguerle: nessun treno, oppure treni che
                     * esistono ma di cui nessuna fonte pubblica ritardi.
                     */
                    message = if (entries.isEmpty()) {
                        "Nessun treno tracciato in questa fascia oraria." +
                            System.lineSeparator() + System.lineSeparator() +
                            "Il tabellone mostra solo corse con ritardo e binario " +
                            "rilevati. Alcune fermate, fra cui quelle sotterranee " +
                            "del Passante milanese, non hanno tracciamento: per " +
                            "quelle usa la ricerca per tratta."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Blocco successivo: si sposta la finestra in avanti e si concatena.
     *
     * Le finestre si sovrappongono di qualche minuto, quindi i doppioni vanno
     * tolti: senza, lo stesso treno comparirebbe due volte a cavallo fra un
     * blocco e l'altro.
     */
    fun loadMore() {
        val s = _state.value
        val code = s.station?.rfiCode ?: return
        if (s.loading || s.loadingMore || s.noMore || s.entries.isEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            nextFrom = nextFrom.plusMinutes(WINDOW_MINUTES)
            firstWindow = false
            val more = fetch(code, nextFrom)

            val seen = _state.value.entries.map { key(it) }.toSet()
            val fresh = more.filter { key(it) !in seen }

            _state.update {
                it.copy(
                    loadingMore = false,
                    entries = it.entries + fresh,
                    // Due finestre di fila senza nulla di nuovo: la giornata e' finita.
                    noMore = fresh.isEmpty(),
                )
            }
        }
    }

    /**
     * Un tabellone, due sorgenti.
     *
     * ViaggiaTreno copre la rete RFI e include gia' i treni Trenord dove li
     * pubblica — Milano Cadorna, capolinea puramente Trenord, ha le sue
     * partenze. Ma sulle fermate sotterranee del Passante milanese non pubblica
     * nulla, e li' l'unica fonte e' il tabellone Trenord.
     *
     * Si interrogano entrambe in parallelo e si fondono. ViaggiaTreno vince sui
     * doppioni perche' porta ritardo e binario, che l'altro non ha.
     */
    private suspend fun fetch(code: String, at: ZonedDateTime): List<BoardEntry> {
        val arrivals = _state.value.mode == BoardMode.ARRIVALS
        val rfi = if (arrivals) trains.arrivals(code, at) else trains.departures(code, at)

        /*
         * Trenord si interroga solo quando ViaggiaTreno tace.
         *
         * Dove RFI pubblica, i treni Trenord ci sono gia' e la fusione li
         * scarterebbe come doppioni: chiedere il tabellone Trenord costerebbe
         * una dozzina di chiamate per non aggiungere una riga. Resta utile solo
         * sulle fermate scoperte, dove e' l'unica fonte.
         *
         * Il suo tabellone e' inoltre sempre "adesso": non accetta un orario,
         * quindi partecipa solo alla prima finestra.
         */
        if (rfi.isNotEmpty() || !firstWindow) return merge(rfi, emptyList())

        val tn = runCatching { trenord.board(code, arrivals) }.getOrDefault(emptyList())
        return merge(rfi, tn)
    }

    /**
     * Fonde le due liste. La chiave e' numero treno + orario: lo stesso treno
     * visto dalle due sorgenti va mostrato una volta sola.
     */
    private fun merge(fromRfi: List<BoardEntry>, fromTrenord: List<BoardEntry>): List<BoardEntry> {
        val seen = fromRfi.map { it.trainRef.number + "|" + it.scheduledTime }.toSet()
        val extra = fromTrenord.filter { it.trainRef.number + "|" + it.scheduledTime !in seen }
        return (fromRfi + extra)
            /*
             * Solo corse tracciate.
             *
             * Il tabellone Trenord e' orario teorico e per due terzi delle corse
             * resta tale anche dopo aver interrogato il dettaglio. Una riga senza
             * ritardo ne' binario non aggiunge nulla a un orario cartaceo, e
             * mescolata alle altre fa sembrare misurato cio' che non lo e'.
             *
             * Il prezzo e' che le fermate senza copertura restano vuote: e'
             * preferibile al rumore.
             */
            .filter { it.hasRealtime }
            .sortedBy { it.scheduledTime ?: "" }
    }

    private fun key(e: BoardEntry) =
        e.trainRef.number + "|" + e.trainRef.departureDateMillis + "|" + e.scheduledTime

    private companion object {
        /** Ampiezza della finestra restituita da ViaggiaTreno, misurata. */
        const val WINDOW_MINUTES = 90L
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
