package it.zawardo.treni.domain.model

import java.text.Collator
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

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

/** Una stazione vicina a un punto, con la distanza in linea d'aria in chilometri. */
data class NearbyStation(
    val station: Station,
    val distanceKm: Double,
)

/**
 * Ordinamento alfabetico dei nomi di stazione.
 *
 * Il [Collator] italiano serve davvero: i nomi arrivano dalle API come capita,
 * alcuni tutti maiuscoli ("PASCAROSA") e altri accentati ("Chatillon"), e un
 * confronto fra stringhe li spedirebbe in blocchi separati, lontani dalla lettera
 * a cui l'utente li cerca. Con [Collator.PRIMARY] maiuscole e accenti non contano.
 */
fun List<Station>.sortedByName(): List<Station> = sortedWith(compareBy(STATION_COLLATOR) { it.name })

private val STATION_COLLATOR: Collator = Collator.getInstance(Locale.ITALIAN).apply {
    strength = Collator.PRIMARY
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
    /**
     * Soppressa solo in parte: la corsa si fa, ma salta delle fermate che non
     * sono ne' quella da cui sali ne' quella a cui scendi.
     */
    val partiallyCancelled: Boolean = false,
    val delayMinutes: Int? = null,
    /**
     * Il prezzo piu' basso di questa soluzione, quando la sorgente lo pubblica.
     *
     * Null non vuol dire gratis: vuol dire **non lo so**. Lo espongono le due
     * sorgenti che vendono biglietti — il BFF Le Frecce e Trenord — e nemmeno
     * loro sempre: Trenitalia lo omette su circa una ricerca su cinque, Trenord
     * fuori dall'area tariffaria integrata. Le altre sorgenti sono servizi di
     * informazione sulla circolazione e un prezzo non lo conoscono affatto.
     *
     * Trattare null come zero, o non distinguerlo da "esaurito", darebbe per
     * certo qualcosa che nessuno ha detto.
     */
    val price: Price? = null,
    /**
     * Vero quando questo viaggio l'abbiamo **costruito noi** concatenando piu'
     * operatori, invece di riceverlo gia' fatto da una sorgente.
     *
     * Non e' la stessa cosa di "ha un cambio": il BFF Le Frecce restituisce
     * viaggi con cambio interni alla sua rete, e quelli non sono assemblati.
     * Assemblato e' solo cio' che passa dal motore dei viaggi misti, ed e' la
     * cosa che l'UI marca come beta, che tiene in coda ai diretti nel ranking, e
     * per cui avverte che il prezzo puo' essere parziale.
     */
    val assembled: Boolean = false,
) {
    val changes: Int get() = (legs.size - 1).coerceAtLeast(0)
    val isDirect: Boolean get() = legs.size <= 1

    /** Se nessuna tratta e' un treno, non c'e' alcun tempo reale da mostrare. */
    val hasTrain: Boolean get() = legs.any { it.isTrain }

    /** Le reti attraversate, senza ripetizioni e senza la gamba a piedi. */
    val sources: List<DataSource> get() = legs.mapNotNull { it.source }.distinct()

    /** Vero se il viaggio cambia operatore per strada. */
    val multiOperator: Boolean get() = sources.size > 1
}

/**
 * Il prezzo di una soluzione, con quel tanto di contesto che serve a non mentire.
 *
 * L'app **non vende biglietti** e non ha alcun rapporto commerciale con i
 * gestori: questa e' l'informazione che il servizio pubblica in quel momento,
 * per una persona adulta senza riduzioni, ed e' destinata a orientare, non a
 * impegnare nessuno. Il prezzo di un treno cambia con la disponibilita' anche
 * di ora in ora.
 *
 * [amount] e' tenuto come stringa e non come numero apposta: viene da un
 * decimale che ci e' stato dato gia' formattato, e passarlo per un `Double`
 * significherebbe prendersi gli errori di arrotondamento del binario in cambio
 * di nessun vantaggio, dato che qui non si fa un solo calcolo.
 */
data class Price(
    /** Decimale come lo manda il servizio: `"52.00"`. */
    val amount: String,
    val currency: String = "EUR",
    /** Falso quando quel prezzo esiste ma il biglietto non e' acquistabile ora. */
    val saleable: Boolean = true,
) {
    /** `52,00 €`, con la virgola che si usa scrivendo in italiano. */
    val formatted: String
        get() = amount.replace('.', ',') + " " + when (currency.uppercase()) {
            "EUR" -> "€"
            else -> currency
        }
}

/**
 * Che mezzo serve una tratta.
 *
 * Non tutte le soluzioni sono treni: i bus sostitutivi e i collegamenti urbani
 * non hanno un numero interrogabile su ViaggiaTreno. Confonderli con i treni
 * significa promettere un tempo reale che per loro non esistera' mai.
 */
