package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.trenord.CodiciTrenord
import it.zawardo.treni.data.remote.trenord.TrenordActualDto
import it.zawardo.treni.data.remote.trenord.TrenordJourneyDto
import it.zawardo.treni.data.remote.trenord.TrenordProductDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.trenord.TrenordStationDto
import it.zawardo.treni.data.remote.trenord.TrenordStopDto
import it.zawardo.treni.data.remote.trenord.TrenordTrainDto
import it.zawardo.treni.data.remote.trenord.TrenordAlertDto
import it.zawardo.treni.domain.model.DataSource
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
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.nomeLeggibile
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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

/**
 * Un orario senza giorno, messo nel giorno che lo porta piu' vicino a
 * [riferimento]: il giorno prima, lo stesso o il dopo. Un treno delle 23:50
 * rilevato alle 00:05 e' arrivato il giorno dopo, non diciassette ore prima.
 */
private fun vicinoA(riferimento: LocalDateTime?, time: String?): LocalDateTime? {
    val base = riferimento ?: return null
    val t = parseTime(time) ?: return null
    return (-1L..1L).map { base.toLocalDate().plusDays(it).atTime(t) }
        .minBy { abs(Duration.between(base, it).toMinutes()) }
}

/** `HH:mm:ss` di durata, non un orario. */
private fun parseDuration(s: String?): Duration? {
    val t = parseTime(s) ?: return null
    return Duration.ofHours(t.hour.toLong())
        .plusMinutes(t.minute.toLong())
        .plusSeconds(t.second.toLong())
}

private fun TrenordStationDto.toStation() = Station(
    // station_id e' il MIR di Trenord, quasi sempre il codice RFI: vedi CodiciTrenord.
    rfiCode = CodiciTrenord.perApp(stationId),
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
    // Prima solo la prima lettera: «Calolziocorte olginate». Vedi `nomeLeggibile`.
    name = nomeLeggibile(name.orEmpty()),
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
        // Col giorno della fermata, non della soluzione: vedi TrenordStopDto.departureDayOffset.
        departure = combine(date, first.scheduledDeparture, first.departureDayOffset ?: 0) ?: fallback,
        arrival = combine(date, last.scheduledArrival, last.arrivalDayOffset ?: 0) ?: fallback,
        kind = t.kind(),
        kindLabel = t.category,
        venditore = t.venditore(),
    )
}

/**
 * Le tratte con le camminate che stanno **in mezzo** a due mezzi: quelle di un
 * cambio, il cui tempo serve alla coincidenza. Quelle in testa e in coda non ci
 * sono: una camminata di Trenord non ha fermate, e `toLeg` la scarta.
 *
 * Trenord la dichiara con la sola durata (`walk.duration`): parte quando arriva
 * il mezzo prima e va da li' alla stazione del mezzo dopo.
 */
private fun conCamminateNeiCambi(tratte: List<Pair<TrenordJourneyDto, Leg?>>): List<Leg> {
    val esito = mutableListOf<Leg>()
    tratte.forEachIndexed { i, (journey, leg) ->
        if (leg != null) {
            esito += leg
            return@forEachIndexed
        }
        val minuti = parseDuration(journey.walk?.duration) ?: return@forEachIndexed
        val prima = esito.lastOrNull() ?: return@forEachIndexed
        val dopo = tratte.drop(i + 1).firstNotNullOfOrNull { it.second } ?: return@forEachIndexed
        esito += Leg(
            trainNumber = null,
            category = null,
            from = prima.to,
            to = dopo.from,
            departure = prima.arrival,
            arrival = prima.arrival.plus(minuti),
            kind = TransportKind.WALK,
        )
    }
    return esito
}

/**
 * Chi vende il biglietto di questo treno, da `train_operator`: "TRENORD",
 * "TRENORD$:$FNM3" per i suoi treni sulla rete Ferrovienord, "TRENITALIA" per
 * l'EuroCity dentro una sua soluzione. Null se non si sa: una tratta di cui non
 * si sa chi la venda non si prezza.
 */
