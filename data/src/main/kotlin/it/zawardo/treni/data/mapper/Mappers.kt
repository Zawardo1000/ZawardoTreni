package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.consolidate
import it.zawardo.treni.domain.model.nomeLeggibile
import it.zawardo.treni.domain.model.projectedBy
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

val ROME: ZoneId = ZoneId.of("Europe/Rome")

fun Long?.toRomeDateTime(): LocalDateTime? =
    this?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(ROME).toLocalDateTime() }

private fun String?.parseIso(): LocalDateTime? =
    this?.takeIf { it.isNotBlank() }?.let {
        runCatching { OffsetDateTime.parse(it).toLocalDateTime() }
            .getOrElse { _ -> runCatching { LocalDateTime.parse(this) }.getOrNull() }
    }

// ---------------------------------------------------------------- Le Frecce

fun LocationDto.toStation() = Station(
    // Solo le stazioni RFI hanno bdoCode: senza, il realtime non e' interrogabile.
    rfiCode = bdoCode?.takeIf { it.isNotBlank() && it != "S00000" },
    locationId = locationId,
    // Di solito arriva gia' scritto, ma non sempre: «BRESCIA POLIAMBULANZA».
    name = nomeLeggibile(name),
    latitude = geographicCoordinates?.latitude ?: 0.0,
    longitude = geographicCoordinates?.longitude ?: 0.0,
)

/**
 * Appiattisce l'albero dei nodi nelle sole tratte reali.
 *
 * Il BFF avvolge i viaggi regionali con cambio dentro un `ROUTE_SEGMENT` che
 * tiene le tratte in `subSegments`. Guardare solo i `SOLUTION_SEGMENT` di primo
 * livello faceva sparire quelle soluzioni: la lista mostrava le sole Frecce, e
 * su tratte servite solo da regionali poteva restare vuota.
 */
private fun List<SolutionNodeDto>.flattenSegments(): List<SolutionNodeDto> =
    flatMap { node ->
        when (node.type) {
            "SOLUTION_SEGMENT" -> listOf(node)
            "ROUTE_SEGMENT" -> node.subSegments.flattenSegments()
            // SOLUTION_LOCATION e simili: interscambi senza mezzo, niente da estrarre.
            else -> emptyList()
        }
    }

/** Converte una soluzione del BFF in [Journey]. */
fun SolutionDto.toJourney(): Journey? {
    val dep = departureTime.parseIso() ?: return null
    val arr = arrivalTime.parseIso() ?: return null

    val legs = solutionNodes
        .flattenSegments()
        .mapNotNull { node ->
            val from = node.startLocation?.toStation() ?: return@mapNotNull null
            val to = node.endLocation?.toStation() ?: return@mapNotNull null
            val mean = node.offeredTransportMeanDeparture
            val cls = mean?.classification
            /*
             * La sigla di linea dei suburbani.
             *
             * Un "SU 24237" da solo non dice niente: la linea — S1, S2, S13 — la
             * gente la legge sui monitor e la cerca qui. Il BFF la nasconde in
             * `trainDescription`, che per un suburbano e' "S2 TRENORD 24237" e per
             * tutti gli altri e' il solo numero. Quando c'e', prende il posto della
             * sigla generica "SU"; il numero resta, per aprire la corsa.
             */
            val linea = mean?.trainDescription?.trim()?.substringBefore(' ')
                ?.takeIf { it.matches(Regex("S\\d+")) }
            /*
             * «WK» e' una camminata: Le Frecce la chiama «Urbano» come il tram o
             * la metropolitana («UB»), ma e' il passaggio a piedi fra due stazioni
             * gemelle, come Porta Garibaldi e il suo Passante. Una camminata non
             * e' un mezzo e non ha un numero: il «Urb» che porta non lo e'.
             */
            if (cls?.acronym.equals(CAMMINATA, ignoreCase = true)) {
                return@mapNotNull Leg(
                    trainNumber = null,
                    category = null,
                    from = from,
                    to = to,
                    departure = node.departureTime.parseIso() ?: dep,
                    arrival = node.arrivalTime.parseIso() ?: arr,
                    kind = TransportKind.WALK,
                )
            }
            Leg(
                trainNumber = mean?.name?.takeIf { it.isNotBlank() },
                category = linea ?: cls?.acronym,
                from = from,
                to = to,
                departure = node.departureTime.parseIso() ?: dep,
                arrival = node.arrivalTime.parseIso() ?: arr,
                kind = when (cls?.type?.uppercase()) {
                    "BUS" -> TransportKind.BUS
                    "TRAIN", null -> TransportKind.TRAIN
                    else -> TransportKind.OTHER
                },
                kindLabel = cls?.classification,
            )
        }

    // Le camminate in testa e in coda non si contano: vedi [senzaCamminateAgliEstremi].
    val mezzi = legs.senzaCamminateAgliEstremi()
    if (mezzi.isEmpty()) return null
    val partenza = mezzi.first().departure
    val arrivo = mezzi.last().arrival
    return Journey(
        departure = partenza,
        arrival = arrivo,
        duration = when {
            partenza != dep || arrivo != arr -> Duration.between(partenza, arrivo)
            totalDuration > 0 -> Duration.ofMillis(totalDuration)
            else -> Duration.between(dep, arr)
        },
        legs = mezzi,
        price = toPrice(),
    )
}

