package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.FiltroFonti
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.declaredState
import it.zawardo.treni.domain.model.soppressione
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/** Una soluzione più, se disponibile, il suo stato in tempo reale. */
data class JourneyRow(
    val journey: Journey,
    val loadingStatus: Boolean = false,
    val state: TrainState? = null,
    val delayMinutes: Int? = null,
) {
    /** Stabile fra un refresh e l'altro: evita che la lista salti sotto le dita. */
    val key: String
        get() = journey.departure.toString() + "|" +
            journey.legs.joinToString(",") { it.trainNumber ?: "?" }

    /**
     * Bus sostitutivi e collegamenti urbani non esistono su ViaggiaTreno.
     * Lasciare "stato in aggiornamento" all'infinito sarebbe una bugia.
     */
    val realtimePossible: Boolean get() = journey.hasTrain

    /**
     * Il tempo reale vale per il giorno della **soluzione**, non per quello
     * cercato.
     *
     * Non sono la stessa cosa: una ricerca fatta stasera puo' tornare corse di
     * domani mattina, e chiedere per quelle lo stato di oggi risponde con la
     * corsa sbagliata, quasi sempre gia' arrivata.
     */
    val isRealtimeDay: Boolean get() = journey.departure.toLocalDate() == LocalDate.now()

    /** Interrogabile davvero: un treno, e nella giornata in cui il dato esiste. */
    val realtimeNow: Boolean get() = realtimePossible && isRealtimeDay
}

data class ResultsUiState(
    val loading: Boolean = true,
    val loadingEarlier: Boolean = false,
    val loadingLater: Boolean = false,
    val journeys: List<JourneyRow> = emptyList(),
    val realtimeAvailable: Boolean = true,
    val noMoreEarlier: Boolean = false,
    val noMoreLater: Boolean = false,
    val error: String? = null,
    /**
     * Vero quando nessuna corsa cade nel giorno richiesto.
     *
     * Succede nei casi eccezionali: linea chiusa per lavori, servizio sostituito
     * da bus, ultimo treno gia' passato. Il BFF non manda alcun avviso, quindi
     * la condizione va dedotta e dichiarata: due corse notturne di domani,
     * mostrate senza spiegazione, sembrano un guasto dell'app.
     */
    val noSameDayResults: Boolean = false,
    /** Avvisi di servizio: lavori, sospensioni, bus sostitutivi. Solo da Trenord. */
    val alerts: List<ServiceAlert> = emptyList(),
    val directOnly: Boolean = false,
    /** Sta ancora cercando i viaggi misti (beta), che arrivano dopo i diretti. */
    val loadingMisti: Boolean = false,
)