private fun TrenordTrainDto.venditore(): DataSource? = when {
    operator == null -> null
    operator.startsWith("TRENORD", ignoreCase = true) -> DataSource.TRENORD
    operator.startsWith("TRENITALIA", ignoreCase = true) -> DataSource.TRENITALIA
    else -> null
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
private enum class Soppressione { NESSUNA, PARZIALE, LIMITATA, TOTALE }

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
        // Soppressa e' la corsa intera: tutte le fermate che Trenord elenca.
        stops.all { it.cancelled } -> Soppressione.TOTALE
        /*
         * Salta la fermata da cui sali o quella a cui scendi: e' il treno
         * limitato che non arriva piu' fin li'. La corsa esiste ancora, ma per
         * te vale quanto una soppressione, ed e' meglio dirlo che lasciartela
         * prendere. Soppressa pero' non e': e' variata.
         */
        tratta.all { it.cancelled } || tratta.first().cancelled || tratta.last().cancelled -> Soppressione.LIMITATA
        tratta.any { it.cancelled } -> Soppressione.PARZIALE
        else -> Soppressione.NESSUNA
    }
}

fun TrenordSolutionDto.toJourney(): Journey? {
    val date = parseDate(date)
    val dep = combine(date, departureTime, departureDayOffset) ?: return null
    val arr = combine(date, arrivalTime, arrivalDayOffset) ?: return null
    val legs = conCamminateNeiCambi(journeys.map { it to it.toLeg(date, dep) })
    if (legs.isEmpty()) return null

    /*
     * I tratti a piedi in testa e in coda non si contano: la soluzione parte
     * col primo mezzo e arriva con l'ultimo. Trenord da Milano Porta Garibaldi
     * mette cinque minuti a piedi fino al Passante: il 19/09/2026 la soluzione
     * dell'S5 24531 diceva 10:20, l'ora di uscire dalla stazione di superficie,
     * e il treno partiva alle 10:25. Col ritardo accanto, «10:20 +3» si leggeva
     * come un treno gia' partito, mentre dal Passante era ancora li'. Chi cerca
     * puo' essere gia' sulla banchina giusta (deciso con l'utente): conta il
     * treno. In mezzo, invece, la camminata resta: vedi [conCamminateNeiCambi].
     */
    val partenza = legs.first().departure
    val arrivo = legs.last().arrival

    val soppressioni = journeys.map { it.soppressione() }

    return Journey(
        departure = partenza,
        arrival = arrivo,
        duration = if (partenza == dep && arrivo == arr) {
            parseDuration(duration) ?: Duration.between(dep, arr)
        } else {
            Duration.between(partenza, arrivo)
        },
        legs = legs,
        source = JourneySource.TRENORD,
        cancelled = cancelled || soppressioni.any { it == Soppressione.TOTALE || it == Soppressione.LIMITATA },
        partiallyCancelled = soppressioni.any { it == Soppressione.PARZIALE },
        variato = !cancelled && soppressioni.none { it == Soppressione.TOTALE } &&
            soppressioni.any { it == Soppressione.LIMITATA },
        delayMinutes = ritardoDichiarato(),
        price = toPrice(),
        venditaChiusa = saleability?.saleable == false && saleability.reason == PARTENZA_PASSATA,
    )
}

/**
 * Il ritardo che Trenord dichiara sulla soluzione, se e' di questa corsa.
 *
 * `delay` vale solo col flag `delay_defined`: senza e' assenza di dato, non
 * assenza di ritardo. E nemmeno col flag basta. La notte del 18-19/09/2026 le
 * sole soluzioni non ancora partite con un ritardo dichiarato portavano quello
 * di un'altra corsa: il REG 10911 delle 00:15 era a +5 con `status` "A",
 * arrivato, e l'ultimo rilevamento del giorno prima; il RE 2211 delle 05:05 a +2
 * con `has_live_info` falso, in tre risposte su otto identiche. Nell'elenco
 * uscivano come «+2 non partito» su un treno che partiva cinque ore dopo. Il
 * mattino dopo, su quindici soluzioni in partenza fra zero e tre ore, nessuna
 * dichiarava un ritardo.
 *
 * Quindi conta solo se Trenord segue il primo treno dal vivo e non lo da' per
 * arrivato. Il caso per cui serve resta: un treno fermo all'origine e' "N" dal
 * vivo, e il suo ritardo e' proprio quello che ViaggiaTreno non scrive.
 *
 * Il primo **treno**, non la prima tratta: Varese-Brescia delle 10:10 comincia
 * con dieci minuti a piedi fino a Varese Nord, una tratta senza numero e senza
 * tempo reale, e col primo della lista il ritardo non sarebbe contato mai.
 */
private fun TrenordSolutionDto.ritardoDichiarato(): Int? {
    if (!delayDefined) return null
    val primo = journeys.firstOrNull { !it.train?.id.isNullOrBlank() }?.train ?: return null
    if (!primo.hasLiveInfo || primo.status == ARRIVATA) return null
    return delay
}

private const val ARRIVATA = "A"

