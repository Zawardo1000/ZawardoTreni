package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.italo.ItaloBoardTrainDto
import it.zawardo.treni.data.remote.italo.ItaloScheduleDto
import it.zawardo.treni.data.remote.italo.ItaloStations
import it.zawardo.treni.data.remote.italo.ItaloStopDto
import it.zawardo.treni.data.remote.italo.ItaloTrainDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Da NTV al modello dell'app.
 *
 * Italo pubblica orari come `HH:mm` e nient'altro: niente data, niente fuso.
 * Vanno quindi appoggiati su un giorno, e su una corsa che passa la mezzanotte
 * il solo orario tornerebbe indietro. La regola e' quella del buon senso
 * ferroviario: se un orario e' precedente al passaggio precedente, e' del
 * giorno dopo.
 */

/** L'etichetta con cui la gente li chiama: "Italo 9961". */
private fun etichetta(numero: String?): String =
    "Italo " + (numero.orEmpty().trim().ifBlank { "?" })

private fun String?.toTime(): LocalTime? =
    this?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { LocalTime.parse(if (it.length == 5) it else it.take(5)) }.getOrNull()
    }

/**
 * Voce di tabellone.
 *
 * [scheduledDate] e' la giornata a cui la voce appartiene: Italo non la manda,
 * e l'app ne ha bisogno per aprire la corsa.
 */
fun ItaloBoardTrainDto.toBoardEntry(scheduledDate: LocalDate = LocalDate.now(ROME)): BoardEntry? {
    val numero = number?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val orario = scheduledTime?.trim()?.takeIf { it.isNotBlank() } ?: return null

    return BoardEntry(
        trainRef = TrainRef(
            number = numero,
            /*
             * Italo non espone il codice origine di ViaggiaTreno, e non
             * potrebbe: quelle corse ViaggiaTreno non le conosce. Il dettaglio
             * si apre per numero e data, che e' esattamente il caso previsto.
             */
            originCode = "",
            departureDateMillis = scheduledDate.atStartOfDay(ROME).toInstant().toEpochMilli(),
        ),
        label = etichetta(numero),
        category = "Italo",
        direction = direction?.trim()?.takeIf { it.isNotBlank() },
        scheduledTime = orario,
        delayMinutes = delay,
        // Ne pubblicano uno solo, ed e' quello vero del momento.
        scheduledPlatform = null,
        actualPlatform = platform?.trim()?.takeIf { it.isNotBlank() },
        state = if (delay > 0) TrainState.DELAYED else TrainState.REGULAR,
        inStation = false,
    )
}

/**
 * Le fermate, rimesse in fila e datate.
 *
 * Le tre liste di Italo — origine, fermate fatte, fermate da fare — sono
 * separate ma numerate: `StationNumber` e' l'unico ordinamento di cui fidarsi.
 */
private fun List<Pair<ItaloStopDto, Boolean>>.toStops(giorno: LocalDate): List<Stop> {
    // Il riferimento per il salto di mezzanotte lo danno **solo gli orari
    // teorici**, che lungo una corsa crescono sempre. Gli orari reali no: un
    // segnaposto ("01:00") su una fermata non ancora fatta, passato a `quando`,
    // spingeva `ultimo` a domani, e da li' ogni orario teorico successivo
    // risultava "prima" e slittava di un giorno — erano le corse Italo che
    // uscivano con un arrivo "a 25 ore".
    var ultimoTeorico: LocalDateTime? = null

    /** Appoggia un orario teorico sul giorno giusto: indietro vuol dire domani. */
    fun teorico(t: LocalTime?): LocalDateTime? {
        if (t == null) return null
        var d = giorno.atTime(t)
        val prima = ultimoTeorico
        if (prima != null && d.isBefore(prima)) d = d.plusDays(1)
        ultimoTeorico = d
        return d
    }

    /**
     * L'orario reale sta sul giorno del suo teorico — gli e' vicino — e non fa
     * da riferimento a nessuno. Senza teorico ripiega sull'ultimo giorno noto.
     */
    fun reale(t: LocalTime?, rif: LocalDateTime?): LocalDateTime? {
        if (t == null) return null
        val base = (rif ?: ultimoTeorico)?.toLocalDate() ?: giorno
        var d = base.atTime(t)
        // Reale molto prima del teorico: e' scattata la mezzanotte fra i due.
        if (rif != null && d.isBefore(rif.minusHours(6))) d = d.plusDays(1)
        return d
    }

    return mapIndexed { i, (dto, fatta) ->
        val primo = i == 0
        val ultimoIndice = i == lastIndex

        // Al capolinea di partenza non esiste un arrivo, a quello finale non
        // esiste una partenza: Italo li riempie con un segnaposto ("01:00").
        val arrivoTeorico = if (primo) null else teorico(dto.scheduledArrival.toTime())
        val arrivoReale = if (primo) null else reale(dto.actualArrival.toTime(), arrivoTeorico)
        val partenzaTeorica = if (ultimoIndice) null else teorico(dto.scheduledDeparture.toTime())
        val partenzaReale = if (ultimoIndice) null else reale(dto.actualDeparture.toTime(), partenzaTeorica)

        Stop(
            index = dto.index,
            stationName = dto.name.orEmpty(),
            // Il codice RFI rende la fermata apribile come tabellone.
            stationCode = ItaloStations.rfiCode(dto.code),
            scheduledArrival = arrivoTeorico,
            // Su una fermata ancora da fare gli stessi campi sono una stima
            // loro, non una misura: vanno dove l'app tiene le proiezioni.
            actualArrival = if (fatta) arrivoReale else null,
            arrivalDelayMinutes = scarto(arrivoTeorico, arrivoReale),
            scheduledDeparture = partenzaTeorica,
            actualDeparture = if (fatta) partenzaReale else null,
            departureDelayMinutes = scarto(partenzaTeorica, partenzaReale),
            scheduledPlatform = null,
            actualPlatform = dto.platform?.trim()?.takeIf { it.isNotBlank() },
            status = if (fatta) StopStatus.DONE else StopStatus.FUTURE,
            projectedArrival = if (fatta) null else arrivoReale,
            projectedDeparture = if (fatta) null else partenzaReale,
        )
    }
}

