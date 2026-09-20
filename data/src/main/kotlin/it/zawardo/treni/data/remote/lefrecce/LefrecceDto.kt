package it.zawardo.treni.data.remote.lefrecce

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /** Il searchId scade 15 minuti dopo: oltre, `/solutions` risponde 410. */
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
    /**
     * Il prezzo piu' basso disponibile per questa soluzione, come stringa
     * decimale: `"52.00"`.
     *
     * Il BFF lo ripete in una decina di posti — dentro ogni offerta, dentro
     * ogni nodo, dentro `bookingInfo` — ma qui in cima e' gia' il totale del
     * viaggio, cambi compresi, che e' l'unica cifra sensata da mostrare in un
     * elenco di soluzioni.
     */
    val totalPrice: String? = null,
    val totalAmount: AmountDto? = null,
    /**
     * Se il biglietto e' acquistabile adesso.
     *
     * Va guardato: una soluzione puo' avere un prezzo e non essere vendibile —
     * esaurita, inibita, o fuori dalla finestra di vendita. Mostrare "52,00 €"
     * accanto a un treno che non si puo' prendere sarebbe peggio che tacere.
     */
    val saleable: Boolean? = null,
    val soldOut: Boolean? = null,
    val inhibited: Boolean? = null,
)

/**
 * Un importo col suo perche'.
 *
 * [showPrice] non e' decorativo: il BFF lo mette a falso quando il prezzo
 * esiste nei suoi archivi ma non va mostrato all'utente — tariffe riservate,
 * abbonamenti, soluzioni non commercializzate. Ignorarlo significherebbe
 * pubblicare cifre che Trenitalia stessa non pubblica.
 */
