package it.zawardo.treni.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.FiltroFonti
import it.zawardo.treni.domain.model.NearbyStation
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.SuggerimentiStazioni
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.minutesFrom
import it.zawardo.treni.domain.model.terminus
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import it.zawardo.treni.domain.model.stillCatchable
import java.time.LocalTime
import java.time.ZonedDateTime

enum class BoardMode { DEPARTURES, ARRIVALS }

data class BoardUiState(
    val station: Station? = null,
    val query: String = "",
    val suggestions: List<Station> = emptyList(),
    /** Le stazioni proposte dal mirino: si mostrano al posto dei suggerimenti. */
    val nearby: List<NearbyStation> = emptyList(),
    /** Il campo stazione resta sempre visibile: si cambia senza tornare indietro. */
    val suggestionsOpen: Boolean = false,
    val mode: BoardMode = BoardMode.DEPARTURES,
    val loading: Boolean = false,
    val entries: List<BoardEntry> = emptyList(),
    val message: String? = null,
    val locatingNearest: Boolean = false,
    val loadingMore: Boolean = false,
    val noMore: Boolean = false,
    /** Serve a non paginare prima del primo caricamento. */
    val loadedOnce: Boolean = false,
)

@OptIn(FlowPreview::class)
class BoardViewModel : ViewModel() {

    private val stationsRepo = ServiceLocator.stationRepository
    private val trains = ServiceLocator.trainStatusRepository
    private val trenord = ServiceLocator.trenordRepository
    private val italo = ServiceLocator.italoRepository
    private val fnb = ServiceLocator.fnbRepository
    private val svizzera = ServiceLocator.svizzeraRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    /** Il registro delle reti con stazioni proprie, per i suggerimenti. */
    private val fonteLocali = ServiceLocator.fontiStazioniLocali
    private val store = ServiceLocator.searchStore
    private val preferite = ServiceLocator.stationFavorites

    private val _state = MutableStateFlow(BoardUiState())
    val state: StateFlow<BoardUiState> = _state.asStateFlow()

    private val queries = MutableStateFlow("")