/** Minuti fra orario di tabella e orario reale (o stimato): 0 se manca qualcosa. */
private fun scarto(teorico: LocalDateTime?, reale: LocalDateTime?): Int {
    if (teorico == null || reale == null) return 0
    return java.time.Duration.between(teorico, reale).toMinutes().toInt()
}

/**
 * Stato di una corsa Italo.
 *
 * Restituisce null quando il servizio non ha nulla: succede spesso, anche su
 * treni in viaggio, ed e' meglio dirlo che inventare una corsa vuota.
 */
fun ItaloTrainDto.toTrainStatus(giorno: LocalDate = LocalDate.now(ROME)): TrainStatus? {
    if (empty) return null
    return schedule?.toTrainStatus(lastUpdate, giorno)
}

/**
 * Una corsa, da qualunque delle due risposte arrivi.
 *
 * Italo la descrive in due modi diversi. Il dettaglio per numero divide le
 * fermate in fatte e da fare, e dice tutto da se'. Il percorso di tratta le
 * manda in un elenco unico, e dove sia arrivato il treno lo dice `Leg`: quella
 * che sta percorrendo adesso. Il resto del codice non deve accorgersi di questa
 * differenza.
 */
fun ItaloScheduleDto.toTrainStatus(
    lastUpdate: String?,
    giorno: LocalDate = LocalDate.now(ROME),
): TrainStatus? {
    val corsa = this

    val elencate = if (corsa.stations.isNotEmpty()) {
        val ordinate = corsa.stations.sortedBy { it.index }
        val arrivato = corsa.leg?.arrivalStation?.uppercase()
        val dovE = ordinate.indexOfFirst { it.code?.uppercase() == arrivato }
        ordinate.mapIndexed { i, fermata -> fermata to (dovE >= 0 && i <= dovE) }
    } else {
        val fatte = (listOfNotNull(corsa.originStop) + corsa.doneStops).map { it to true }
        val daFare = corsa.futureStops.map { it to false }
        (fatte + daFare).sortedBy { it.first.index }
    }
    val fermate = elencate.toStops(giorno)

    val ritardo = corsa.disruption?.delayMinutes ?: 0

    return TrainStatus(
        number = corsa.number.orEmpty(),
        category = "Italo",
        label = etichetta(corsa.number),
        origin = corsa.origin,
        destination = corsa.destination,
        delayMinutes = ritardo,
        state = when {
            fermate.isEmpty() -> TrainState.REGULAR
            fermate.all { it.status == StopStatus.DONE } -> TrainState.ARRIVED
            fermate.none { it.status == StopStatus.DONE } -> TrainState.NOT_DEPARTED
            ritardo > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        // L'ultimo posto dove Italo dichiara di aver visto il treno.
        lastDetectionStation = corsa.disruption?.locationCode
            ?.let { codice -> fermate.firstOrNull { it.stationCode == ItaloStations.rfiCode(codice) }?.stationName }
            ?: fermate.lastOrNull { it.status == StopStatus.DONE }?.stationName,
        lastDetectionTime = lastUpdate.toTime()?.let { giorno.atTime(it) },
        /*
         * L'ora della fotografia, sempre dichiarata.
         *
         * Il servizio conserva l'ultimo stato conosciuto e lo ripropone anche
         * ore dopo: senza questa riga, i dati delle otto del mattino sembrano di
         * adesso.
         */
        notice = lastUpdate?.takeIf { it.isNotBlank() }?.let { "Dati Italo aggiornati alle $it" },
        stops = fermate,
    )
}
