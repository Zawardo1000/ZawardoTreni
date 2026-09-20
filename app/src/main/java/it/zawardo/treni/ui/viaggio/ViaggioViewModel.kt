package it.zawardo.treni.ui.viaggio

import it.zawardo.treni.ui.common.ORA_DEL_GIORNO
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.oggiInItalia
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.ui.TrattaViaggio
import it.zawardo.treni.ui.train.CaricatoreCorsa
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Un tratto di percorso che si puo' chiudere dietro i tre punti.
 *
 * Sono due: le fermate **dopo la discesa**, quelle che il treno fa senza di te,
 * e dal secondo treno in poi quelle **prima della salita**, la strada che il
 * treno ha fatto prima di arrivare da te (accese l'11/09/2026, vedi
 * `primaDellaSalita`). Tutto il resto — lo stato di apertura, la riga coi punti,
 * il conteggio — non distingue fra i due.
 */
enum class BloccoNascosto {
    DOPO_LA_DISCESA,
    PRIMA_DELLA_SALITA,
}

/**
 * Una tratta del viaggio con quel che se ne e' potuto sapere.
 *
 * [espansi] e' l'unico pezzo di stato che appartiene solo allo schermo: dice
 * quali tratti chiusi sono stati riaperti a mano. Sta qui e non dentro la
 * schermata perche' deve sopravvivere al rinfresco automatico di ogni minuto —
 * altrimenti si richiuderebbero da sole sotto gli occhi di chi le aveva appena
 * aperte.
 */
data class TrattaUiState(
    val tratta: TrattaViaggio,
    val loading: Boolean = false,
    val status: TrainStatus? = null,
    val error: String? = null,
    val espansi: Set<BloccoNascosto> = emptySet(),
)

data class ViaggioUiState(
    val tratte: List<TrattaUiState> = emptyList(),
    val refreshing: Boolean = false,
    /** Solo per il trascinamento: il rinfresco automatico non fa girare niente. */
    val pulling: Boolean = false,
)

/**
 * Il viaggio intero, caricato tratta per tratta.
 *
 * Le corse si chiedono **in parallelo e si mostrano appena arrivano**: sono
 * chiamate indipendenti verso fonti diverse, e aspettare la piu' lenta per
 * disegnare tutto vorrebbe dire tenere la pagina bianca per il tempo del peggio.
 * Ogni tratta ha il suo errore: se la coincidenza non risponde, il treno su cui
 * stai salendo si vede lo stesso.
 */
