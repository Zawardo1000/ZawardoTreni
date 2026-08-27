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

private fun TrenordJourneyDto.toLeg(date: LocalDate?, fallback: LocalDateTime): Leg? {
    val t = train ?: return null
    val first = stops.firstOrNull()
    val last = stops.lastOrNull()
    val from = first?.station?.toStation() ?: return null
    val to = last?.station?.toStation() ?: return null
    return Leg(
        trainNumber = t.id?.takeIf { it.isNotBlank() },
        // Per le linee S l'etichetta utile e' la linea, non la sigla di categoria.
        category = t.line?.takeIf { it.isNotBlank() } ?: t.category,
        from = from,
        to = to,
        departure = combine(date, first.scheduledDeparture) ?: fallback,
        arrival = combine(date, last.scheduledArrival) ?: fallback,
        kind = t.kind(),
        kindLabel = t.category,
    )
}

fun TrenordSolutionDto.toJourney(): Journey? {
    val date = parseDate(date)
    val dep = combine(date, departureTime, departureDayOffset) ?: return null
    val arr = combine(date, arrivalTime, arrivalDayOffset) ?: return null
    val legs = journeys.mapNotNull { it.toLeg(date, dep) }
    if (legs.isEmpty()) return null

    return Journey(
        departure = dep,
        arrival = arr,
        duration = parseDuration(duration) ?: Duration.between(dep, arr),
        legs = legs,
        source = JourneySource.TRENORD,
        cancelled = cancelled,
        // `delay` e' attendibile solo quando il flag lo dichiara: altrimenti e'
        // assenza di dato, non assenza di ritardo.
        delayMinutes = delay?.takeIf { delayDefined },
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
