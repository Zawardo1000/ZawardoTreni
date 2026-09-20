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
    /**
     * L'id con cui **Le Frecce** conosce questo stesso posto, quando esiste.
     *
     * Serve alle stazioni fuori-RFI che hanno un gemello nazionale: Sorrento ha
     * il suo tabellone su EAV (codice `EAV62`, [locationId] sintetico), ma il BFF
     * la conosce anche come `830013838` e da li' sa venderci il bus+Freccia. La
     * dedup fonde i due in questa stazione, tenendo EAV per tabellone, badge e
     * misti, e appuntando qui l'indirizzo nazionale: senza, cercare una tratta da
     * Sorrento-EAV non darebbe nulla, perche' Le Frecce non instrada `EAV62`.
     * Nullo per le stazioni RFI (che gia' si instradano da se') e per le
     * fuori-RFI senza gemello nazionale.
     */
    val idNazionale: Long? = null,
) {
    val trackable: Boolean get() = rfiCode != null

    /**
     * La stessa stazione vista dal nazionale: se ha un [idNazionale], il suo
     * [locationId] diventa quello che Le Frecce sa instradare. Altrimenti se stessa.
     */
    fun perNazionale(): Station = idNazionale?.let { copy(locationId = it) } ?: this

    /**
     * L'identificativo che Le Frecce sa instradare, se c'e': ne' zero, ne' uno
     * inventato per una rete fuori-RFI. Chiasso nell'orario svizzero ha il codice
     * RFI ma un id suo, e mandato a Trenitalia non avrebbe trovato niente.
     */
    val idLeFrecce: Long?
        get() = perNazionale().locationId.takeIf { it in 1 until PRIMO_ID_FUORI_RFI }
}

/**
 * Il primo identificativo della fascia inventata per le reti fuori-RFI: sotto
 * stanno quelli veri del nazionale. Vedi le `LOCATION_ID_BASE` dei repository
 * fuori-RFI (9,0·10⁹ …).
 */
const val PRIMO_ID_FUORI_RFI = 9_000_000_000L

