package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
}

data class ResultsUiState(
    val loading: Boolean = true,
    val journeys: List<JourneyRow> = emptyList(),
    val realtimeAvailable: Boolean = true,
    val error: String? = null,
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
            _state.update { it.copy(loading = true, error = null, realtimeAvailable = isToday) }

            val result = runCatching { journeys.search(from, to, departure, limit = 12) }
            result.onFailure { e ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = "Ricerca non riuscita: ${e.message ?: "errore di rete"}",
                    )
                }
            }
            val list = result.getOrNull() ?: return@launch

            _state.update {
                it.copy(
                    loading = false,
                    journeys = list.map { j -> JourneyRow(j, loadingStatus = isToday) },
                )
            }

            if (isToday) enrichWithRealtime()
        }
    }

    /**
     * Arricchisce ogni soluzione con lo stato del suo primo treno.
     *
     * Le chiamate partono in parallelo: in serie sarebbero una dozzina di round-trip
     * verso ViaggiaTreno e la lista resterebbe grigia per parecchi secondi.
     */
    private suspend fun enrichWithRealtime() {
        val rows = _state.value.journeys
        val date = departure.toLocalDate()

        val enriched = viewModelScope.async {
            rows.map { row ->
                async {
                    val number = row.journey.legs.firstOrNull()?.trainNumber
                        ?: return@async row.copy(loadingStatus = false)
                    val status = runCatching { trains.statusByNumber(number, date) }.getOrNull()
                    row.copy(
                        loadingStatus = false,
                        state = status?.state,
                        delayMinutes = status?.delayMinutes,
                    )
                }
            }.awaitAll()
        }.await()

        _state.update { it.copy(journeys = enriched) }
    }
}
