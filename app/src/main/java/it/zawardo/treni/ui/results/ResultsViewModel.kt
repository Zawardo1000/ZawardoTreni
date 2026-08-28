package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.DataSource
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
             * Il filtro si applica dopo, non prima: le sorgenti non sanno
             * filtrare i cambi, e chiedere meno risultati per poi scartarne una
             * parte svuoterebbe la lista. Per questo si chiede piu' del dovuto.
             */
            val list = outcome.journeys.applyDirectFilter()

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
            cercaMisti(direttoMigliore = list.minByOrNull { it.duration }?.duration)
        }
    }

    /**
     * Cerca i viaggi misti (beta) e li aggiunge in coda, senza rallentare i diretti.
     *
     * Parte **dopo** che la lista principale e' gia' a schermo, in una coroutine
     * sua, perche' la gamba Italo costa un paio di secondi e non deve pesare su
     * chi cerca Milano-Roma e vuole solo la lista pulita. Silenzioso quando il
     * flag e' spento o la tratta non e' del tipo giusto: nessun cartello, nessun
     * indicatore, come se la funzione non esistesse.
     */
    private fun cercaMisti(direttoMigliore: java.time.Duration?) {
        viewModelScope.launch {
            // Un misto ha sempre un cambio: con "solo diretti" attivo non lo si
            // vuole, e non ha senso spendere le chiamate per poi scartarlo.
            if (directOnly) return@launch
            val attivo = runCatching { settings.viaggiMisti.first() }.getOrDefault(false)
            if (!attivo) return@launch

            _state.update { it.copy(loadingMisti = true) }
            val trovati = runCatching { misti.cerca(from, to, departure, direttoMigliore) }
                .getOrDefault(emptyList())

            _state.update { s ->
                // I misti non si arricchiscono col tempo reale aggregato: le loro
                // gambe sono di reti diverse e il realtime si legge aprendo la
                // singola corsa. Si aggiungono evitando i doppioni con la lista.
                val gia = s.journeys.map { it.key }.toHashSet()
                val nuove = trovati.map { it.toRow() }.filter { it.key !in gia }
                s.copy(loadingMisti = false, journeys = s.journeys + nuove)
            }
        }
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
                _state.update { it.copy(loadingEarlier = false, noMoreEarlier = true) }
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

            val existing = current.journeys.map { it.key }.toSet()
            val rows = batch
                .filter { it.departure.isAfter(last) }
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