/** La sigla con cui la porta dell'app di Le Frecce segna una camminata. */
private const val CAMMINATA = "WK"

/**
 * Le tratte senza le camminate in testa e in coda.
 *
 * Due stazioni gemelle nello stesso luogo — la superficie di Porta Garibaldi e
 * il suo Passante — per partenza e arrivo sono una stazione sola (deciso con
 * l'utente il 19/09/2026): chi cerca puo' essere gia' sulla banchina giusta, e
 * il viaggio comincia col treno. I minuti a piedi contano solo in un cambio, e
 * li' la camminata resta.
 */
fun List<Leg>.senzaCamminateAgliEstremi(): List<Leg> =
    dropWhile { it.isWalk }.dropLastWhile { it.isWalk }

/**
 * La sigla del treno come la scrive ViaggiaTreno, senza gli spazi di contorno.
 * Per le Frecce `compNumeroTreno` arriva « FR 9712», con la categoria vuota
 * davanti (19/09/2026): nel tabellone la sigla partiva uno spazio piu' in la'
 * di quella dei regionali, e non stava in colonna.
 */
internal fun etichettaTreno(grezza: String?): String? =
    grezza?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

/**
 * Il prezzo della soluzione, quando c'e' ed e' lecito mostrarlo.
 *
 * Tre condizioni, e servono tutte e tre:
 *
 *  - **una cifra**, ovviamente. Dalla porta dell'app del BFF spesso manca, e
 *    ai regionali lombardi quasi sempre: e' per questo che i prezzi si prendono
 *    anche dalla porta del sito, vedi `LefrecceApi.soluzioniDelSito`.
 *  - **`showPrice`**, che il BFF mette a falso quando il prezzo esiste nei suoi
 *    archivi ma non e' da pubblicare. Ignorarlo vorrebbe dire mostrare cifre
 *    che Trenitalia stessa non mostra.
 *  - **una cifra sensata**: uno zero o un valore illeggibile e' quasi sempre un
 *    campo non popolato, e "0,00 €" su un Frecciarossa sarebbe una bugia
 *    vistosa.
 *
 * `saleable` invece non filtra, qualifica: un treno esaurito il suo prezzo ce
 * l'ha, e sapere quanto costava serve comunque a scegliere. Chi mostra la riga
 * dira' che non e' acquistabile.
 *
 * **Il prezzo e' intermittente, e non e' un difetto nostro.** Misurato il
 * 28/08/2026 su cinque sessioni di ricerca consecutive per la stessa tratta e
 * lo stesso orario: quattro rispondevano con i prezzi, una con `totalPrice`
 * null su tutte le soluzioni. Richiamare le soluzioni sullo stesso `searchId`
 * non cambia nulla — provato tre volte di seguito — quindi non c'e' un ritardo
 * da aspettare ne' una chiamata da ripetere: quella sessione di ricerca i
 * prezzi non li ha e basta. Ne segue che la UI deve reggere l'assenza come
 * caso normale, non come errore.
 *
 * **Rimisurato l'11/09/2026 su 36 ricerche, e la forma e' cambiata.** Non piu'
 * `totalPrice` null, ma `totalAmount = {amount: "0.00", showPrice: false}` su
 * tutte le soluzioni della ricerca, Frecce comprese. E' capitato in 12 ricerche
 * su 36, quasi tutte su tratte regionali lombarde (Varese-Brescia 4 su 6,
 * Milano-Bergamo 5 su 6), mai su Milano-Roma o Napoli-Salerno. La stessa
 * sessione richiamata da' lo stesso esito, ma una ricerca **nuova** puo'
 * riportare i prezzi. Il filtro qui regge entrambe le forme.
 *
 * **Il 18/09/2026 la causa: e' la porta.** La stessa ricerca, alla stessa ora,
 * chiesta alla porta del sito (`/website/ticket/solutions`) tornava coi prezzi
 * 16 volte su 16, regionali Trenord compresi, mentre questa porta dell'app li
 * perdeva in 3 ricerche su 16 e ai regionali lombardi quasi sempre.
 */