@Serializable
data class AmountDto(
    val amount: String? = null,
    val currency: String? = null,
    val showPrice: Boolean? = null,
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

// ------------------------------------------------ la ricerca del sito

/**
 * La ricerca come la fa il sito di Trenitalia: vedi `LefrecceApi.soluzioniDelSito`.
 * Tutti i campi espliciti, senza valori di default: il JSON dell'app non scrive i
 * default, e il sito li manda tutti.
 */
@Serializable
data class RichiestaSito(
    val departureLocationId: Long,
    val arrivalLocationId: Long,
    /** Ora locale senza fuso, come la scrive il sito: `2026-09-19T08:00:00.000`. */
    val departureTime: String,
    val adults: Int,
    val children: Int,
    val criteria: CriteriSito,
    val advancedSearchRequest: RicercaAvanzataSito,
)

@Serializable
data class CriteriSito(
    val frecceOnly: Boolean,
    val regionalOnly: Boolean,
    val intercityOnly: Boolean,
    val tourismOnly: Boolean,
    val noChanges: Boolean,
    val order: String,
    val offset: Int,
    /** Il sito ne restituisce dieci, qualunque sia il limite: si pagina con [offset]. */
    val limit: Int,
)

@Serializable
data class RicercaAvanzataSito(val bestFare: Boolean)

@Serializable
data class RispostaSito(
    val solutions: List<VoceSito> = emptyList(),
    /**
     * Il carrello della ricerca: con l'id di una soluzione apre le sue fermate
     * (`stops`). Vale solo col cookie della sessione che l'ha aperto, e per 13-16
     * minuti (vedi `data/fonti/LEFRECCE.md`).
     */
    val cartId: String? = null,
)

@Serializable
data class VoceSito(
    val solution: SoluzioneSito? = null,
    /** Accanto alla soluzione, non dentro; la lista puo' contenere dei null. */
    val messages: List<MessaggioSito?> = emptyList(),
)

/**
 * Un messaggio di una soluzione del sito: «Il treno non effettua servizio
 * viaggiatori», «Posti Esauriti sul treno 9588», «due convogli non
 * comunicanti». Vedi [avvisiDelSito].
 */
@Serializable
data class MessaggioSito(
    /** `INFO` o `WARNING`. */
    val status: String? = null,
    /** L'icona: `calendar`, `family`, `attention`, `UB` per il mezzo urbano, o null. */
    val imageId: String? = null,
    val message: String? = null,
)

@Serializable
data class SoluzioneSito(
    /** L'id della soluzione nel carrello: vedi [RispostaSito.cartId]. */
    val id: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    /** `SALEABLE`, `SOLD_OUT`, o `NOT_SALEABLE` per un biglietto che adesso non si compra. */
    val status: String? = null,
    val trains: List<TrenoSito> = emptyList(),
    val price: PrezzoSito? = null,
    /** Le tratte, con stazioni per nome e orari: il codice delle stazioni no. */
    val nodes: List<NodoSito> = emptyList(),
    /** I messaggi della voce che la contiene ([VoceSito.messages]), portati qui leggendola. */
    @Transient val messaggi: List<MessaggioSito> = emptyList(),
)

@Serializable
data class NodoSito(
    val origin: String? = null,
    /**
     * Il codice RFI della stazione d'**origine della corsa** (`S11781`), non di
     * questa tratta: con numero e data d'origine e' la chiave esatta di
     * `andamentoTreno`, 12 treni su 12 il 19/09/2026 (vedi `data/fonti/LEFRECCE.md`).
     * Null sul tratto urbano e a piedi.
     */
    val bdoOrigin: String? = null,
    val destination: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val train: TrenoSito? = null,
)

@Serializable
data class TrenoSito(
    val acronym: String? = null,
    /** Il numero del treno; null sul tratto urbano. */
    val name: String? = null,
    /** Il tratto urbano, che il prezzo non comprende ("not included in the price"). */
    val urban: Boolean = false,
    /** "WK" sulla camminata ("Walking route"), "UB" sul trasporto urbano vero. */
    val logoId: String? = null,
    /** "S5 TRENORD 24537": da qui la sigla della linea S. */
    val description: String? = null,
    val denomination: String? = null,
    val trainCategory: String? = null,
)

@Serializable
data class PrezzoSito(
    val amount: Double? = null,
    val hideAmount: Boolean = false,
)

/**
 * Una tratta di `stops?cartId&solutionId`: le fermate del pezzo percorso, con gli
 * orari **di quel giorno**, per qualunque data dell'orario (vedi
 * `JourneyRepository.corsaDelGiorno`). Niente binari: Le Frecce non ne ha.
 */
@Serializable
data class TrattaDelSito(
    val summary: SommarioTrattaSito? = null,
    val stops: List<FermataDelSito> = emptyList(),
)

@Serializable
data class SommarioTrattaSito(
    val trainInfo: TrenoSito? = null,
    /** Il codice RFI dell'origine della corsa, come in [NodoSito.bdoOrigin]. */
    val bdoOrigin: String? = null,
    val departureLocationName: String? = null,
    val arrivalLocationName: String? = null,
)

@Serializable
data class FermataDelSito(
    val location: LuogoDelSito? = null,
    /** Con il fuso: `2026-09-20T13:21:00.000+02:00`. Null all'ultima fermata. */
    val departureTime: String? = null,
    /** Null alla prima fermata della corsa; c'e' alla salita, che e' una sosta. */
    val arrivalTime: String? = null,
    val trainNumber: String? = null,
)

@Serializable
data class LuogoDelSito(
    /** L'id di Le Frecce: `830012328`, cioe' 83 piu' le cifre del codice RFI, quasi sempre. */
    val id: Long = 0,
    val name: String? = null,
)

/**
 * I messaggi di una soluzione che vale la pena mostrare: quelli che dicono
 * qualcosa che la scheda non dice gia'.
 *
 * Censiti il 19/09/2026 su 32 ricerche (`data/fonti/LEFRECCE.md`). Restano «Il
 * treno non effettua servizio viaggiatori» — il FR 9716 fra Venezia S.L. e
 * Mestre, una tratta non commerciale che nessun'altra fonte segnala —, «Posti
 * Esauriti sul treno 9588», che dice **quale** treno e' esaurito, e i due
 * convogli non comunicanti, per salire sulla parte giusta. Fuori il giorno
 * successivo (lo dicono gli orari), l'area family, il mezzo urbano (la tratta
 * urbana si vede gia'), e le frasi sulla vendita, che la colonna del prezzo dice
 * gia': di «Posti Esauriti sul treno 9588. Soluzione non acquistabile.» resta
 * la prima frase.
 */
fun avvisiDelSito(messaggi: List<MessaggioSito>): List<String> = messaggi
    .filter { it.imageId?.lowercase() !in ICONE_SENZA_NOTIZIA }
    .mapNotNull { m ->
        m.message
            ?.split(FINE_FRASE)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !SOLO_VENDITA.containsMatchIn(it) }
            ?.joinToString(" ") { if (it.endsWith('.')) it else "$it." }
            ?.takeIf { it.isNotBlank() }
    }
    .distinct()

private val ICONE_SENZA_NOTIZIA = setOf("calendar", "family", "ub")
private val FINE_FRASE = Regex("""(?<=\.)\s+""")
private val SOLO_VENDITA = Regex(
    """non\s+(?:e'|è)?\s*acquistabil|temporaneamente\s+non|impossibile\s+acquistare|non\s+sono\s+vendibil|non\s+vendibil""",
    RegexOption.IGNORE_CASE,
)

