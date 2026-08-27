package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.TrainRef
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
    val error: String? = null,
)

class TrainDetailViewModel(
    private val trainNumber: String,
    private val date: LocalDate,
    /**
     * Stazione da cui si sale, quando si arriva da una ricerca per tratta.
     *
     * Non e' un dettaglio: due treni diversi possono avere lo stesso numero
     * nello stesso giorno, e questa e' l'unica cosa che dice quale dei due sia
     * quello che si sta guardando.
     */
    private val boardingCode: String? = null,
    /** Ora di salita: distingue due corse dello stesso numero in giorni diversi. */
    private val boardingAt: LocalDateTime? = null,
    /** Nome della stazione di salita: serve a Italo, che di suo non lo dice. */
    private val boardingName: String? = null,
    /**
     * Corsa gia' identificata da chi ci ha portati qui.
     *
     * Tabellone ed elenco corse sanno esattamente di quale treno si tratta:
     * passarlo evita di ricercarlo per numero e, soprattutto, di sceglierne uno
     * diverso fra quelli che quel numero lo condividono.
     */
    private val originCode: String? = null,
    private val departureMillis: Long? = null,
) : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository
    private val trenord = ServiceLocator.trenordRepository
    private val italo = ServiceLocator.italoRepository
    private val memory = ServiceLocator.trainMemory

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

    private fun exactRef(): TrainRef? {
        val origine = originCode?.takeIf { it.isNotBlank() } ?: return null
        val millis = departureMillis?.takeIf { it > 0 } ?: return null
        return TrainRef(trainNumber, origine, millis)
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

            /*
             * ViaggiaTreno non copre tutto: sulle linee S del Passante milanese
             * non ha alcun dato. Quando non risponde si ripiega su Trenord,
             * che quelle corse le conosce.
             */
            val status = runCatching {
                exactRef()?.let { trains.status(it) }
                    ?: trains.statusByNumber(trainNumber, date, boardingCode, boardingAt)
                    // Con la data: senza, per una corsa di domani Trenord
                    // risponderebbe con quella di oggi.
                    ?: trenord.trainStatus(trainNumber, date)
                    // Ultima: le corse Italo, che nelle altre fonti non esistono.
                    ?: italo.trainStatus(trainNumber, date, boardingCode, boardingName)
            }
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
