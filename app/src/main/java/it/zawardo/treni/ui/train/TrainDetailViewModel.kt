package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class TrainDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /**
     * Solo per l'aggiornamento chiesto col gesto.
     *
     * L'indicatore del trascinamento deve rispondere a chi trascina: farlo
     * girare anche per il rinfresco automatico di ogni minuto sembrerebbe un
     * difetto, non un servizio.
     */
    val pulling: Boolean = false,
    val status: TrainStatus? = null,
    /** Distinguere "non esiste" da "non c'e' il realtime" cambia il messaggio da mostrare. */
    val realtimeUnavailable: Boolean = false,
    /**
     * La data cercata e' futura: cambia il perche' di un dato mancante. Non e'
     * un guasto ne' un "oggi non c'e' ancora", ma "quella corsa oggi non circola"
     * — il tempo reale esiste solo per oggi, e da li' non si ricava il suo orario.
     */
    val futureDate: Boolean = false,
    val error: String? = null,
)

class TrainDetailViewModel(
    private val trainNumber: String,
    private val date: LocalDate,
    boardingCode: String? = null,
    boardingAt: LocalDateTime? = null,
    boardingName: String? = null,
    alightingCode: String? = null,
    originCode: String? = null,
    departureMillis: Long? = null,
) : ViewModel() {

    private val memory = ServiceLocator.trainMemory

    /** La cascata delle fonti sta tutta li': vedi [CaricatoreCorsa]. */
    private val caricatore = CaricatoreCorsa(
        trainNumber = trainNumber,
        date = date,
        boardingCode = boardingCode,
        boardingAt = boardingAt,
        boardingName = boardingName,
        alightingCode = alightingCode,
        originCode = originCode,
        departureMillis = departureMillis,
    )

    private val _state = MutableStateFlow(TrainDetailUiState())
    val state: StateFlow<TrainDetailUiState> = _state.asStateFlow()

    /**
     * Preferito o no, letto dal database e non tenuto a parte: la stellina
     * resta d'accordo con la lista anche se il treno viene tolto da li'.
     */
    val isFavorite: StateFlow<Boolean> = memory.isFavorite(trainNumber)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var autoRefresh: Job? = null

    init {
        load(initial = true)
        startAutoRefresh()
    }

    fun refresh() = load(initial = false, manual = true)

    /**
     * Si salva il numero; nome e capolinea sono solo la descrizione con cui
     * ritrovarlo nella lista, presi da com'e' adesso.
     */
    fun toggleFavorite() {
        val wanted = !isFavorite.value
        viewModelScope.launch {
            memory.toggleFavorite(trainNumber, wanted, _state.value.status, System.currentTimeMillis())
        }
    }

    private fun load(initial: Boolean, manual: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = initial,
                    refreshing = !initial,
                    pulling = manual && !initial,
                    error = null,
                )
            }

            val status = runCatching { caricatore.carica() }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            pulling = false,
                            error = "Aggiornamento non riuscito: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }

            if (status != null) {
                runCatching {
                    memory.recordOpened(trainNumber, status, System.currentTimeMillis())
                }
            }

            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    pulling = false,
                    status = status ?: it.status,
                    // 204 su una data non odierna significa "dato inesistente", non "errore".
                    realtimeUnavailable = status == null && it.status == null,
                    futureDate = date.isAfter(LocalDate.now()),
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
                // Su un treno gia' arrivato non c'e' piu' niente da aggiornare,
                // e nemmeno su una corsa che viene dall'orario: li' non c'e'
                // niente che possa cambiare fra un minuto e l'altro.
                if (s != null && (s.state == TrainState.ARRIVED || !s.realtime)) break
                load(initial = false)
            }
        }
    }

    override fun onCleared() {
        autoRefresh?.cancel()
        super.onCleared()
    }
}
