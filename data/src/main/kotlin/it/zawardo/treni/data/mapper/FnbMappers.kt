package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.fnb.FnbCorsaDto
import it.zawardo.treni.data.remote.fnb.FnbDettaglioDto
import it.zawardo.treni.data.remote.fnb.FnbSitoRifDto
import it.zawardo.treni.data.remote.fnb.FnbStations
import it.zawardo.treni.data.remote.fnb.FnbTrattaDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.binarioPulito
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Da Ferrotramviaria al modello comune.
 *
 * Il portale pubblica gia' tutto quello che serve a un tabellone — numero,
 * direzione, orario, ritardo, binario, soppressione — quindi qui non si
 * ricostruisce niente: si traduce e si scarta cio' che non regge.
 */

/** `yyyyMMddHHmmss`, l'unico formato in cui il portale scrive gli orari. */
private val ORARIO_FNB: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/**
 * Le sigle di servizio, sciolte.
 *
 * `S` e' l'unica che conti davvero: e' la corsa fatta in parte in treno e in
 * parte in bus, che sulla Andria - Barletta in lavori e' la norma. Chiamarla
 * "Treno" e basta vuol dire far aspettare un treno a chi salira' su un pullman.
 */
private fun etichettaServizio(sigla: String?): String? = when (sigla?.uppercase()) {
    "T" -> "Treno"
    "B" -> "Bus"
    "S" -> "Treno e bus"
    else -> null
}

/**
 * Una riga di tabellone.
 *
 * Null quando manca il numero o l'orario: sono le due cose senza le quali la
 * riga non identifica niente e non si puo' collocare nel tempo. Meglio una
 * corsa in meno che una riga che non si sa cosa sia.
 *
 * [arrivals] non cambia solo quale campo porta l'orario: cambia il significato
 * di `nomeDestinazione`, che fra gli arrivi e' l'origine. Vedi [FnbCorsaDto].
 */