class ResultsViewModel(
    private val from: Station,
    private val to: Station,
    private val departure: LocalDateTime,
    private val directOnly: Boolean = false,
) : ViewModel() {

    private val journeys = ServiceLocator.journeyRepository
    private val misti = ServiceLocator.viaggiMistiRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    private val italo = ServiceLocator.italoRepository
    private val trains = ServiceLocator.trainStatusRepository
    private val settings = ServiceLocator.settings

    /**
     * Le reti accese, tenute aggiornate mentre la schermata vive: se l'utente
     * ne spegne una e torna qui, la prossima ricerca la salta.
     */
    private var sources: Set<DataSource> = DataSource.defaultEnabled

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    /**
     * Solo per il cartello in cima alla lista: la data cercata non e' oggi.
     *
     * Quale riga sia interrogabile lo decide la riga stessa, dalla propria data
     * di partenza: vedi [JourneyRow.isRealtimeDay].
     */
    private val isToday: Boolean = departure.toLocalDate() == LocalDate.now()

    init {
        viewModelScope.launch {
            // La prima ricerca deve gia' sapere quali reti sono accese, o
            // partirebbe col default ignorando chi l'utente ha spento.
            sources = runCatching { settings.enabledSources.first() }.getOrDefault(sources)
            reload()
        }
        viewModelScope.launch { settings.enabledSources.collect { sources = it } }
    }

    fun reload() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    directOnly = directOnly,
                    realtimeAvailable = isToday,
                    noMoreEarlier = false,
                    noMoreLater = false,
                )
            }

            val outcome = runCatching { journeys.searchAll(from, to, departure, limit = PAGE, sources = sources) }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Ricerca non riuscita: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }
            /*
             * I diretti sulle reti fuori-RFI, quando le due punte sono della
             * stessa rete: Sorrento→Napoli su EAV, Sassari→Nuoro su ARST. Il BFF
             * non conosce quelle stazioni e i viaggi misti richiedono l'alta
             * velocita', quindi senza questo passo una tratta tutta-EAV o
             * tutta-ARST resterebbe senza risultati, ora che quelle stazioni si
             * possono scegliere. Escono senza tempo reale, come la loro rete.
             */
            val fuoriRfi = direttiFuoriRfi(sources)

            /*
             * Il filtro si applica dopo, non prima: le sorgenti non sanno
             * filtrare i cambi, e chiedere meno risultati per poi scartarne una
             * parte svuoterebbe la lista. Per questo si chiede piu' del dovuto.
             */
            val list = (outcome.journeys + fuoriRfi)
                .applyDirectFilter()
                .sortedBy { it.departure }

            val rows = list.map { it.toRow() }
            val requestedDay = departure.toLocalDate()
            _state.update {
                it.copy(
                    loading = false,
                    journeys = rows,
                    alerts = outcome.alerts,
                    noSameDayResults = rows.isNotEmpty() &&
                        rows.none { r -> r.journey.departure.toLocalDate() == requestedDay },
                )
            }
            enrich(rows)
            cercaAltreSoluzioni(direttoMigliore = list.minByOrNull { it.duration }?.duration)
        }
    }

    /**
     * I viaggi diretti quando partenza e arrivo sono della stessa rete fuori-RFI.
     *
     * Solo EAV e ARST, che un orario ce l'hanno da cui ricavare gli itinerari.
     * Ferrotramviaria e Vigezzina hanno il solo tabellone di stazione, da cui una
     * ricerca A→B non si ricava: per ora quelle tratte interne restano scoperte.
     */
    private suspend fun direttiFuoriRfi(sources: Set<DataSource>): List<Journey> {
        val f = from.rfiCode ?: return emptyList()
        val t = to.rfiCode ?: return emptyList()
        val giorno = departure.toLocalDate()
        return when {
            DataSource.EAV in sources && eav.covers(f) && eav.covers(t) ->
                eav.itinerario(f, t, giorno)
            DataSource.ARST in sources && arst.covers(f) && arst.covers(t) ->
                arst.itinerario(f, t, giorno)
            else -> emptyList()
        }
    }

    /**
     * Le soluzioni che la ricerca principale non copre, cercate **dopo** e in
     * asincrono: sono lente (rete, spesso vuote) e i diretti Trenitalia/Trenord
     * sono gia' a schermo.
     *
     *  - **Italo diretti** su una tratta tutta-Italo (es. Napoli→Roma): la ricerca
     *    A→B interroga solo Le Frecce e Trenord, Italo no. Come i diretti EAV/ARST,
     *    ma via rete e solo per oggi — il suo real-time non va oltre. Non serve la beta.
     *  - **Viaggi misti** (beta): feeder fuori-RFI piu' alta velocita'.
     *
     * EAV e ARST diretti restano invece **sincroni** (orario imbarcato, istantaneo,
     * e spesso sono l'unico risultato della tratta): vedi [direttiFuoriRfi].
     */
    private fun cercaAltreSoluzioni(direttoMigliore: java.time.Duration?) {
        viewModelScope.launch {
            if (!componeAltre()) return@launch
            _state.update { it.copy(loadingMisti = true) }
            val trovate = altreSoluzioni(departure, direttoMigliore)
            _state.update { s ->
                val gia = s.journeys.map { it.key }.toHashSet()
                // Ne' i misti ne' i diretti Italo si arricchiscono col tempo reale
                // aggregato: le loro corse stanno fuori da ViaggiaTreno e il realtime
                // si legge aprendo la singola corsa. Nascono quindi gia' "fermi"
                // (loadingStatus = false). Solo dall'ora cercata in avanti, come la
                // ricerca principale: Italo traccia anche le corse gia' partite e un
                // misto puo' avere un feeder mattutino, orari che non si prendono piu'.
                val nuove = trovate
                    .filter { !it.departure.isBefore(departure) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in gia }
                s.copy(
                    loadingMisti = false,
                    journeys = (s.journeys + nuove).sortedBy { it.journey.departure },
                )
            }
        }
    }

    /** Vero se la tratta puo' comporre misti o Italo diretti: decide il velo. */
    private suspend fun componeAltre(): Boolean {
        val betaAttivo = runCatching { settings.viaggiMisti.first() }.getOrDefault(false)
        val vuoleMisti = FiltroFonti.componiMisti(soloDiretti = directOnly, betaAttivo = betaAttivo)
        val vuoleItalo = DataSource.ITALO in sources &&
            italo.covers(from.rfiCode) && italo.covers(to.rfiCode)
        return vuoleMisti || vuoleItalo
    }

    /**
     * I misti (beta) e gli Italo diretti in partenza da [quando]; la finestra la
     * ritaglia chi chiama (avanti o indietro).
     *
     * Self-gating: torna vuoto se la tratta non ne compone. La usano sia la prima
     * ricerca sia la paginazione: su una tratta di soli misti — Sorrento-EAV, che
     * il BFF non conosce e per cui `searchAll` e' muto — e' l'unico modo perche'
     * «corse precedenti/successive» non restino vuote.
     */
    private suspend fun altreSoluzioni(
        quando: LocalDateTime,
        direttoMigliore: java.time.Duration?,
    ): List<Journey> {
        val betaAttivo = runCatching { settings.viaggiMisti.first() }.getOrDefault(false)
        val vuoleMisti = FiltroFonti.componiMisti(soloDiretti = directOnly, betaAttivo = betaAttivo)
        val vuoleItalo = DataSource.ITALO in sources &&
            italo.covers(from.rfiCode) && italo.covers(to.rfiCode)
        val italoDiretti = if (vuoleItalo) {
            runCatching { italo.itinerario(from.rfiCode!!, to.rfiCode!!, quando.toLocalDate()) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val mistiJ = if (vuoleMisti) {
            runCatching { misti.cerca(from, to, quando, direttoMigliore, sources) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return italoDiretti + mistiJ
    }

    /**
     * Corse precedenti alla prima mostrata.
     *
     * Il BFF non sa tornare indietro: `/search` restituisce sempre soluzioni
     * *successive* all'orario chiesto. Quindi si riparte da qualche ora prima e
     * si tengono le ultime che cadono prima di quella gia' in cima. Se la tratta
     * e' scarsa la finestra si allarga una volta sola, poi si smette.
     */
    fun loadEarlier() {
        val current = _state.value
        if (current.loadingEarlier || current.noMoreEarlier) return
        val first = current.journeys.firstOrNull()?.journey?.departure ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingEarlier = true) }

            var found = emptyList<Journey>()
            for (hoursBack in intArrayOf(3, 8)) {
                val start = first.minusHours(hoursBack.toLong())
                // Prima dell'inizio del giorno non c'e' niente da cercare.
                val clamped = maxOf(start, first.toLocalDate().atStartOfDay())
                // searchAll e non search: anche andando indietro le corse Trenord
                // devono comparire, altrimenti la lista cambia natura scorrendo.
                val batch = runCatching { journeys.searchAll(from, to, clamped, limit = WIDE_PAGE, sources = sources) }
                    .getOrNull()?.journeys.orEmpty()
                    .applyDirectFilter()
                    .filter { it.departure.isBefore(first) }
                if (batch.isNotEmpty()) {
                    found = batch.takeLast(PAGE)
                    break
                }
                if (clamped == first.toLocalDate().atStartOfDay()) break
            }

            if (found.isEmpty()) {
                // Tratta di soli misti (Sorrento-EAV: il BFF non la conosce):
                // searchAll e' muto, ma feeder e Freccia girano anche prima. Si
                // pesca la finestra precedente dei misti — gia' "fermi", come nella
                // prima ricerca, e senza arricchimento in tempo reale.
                val anchor = maxOf(first.minusHours(4), first.toLocalDate().atStartOfDay())
                val existing = current.journeys.map { it.key }.toSet()
                val rows = altreSoluzioni(anchor, null)
                    .filter { it.departure.isBefore(first) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in existing }
                    .sortedBy { it.journey.departure }
                    .takeLast(PAGE)
                _state.update { s ->
                    s.copy(
                        loadingEarlier = false,
                        journeys = rows + s.journeys,
                        noMoreEarlier = rows.isEmpty(),
                    )
                }
                return@launch
            }

            val existing = current.journeys.map { it.key }.toSet()
            val rows = found.map { it.toRow() }
                .filter { it.key !in existing }

            _state.update { s ->
                s.copy(loadingEarlier = false, journeys = rows + s.journeys, noMoreEarlier = rows.isEmpty())
            }
            enrich(rows)
        }
    }

    /** Corse successive all'ultima mostrata: qui il BFF lavora nella sua direzione naturale. */
    fun loadLater() {
        val current = _state.value
        if (current.loadingLater || current.noMoreLater) return
        val last = current.journeys.lastOrNull()?.journey?.departure ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingLater = true) }

            val batch = runCatching {
                journeys.searchAll(from, to, last.plusMinutes(1), limit = WIDE_PAGE, sources = sources)
            }.getOrNull()?.journeys.orEmpty().applyDirectFilter()
                .filter { it.departure.isAfter(last) }

            val existing = current.journeys.map { it.key }.toSet()

            if (batch.isEmpty()) {
                // Tratta di soli misti: come per «corse precedenti», la finestra
                // successiva la danno i misti — gia' "fermi", niente arricchimento.
                val rows = altreSoluzioni(last.plusMinutes(1), null)
                    .filter { it.departure.isAfter(last) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in existing }
                    .sortedBy { it.journey.departure }
                    .take(PAGE)
                _state.update { s ->
                    s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty())
                }
                return@launch
            }

            val rows = batch
                .map { it.toRow() }
                .filter { it.key !in existing }
                .take(PAGE)

            _state.update { s ->
                s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty())
            }
            enrich(rows)
        }
    }

    /**
     * Riga pronta da mostrare, gia' con quel che la sorgente dichiara.
     *
     * Trenord manda soppressione e ritardo insieme alla soluzione, e per le
     * linee S sono l'unico dato che esistera' mai: ViaggiaTreno quelle corse non
     * le conosce. Partire da li' vuol dire che un treno soppresso si vede subito,
     * anche quando l'interrogazione successiva non trovera' nulla.
     */
    private fun Journey.toRow(): JourneyRow {
        val row = JourneyRow(this, state = declaredState, delayMinutes = delayMinutes)
        return row.copy(loadingStatus = row.realtimeNow)
    }

    /**
     * Arricchisce le righe indicate con lo stato del loro primo treno.
     *
     * Le chiamate partono in parallelo: in serie sarebbero una dozzina di
     * round-trip verso ViaggiaTreno e la lista resterebbe grigia per secondi.
     */
    private fun enrich(rows: List<JourneyRow>) {
        // Ogni riga vale per il proprio giorno: quelle di domani non si chiedono.
        val interrogabili = rows.filter { it.realtimeNow }
        if (interrogabili.isEmpty()) return

        viewModelScope.launch {
            val enriched = coroutineScope {
                interrogabili.map { row ->
                    async {
                        // Solo i treni: interrogare ViaggiaTreno col "890A" di un bus
                        // sostitutivo e' una chiamata sprecata che fallisce sempre.
                        val leg = row.journey.legs.firstOrNull { it.isTrain }
                            ?: return@async row.copy(loadingStatus = false)
                        val number = leg.trainNumber
                            ?: return@async row.copy(loadingStatus = false)
                        val status = runCatching {
                            /*
                             * Data e stazione di salita sono della tratta, non
                             * della ricerca: lo stesso numero torna ogni giorno e
                             * puo' appartenere a due treni diversi. Senza, la
                             * soluzione delle 01:31 di domani ereditava la corsa
                             * di stamattina, gia' arrivata.
                             */
                            trains.statusByNumber(
                                trainNumber = number,
                                date = row.journey.departure.toLocalDate(),
                                boardingCode = leg.from.rfiCode,
                                boardingAt = leg.departure,
                            )
                        }.getOrNull()
                        row.copy(
                            loadingStatus = false,
                            /*
                             * La soppressione dichiarata dalla sorgente resta:
                             * di un treno soppresso ViaggiaTreno non ha nemmeno
                             * il record, e il suo silenzio non e' una smentita.
                             */
                            state = row.journey.declaredState?.takeIf { it.soppressione }
                                ?: status?.state ?: row.state,
                            delayMinutes = status?.delayMinutes ?: row.delayMinutes,
                        )
                    }
                }.awaitAll()
            }
            val byKey = enriched.associateBy { it.key }
            _state.update { s ->
                s.copy(journeys = s.journeys.map { byKey[it.key] ?: it })
            }
        }
    }

    private fun List<Journey>.applyDirectFilter(): List<Journey> =
        if (directOnly) filter { it.isDirect } else this

    private companion object {
        /**
         * Quante corse per volta, avanti o indietro.
         *
         * Cinque risultavano pochi: la lista sembrava un tabellone troncato e
         * costringeva a chiedere subito le successive.
         */
        const val PAGE = 8

        /** Si chiede piu' del necessario perche' molte cadono fuori finestra. */
        const val WIDE_PAGE = 15
    }
}