private fun SolutionDto.toPrice(): Price? {
    val cifra = (totalAmount?.amount ?: totalPrice)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (totalAmount?.showPrice == false) return null
    if ((cifra.toDoubleOrNull() ?: return null) <= 0.0) return null

    return Price(
        amount = cifra,
        currency = totalAmount?.currency?.takeIf { it.isNotBlank() } ?: "EUR",
        // Vendibile finche' non e' detto il contrario: i tre flag arrivano null
        // sulle soluzioni che il BFF non tratta commercialmente.
        saleable = saleable != false && soldOut != true && inhibited != true,
        esaurito = soldOut == true,
    )
}

// ------------------------------------------------------------ ViaggiaTreno

/**
 * Deriva lo stato dai flag di ViaggiaTreno, che sono ridondanti e in parte
 * sovrapposti.
 *
 * `tipoTreno` ha piu' valori di quelli che si leggevano. Contati il 18/09/2026
 * su 740 corse: `PG` regolare, `ST` soppresso, `PP` soppresso in parte, e poi
 * `SI`, `SF`, `SM` — soppresso all'inizio, alla fine, in mezzo: il REG 2839
 * "parte da Sondrio" invece che da Tirano, il REG 2833 finiva a Sesto invece
 * che a Milano — e `DV`, deviato. Non basta nemmeno quello: l'IC 612 era "PG"
 * con due fermate straordinarie. Per questo contano anche le fermate, che non
 * mentono: una soppressa e' una soppressione, una straordinaria una variazione.
 *
 * L'ordine dei controlli conta. La soppressione totale prevale su tutto. Poi
 * vengono arrivo e partenza, prima delle variazioni: su "non partito" si
 * calcola il ritardo di un treno fermo all'origine (`conRitardoDaFermo`), e su
 * "arrivato" smettono gli aggiornamenti. Una corsa limitata che restasse
 * "soppressa in parte" anche a destinazione non finirebbe mai. Cosa sia
 * cambiato lo dicono comunque l'avviso e le fermate barrate.
 */
private fun AndamentoTrenoDto.deriveState(): TrainState {
    val tutte = fermate + fermateSoppresse
    return when {
        provvedimento == 1 || tipoTreno == "ST" -> TrainState.CANCELLED
        tutte.isNotEmpty() && tutte.all { it.actualFermataType == 3 } -> TrainState.CANCELLED
        arrivato -> TrainState.ARRIVED
        nonPartito -> TrainState.NOT_DEPARTED
        tipoTreno in SOPPRESSO_IN_PARTE || tutte.any { it.actualFermataType == 3 } ->
            TrainState.PARTIALLY_CANCELLED
        provvedimento == 2 || tipoTreno == "DV" || tutte.any { it.actualFermataType == 2 } ->
            TrainState.DIVERTED
        ritardo > 0 -> TrainState.DELAYED
        else -> TrainState.REGULAR
    }
}