fun FnbCorsaDto.toBoardEntry(arrivals: Boolean): BoardEntry? {
    val numero = numero?.takeIf { it.isNotBlank() } ?: return null
    val grezzo = (if (arrivals) arrivo else partenza)?.takeIf { it.length == 14 } ?: return null
    val quando = runCatching { LocalDateTime.parse(grezzo, ORARIO_FNB) }.getOrNull() ?: return null

    /*
     * Un ritardo assente non e' un ritardo di zero.
     *
     * Il portale omette il campo finche' la corsa non e' monitorata. Il modello
     * comune vuole un intero, quindi diventa zero come per le altre sorgenti,
     * ma lo stato resta REGULAR e non si scrive "in orario" da nessuna parte:
     * quello che non si sa non si racconta.
     */
    val ritardo = ritardo?.coerceAtLeast(0) ?: 0
    val categoria = etichettaServizio(servizio)

    return BoardEntry(
        trainRef = TrainRef(
            number = numero,
            // Il portale non espone un codice di origine: la corsa si identifica
            // col numero, che e' anche quello stampato sull'orario cartaceo.
            originCode = "",
            departureDateMillis = quando.toLocalDate().atStartOfDay(ROME).toInstant().toEpochMilli(),
        ),
        label = listOfNotNull(categoria, numero).joinToString(" "),
        category = categoria,
        direction = nomeDestinazione?.takeIf { it.isNotBlank() },
        scheduledTime = "%02d:%02d".format(quando.hour, quando.minute),
        delayMinutes = ritardo,
        // Un solo binario, quello vero: non c'e' il programmato da confrontare.
        scheduledPlatform = null,
        actualPlatform = binarioPulito(binarioEffettivo),
        state = when {
            soppressa.equals("Y", ignoreCase = true) -> TrainState.CANCELLED
            ritardo > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        // Il tabellone non dice se il treno e' gia' in banchina.
        inStation = false,
    )
}

/**
 * Il numero di una corsa come lo scrive la ricerca: sigla e numero, `ET 91008`.
 * Il tabellone invece scrive il numero nudo, `91008`, ed e' quello con cui la
 * corsa si riconosce da una parte all'altra.
 */
private val NUMERO_FNB = Regex("""^\s*([A-Za-z]{1,3})?\s*(\d+)\s*$""")

/** La sigla e il numero, separati; null se non si riconosce niente. */
internal fun numeroFnb(scritto: String?): Pair<String?, String>? {
    val m = NUMERO_FNB.find(scritto.orEmpty()) ?: return null
    return m.groupValues[1].takeIf { it.isNotBlank() }?.uppercase() to m.groupValues[2]
}

/** La stazione di una fermata del portale, col codice sintetico e le coordinate vere. */
private fun FnbSitoRifDto.toStation(): Station? {
    val codice = FnbStations.daCodSito(codSito) ?: return null
    val id = codice.removePrefix(FnbStations.PREFIX).toLongOrNull() ?: return null
    return Station(
        rfiCode = codice,
        locationId = LOCATION_ID_BASE_FNB + id,
        name = nome?.takeIf { it.isNotBlank() } ?: codice,
        latitude = lat ?: 0.0,
        longitude = lon ?: 0.0,
    )
}

/**
 * La base degli id sintetici di Ferrotramviaria: la stessa di
 * `FnbRepository`, dove sta il perche' delle fasce separate.
 */
private const val LOCATION_ID_BASE_FNB = 9_100_000_000L

private fun orarioFnb(grezzo: String?): LocalDateTime? =
    grezzo?.takeIf { it.length == 14 }?.let { runCatching { LocalDateTime.parse(it, ORARIO_FNB) }.getOrNull() }

/**
 * Una tratta come tappa di un viaggio.
 *
 * Il servizio decide il mezzo: `T` e' un treno, `B` un autobus di linea, `S`
 * l'autoservizio che sostituisce il treno dove la linea e' interrotta — sulla
 * Andria - Barletta, da anni, e' la norma. Un bus disegnato come un treno
 * manderebbe qualcuno ad aspettare sul marciapiede sbagliato.
 */
internal fun FnbTrattaDto.toLeg(): Leg? {
    val da = sitoPartenza?.toStation() ?: fermate.firstOrNull()?.sito?.toStation() ?: return null
    val a = sitoArrivo?.toStation() ?: fermate.lastOrNull()?.sito?.toStation() ?: return null
    val partenza = orarioFnb(timePartenza) ?: return null
    val arrivo = orarioFnb(timeArrivo) ?: return null
    val (sigla, numero) = numeroFnb(numero) ?: (null to "")
    val treno = servizio?.uppercase() == "T"
    return Leg(
        trainNumber = numero.takeIf { it.isNotEmpty() },
        category = sigla,
        from = da,
        to = a,
        departure = partenza,
        arrival = arrivo,
        kind = if (treno) TransportKind.TRAIN else TransportKind.BUS,
        kindLabel = etichettaServizio(servizio),
        source = DataSource.FNB,
    )
}

/**
 * Una soluzione come viaggio, con le sue tappe.
 *
 * Null se nessuna tratta si legge: una soluzione senza mezzi non e' un viaggio.
 * Il prezzo e' quello del biglietto intero, in centesimi.
 */
internal fun FnbDettaglioDto.toJourney(prezzo: Int?): Journey? {
    val tappe = tratte.mapNotNull { it.toLeg() }
    if (tappe.isEmpty()) return null
    val euro = (prezzo ?: this.prezzo)?.takeIf { it > 0 }
    return Journey(
        departure = tappe.first().departure,
        arrival = tappe.last().arrival,
        duration = Duration.between(tappe.first().departure, tappe.last().arrival),
        legs = tappe,
        price = euro?.let {
            Price(amount = "%.2f".format(Locale.US, it / 100.0), currency = "EUR", saleable = true)
        },
    )
}

/**
 * Una tratta come corsa da aprire: le fermate con gli orari di tabella.
 *
 * Ferrotramviaria il tempo reale lo pubblica **solo sul tabellone di fermata**,
 * corsa per corsa no: quindi [TrainStatus.realtime] resta falso e il ritardo non
 * si scrive. Le fermate sono quelle del tratto che si percorre: il portale il
 * resto della corsa non lo dice.
 */
internal fun FnbTrattaDto.toTrainStatus(notice: String?): TrainStatus? {
    val fermate = fermate.sortedBy { it.ordine ?: 0 }.mapIndexedNotNull { i, f ->
        val stazione = f.sito?.toStation() ?: return@mapIndexedNotNull null
        Stop(
            index = i,
            stationName = stazione.name,
            stationCode = stazione.rfiCode,
            scheduledArrival = orarioFnb(f.timeArrivo),
            actualArrival = null,
            arrivalDelayMinutes = 0,
            scheduledDeparture = orarioFnb(f.timePartenza),
            actualDeparture = null,
            departureDelayMinutes = 0,
            scheduledPlatform = null,
            actualPlatform = null,
            status = StopStatus.FUTURE,
            // Orari di tabella: il portale di Ferrotramviaria i passaggi non li
            // rileva, e dirlo serve a chi legge la corsa — e a «Segui treno», che
            // su una corsa senza rilevamenti sa di doversi chiudere sull'orologio
            // invece di aspettare una fermata «fatta» che non arrivera' mai.
            detected = false,
        )
    }
    if (fermate.size < 2) return null
    val (sigla, numero) = numeroFnb(numero) ?: return null
    return TrainStatus(
        number = numero,
        category = sigla ?: etichettaServizio(servizio),
        label = listOfNotNull(sigla, numero).joinToString(" "),
        origin = fermate.first().stationName,
        destination = fermate.last().stationName,
        delayMinutes = 0,
        state = TrainState.REGULAR,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = notice,
        stops = fermate,
        realtime = false,
    )
}

