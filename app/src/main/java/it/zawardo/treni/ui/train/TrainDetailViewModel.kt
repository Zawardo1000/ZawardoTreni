package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TrainDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val status: TrainStatus? = null,
    /** Distinguere "non esiste" da "non c'e' il realtime" cambia il messaggio da mostrare. */
    val realtimeUnavailable: Boolean = false,
    val error: String? = null,
)

class TrainDetailViewModel(
    private val trainNumber: String,
    private val date: LocalDate,
) : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository

    private val _state = MutableStateFlow(TrainDetailUiState())
    val state: StateFlow<TrainDetailUiState> = _state.asStateFlow()

    private var autoRefresh: Job? = null

    init {
        load(initial = true)
        startAutoRefresh()
    }

    fun refresh() = load(initial = false)

    private fun load(initial: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = initial, refreshing = !initial, error = null) }

            val status = runCatching { trains.statusByNumber(trainNumber, date) }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            error = "Aggiornamento non riuscito: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }

            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    status = status ?: it.status,
                    // 204 su una data non odierna significa "dato inesistente", non "errore".
                    realtimeUnavailable = status == null && it.status == null,
                )
            }
        }
    }

    /**
     * Aggiornamento ogni 60 s finche' la schermata e' viva. Non e' il "segui treno":
     * quello sopravvive alla chiusura dell'app e usa un foreground service.
     */
    private fun startAutoRefresh() {
        autoRefresh?.cancel()
        autoRefresh = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                val s = _state.value.status
                // Su un treno gia' arrivato non c'e' piu' niente da aggiornare.
                if (s != null && s.state == TrainState.ARRIVED) break
                load(initial = false)
            }
        }
    }

    override fun onCleared() {
        autoRefresh?.cancel()
        super.onCleared()
    }
}