private val SOPPRESSO_IN_PARTE = setOf("PP", "SI", "SF", "SM")

/**
 * Le fermate nell'ordine in cui il treno le incontra.
 *
 * Nessuno dei due ordini che ViaggiaTreno offre regge da solo. L'elenco sbaglia
 * quando aggiunge un capolinea: nel REG 2833 del 18/09/2026 Sesto, capolinea
 * nuovo, stava dopo Milano Centrale soppressa. Il `progressivo` sbaglia quando
 * la corsa riparte da una stazione nuova, che riprende a contare da 1: nel REG
 * 4972 Velletri e le sette soppresse dopo andavano da 1 a 9, e Ciampino, la
 * nuova origine, era di nuovo 1 — ordinato per numero, Ciampino finiva fra
 * Velletri e S.Gennaro. Nemmeno l'orario basta: le fermate di un percorso
 * deviato lo portano ricalcolato, e il PM Eccellente del FR 9588 aveva l'arrivo
 * dopo Lamezia, che sta dopo.
 *
 * Quindi la numerazione vale dentro un tratto, e un tratto nuovo comincia dove
 * compare una nuova origine (`tipoFermata` "P" dopo la prima fermata). I tratti
 * restano nell'ordine dell'elenco.
 */
private fun List<FermataDto>.inOrdineDiPercorso(): List<FermataDto> {
    val tratti = mutableListOf<MutableList<FermataDto>>()
    forEachIndexed { i, fermata ->
        if (i == 0 || fermata.tipoFermata == "P") tratti += mutableListOf<FermataDto>()
        tratti.last() += fermata
    }
    return tratti.flatMap { tratto -> tratto.sortedBy { it.progressivo } }
}

private fun FermataDto.toStop() = Stop(
    index = progressivo,
    // ViaggiaTreno scrive tutto in maiuscolo: vedi `nomeLeggibile`.
    stationName = nomeLeggibile(stazione.orEmpty()),
    stationCode = id,
    scheduledArrival = arrivoTeorico.toRomeDateTime(),
    actualArrival = arrivoReale.toRomeDateTime(),
    arrivalDelayMinutes = ritardoArrivo,
    scheduledDeparture = partenzaTeorica.toRomeDateTime(),
    actualDeparture = partenzaReale.toRomeDateTime(),
    departureDelayMinutes = ritardoPartenza,
    scheduledPlatform = binarioDiPartenzaOArrivo(
        binarioProgrammatoPartenzaDescrizione,
        binarioProgrammatoArrivoDescrizione,
    ),
    actualPlatform = binarioDiPartenzaOArrivo(
        binarioEffettivoPartenzaDescrizione,
        binarioEffettivoArrivoDescrizione,
    ),
    /*
     * `actualFermataType` dice se la fermata e' stata effettuata, non dove sia
     * il treno adesso: leggerlo come posizione riempiva il percorso di "sei
     * qui".
     *
     * Il 2 e' la **fermata straordinaria**, non una fermata fatta. Lo dice il
     * sito di ViaggiaTreno, che la colora di giallo come l'icona "fermata
     * straordinaria" della sua legenda. Letta come effettuata, il 18/09/2026
     * ha disegnato il REG 2833 gia' a Sesto S.Giovanni, capolinea aggiunto al
     * posto di Milano Centrale, mentre era ad Airuno: e dietro Sesto, Monza
     * "passaggio non rilevato", venti minuti prima che ci arrivasse. Nella
     * stessa corsa era straordinaria anche Ponte in Valtellina, che Trenord non
     * ha nel suo orario. Una straordinaria e' fatta quando ha un orario reale,
     * come ogni altra.
     */
    status = when {
        actualFermataType == 3 -> StopStatus.CANCELLED
        actualFermataType == 1 -> StopStatus.DONE
        actualFermataType == 2 && (arrivoReale != null || partenzaReale != null) -> StopStatus.DONE
        else -> StopStatus.FUTURE
    },
    straordinaria = actualFermataType == 2,
)