class ViaggioViewModel(
    private val tratte: List<TrattaViaggio>,
) : ViewModel() {

    private val memory = ServiceLocator.trainMemory

    private val _state = MutableStateFlow(
        ViaggioUiState(tratte = tratte.map { TrattaUiState(it, loading = it.treno) }),
    )
    val state: StateFlow<ViaggioUiState> = _state.asStateFlow()

    /** I numeri che stanno fra i preferiti, per accendere la stella giusta. */
    val preferiti: StateFlow<Set<String>> = numeri()
        .let { numeri ->
            if (numeri.isEmpty()) {
                MutableStateFlow(emptySet())
            } else {
                combine(numeri.map { n -> memory.isFavorite(n).map { n to it } }) { coppie ->
                    coppie.filter { it.second }.map { it.first }.toSet()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var autoRefresh: Job? = null

    init {
        load(manual = false)
        startAutoRefresh()
    }

    /**
     * Ricarica le tratte, **se non si stanno gia' caricando**: come nel dettaglio
     * di una corsa, `ON_RESUME` arriva anche all'apertura, e senza guardia ogni
     * gamba del viaggio veniva interrogata due volte in parallelo — con la
     * rotella del trascinamento che girava senza che nessuno avesse trascinato.
     */
    fun refresh() {
        if (_state.value.refreshing) return
        load(manual = true)
    }

    /** Apre o richiude un tratto di percorso chiuso dietro i tre punti. */
    fun espandi(indice: Int, blocco: BloccoNascosto) {
        _state.update { s ->
            s.copy(
                tratte = s.tratte.mapIndexed { i, t ->
                    if (i != indice) t
                    else t.copy(
                        espansi = if (blocco in t.espansi) t.espansi - blocco else t.espansi + blocco,
                    )
                },
            )
        }
    }

    fun toggleFavorite(numero: String) {
        val gia = numero in preferiti.value
        val status = _state.value.tratte.firstOrNull { it.tratta.numero == numero }?.status
        viewModelScope.launch {
            memory.toggleFavorite(numero, !gia, status, System.currentTimeMillis())
        }
    }

    private fun numeri(): List<String> =
        tratte.filter { it.treno }.mapNotNull { it.numero }.distinct()

    private fun load(manual: Boolean) {
        _state.update { it.copy(refreshing = true, pulling = manual) }
        viewModelScope.launch {
            val lavori = tratte.mapIndexedNotNull { i, tratta ->
                if (!tratta.treno || tratta.numero == null) return@mapIndexedNotNull null
                launch { caricaTratta(i, tratta, tratta.numero) }
            }
            lavori.forEach { it.join() }
            _state.update { it.copy(refreshing = false, pulling = false) }
        }
    }

    private suspend fun caricaTratta(indice: Int, tratta: TrattaViaggio, numero: String) {
        aggiorna(indice) { it.copy(loading = it.status == null, error = null) }

        val esito = runCatching {
            CaricatoreCorsa(
                trainNumber = numero,
                date = tratta.giorno,
                boardingCode = tratta.salitaRfi,
                boardingAt = tratta.partenza,
                boardingName = tratta.salitaNome,
                alightingCode = tratta.discesaRfi,
                alightingName = tratta.discesaNome,
                origineCorsa = tratta.origineRfi,
            ).carica()
        }

        esito.onSuccess { status ->
            if (status != null) {
                runCatching { memory.recordOpened(numero, status, System.currentTimeMillis()) }
            }
            aggiorna(indice) {
                it.copy(
                    loading = false,
                    // Una risposta vuota non cancella quel che si sapeva prima:
                    // il rinfresco successivo puo' trovare la fonte muta.
                    status = status ?: it.status,
                    error = when {
                        status != null || it.status != null -> null
                        /*
                         * Di un giorno futuro le fermate le da' Le Frecce (vedi
                         * `CaricatoreCorsa.delGiornoDaLeFrecce`); se non risponde, il
                         * treno c'e' lo stesso, e dirlo "inesistente" sarebbe falso:
                         * si dice quel che la soluzione sa gia'.
                         */
                        tratta.giorno.isAfter(oggiInItalia()) ->
                            "Le fermate di questo treno per il giorno scelto adesso non " +
                                "si trovano. Sale a ${tratta.salitaNome} alle " +
                                "${tratta.partenza.format(ORA_DEL_GIORNO)}, scende a ${tratta.discesaNome} " +
                                "alle ${tratta.arrivo.format(ORA_DEL_GIORNO)}."
                        else -> "Di questa corsa non risulta nulla per il giorno scelto."
                    },
                )
            }
        }.onFailure { e ->
            aggiorna(indice) {
                it.copy(
                    loading = false,
                    error = "Aggiornamento non riuscito: ${e.message ?: "errore di rete"}",
                )
            }
        }
    }

    private fun aggiorna(indice: Int, blocco: (TrattaUiState) -> TrattaUiState) {
        _state.update { s ->
            s.copy(tratte = s.tratte.mapIndexed { i, t -> if (i == indice) blocco(t) else t })
        }
    }

    /**
     * Come sul dettaglio della corsa singola: ogni minuto, finche' c'e' qualcosa
     * che possa cambiare. Un viaggio tutto arrivato, o tutto ricavato
     * dall'orario, non ha piu' niente da chiedere a nessuno.
     */
    private fun startAutoRefresh() {
        autoRefresh?.cancel()
        autoRefresh = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                val vive = _state.value.tratte.any { t ->
                    val s = t.status
                    t.tratta.treno && t.tratta.giorno == oggiInItalia() &&
                        (s == null || (s.realtime && s.state != TrainState.ARRIVED))
                }
                if (!vive) break
                load(manual = false)
            }
        }
    }

    override fun onCleared() {
        autoRefresh?.cancel()
        super.onCleared()
    }
}

/** L'ora di salita e discesa nel messaggio di una corsa futura senza fermate. */