/**
 * Il motivo con cui Trenord dice che il biglietto non si vende piu' perche' la
 * partenza e' passata. Gli altri — `OTHER_OPERATOR`, `NO_PRODUCTS` — dicono che
 * quel biglietto Trenord non lo vende proprio, che e' un'altra cosa.
 */
private const val PARTENZA_PASSATA = "PAST_DEPARTURE_DATE"

/**
 * Il prezzo della corsa semplice a tariffa intera, sommato su tutte le tratte.
 *
 * Trenord vende, e nella risposta di ricerca allega i titoli validi per ogni
 * tratta. Tre scelte guidano questa funzione:
 *
 *  - **solo i biglietti ordinari.** Fra i prodotti ci sono anche i giornalieri e
 *    gli altri titoli a tempo, che costano il triplo e valgono un giorno intero:
 *    mescolarli farebbe apparire Trenord molto piu' cara di quanto sia. Su
 *    Milano Centrale - Porta Garibaldi l'ordinario e' 2,20 e il giornaliero
 *    7,60, e il prezzo giusto da mostrare accanto a una singola corsa e' il
 *    primo.
 *  - **fra gli ordinari, la tariffa piena in seconda classe.** `ordinary` non
 *    e' un prezzo solo: sulla corsa singola regionale torna sei volte,
 *    `tariff_type` fra `adulto`, `ragazzo` e `anziano` per `class` 1 e 2.
 *    Prendere il minimo, come si faceva, dava sempre il ridotto ragazzo in
 *    seconda: su Calolziocorte - Milano Centrale usciva 2,60 al posto di 5,20,
 *    e la stessa tratta cambiava prezzo a meta' lista appena le soluzioni
 *    Trenord finivano e subentravano quelle di Le Frecce, che il prezzo intero
 *    lo danno. Vedi [tariffaIntera] per l'altra famiglia, quella a zone.
 *  - **la somma sulle tratte.** `ticket_routes` e' una lista perche' un viaggio
 *    puo' richiedere piu' biglietti; nelle risposte viste ce n'e' sempre una
 *    sola, che copre origine-destinazione cambi compresi. Finche' e' cosi' la
 *    somma non cambia niente, e il giorno che ne arrivassero due sarebbero due
 *    titoli da pagare entrambi.
 *
 * Null quando i titoli non ci sono — capita sui treni che Trenord non vende,
 * fuori dalla Lombardia — che e' diverso da gratis. E null anche quando nessun
 * titolo si dichiara a tariffa piena: un prezzo che non si sa piu' riconoscere
 * e' peggio di un prezzo assente, e `PrezziLiveTest` diventa rosso se quei nomi
 * cambiano.
 */
