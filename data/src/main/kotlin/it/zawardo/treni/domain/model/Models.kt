package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Una stazione.
 *
 * [rfiCode] (es. "S01700") e' il codice ViaggiaTreno ed e' l'unico che permette il
 * realtime. Puo' essere null per fermate bus o voci "tutte le stazioni" del BFF:
 * in quel caso la stazione e' cercabile ma il treno non e' tracciabile.
 */
data class Station(
    val rfiCode: String?,
    val locationId: Long,
    val name: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
) {
    val trackable: Boolean get() = rfiCode != null
}

/** Una soluzione di viaggio: una o piu' tratte consecutive. */
data class Journey(
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val duration: Duration,
    val legs: List<Leg>,
) {
    val changes: Int get() = (legs.size - 1).coerceAtLeast(0)
    val isDirect: Boolean get() = legs.size <= 1
}

/** Una singola tratta servita da un treno. */
data class Leg(
    val trainNumber: String?,
    val category: String?,
    val from: Station,
    val to: Station,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
) {
    /** Es. "FR 9505". */
    val label: String get() = listOfNotNull(category, trainNumber).joinToString(" ").ifBlank { "—" }
}

/** Riferimento univoco a una corsa, necessario per interrogare ViaggiaTreno. */
data class TrainRef(
    val number: String,
    val originCode: String,
    val departureDateMillis: Long,
    val originName: String? = null,
    val departureDate: LocalDate? = null,
)

enum class TrainState {
    /** In orario o in anticipo. */
    REGULAR,
    DELAYED,
    /** Soppresso integralmente. */
    CANCELLED,
    /** Soppresso su parte del percorso. */
    PARTIALLY_CANCELLED,
    /** Percorso variato o deviato. */
    DIVERTED,
    NOT_DEPARTED,
    ARRIVED,
}

enum class StopStatus { FUTURE, DONE, CURRENT, CANCELLED }

/** Stato completo di una corsa, da `andamentoTreno`. */
data class TrainStatus(
    val number: String,
    val category: String?,
    val label: String,
    val origin: String?,
    val destination: String?,
    /** Minuti; negativo = anticipo. */
    val delayMinutes: Int,
    val state: TrainState,
    /** Null finche' il treno non e' stato rilevato la prima volta. */
    val lastDetectionStation: String?,
    val lastDetectionTime: LocalDateTime?,
    val notice: String?,
    val stops: List<Stop>,
) {
    /** Indice dell'ultima fermata effettuata, -1 se non ancora partito. */
    val currentStopIndex: Int
        get() = stops.indexOfLast { it.status == StopStatus.DONE || it.status == StopStatus.CURRENT }
}

data class Stop(
    val index: Int,
    val stationName: String,
    val stationCode: String?,
    val scheduledArrival: LocalDateTime?,
    val actualArrival: LocalDateTime?,
    val arrivalDelayMinutes: Int,
    val scheduledDeparture: LocalDateTime?,
    val actualDeparture: LocalDateTime?,
    val departureDelayMinutes: Int,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val status: StopStatus,
    /**
     * Orari ricalcolati sul ritardo corrente della corsa, valorizzati solo per
     * le fermate non ancora effettuate.
     *
     * Servono perche' ViaggiaTreno **non** proietta il ritardo in avanti: su un
     * treno dichiarato a +8 tutte le fermate future arrivano con `ritardo = 0`.
     * Fidarsi di quel dato significherebbe dire all'utente che arrivera' in
     * orario mentre il treno accumula ritardo.
     */
    val projectedArrival: LocalDateTime? = null,
    val projectedDeparture: LocalDateTime? = null,
) {
    /** Se true, i minuti mostrati sono una proiezione e non una misura. */
    val isEstimate: Boolean get() = status == StopStatus.FUTURE

    /** L'orario da mostrare: reale se c'e', altrimenti proiettato. */
    val effectiveArrival: LocalDateTime? get() = actualArrival ?: projectedArrival
    val effectiveDeparture: LocalDateTime? get() = actualDeparture ?: projectedDeparture

    val platformChanged: Boolean
        get() = actualPlatform != null && scheduledPlatform != null && actualPlatform != scheduledPlatform
}

/** Voce di tabellone partenze/arrivi. */
data class BoardEntry(
    val trainRef: TrainRef,
    val label: String,
    val category: String?,
    /** Destinazione per le partenze, origine per gli arrivi. */
    val direction: String?,
    val scheduledTime: String?,
    val delayMinutes: Int,
    val scheduledPlatform: String?,
    val actualPlatform: String?,
    val state: TrainState,
    val inStation: Boolean,
)