fun AndamentoTrenoDto.toTrainStatus(): TrainStatus {
    val detected = stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" }
    return TrainStatus(
        number = numeroTreno.toString(),
        category = categoria?.takeIf { it.isNotBlank() },
        label = etichettaTreno(compNumeroTreno)
            ?: listOfNotNull(categoria, numeroTreno.toString()).joinToString(" "),
        origin = origine?.let(::nomeLeggibile),
        destination = destinazione?.let(::nomeLeggibile),
        delayMinutes = ritardo,
        state = deriveState(),
        lastDetectionStation = detected?.let(::nomeLeggibile),
        lastDetectionTime = oraUltimoRilevamento.toRomeDateTime(),
        notice = subTitle?.takeIf { it.isNotBlank() },
        // Le soppresse possono stare in `fermateSoppresse`: vanno riunite e riordinate.
        stops = (fermate + fermateSoppresse)
            .inOrdineDiPercorso()
            .map { it.toStop().projectedBy(ritardo) }
            .consolidate(),
        impresa = codiceCliente,
    )
}

/**
 * Il binario di una fermata: quello della partenza, o quello dell'arrivo dove la
 * partenza non c'e'.
 *
 * **La pulizia sta prima del ripiego, non dopo**: un campo che c'e' ma non dice
 * niente — un piazzale senza numero, uno spazio — deve lasciare il posto a
 * quello dell'arrivo, che al capolinea e' l'unico esistente. Vedi
 * `binarioPulito`, che spiega perche' non basti ricopiare la stringa.
 *
 * Serve identica alla corsa e alla riga di tabellone, ed era scritta due volte
 * nello stesso file, col commento solo sulla prima.
 */
private fun binarioDiPartenzaOArrivo(partenza: String?, arrivo: String?): String? =
    binarioPulito(partenza) ?: binarioPulito(arrivo)

fun TabelloneVoceDto.toBoardEntry(): BoardEntry? {
    val origin = codOrigine ?: return null
    val millis = dataPartenzaTreno ?: return null
    return BoardEntry(
        trainRef = TrainRef(
            number = numeroTreno.toString(),
            originCode = origin,
            departureDateMillis = millis,
            originName = origine?.let(::nomeLeggibile),
        ),
        label = etichettaTreno(compNumeroTreno)
            ?: listOfNotNull(categoria, numeroTreno.toString()).joinToString(" "),
        category = categoria?.takeIf { it.isNotBlank() },
        direction = (destinazione ?: origine)?.let(::nomeLeggibile),
        scheduledTime = compOrarioPartenza ?: compOrarioArrivo,
        delayMinutes = ritardo,
        scheduledPlatform = binarioDiPartenzaOArrivo(
            binarioProgrammatoPartenzaDescrizione,
            binarioProgrammatoArrivoDescrizione,
        ),
        actualPlatform = binarioDiPartenzaOArrivo(
            binarioEffettivoPartenzaDescrizione,
            binarioEffettivoArrivoDescrizione,
        ),
        state = when {
            provvedimento == 1 -> TrainState.CANCELLED
            provvedimento == 2 -> TrainState.DIVERTED
            nonPartito -> TrainState.NOT_DEPARTED
            ritardo > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        inStation = inStazione,
    )
}

/**
 * Parsa una riga di `cercaNumeroTrenoTrenoAutocomplete`:
 * `25510 - MILANO CENTRALE - 27/08/26|25510-S01700-1787781600000`
 *
 * La parte a destra della pipe non contiene mai trattini oltre ai due separatori,
 * quindi lo split e' sicuro anche con nomi stazione tipo "MUSIANO-PIAN DI MACINA".
 */
fun parseTrainRefLine(line: String): TrainRef? {
    val parts = line.trim().split('|')
    if (parts.size != 2) return null
    val rhs = parts[1].split('-')
    if (rhs.size != 3) return null
    val millis = rhs[2].toLongOrNull() ?: return null
    val originName = parts[0].split(" - ").getOrNull(1)?.trim()?.let(::nomeLeggibile)
    return TrainRef(
        number = rhs[0].trim(),
        originCode = rhs[1].trim(),
        departureDateMillis = millis,
        originName = originName,
    )
}
