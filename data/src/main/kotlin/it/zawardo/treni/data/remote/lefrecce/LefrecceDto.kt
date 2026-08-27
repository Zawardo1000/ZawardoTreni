package it.zawardo.treni.data.remote.lefrecce

import kotlinx.serialization.Serializable

/**
 * Stazione secondo il BFF Le Frecce.
 *
 * [bdoCode] e' il ponte verso ViaggiaTreno: e' esattamente il codice stazione
 * usato da quell'API (es. "S01700"). Vale la relazione
 * `locationId == 830000000 + bdoCode.drop(1).toLong()` per le stazioni RFI reali,
 * ma NON per fermate bus, multistazione ("Tutte le stazioni") e operatori non FS,
 * che hanno [bdoCode] null e vanno esclusi quando serve il realtime.
 */
@Serializable
data class LocationDto(
    val locationId: Long = 0,
    val name: String = "",
    val bdoCode: String? = null,
    val bdo: Boolean = false,
    val visible: Boolean = true,
    val multistation: Boolean = false,
    val bus: Boolean = false,
    val geographicCoordinates: CoordinatesDto? = null,
)

@Serializable
data class CoordinatesDto(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
)

/** Risposta di `/search`: apre una sessione di ricerca lato server. */
@Serializable
data class SearchResponseDto(
    val searchId: String = "",
    val totalSolutions: Int = 0,
    /** Il searchId scade circa 10 minuti dopo: oltre, `/solutions` risponde 410. */
    val expirationDate: String? = null,
)

@Serializable
data class SolutionDto(
    val id: SolutionIdDto? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    /** Durata totale in millisecondi. */
    val totalDuration: Long = 0,
    /** Sequenza delle categorie, una per tratta: es. ["RE","RE"] oppure ["FR"]. */
    val classificationAcronymsSequence: List<String> = emptyList(),
    val solutionNodes: List<SolutionNodeDto> = emptyList(),
)

@Serializable
data class SolutionIdDto(val travelSolutionId: Int = 0)

/**
 * Un elemento di `solutionNodes`. Il BFF ne usa **tre** tipi diversi:
 *
 *  - `SOLUTION_SEGMENT`: una tratta con il suo mezzo, il caso semplice;
 *  - `ROUTE_SEGMENT`: un raggruppamento che tiene le tratte vere dentro
 *    [subSegments]. E' la forma usata di norma per i viaggi regionali con
 *    cambio: ignorarla significa perdere del tutto quelle soluzioni;
 *  - `SOLUTION_LOCATION`: punto di interscambio o fermata bus, senza mezzo.
 */
@Serializable
data class SolutionNodeDto(
    val type: String? = null,
    val idXml: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val startLocation: LocationDto? = null,
    val endLocation: LocationDto? = null,
    val offeredTransportMeanDeparture: TransportMeanDto? = null,
    /** Fermate intermedie note al BFF; spesso incompleto, il dettaglio vero e' su ViaggiaTreno. */
    val transitNodes: List<LocationDto> = emptyList(),
    /** Valorizzato sui `ROUTE_SEGMENT`: contiene i veri `SOLUTION_SEGMENT`. */
    val subSegments: List<SolutionNodeDto> = emptyList(),
)

@Serializable
data class TransportMeanDto(
    /** Numero del treno, es. "9505". */
    val name: String? = null,
    val trainDescription: String? = null,
    val classification: ClassificationDto? = null,
)

@Serializable
data class ClassificationDto(
    /** FR, FA, FB, IC, ICN, EC, REG, RE, RV, MET, BU, UB... */
    val acronym: String? = null,
    val name: String? = null,
    /**
     * TRAIN, BUS, UNCLASSIFIED.
     *
     * Le tratte sostitutive sono BUS e i collegamenti urbani UNCLASSIFIED:
     * non hanno un numero treno interrogabile e vanno distinte, altrimenti
     * l'app promette un tempo reale che per loro non esiste.
     */
    val type: String? = null,
    /** Testo leggibile: "Autobus", "Urbano", "Frecciarossa"... */
    val classification: String? = null,
)
