package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.trenord.TrenordActualDto
import it.zawardo.treni.data.remote.trenord.TrenordJourneyDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.trenord.TrenordStationDto
import it.zawardo.treni.data.remote.trenord.TrenordStopDto
import it.zawardo.treni.data.remote.trenord.TrenordTrainDto
import it.zawardo.treni.data.remote.trenord.TrenordAlertDto
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun parseDate(s: String?): LocalDate? =
    s?.takeIf { it.length == 8 }?.let { runCatching { LocalDate.parse(it, YMD) }.getOrNull() }

/** Gli orari arrivano come `HH:mm:ss`, a volte come `HH:mm`. */
private fun parseTime(s: String?): LocalTime? =
    s?.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalTime.parse(if (it.length == 5) "$it:00" else it) }.getOrNull()
    }

private fun combine(date: LocalDate?, time: String?, dayOffset: Int = 0): LocalDateTime? {
    val d = date ?: return null
    val t = parseTime(time) ?: return null
    return d.plusDays(dayOffset.toLong()).atTime(t)
}

/** `HH:mm:ss` di durata, non un orario. */
private fun parseDuration(s: String?): Duration? {
    val t = parseTime(s) ?: return null
    return Duration.ofHours(t.hour.toLong())
        .plusMinutes(t.minute.toLong())
        .plusSeconds(t.second.toLong())
}

private fun TrenordStationDto.toStation() = Station(
    // station_id e' il codice RFI: aggancia direttamente il resto dell'app.
    rfiCode = stationId?.takeIf { it.isNotBlank() },
    /*
     * locationId resta 0: e' l'identificativo del BFF Le Frecce e Trenord non
     * lo espone.
     *
     * Ricavarlo per formula dal codice RFI sembrava funzionare ma e' falso:
     * Milano Dateo ha codice S01650 e locationId 830001665, non 830001650.
     * Un id inventato non da' errore, punta a un'ALTRA stazione — ed e'
     * esattamente il tipo di guasto che non si vede finche' non produce
     * risultati plausibili e sbagliati.
     */
    locationId = 0L,
    name = name.orEmpty().lowercase().replaceFirstChar { it.uppercase() },
)

private fun TrenordTrainDto.kind(): TransportKind = when {
    category.equals("BUS", ignoreCase = true) -> TransportKind.BUS
    category.isNullOrBlank() -> TransportKind.OTHER
    else -> TransportKind.TRAIN
}

fun TrenordAlertDto.toServiceAlert(): ServiceAlert? {
    val body = message?.stripHtml()?.takeIf { it.isNotBlank() } ?: return null
    return ServiceAlert(
        title = title?.stripHtml()?.takeIf { it.isNotBlank() },
        message = body,
        severe = severity.equals("WARNING", true) || severity.equals("ERROR", true),
    )
}

/** Gli avvisi arrivano come frammenti HTML: qui servono come testo. */
private fun String.stripHtml(): String =
    replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&egrave;", "è")
        .replace("&agrave;", "à")
        .replace("&ograve;", "ò")
        .replace("&ugrave;", "ù")
        .replace("&igrave;", "ì")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()

/**
 * La parte di corsa che percorri davvero.
 *
 * `pass_list` e' la corsa intera, non la tua tratta: l'S5 per Varese parte da
 * Pioltello Limito alle 19:40 anche se sali a Porta Garibaldi alle 20:02, e le
 * prime tre fermate dell'elenco sono gia' andate quando la soluzione comincia.
 * A dirlo sono i marcatori: `start` dove sali, `end` dove scendi.
 *
 * Prendere la prima e l'ultima fermata dell'elenco significava scrivere che la
 * tratta parte da Pioltello alle 19:40 — la corsa giusta, il viaggio di un
 * altro.
 *
 * Senza marcatori si tiene tutto: una tratta piu' lunga del vero e' meglio di
 * nessuna tratta.
 */
private fun TrenordJourneyDto.ridden(): List<TrenordStopDto> {
    val salita = stops.indexOfFirst { it.type.equals("start", ignoreCase = true) }
    val discesa = stops.indexOfLast { it.type.equals("end", ignoreCase = true) }
    if (salita < 0 || discesa < salita) return stops
    return stops.subList(salita, discesa + 1)
}

