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

/**
 * Da quale sorgente arriva una soluzione.
 *
 * Non e' un dettaglio interno: decide dove chiedere il tempo reale. ViaggiaTreno
 * non copre il Passante milanese, Trenord non copre le lunghe percorrenze.
 */
enum class JourneySource { LEFRECCE, TRENORD }

/** Avviso di servizio: lavori, sospensioni, servizi sostitutivi. */
data class ServiceAlert(
    val title: String?,
    val message: String,
    val severe: Boolean,
)

/** Una soluzione di viaggio: una o piu' tratte consecutive. */
data class Journey(
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val duration: Duration,
    val legs: List<Leg>,
    val source: JourneySource = JourneySource.LEFRECCE,
    /** Valorizzati quando la sorgente li espone: Trenord li fornisce, il BFF no. */
    val cancelled: Boolean = false,
    val delayMinutes: Int? = null,
) {
    val changes: Int get() = (legs.size - 1).coerceAtLeast(0)
    val isDirect: Boolean get() = legs.size <= 1

    /** Se nessuna tratta e' un treno, non c'e' alcun tempo reale da mostrare. */
    val hasTrain: Boolean get() = legs.any { it.isTrain }
}

/**
 * Che mezzo serve una tratta.
 *
 * Non tutte le soluzioni sono treni: i bus sostitutivi e i collegamenti urbani
 * non hanno un numero interrogabile su ViaggiaTreno. Confonderli con i treni
 * significa promettere un tempo reale che per loro non esistera' mai.
 */
enum class TransportKind { TRAIN, BUS, OTHER }

/** Una singola tratta del viaggio. */
data class Leg(
    val trainNumber: String?,
    val category: String?,
    val from: Station,
    val to: Station,
    val departure: LocalDateTime,
    val arrival: LocalDateTime,
    val kind: TransportKind = TransportKind.TRAIN,
    /** Testo leggibile fornito dal BFF: "Autobus", "Urbano", "Frecciarossa". */
    val kindLabel: String? = null,
) {
    /** Solo i treni si possono seguire in tempo reale. */
    val isTrain: Boolean get() = kind == TransportKind.TRAIN && trainNumber != null

    /** Es. "FR 9505", "Bus 888A", "Urbano". */
    val label: String
        get() = when (kind) {
            TransportKind.TRAIN -> listOfNotNull(category, trainNumber).joinToString(" ")
            TransportKind.BUS -> listOfNotNull("Bus", trainNumber).joinToString(" ")
            TransportKind.OTHER -> kindLabel ?: "Collegamento"
        }.ifBlank { kindLabel ?: "—" }
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