private fun TrenordSolutionDto.toPrice(): Price? {
    if (ticketRoutes.isEmpty()) return null

    val perTratta = ticketRoutes.mapNotNull { tratta ->
        tratta.products
            .filter { it.type.equals("ordinary", ignoreCase = true) }
            .tariffaIntera()
            .secondaClasse()
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

/** Le due tariffe che nessuno sconto ha gia' abbassato. Vedi [tariffaIntera]. */
private val TARIFFE_INTERE = setOf("adulto", "standard")

/**
 * I titoli a tariffa piena.
 *
 * Le famiglie tariffarie sono due e si riconoscono da qui:
 *
 *  - **la corsa singola regionale** — `adulto`, `ragazzo`, `anziano` per due
 *    classi. Piena e' la prima; le altre due sono riduzioni per eta'.
 *  - **il biglietto a zone STIBM** dell'area milanese — un solo `standard`,
 *    senza classe: li' la riduzione per eta' non esiste e quello e' il prezzo
 *    che pagano tutti. Milano Dateo - Vignate sono 3,00 euro e basta.
 *
 * Si tiene un elenco di cio' che e' pieno invece di scartare cio' che e'
 * ridotto. Le due liste oggi si equivalgono, ma sbagliano in modo diverso: se
 * Trenord aggiunge una riduzione che qui non c'e', scartare farebbe passare uno
 * sconto per il prezzo di tutti — mentre cosi' il prezzo sparisce e i test
 * live diventano rossi, che e' come volersene accorgere.
 *
 * Chi non dichiara la tariffa resta comunque: campo assente non vuol dire
 * sconto.
 */
private fun List<TrenordProductDto>.tariffaIntera(): List<TrenordProductDto> =
    filter { it.tariffType.isNullOrBlank() || it.tariffType.lowercase() in TARIFFE_INTERE }

/**
 * La seconda classe, dove esiste.
 *
 * E' il prezzo di riferimento: la prima costa la meta' in piu' e la prende una
 * minoranza. Se la classe non e' dichiarata non si scarta niente, e il minimo
 * fra i sopravvissuti fa comunque la stessa scelta.
 */
private fun List<TrenordProductDto>.secondaClasse(): List<TrenordProductDto> =
    filter { it.classe == "2" }.ifEmpty { this }

// ------------------------------------------------------- dettaglio corsa

private fun TrenordStopDto.toStop(index: Int, date: LocalDate?, now: LocalDateTime): Stop {
    val a: TrenordActualDto? = actual
    val schedArr = combine(date, scheduledArrival, arrivalDayOffset ?: 0)
    val schedDep = combine(date, scheduledDeparture, departureDayOffset ?: 0)
    // Gli orari veri non hanno un giorno loro: si mettono accanto a quelli di
    // tabella, perche' un ritardo puo' scavalcare la mezzanotte da solo.
    // Senza orari di tabella, il giorno della soluzione a mezzogiorno: resta quel giorno.
    val giorno = date?.atTime(12, 0)
    val realArr = vicinoA(schedArr ?: schedDep ?: giorno, a?.actualArrival)
    val realDep = vicinoA(schedDep ?: schedArr ?: giorno, a?.actualDeparture)
    val estArr = vicinoA(schedArr ?: schedDep ?: giorno, a?.estimatedArrival)
    val estDep = vicinoA(schedDep ?: schedArr ?: giorno, a?.estimatedDeparture)

    val done = realArr != null || realDep != null
    val binario = binarioPulito(platform)
    return Stop(
        index = index,
        stationName = nomeLeggibile(station?.name.orEmpty()),
        stationCode = CodiciTrenord.perApp(station?.stationId),
        scheduledArrival = schedArr,
        actualArrival = realArr,
        arrivalDelayMinutes = a?.arrivalDelay ?: 0,
        scheduledDeparture = schedDep,
        actualDeparture = realDep,
        departureDelayMinutes = a?.departureDelay ?: 0,
        /*
         * Un solo campo per due significati, che `is_actual_platform` separa:
         * vero e' il binario assegnato, falso quello di tabella. Chi non lo
         * dichiara finisce fra i programmati, perche' spacciarlo per effettivo
         * significherebbe annunciare cambi di binario mai avvenuti.
         */
        scheduledPlatform = binario?.takeIf { isActualPlatform != true },
        actualPlatform = binario?.takeIf { isActualPlatform == true },
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
            /*
             * Arrivata prima che soppressa in parte, contando solo le fermate
             * che si fanno: una corsa limitata restava "soppressa in parte"
             * anche a destinazione, e non risultava arrivata mai — con le
             * soppresse fra le fermate, "tutte effettuate" non puo' essere vero.
             */
            stops.any { it.status != StopStatus.CANCELLED } &&
                stops.all { it.status == StopStatus.DONE || it.status == StopStatus.CANCELLED } ->
                TrainState.ARRIVED
            journey.stops.any { it.cancelled } -> TrainState.PARTIALLY_CANCELLED
            stops.none { it.status == StopStatus.DONE } -> TrainState.NOT_DEPARTED
            delay > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        lastDetectionStation = lastDetection?.lastDetectionName,
        lastDetectionTime = null,
        notice = when {
            // Va detto: senza tracciamento gli orari sono quelli previsti, non rilevati.
            !t.hasLiveInfo -> "Corsa non tracciata in tempo reale"
            else -> t.alerts.firstOrNull { it.type == SOPPRESSIONE }?.message?.let(::testoAvviso)
        },
        stops = stops,
        motivo = (t.suppressionReason ?: t.alerts.firstNotNullOfOrNull { it.reason })
            ?.let(::testoAvviso),
        // La soppressione non e' fra gli avvisi: il suo testo sta in `notice`, o in
        // quello di ViaggiaTreno quando la corsa arriva da li'.
        avvisi = t.alerts.filter { it.type != SOPPRESSIONE }
            .mapNotNull { it.message?.let(::testoAvviso) }
            .distinct(),
    )
}

private const val SOPPRESSIONE = "suppressed"

/**
 * Il testo di un avviso Trenord, senza le sbavature con cui arriva: il
 * "+Circolazione fortemente rallentata..." del 18/09/2026 aveva un segno piu'
 * davanti e uno spazio in fondo.
 */
private fun testoAvviso(testo: String): String? =
    testo.trim().trimStart('+').trim().takeIf { it.isNotEmpty() }