    /** Le stazioni preferite, per aprirle senza ricercarle. */
    val favorites: StateFlow<List<Station>> = preferite.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Preferita o no, letto dal database e non tenuto a parte: la stellina resta
     * d'accordo con l'elenco anche se la stazione viene tolta da li'.
     */
    val isFavorite: StateFlow<Boolean> = combine(favorites, _state) { elenco, s ->
        val code = s.station?.rfiCode ?: return@combine false
        elenco.any { it.rfiCode.equals(code, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Da che ora chiedere il prossimo blocco.
     *
     * ViaggiaTreno non pagina: `/partenze` restituisce una finestra di circa due
     * ore attorno all'orario richiesto. Per vedere piu' avanti si rifa' la
     * chiamata spostando l'orario, e si concatenano i blocchi.
     */
    private var nextFrom: ZonedDateTime = ZonedDateTime.now()

    /*
     * Queste tre devono restare sopra `init`.
     *
     * Il blocco `init` arriva a chiamare `load()`, e le proprieta' si
     * inizializzano in ordine di dichiarazione: una dichiarata piu' in basso, a
     * quel punto, e' ancora null. Il compilatore non lo segnala e il guasto e'
     * un crash all'apertura del tabellone.
     */

    /** Corse gia' interrogate: una volta a testa, anche scorrendo avanti e indietro. */
    private val verificate = mutableSetOf<String>()

    /** Numeri gia' chiesti a Trenord cercando i soppressi: si chiedono una volta sola. */
    private val chieste = mutableSetOf<String>()

    /**
     * Poche richieste per volta. Una schermata mostra una decina di righe e
     * lanciarle tutte insieme vorrebbe dire dieci connessioni per uno sguardo.
     */
    private val limite = Semaphore(3)

    /** Le reti accese: il tabellone non interroga quelle spente. */
    private var sources: Set<DataSource> = DataSource.defaultEnabled

    init {
        viewModelScope.launch { ServiceLocator.settings.enabledSources.collect { sources = it } }
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
        _state.update { it.copy(query = text, suggestionsOpen = true, nearby = emptyList()) }
        queries.value = text
    }

    private suspend fun suggest(query: String) {
        if (query.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        /*
         * Le reti fuori da RFI si cercano in locale.
         *
         * Il loro elenco di fermate viaggia dentro l'app, quindi la ricerca non
         * costa una chiamata e risponde anche senza rete. Vanno unite agli
         * altri suggerimenti e non mostrate a parte: chi scrive "Andria" vuole
         * Andria, e non deve sapere di chi sia la ferrovia per trovarla.
         */
        // Solo le reti accese: spegnerne una ne toglie le stazioni dai
        // suggerimenti, invece di proporre fermate che poi non danno tabellone.
        val locali = FiltroFonti.fontiLocali(sources)
            .flatMap { fonteLocali[it]?.suggerisci(query).orEmpty() }

        val offline = runCatching { store.suggestOffline(query) }.getOrDefault(emptyList())
        _state.update {
            it.copy(suggestions = SuggerimentiStazioni.unisci(locali, offline))
        }
        val remote = runCatching { stationsRepo.search(query) }.getOrNull() ?: return
        runCatching { store.cacheAll(remote) }
        _state.update {
            it.copy(suggestions = SuggerimentiStazioni.unisci(locali, remote + offline))
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
                    nearby = emptyList(),
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
                nearby = emptyList(),
            )
        }
        viewModelScope.launch { runCatching { store.cache(station) } }
        load()
    }

    /** Svuota il campo per digitare un'altra stazione, senza perdere il tabellone. */
    fun clearQuery() {
        _state.update {
            it.copy(query = "", suggestions = emptyList(), nearby = emptyList(), suggestionsOpen = true)
        }
    }

    fun closeSuggestions() {
        _state.update {
            it.copy(suggestionsOpen = false, suggestions = emptyList(), nearby = emptyList())
        }
    }

    fun toggleFavorite() {
        val station = _state.value.station ?: return
        val wanted = !isFavorite.value
        viewModelScope.launch {
            preferite.toggle(station, wanted, System.currentTimeMillis())
        }
    }

    fun removeFavorite(rfiCode: String) {
        viewModelScope.launch { preferite.remove(rfiCode) }
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
        verificate.clear()
        chieste.clear()
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, noMore = false) }

            /*
             * Se la finestra contiene solo corse gia' andate si sposta avanti da
             * sola. Succede a fine giornata e nelle stazioni piccole: lasciare
             * una lista vuota bloccherebbe anche lo scorrimento infinito, che
             * senza righe non ha nulla su cui scattare.
             */
            var grezzi = fetch(code, nextFrom)
            var entries = grezzi.stillCatchable()
            var tentativi = 0
            while (entries.isEmpty() && grezzi.isNotEmpty() && tentativi < EXTRA_WINDOWS) {
                tentativi++
                nextFrom = nextFrom.plusMinutes(WINDOW_MINUTES)
                grezzi = fetch(code, nextFrom)
                entries = grezzi.stillCatchable()
            }

            val avanzato = grezzi.isNotEmpty()
            _state.update {
                it.copy(
                    loading = false,
                    entries = entries,
                    loadedOnce = true,
                    /*
                     * Vuoto puo' voler dire tre cose diverse e l'utente ha
                     * diritto di distinguerle: nessun treno, treni che esistono
                     * ma di cui nessuna fonte pubblica ritardi, oppure una
                     * giornata finita.
                     */
                    message = when {
                        entries.isNotEmpty() -> null
                        avanzato ->
                            "Da qui non parte piu' nulla per oggi." +
                                System.lineSeparator() + System.lineSeparator() +
                                "Le corse rimaste in questa fascia sono gia' passate."
                        else ->
                            "Nessun treno tracciato in questa fascia oraria." +
                                System.lineSeparator() + System.lineSeparator() +
                                "Il tabellone mostra solo corse con ritardo e binario " +
                                "rilevati. Se la stazione e' interessata da lavori o " +
                                "sospensioni, la ricerca per tratta indica cosa circola " +
                                "e da quando."
                    },
                )
            }

            recuperaSoppresse(code)
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
        if (s.loading || s.loadingMore || s.noMore || !s.loadedOnce) return

        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            nextFrom = nextFrom.plusMinutes(WINDOW_MINUTES)
            val more = fetch(code, nextFrom).stillCatchable()

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

            recuperaSoppresse(code)
        }
    }