/** Una stazione vicina a un punto, con la distanza in linea d'aria in chilometri. */
data class NearbyStation(
    val station: Station,
    val distanceKm: Double,
)


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
    /**
     * La corsa si fa, ma salta la fermata da cui sali o quella a cui scendi: il
     * treno limitato. Per questo viaggio non serve, e [cancelled] e' vero; ma
     * soppresso non e', e la riga dice «Variato» (19/09/2026: l'S5 24543 era
     * soppressa da Varese a Rho Fiera e circolava da li' a Treviglio).
     */
    val variato: Boolean = false,
    val delayMinutes: Int? = null,
    /**
     * Quel che la fonte dice della soluzione, gia' scelto per essere letto: «Il
     * treno non effettua servizio viaggiatori», «Posti Esauriti sul treno 9588».
     * Oggi solo dal sito di Le Frecce: vedi `avvisiDelSito`.
     */
    val avvisi: List<String> = emptyList(),
    /**
     * Il prezzo piu' basso di questa soluzione, quando la sorgente lo pubblica.
     *
     * Null non vuol dire gratis: vuol dire **non lo so**. Lo espongono le due
     * sorgenti che vendono biglietti — il BFF Le Frecce e Trenord — e nemmeno
     * loro sempre: Trenitalia non prezza i viaggi dentro la zona urbana di
     * Milano, Trenord i treni che non vende, fuori dalla Lombardia. Le altre
     * sorgenti sono servizi di informazione sulla circolazione e un prezzo non
     * lo conoscono affatto.
     *
     * Trattare null come zero, o non distinguerlo da "esaurito", darebbe per
     * certo qualcosa che nessuno ha detto.
     */
    val price: Price? = null,
    /**
     * Il prezzo di **una parte** del viaggio, dichiarata come tale.
     *
     * Su un viaggio misto e' la gamba che lo pubblica (la Freccia di Trenitalia,
     * o Trenord) quando l'altra no (EAV, ARST, Italo). Su una soluzione Le Frecce
     * con un tratto urbano o in autobus e' la **parte in treno**, chiesta a
     * Trenord quando Le Frecce non la prezza: vedi [prezzoDaTrenord].
     *
     * Non e' il costo del viaggio — quello resta ignoto, e per questo [price] e'
     * null — ma dire la parte che si conosce, etichettata, e' piu' utile che
     * tacere tutto. Distinto da [price] apposta: [price] e' il totale di una
     * soluzione intera, questo e' un pezzo. Nullo quando nessuna parte ha un prezzo.
     */
    val partialPrice: Price? = null,
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
    /**
     * La fonte dichiara che il biglietto per questa partenza non si vende piu':
     * e' gia' passata in tabella. Trenord lo scrive per soluzione —
     * `PAST_DEPARTURE_DATE`, e nessun titolo allegato — e cosi' il RE 2844 del
     * 18/09/2026, ancora in banchina a +48, arrivava senza prezzo e senza un
     * perche'. Vedi `VenditaChiusa` nella lista dei risultati.
     */
    val venditaChiusa: Boolean = false,
    /**
     * I biglietti che compongono [price], quando sono piu' d'uno: vedi
     * `tratteDaBiglietto`. Vuota per la soluzione che si compra con un
     * biglietto solo, cioe' quasi sempre.
     */
    val biglietti: List<Biglietto> = emptyList(),
) {
    /**
     * Le tratte fatte con un mezzo: le camminate no. Passare a piedi dalla
     * superficie di Porta Garibaldi al Passante non e' un cambio, e un treno che
     * comincia li' e' diretto (deciso con l'utente il 19/09/2026): la camminata
     * conta solo dentro un cambio, per il tempo che ci vuole.
     */
    val mezzi: List<Leg> get() = legs.filterNot { it.isWalk }
    val changes: Int get() = (mezzi.size - 1).coerceAtLeast(0)
    val isDirect: Boolean get() = mezzi.size <= 1

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
    /**
     * Vero solo quando la fonte dice che i posti sono finiti: `soldOut` della
     * porta dell'app, `SOLD_OUT` di quella del sito. Non vendibile non vuol dire
     * esaurito: `NOT_SALEABLE`, `saleable` falso e `inhibited` dicono che quel
     * biglietto adesso non si vende, e scriverci «esaurito» era falso.
     */
    val esaurito: Boolean = false,
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
    /**
     * Chi vende il biglietto di questa tratta, quando la fonte lo dice: Trenord
     * lo scrive per treno (`train_operator`), e su una sua soluzione con dentro
     * un EuroCity di Trenitalia i biglietti sono due. Vedi `tratteDaBiglietto`.
     */
    val venditore: DataSource? = null,
    /**
     * Il codice RFI della stazione d'origine della corsa, quando la fonte lo dice
     * (Le Frecce, `bdoOrigin`): con numero e data e' la corsa esatta di
     * ViaggiaTreno, senza cercarla per numero. Vedi
     * `TrainStatusRepository.resolveFor`.
     */
    val origineCorsa: String? = null,
) {
    /** Solo i treni si possono seguire in tempo reale. */
    val isTrain: Boolean get() = kind == TransportKind.TRAIN && trainNumber != null

    /**
     * Il tratto urbano di Le Frecce ("UB"), da una stazione all'altra della
     * stessa citta'. Il prezzo della soluzione non lo comprende: lo scrive il
     * sito stesso, "Urban transport (not included in the price)".
     */
    val urbano: Boolean get() = category.equals("UB", ignoreCase = true)

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

/**
 * Una soluzione Le Frecce senza prezzo, con dei treni: il loro prezzo si puo'
 * chiedere a Trenord. Vedi `JourneyRepository.prezziDeiTreni`.
 *
 * Succede poco, da quando i prezzi si prendono dal sito di Trenitalia: nella
 * zona urbana di Milano, che Trenitalia non prezza e Trenord si', e quando il
 * sito non risponde.
 */
val Journey.prezzoDaTrenord: Boolean
    get() = source == JourneySource.LEFRECCE && !assembled &&
        price == null && partialPrice == null &&
        legs.any { it.isTrain }

/**
 * Se il prezzo dei treni e' il prezzo di tutto: vero quando la soluzione e' fatta
 * solo di treni. Con un tratto urbano o in autobus e' un prezzo parziale, e il
 * biglietto di quel tratto resta fuori. Una camminata no: non ha biglietto.
 */
val Journey.soloTreni: Boolean
    get() = legs.all { it.isTrain || it.isWalk }

/** Vero per gli stati che dicono "questa corsa, tutta o in parte, non si fa". */
val TrainState.soppressione: Boolean
    get() = this == TrainState.CANCELLED || this == TrainState.PARTIALLY_CANCELLED

/**
 * Vero per una corsa che viaggia, ma non come in orario: deviata, o soppressa
 * solo in parte.
 *
 * Accanto a questi stati **il ritardo resta**: sono due notizie, e l'una non
 * sostituisce l'altra. Chiesto il 18/09/2026 guardando il FR 9588, deviato per
 * un incendio e a +193, che in cima diceva soltanto «Percorso variato». Una
 * soppressione totale no: di un treno che non viaggia non c'e' ritardo da dire.
 */
val TrainState.variazione: Boolean
    get() = this == TrainState.DIVERTED || this == TrainState.PARTIALLY_CANCELLED

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
    /**
     * Falso quando questa corsa non viene da un tempo reale ma da un orario.
     *
     * Stessa distinzione di [BoardEntry.realtime], e per la stessa ragione:
     * [delayMinutes] e' un intero, e uno zero che significa "nessuno lo sa"
     * verrebbe letto come "confermato in orario". Succede su tre fronti — ARST,
     * che il tempo reale non lo pubblica affatto; EAV fuori dal suo monitor; e
     * qualunque rete per un **giorno diverso da oggi**, dove esiste solo
     * l'orario di tabella.
     *
     * Quando e' falso, di questa corsa si conoscono le fermate e gli orari
     * previsti e nient'altro: ritardo, binario reale e stato non sono assenti,
     * sono inconoscibili, e chi la mostra deve dirlo invece di disegnare un
     * treno puntuale.
     */
    val realtime: Boolean = true,
    /**
     * Perche' la corsa oggi non fa il suo percorso, quando qualcuno lo dice.
     *
     * Lo dice solo Trenord, e solo sulle sue corse: ViaggiaTreno scrive *cosa*
     * cambia in [notice] ma non il perche' — nel REG 2833 del 18/09/2026, col
     * capolinea spostato da Milano Centrale a Sesto, `provvedimenti`,
     * `anormalita`, `segnalazioni` e `motivoRitardoPrevalente` erano tutti
     * vuoti, mentre Trenord scriveva "Richiesta Impresa Ferroviaria". Il testo e'
     * quello della fonte, com'e'.
     */
    val motivo: String? = null,
    /**
     * Gli avvisi di circolazione che Trenord attacca alla corsa: spiegano il
     * ritardo quando il ritardo ha una causa sola, per esempio "Circolazione
     * fortemente rallentata, per accertamenti delle forze dell'ordine nella
     * stazione di MILANO ROGOREDO", che il 18/09/2026 stava su 61 corse lombarde
     * su 189.
     */
    val avvisi: List<String> = emptyList(),
    /**
     * Chi fa il treno, col `codiceCliente` di ViaggiaTreno (vedi [Imprese]); null
     * se la corsa viene da un'altra fonte, che non lo dice.
     */
    val impresa: Int? = null,
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
    /**
     * Fermata che la corsa oggi fa ma che il suo orario non ha: un capolinea
     * anticipato, o una stazione in piu' al posto di un treno soppresso.
     *
     * Va detto perche' da sola, fra le altre, sembra una fermata come tutte e
     * non spiega niente; accanto alla soppressa che sostituisce, racconta la
     * variazione. Solo ViaggiaTreno la dichiara.
     */
    val straordinaria: Boolean = false,
) {
    /** Se true, i minuti mostrati sono una proiezione e non una misura. */
    val isEstimate: Boolean get() = status == StopStatus.FUTURE

    /** L'orario da mostrare: reale se c'e', altrimenti proiettato. */
    val effectiveArrival: LocalDateTime? get() = actualArrival ?: projectedArrival
    val effectiveDeparture: LocalDateTime? get() = actualDeparture ?: projectedDeparture

    /** Il binario da mostrare: quello vero se c'e', altrimenti quello di tabella. */
    val platform: String? get() = binarioDaMostrare(scheduledPlatform, actualPlatform)

    val platformChanged: Boolean get() = binarioCambiato(scheduledPlatform, actualPlatform)

    /** L'altra meta' di [platformChanged]: vedi [binarioConfermato]. */
    val platformConfirmed: Boolean get() = binarioConfermato(scheduledPlatform, actualPlatform)
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
) {
    /** Come su [Stop]: il binario vero se c'e', altrimenti quello di tabella. */
    val platform: String? get() = binarioDaMostrare(scheduledPlatform, actualPlatform)

    /** Come su [Stop]: vedi [binarioCambiato], che ne spiega i limiti. */
    val platformChanged: Boolean get() = binarioCambiato(scheduledPlatform, actualPlatform)

    /** Come su [Stop]: il binario annunciato e' stato confermato tale e quale. */
    val platformConfirmed: Boolean get() = binarioConfermato(scheduledPlatform, actualPlatform)
}

/**
 * I `codiceCliente` di ViaggiaTreno: l'impresa che fa il treno, sulla corsa e
 * sulle righe dei tabelloni. Misurati il 19/09/2026 su 452 righe di sei stazioni
 * lombarde e sui campioni di `data/fonti`: sigla e codice combaciavano sempre.
 */
object Imprese {
    /** Frecce, e gli EuroCity di Trenitalia per la Svizzera. */
    const val FRECCE = 1
    const val REGIONALE_TRENITALIA = 2
    const val INTERCITY = 4
    const val TPER = 18
    const val TRENORD = 63
    /** Gli EuroCity DB-OBB per il Brennero. */
    const val DB_OBB = 64
}
