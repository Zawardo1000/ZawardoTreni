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
import it.zawardo.treni.domain.model.consolidate
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
    name = name,
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
            Leg(
                trainNumber = mean?.name?.takeIf { it.isNotBlank() },
                category = cls?.acronym,
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

    return Journey(
        departure = dep,
        arrival = arr,
        duration = if (totalDuration > 0) Duration.ofMillis(totalDuration) else Duration.between(dep, arr),
        legs = legs,
        price = toPrice(),
    )
}

/**
 * Il prezzo della soluzione, quando c'e' ed e' lecito mostrarlo.
 *
 * Tre condizioni, e servono tutte e tre:
 *
 *  - **una cifra**, ovviamente. Le soluzioni regionali spesso non ce l'hanno,
 *    perche' il BFF non le commercializza tutte.
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
    )
}

// ------------------------------------------------------------ ViaggiaTreno

/**
 * Deriva lo stato dai flag di ViaggiaTreno, che sono ridondanti e in parte
 * sovrapposti. L'ordine dei controlli conta: le soppressioni prevalgono.
 */
private fun AndamentoTrenoDto.deriveState(): TrainState = when {
    provvedimento == 1 || tipoTreno == "ST" -> TrainState.CANCELLED
    tipoTreno == "PP" || fermateSoppresse.isNotEmpty() -> TrainState.PARTIALLY_CANCELLED
    provvedimento == 2 -> TrainState.DIVERTED
    arrivato -> TrainState.ARRIVED
    nonPartito -> TrainState.NOT_DEPARTED
    ritardo > 0 -> TrainState.DELAYED
    else -> TrainState.REGULAR
}

private fun FermataDto.toStop() = Stop(
    index = progressivo,
    stationName = stazione.orEmpty(),
    stationCode = id,
    scheduledArrival = arrivoTeorico.toRomeDateTime(),
    actualArrival = arrivoReale.toRomeDateTime(),
    arrivalDelayMinutes = ritardoArrivo,
    scheduledDeparture = partenzaTeorica.toRomeDateTime(),
    actualDeparture = partenzaReale.toRomeDateTime(),
    departureDelayMinutes = ritardoPartenza,
    scheduledPlatform = binarioProgrammatoPartenzaDescrizione
        ?: binarioProgrammatoArrivoDescrizione,
    actualPlatform = binarioEffettivoPartenzaDescrizione
        ?: binarioEffettivoArrivoDescrizione,
    /*
     * `actualFermataType` dice se la fermata e' stata effettuata, non dove sia
     * il treno adesso. Il 2 significa "effettuata ma non rilevata": gli orari
     * sono ricostruiti. Leggerlo come posizione corrente riempiva il percorso
     * di "sei qui" - su un IC per la Sicilia erano cinque, da Pisa in giu',
     * mentre il treno era gia' in vista di Catania.
     */
    status = when (actualFermataType) {
        1, 2 -> StopStatus.DONE
        3 -> StopStatus.CANCELLED
        else -> StopStatus.FUTURE
    },
    detected = actualFermataType != 2,
)

/**
 * Proietta il ritardo corrente sulle fermate non ancora effettuate.
 *
 * ViaggiaTreno lascia `ritardoArrivo` e `ritardoPartenza` a zero su tutte le
 * fermate future, anche quando la corsa e' dichiarata in ritardo: verificato su
 * un FR a +8 minuti con quattro fermate future tutte a zero. Senza questo
 * ricalcolo l'app direbbe che il treno arriva in orario mentre e' in ritardo.
 *
 * E' una stima lineare: non sa nulla di recuperi di orario sulle tratte veloci
 * ne' di soste comprimibili. Resta molto piu' vicina al vero dello zero.
 */
private fun Stop.projectedBy(delayMinutes: Int): Stop {
    if (status != StopStatus.FUTURE || delayMinutes == 0) return this
    return copy(
        arrivalDelayMinutes = delayMinutes,
        departureDelayMinutes = delayMinutes,
        projectedArrival = scheduledArrival?.plusMinutes(delayMinutes.toLong()),
        projectedDeparture = scheduledDeparture?.plusMinutes(delayMinutes.toLong()),
    )
}

fun AndamentoTrenoDto.toTrainStatus(): TrainStatus {
    val detected = stazioneUltimoRilevamento?.takeIf { it.isNotBlank() && it != "--" }
    return TrainStatus(
        number = numeroTreno.toString(),
        category = categoria?.takeIf { it.isNotBlank() },
        label = compNumeroTreno?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(categoria, numeroTreno.toString()).joinToString(" "),
        origin = origine,
        destination = destinazione,
        delayMinutes = ritardo,
        state = deriveState(),
        lastDetectionStation = detected,
        lastDetectionTime = oraUltimoRilevamento.toRomeDateTime(),
        notice = subTitle?.takeIf { it.isNotBlank() },
        // Le soppresse non sono in `fermate`: vanno riunite e riordinate.
        stops = (fermate + fermateSoppresse)
            .map { it.toStop().projectedBy(ritardo) }
            .sortedBy { it.index }
            .consolidate(),
    )
}

fun TabelloneVoceDto.toBoardEntry(): BoardEntry? {
    val origin = codOrigine ?: return null
    val millis = dataPartenzaTreno ?: return null
    return BoardEntry(
        trainRef = TrainRef(
            number = numeroTreno.toString(),
            originCode = origin,
            departureDateMillis = millis,
            originName = origine,
        ),
        label = compNumeroTreno?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(categoria, numeroTreno.toString()).joinToString(" "),
        category = categoria?.takeIf { it.isNotBlank() },
        direction = destinazione ?: origine,
        scheduledTime = compOrarioPartenza ?: compOrarioArrivo,
        delayMinutes = ritardo,
        scheduledPlatform = binarioProgrammatoPartenzaDescrizione
            ?: binarioProgrammatoArrivoDescrizione,
        actualPlatform = binarioEffettivoPartenzaDescrizione
            ?: binarioEffettivoArrivoDescrizione,
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
    val originName = parts[0].split(" - ").getOrNull(1)?.trim()
    return TrainRef(
        number = rhs[0].trim(),
        originCode = rhs[1].trim(),
        departureDateMillis = millis,
        originName = originName,
    )
}