enum class TransportKind {
    TRAIN,
    BUS,
    OTHER,

    /**
     * Un trasferimento a piedi fra due stazioni vicine di operatori diversi.
     *
     * Non e' un mezzo, e' l'assenza di mezzo: nasce solo dentro un viaggio misto
     * (vedi [Journey.assembled]), quando si cambia rete a Napoli scendendo da EAV
     * a Garibaldi e salendo su Italo a Centrale. Non si segue in tempo reale e
     * non ha numero: e' un tempo dichiarato, non una corsa.
     */
    WALK,
}

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
    /**
     * La rete che segue questa tratta in tempo reale.
     *
     * Sui viaggi a rete singola resta null: il tempo reale si risolve gia' per
     * numero di treno, in cascata. Serve invece sui **viaggi misti**, dove ogni
     * gamba appartiene a un operatore diverso e va interrogata alla sua fonte —
     * la gamba EAV al tabellone EAV, quella Italo al servizio Italo — perche' il
     * numero da solo non basta a dire chi lo conosce. Null anche sulla gamba a
     * piedi, che una fonte non ce l'ha.
     */
    val source: DataSource? = null,
) {
    /** Solo i treni si possono seguire in tempo reale. */
    val isTrain: Boolean get() = kind == TransportKind.TRAIN && trainNumber != null

    /** Vero per il trasferimento a piedi di un viaggio misto. */
    val isWalk: Boolean get() = kind == TransportKind.WALK

    /** Minuti di questa tratta, utile per il trasferimento a piedi. */
    val minutes: Long get() = java.time.Duration.between(departure, arrival).toMinutes()

    /** Es. "FR 9505", "Bus 890A", "Urbano", "10 min a piedi". */
    val label: String
        get() = when (kind) {
            TransportKind.TRAIN -> listOfNotNull(category, trainNumber).joinToString(" ")
            TransportKind.BUS -> listOfNotNull("Bus", trainNumber).joinToString(" ")
            TransportKind.WALK -> "${minutes.coerceAtLeast(1)} min a piedi"
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

/**
 * Una corsa trovata cercando per numero, con quel poco che serve a riconoscerla
 * senza aprirla.
 *
 * Il numero da solo non basta: puo' appartenere a due treni diversi lo stesso
 * giorno, e "Treno 20" non dice quale sia. La sigla e i capolinea invece si
 * leggono a colpo d'occhio, ma stanno nel dettaglio della corsa, non
 * nell'elenco: vanno chiesti apposta.
 */
data class TrainRun(
    val ref: TrainRef,
    val label: String,
    val origin: String?,
    val destination: String?,
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

/**
 * Lo stato che la soluzione dichiara da se', prima di qualsiasi interrogazione.
 *
 * Trenord pubblica soppressione e ritardo insieme alla soluzione; il BFF Le
 * Frecce no. Dove c'e', spesso e' l'unico dato esistente: sulle linee S del
 * Passante ViaggiaTreno non risponde, quindi ignorarlo significava mostrare un
 * treno soppresso come se partisse regolarmente.
 */
val Journey.declaredState: TrainState?
    get() = when {
        cancelled -> TrainState.CANCELLED
        partiallyCancelled -> TrainState.PARTIALLY_CANCELLED
        delayMinutes == null -> null
        delayMinutes > 0 -> TrainState.DELAYED
        else -> TrainState.REGULAR
    }

/** Vero per gli stati che dicono "questa corsa, tutta o in parte, non si fa". */
val TrainState.soppressione: Boolean
    get() = this == TrainState.CANCELLED || this == TrainState.PARTIALLY_CANCELLED

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
    /**
     * Falso quando la fermata risulta effettuata ma senza rilevamento.
     *
     * Gli orari ci sono e sono l'unica cosa che si ha, ma sono ricostruiti, non
     * misurati: succede dove mancano punti di rilevamento e sulla traversata in
     * traghetto. Vale la pena dirlo, perche' altrimenti quei minuti sembrano
     * precisi quanto gli altri.
     */
    val detected: Boolean = true,
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
    /**
     * Falso quando la riga viene da un orario e non da un tabellone.
     *
     * Non e' una sfumatura. Il resto del modello dice il ritardo con un intero,
     * e zero significa "in orario": una corsa di cui **nessuno sa niente**
     * arriverebbe qui con zero e verrebbe letta come confermata puntuale. Su
     * ARST, che un tempo reale non lo pubblica affatto, sarebbe la totalita'
     * delle righe.
     *
     * Chi mostra la riga deve dirlo. Il ritardo, il binario e la soppressione,
     * quando questo e' falso, non sono "assenti": sono *inconoscibili*, ed e'
     * un'informazione diversa che l'utente ha diritto di distinguere.
     */
    val realtime: Boolean = true,
)