private fun TrenordJourneyDto.toLeg(date: LocalDate?, fallback: LocalDateTime): Leg? {
    val t = train ?: return null
    val tratta = ridden()
    val first = tratta.firstOrNull()
    val last = tratta.lastOrNull()
    val from = first?.station?.toStation() ?: return null
    val to = last?.station?.toStation() ?: return null
    return Leg(
        trainNumber = t.id?.takeIf { it.isNotBlank() },
        // Per le linee S l'etichetta utile e' la linea, non la sigla di categoria.
        // L'underscore e' un separatore interno di HAFAS ("RE_8"): toglierlo
        // rende l'etichetta uguale a come la si scrive e la si cerca.
        category = t.line?.takeIf { it.isNotBlank() }?.replace("_", "") ?: t.category,
        from = from,
        to = to,
        departure = combine(date, first.scheduledDeparture) ?: fallback,
        arrival = combine(date, last.scheduledArrival) ?: fallback,
        kind = t.kind(),
        kindLabel = t.category,
    )
}

/**
 * Quanto pesa la soppressione su una tratta.
 *
 * HAFAS la dichiara **sulle fermate**, non sulla soluzione: il `cancelled` di
 * primo livello resta falso anche su una corsa cancellata per intero. L'S5 11862
 * del 27 agosto 2026 arrivava con `cancelled = false` e tutte e diciannove le
 * fermate soppresse, e nella lista dei risultati compariva come una corsa
 * qualunque. ViaggiaTreno non lo smentisce: di un treno soppresso non ha
 * nemmeno il record, `cercaNumeroTreno` non lo trova e `andamentoTreno`
 * risponde 204. Quel flag sulle fermate e' l'unica cosa che lo dice.
 */
private enum class Soppressione { NESSUNA, PARZIALE, TOTALE }

private fun TrenordJourneyDto.soppressione(): Soppressione {
    /*
     * Si guarda solo la tratta che percorri.
     *
     * Un treno limitato — che oggi parte dopo la sua origine o si ferma prima
     * del capolinea — ha le fermate soppresse a un capo della corsa. Se cadono
     * fuori dal tuo pezzo di viaggio non ti riguardano, e dichiararle
     * soppressione vorrebbe dire barrare una corsa che ti porta benissimo.
     */
    val tratta = ridden()
    return when {
        tratta.isEmpty() -> Soppressione.NESSUNA
        tratta.all { it.cancelled } -> Soppressione.TOTALE
        /*
         * Salta la fermata da cui sali o quella a cui scendi: e' il treno
         * limitato che non arriva piu' fin li'. La corsa esiste ancora, ma per
         * te vale quanto una soppressione, ed e' meglio dirlo che lasciartela
         * prendere.
         */
        tratta.first().cancelled || tratta.last().cancelled -> Soppressione.TOTALE
        tratta.any { it.cancelled } -> Soppressione.PARZIALE
        else -> Soppressione.NESSUNA
    }
}

fun TrenordSolutionDto.toJourney(): Journey? {
    val date = parseDate(date)
    val dep = combine(date, departureTime, departureDayOffset) ?: return null
    val arr = combine(date, arrivalTime, arrivalDayOffset) ?: return null
    val legs = journeys.mapNotNull { it.toLeg(date, dep) }
    if (legs.isEmpty()) return null

    val soppressioni = journeys.map { it.soppressione() }

    return Journey(
        departure = dep,
        arrival = arr,
        duration = parseDuration(duration) ?: Duration.between(dep, arr),
        legs = legs,
        source = JourneySource.TRENORD,
        cancelled = cancelled || soppressioni.any { it == Soppressione.TOTALE },
        partiallyCancelled = soppressioni.any { it == Soppressione.PARZIALE },
        // `delay` e' attendibile solo quando il flag lo dichiara: altrimenti e'
        // assenza di dato, non assenza di ritardo.
        delayMinutes = delay?.takeIf { delayDefined },
        price = toPrice(),
    )
}

/**
 * Il prezzo della corsa semplice, sommato su tutte le tratte del viaggio.
 *
 * Trenord vende, e nella risposta di ricerca allega i titoli validi per ogni
 * tratta. Due scelte guidano questa funzione:
 *
 *  - **solo i biglietti ordinari.** Fra i prodotti ci sono anche i giornalieri e
 *    gli altri titoli a tempo, che costano il triplo e valgono un giorno intero:
 *    mescolarli farebbe apparire Trenord molto piu' cara di quanto sia. Su
 *    Milano Centrale - Porta Garibaldi l'ordinario e' 2,20 e il giornaliero
 *    7,60, e il prezzo giusto da mostrare accanto a una singola corsa e' il
 *    primo.
 *  - **la somma sulle tratte, non il minimo.** Un viaggio con cambio puo'
 *    richiedere due biglietti distinti, uno per tratta: `ticket_routes` ne ha
 *    una voce per ciascuna, e chi parte deve pagarle tutte. Prendere solo la
 *    piu' economica direbbe meta' del prezzo vero.
 *
 * Null quando i titoli non ci sono — capita sulle tratte fuori dall'area
 * tariffaria integrata — che e' diverso da gratis.
 */
