package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class ResultsViewModel(
    private val from: Station,
    private val to: Station,
    private val departure: LocalDateTime,
) : ViewModel() {

    private val journeys = ServiceLocator.journeyRepository
    private val trains = ServiceLocator.trainStatusRepository

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    /** Il realtime esiste solo per oggi: su altre date non ha senso nemmeno provarci. */
    private val isToday: Boolean = departure.toLocalDate() == LocalDate.now()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    realtimeAvailable = isToday,
                    noMoreEarlier = false,
                    noMoreLater = false,
                )
            }

            val outcome = runCatching { journeys.searchAll(from, to, departure, limit = PAGE) }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Ricerca non riuscita: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }
            val list = outcome.journeys

            val rows = list.map { JourneyRow(it, loadingStatus = isToday && it.hasTrain) }
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
                val batch = runCatching { journeys.searchAll(from, to, clamped, limit = WIDE_PAGE) }
                    .getOrNull()?.journeys.orEmpty()
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
            val rows = found.map { JourneyRow(it, loadingStatus = isToday && it.hasTrain) }
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
                journeys.searchAll(from, to, last.plusMinutes(1), limit = WIDE_PAGE)
            }.getOrNull()?.journeys.orEmpty()

            val existing = current.journeys.map { it.key }.toSet()
            val rows = batch
                .filter { it.departure.isAfter(last) }
                .map { JourneyRow(it, loadingStatus = isToday && it.hasTrain) }
                .filter { it.key !in existing }
                .take(PAGE)

            _state.update { s ->
                s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty())
            }
            enrich(rows)
        }
    }

    /**
     * Arricchisce le righe indicate con lo stato del loro primo treno.
     *
     * Le chiamate partono in parallelo: in serie sarebbero una dozzina di
     * round-trip verso ViaggiaTreno e la lista resterebbe grigia per secondi.
     */
    private fun enrich(rows: List<JourneyRow>) {
        if (!isToday || rows.isEmpty()) {
            if (rows.isNotEmpty()) {
                _state.update { s ->
                    s.copy(journeys = s.journeys.map { it.copy(loadingStatus = false) })
                }
            }
            return
        }
        val date = departure.toLocalDate()

        viewModelScope.launch {
            val enriched = coroutineScope {
                rows.map { row ->
                    async {
                        // Solo i treni: interrogare ViaggiaTreno col "888A" di un bus
                        // sostitutivo e' una chiamata sprecata che fallisce sempre.
                        val number = row.journey.legs.firstOrNull { it.isTrain }?.trainNumber
                            ?: return@async row.copy(loadingStatus = false)
                        val status = runCatching { trains.statusByNumber(number, date) }.getOrNull()
                        row.copy(
                            loadingStatus = false,
                            state = status?.state,
                            delayMinutes = status?.delayMinutes,
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

    private companion object {
        /** Quante corse per volta, avanti o indietro. */
        const val PAGE = 5

        /** Si chiede piu' del necessario perche' molte cadono fuori finestra. */
        const val WIDE_PAGE = 15
    }
}
