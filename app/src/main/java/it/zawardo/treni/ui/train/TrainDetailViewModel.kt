package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.oggiInItalia
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
    origineCorsa: String? = null,
    alightingName: String? = null,
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
        origineCorsa = origineCorsa,
        alightingName = alightingName,
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

    /**
     * Ricarica la corsa, **se non si sta gia' caricando**.
     *
     * La guardia non e' un dettaglio: `ON_RESUME` arriva anche all'apertura della
     * schermata, quando `init` sta gia' leggendo. Senza, ogni apertura costava due
     * volte la cascata intera — due `corsaDelGiorno` e due raffiche di tabelloni
     * per un giorno futuro — e, peggio, il secondo giro spegneva `loading`: la
     * schermata mostrava «Treno non trovato» per tutti i secondi della prima
     * lettura, per poi riempirsi di colpo.
     */
    fun refresh() {
        val ora = _state.value
        if (ora.loading || ora.refreshing) return
        load(initial = false, manual = true)
    }

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
                    futureDate = date.isAfter(oggiInItalia()),
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
            var aVuoto = 0
            while (isActive) {
                delay(60_000)
                val s = _state.value.status
                // Su un treno gia' arrivato non c'e' piu' niente da aggiornare,
                // e nemmeno su una corsa che viene dall'orario: li' non c'e'
                // niente che possa cambiare fra un minuto e l'altro. Finche' il
                // treno viaggia si continua, anche quando ci sei sopra: e' il
                // momento in cui il ritardo cambia di piu'.
                if (s != null && (s.state == TrainState.ARRIVED || !s.realtime)) break
                /*
                 * Ma di una corsa che **non si e' mai vista** non si insiste per
                 * sempre: un numero che nessuna fonte conosce, o un servizio giu',
                 * avrebbero tenuto la schermata a interrogare ogni minuto finche'
                 * restava aperta. Dopo qualche tentativo si smette; basta tirare
                 * giu' per riprovare.
                 */
                if (s == null && ++aVuoto >= LETTURE_A_VUOTO) break
                if (s != null) aVuoto = 0
                load(initial = false)
            }
        }
    }

    override fun onCleared() {
        autoRefresh?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Quante letture a vuoto di fila prima di smettere di riprovare da soli. */
        const val LETTURE_A_VUOTO = 5
    }
}