private fun TrenordSolutionDto.toPrice(): Price? {
    if (ticketRoutes.isEmpty()) return null

    val perTratta = ticketRoutes.mapNotNull { tratta ->
        tratta.products
            .filter { it.type.equals("ordinary", ignoreCase = true) }
            .mapNotNull { it.price }
            .filter { it > 0.0 }
            .minOrNull()
    }
    // Se anche una sola tratta non ha un titolo, il totale sarebbe parziale e
    // quindi falso: meglio non dire niente che dire meno del vero.
    if (perTratta.size != ticketRoutes.size || perTratta.isEmpty()) return null

    val totale = perTratta.sum()
    if (totale <= 0.0) return null

    return Price(
        amount = "%.2f".format(java.util.Locale.US, totale),
        currency = "EUR",
        saleable = saleability?.saleable != false,
    )
}

// ------------------------------------------------------- dettaglio corsa

private fun TrenordStopDto.toStop(index: Int, date: LocalDate?, now: LocalDateTime): Stop {
    val a: TrenordActualDto? = actual
    val schedArr = combine(date, scheduledArrival)
    val schedDep = combine(date, scheduledDeparture)
    val realArr = combine(date, a?.actualArrival)
    val realDep = combine(date, a?.actualDeparture)
    val estArr = combine(date, a?.estimatedArrival)
    val estDep = combine(date, a?.estimatedDeparture)

    val done = realArr != null || realDep != null
    return Stop(
        index = index,
        stationName = station?.name.orEmpty(),
        stationCode = station?.stationId,
        scheduledArrival = schedArr,
        actualArrival = realArr,
        arrivalDelayMinutes = a?.arrivalDelay ?: 0,
        scheduledDeparture = schedDep,
        actualDeparture = realDep,
        departureDelayMinutes = a?.departureDelay ?: 0,
        // HAFAS non espone il binario in questa risposta.
        scheduledPlatform = null,
        actualPlatform = null,
        status = when {
            cancelled -> StopStatus.CANCELLED
            done -> StopStatus.DONE
            else -> StopStatus.FUTURE
        },
        projectedArrival = estArr,
        projectedDeparture = estDep,
    )
}

/**
 * Converte il dettaglio corsa Trenord in [TrainStatus].
 *
 * A differenza di ViaggiaTreno, qui gli orari stimati arrivano gia' calcolati
 * da HAFAS in `arr_estimated_time`: non serve proiettare il ritardo a mano.
 */
fun TrenordSolutionDto.toTrainStatus(): TrainStatus? {
    val journey = journeys.firstOrNull() ?: return null
    val t = journey.train ?: return null
    val date = parseDate(date)
    val now = LocalDateTime.now()

    val stops = journey.stops.mapIndexed { i, s -> s.toStop(i + 1, date, now) }
    val lastDetection = journey.stops
        .lastOrNull { !it.actual?.lastDetectionName.isNullOrBlank() }
        ?.actual

    val delay = t.delay ?: stops.lastOrNull { it.status == StopStatus.DONE }?.arrivalDelayMinutes ?: 0

    return TrainStatus(
        number = t.id.orEmpty(),
        category = t.category,
        label = listOfNotNull(t.line ?: t.category, t.id).joinToString(" "),
        origin = journey.stops.firstOrNull()?.station?.name,
        destination = journey.stops.lastOrNull()?.station?.name ?: t.direction,
        delayMinutes = delay,
        state = when {
            cancelled || journey.stops.all { it.cancelled } -> TrainState.CANCELLED
            journey.stops.any { it.cancelled } -> TrainState.PARTIALLY_CANCELLED
            stops.isNotEmpty() && stops.all { it.status == StopStatus.DONE } -> TrainState.ARRIVED
            stops.none { it.status == StopStatus.DONE } -> TrainState.NOT_DEPARTED
            delay > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        lastDetectionStation = lastDetection?.lastDetectionName,
        lastDetectionTime = null,
        // Va detto: senza tracciamento gli orari sono quelli previsti, non rilevati.
        notice = if (!t.hasLiveInfo) "Corsa non tracciata in tempo reale" else null,
        stops = stops,
    )
}