    /**
     * Le sorgenti del tabellone, interrogate insieme.
     *
     * ViaggiaTreno copre l'intera rete RFI, comprese le stazioni del Passante
     * milanese. Le altre tre esistono perche' quella rete non e' tutta la
     * ferrovia italiana: Italo circola su RFI ma non viene pubblicato,
     * Ferrotramviaria e la Vigezzina non ci circolano affatto.
     *
     * Ognuna sa dire da sola se la stazione la riguarda, e fuori dal proprio
     * territorio non tocca la rete: a Roma Termini le tre aggiunte costano zero
     * chiamate.
     */
    private suspend fun fetch(code: String, at: ZonedDateTime): List<BoardEntry> = coroutineScope {
        val arrivi = _state.value.mode == BoardMode.ARRIVALS

        /*
         * Sulle reti che non sono RFI non si chiede a ViaggiaTreno.
         *
         * Non e' solo una chiamata sprecata: quei codici — `FNB1110`, `VIG…` —
         * non sono codici RFI, e mandarli a ViaggiaTreno significa interrogarlo
         * per una stazione che per lui non esiste.
         */
        /*
         * Per la Svizzera si guarda `soloSvizzera`, non `covers`.
         *
         * Chiasso e Bellinzona hanno anche un codice RFI: la stazione e' una
         * sola e il suo tabellone si compone di due fonti. Trattarle come fuori
         * rete spegnerebbe ViaggiaTreno proprio dove ha qualcosa da dire — a
         * Chiasso quattordici corse verso l'Italia.
         */
        val fuoriRete = fnb.covers(code) || svizzera.soloSvizzera(code) || eav.covers(code) ||
            arst.covers(code)

        val rfi = async {
            if (DataSource.TRENITALIA !in sources || fuoriRete) emptyList()
            else if (arrivi) trains.arrivals(code, at) else trains.departures(code, at)
        }
        /*
         * Italo va chiesto a parte, perche' su ViaggiaTreno non c'e'.
         *
         * Non e' una lacuna occasionale: di NTV quel tabellone non ha una riga,
         * ne' oggi ne' mai. A Roma Termini significava mostrare i Frecciarossa
         * e non gli Italo dallo stesso binario. Fuori dalle 59 stazioni che
         * servono, [ItaloRepository.covers] dice di no e non si chiede niente.
         */
        val ntv = async {
            if (DataSource.ITALO in sources && italo.covers(code)) {
                runCatching { italo.board(code, arrivi) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        /*
         * Ferrotramviaria: la Bari - Barletta e il servizio per l'aeroporto.
         *
         * Bitonto, Terlizzi, Ruvo, Corato e Andria sulla rete nazionale non
         * hanno stazione: quei treni non stanno in nessuna delle altre
         * sorgenti. Fuori dalle sue 25 fermate [FnbRepository.covers] dice di no
         * e non si chiede niente.
         */
        val nordBarese = async {
            if (DataSource.FNB in sources && fnb.covers(code)) {
                runCatching { fnb.board(code, arrivi) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        /*
         * La Vigezzina, che sta nell'orario svizzero e non in quello italiano.
         *
         * Risponde solo alle partenze: l'orario svizzero, per gli arrivi, non
         * pubblica l'origine della corsa. Vedi [SvizzeraRepository].
         */
        val vigezzina = async {
            if (DataSource.SVIZZERA in sources && svizzera.covers(code)) {
                runCatching { svizzera.board(code, arrivi) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        /*
         * EAV: Circumvesuviana, Cumana, Circumflegrea e suburbane napoletane.
         *
         * Sorrento, Pompei Scavi ed Ercolano non sono su RFI: quelle corse non
         * stanno in nessun'altra sorgente. Fuori dalle sue fermate
         * [EavRepository.covers] dice di no e non si chiede niente.
         *
         * Riceve la data come ARST, e per lo stesso motivo: ha un orario oltre
         * al tabellone, quindi sa rispondere anche per i giorni futuri e per le
         * stazioni delle altre reti EAV, che un monitor non ce l'hanno. Quelle
         * righe escono con `realtime = false`. Senza passargliela, il tabellone
         * di domani mostrava le corse di oggi.
         */
        val vesuviana = async {
            if (DataSource.EAV in sources && eav.covers(code)) {
                runCatching { eav.board(code, arrivi, at.toLocalDate()) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        /*
         * ARST: le ferrovie sarde, e l'unica sorgente che non sia in tempo reale.
         *
         * Non c'e' niente da interrogare — ARST non pubblica tabelloni — quindi
         * qui non si tocca la rete: si legge l'orario imbarcato. Ne segue che e'
         * anche l'unica a saper rispondere per un giorno diverso da oggi, ed e'
         * il motivo per cui riceve la data invece di limitarsi ad "adesso".
         *
         * Le sue righe escono con `realtime = false`: il tabellone le mostra
         * come orario previsto, non come corse confermate puntuali.
         */
        val sardegna = async {
            if (DataSource.ARST in sources && arst.covers(code)) {
                runCatching { arst.board(code, arrivi, at.toLocalDate()) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }

        val ora = LocalTime.now()
        (
            rfi.await() + ntv.await() + nordBarese.await() + vigezzina.await() +
                vesuviana.await() + sardegna.await()
            )
            .distinctBy { key(it) }
            .sortedBy { it.minutesFrom(ora) }
    }


    /**
     * Rimette in tabellone le corse soppresse.
     *
     * ViaggiaTreno un treno soppresso lo cancella dall'esistenza: non e' fra le
     * partenze, `cercaNumeroTreno` non lo trova e `andamentoTreno` risponde 204.
     * Chi quel treno lo stava aspettando non lo vede barrato, non lo vede
     * affatto, e il tabellone gli racconta che non era previsto. Verificato sull'
     * S5 11862 del 27 agosto 2026, soppresso da Pioltello a Varese: per
     * ViaggiaTreno non e' mai esistito.
     *
     * Trenord invece la corsa la tiene, con le fermate marcate soppresse. Si
     * prende quindi il suo orario di stazione, si guarda chi manca nella fascia
     * gia' mostrata, e a quei pochi si chiede com'e' andata: chi risulta
     * soppresso torna in elenco, al proprio posto e barrato.
     *
     * Costa una chiamata per stazione, 17 KB compressi e tenuta cinque minuti,
     * piu' una piccola per ogni sospetto. Fuori dall'area Trenord l'orario torna
     * vuoto e non si chiede altro: Roma Termini non paga niente.
     */
    private fun recuperaSoppresse(code: String) {
        if (DataSource.TRENORD !in sources) return
        viewModelScope.launch {
            val arrivi = _state.value.mode == BoardMode.ARRIVALS
            val orario = runCatching { trenord.timetable(code, arrivi) }.getOrDefault(emptyList())
            if (orario.isEmpty()) return@launch

            val ora = LocalTime.now()
            val mostrate = _state.value.entries
            /*
             * Solo dentro la fascia gia' in elenco. Oltre, il tabellone non
             * arriva ancora, e interrogare corse che nessuno sta guardando
             * sarebbe traffico speso per righe che non compariranno.
             *
             * Se l'elenco e' vuoto vale la finestra di una pagina: e' il caso in
             * cui questo serve di piu', perche' una stazione dove ViaggiaTreno
             * non mostra niente puo' essere una stazione dove oggi e' soppresso
             * tutto.
             */
            val finestra = mostrate.maxOfOrNull { it.minutesFrom(ora) } ?: WINDOW_MINUTES.toInt()
            val gia = mostrate.mapTo(mutableSetOf()) { it.trainRef.number }

            val sospette = orario
                .filter { it.trainRef.number !in gia && it.trainRef.number !in chieste }
                .filter { it.minutesFrom(ora) in 0..finestra }
                .take(MAX_SOSPETTE)
            if (sospette.isEmpty()) return@launch
            chieste += sospette.map { it.trainRef.number }

            val soppresse = coroutineScope {
                sospette.map { riga ->
                    async {
                        val stato = runCatching {
                            limite.withPermit { trenord.trainStatus(riga.trainRef.number) }
                        }.getOrNull() ?: return@async null

                        /*
                         * Due cose diverse, e chi aspetta in stazione ha diritto
                         * di distinguerle: la corsa cancellata del tutto, e la
                         * corsa che oggi circola ma qui non ferma piu' perche' e'
                         * limitata. In entrambi i casi quel treno non lo prendi.
                         */
                        val quiSoppressa = stato.stops
                            .firstOrNull { it.stationCode.equals(code, ignoreCase = true) }
                            ?.status == StopStatus.CANCELLED
                        val comeSta = when {
                            stato.state == TrainState.CANCELLED -> TrainState.CANCELLED
                            quiSoppressa -> TrainState.PARTIALLY_CANCELLED
                            else -> return@async null
                        }

                        riga.copy(
                            state = comeSta,
                            // Su una corsa soppressa per intero non resta un
                            // capolinea vivo: vale quello di tabella.
                            direction = stato.terminus(arrivi) ?: riga.direction,
                            label = stato.label.ifBlank { riga.label },
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            if (soppresse.isEmpty()) return@launch

            _state.update { s ->
                val chiavi = s.entries.mapTo(mutableSetOf()) { key(it) }
                val nuove = soppresse.filter { key(it) !in chiavi }
                if (nuove.isEmpty()) s
                else s.copy(
                    entries = (s.entries + nuove).sortedBy { it.minutesFrom(ora) },
                    // Il messaggio spiegava un elenco vuoto che adesso non lo e' piu'.
                    message = if (s.entries.isEmpty()) null else s.message,
                )
            }
        }
    }

    /**
     * Chiede alla corsa dove finisce davvero.
     *
     * Il tabellone di ViaggiaTreno a volte sbaglia la destinazione: il REG 12977
     * da Acireale risulta diretto a Bicocca, mentre il record della corsa dice
     * Catania Aeroporto Fontanarossa in ogni campo, orario compreso, e Bicocca
     * non e' fra le sue fermate. Sul tabellone di Acireale capita a due righe su
     * dieci: troppo per fidarsi, troppo poco per rinunciare al tabellone.
     *
     * Si domanda solo per le righe che l'utente ha davanti, una volta per corsa
     * e poche per volta: il tabellone compare subito col dato grezzo e si
     * corregge da se'. Chi non scorre non paga nulla.
     */
    fun verifyDirection(entry: BoardEntry) {
        // Le corse Italo non stanno su ViaggiaTreno: chiederle sarebbe una
        // chiamata che fallisce sempre, e la destinazione l'hanno gia' detta loro.
        if (entry.trainRef.originCode.isBlank()) return
        val chiave = key(entry)
        if (chiave in verificate) return
        verificate += chiave
        val arrivi = _state.value.mode == BoardMode.ARRIVALS

        viewModelScope.launch {
            val vera = runCatching {
                limite.withPermit { trains.status(entry.trainRef)?.terminus(arrivi) }
            }.getOrNull()

            if (vera.isNullOrBlank() || vera.equals(entry.direction, ignoreCase = true)) return@launch
            _state.update { s ->
                s.copy(
                    entries = s.entries.map {
                        if (key(it) == chiave) it.copy(direction = vera) else it
                    },
                )
            }
        }
    }

    private fun key(e: BoardEntry) =
        e.trainRef.number + "|" + e.trainRef.departureDateMillis + "|" + e.scheduledTime

    private companion object {
        /** Ampiezza della finestra restituita da ViaggiaTreno, misurata. */
        const val WINDOW_MINUTES = 90L

        /** Quante finestre saltare quando sono tutte di corse gia' andate. */
        const val EXTRA_WINDOWS = 2

        /**
         * Quanti sospetti interrogare per volta.
         *
         * I soppressi sono pochi anche nelle giornate storte: alzare il tetto
         * significherebbe pagare chiamate per corse che semplicemente non sono
         * tracciate, non per corse cancellate.
         */
        const val MAX_SOSPETTE = 6
    }

    /**
     * Propone le stazioni piu' vicine, senza aprirne nessuna.
     *
     * Chiamata dopo che il permesso e' stato concesso: la posizione arriva dalla
     * UI. La piu' vicina in linea d'aria non e' sempre quella che serve: dentro
     * un nodo urbano ce ne sono tre nel raggio di un chilometro, e quale sia la
     * propria lo sa solo chi ci sta in mezzo.
     */
    fun proposeNearest(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _state.update { it.copy(locatingNearest = true, message = null) }
            val vicine = runCatching { stationsRepo.nearest(latitude, longitude) }
                .getOrDefault(emptyList())
            _state.update { it.copy(locatingNearest = false) }
            when {
                vicine.isEmpty() -> _state.update {
                    it.copy(message = "Nessuna stazione trovata nei dintorni.")
                }
                // Con una sola candidata non c'e' niente da scegliere.
                vicine.size == 1 -> select(vicine.first().station)
                else -> _state.update {
                    it.copy(nearby = vicine, suggestions = emptyList(), suggestionsOpen = true)
                }
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
