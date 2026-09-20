package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.mapper.fermateDaInfoRoute
import it.zawardo.treni.data.remote.italo.ItaloApi
import it.zawardo.treni.data.remote.italo.ItaloBoardTrainDto
import it.zawardo.treni.data.remote.italo.ItaloStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import it.zawardo.treni.domain.model.projectedBy
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Italo, la quarta sorgente.
 *
 * Copre il buco piu' grande che restava: NTV non compare da nessun'altra parte.
 * ViaggiaTreno non pubblica le sue corse — a Roma Termini e a Milano Centrale
 * non c'e' una riga, e `cercaNumeroTreno` sui suoi numeri non trova niente — il
 * BFF Le Frecce vende Trenitalia e Trenord fa il regionale lombardo. Prima di
 * questa classe, per l'app meta' dell'alta velocita' italiana non esisteva.
 *
 * Copre 59 stazioni e nient'altro: fuori da quelle [covers] dice di no e non si
 * spende una chiamata.
 */
class ItaloRepository(
    private val api: ItaloApi,
) {
    /** Vero se Italo ferma in questa stazione. */
    fun covers(rfiCode: String?): Boolean = ItaloStations.italoCode(rfiCode) != null

    /**
     * Partenze o arrivi Italo di una stazione, gia' nel modello del tabellone.
     *
     * Vuoto dove Italo non ferma, senza interrogare nessuno.
     */
    suspend fun board(
        rfiCode: String,
        arrivals: Boolean = false,
        date: LocalDate = LocalDate.now(ROME),
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        val codice = ItaloStations.italoCode(rfiCode) ?: return@withContext emptyList()
        val risposta = runCatching { api.stazione(codice) }.getOrNull() ?: return@withContext emptyList()
        if (risposta.empty) return@withContext emptyList()

        val righe = if (arrivals) risposta.arrivals else risposta.departures
        righe.mapNotNull { it.toBoardEntry(date) }
    }

    /**
     * Stato di una corsa Italo.
     *
     * Null quando il loro servizio non ha nulla da dire, che capita spesso: e'
     * un extra, non la fonte su cui contare. Il tabellone invece risponde
     * sempre, ed e' da li' che le corse Italo entrano nell'app.
     */
    suspend fun trainStatus(
        trainNumber: String,
        date: LocalDate = LocalDate.now(ROME),
        /** Da dove si sale, quando si arriva da un tabellone: vedi sotto. */
        boardingRfi: String? = null,
        boardingName: String? = null,
        /**
         * Dove si scende, quando si arriva da una soluzione di viaggio.
         *
         * E' la strada migliore: con i due capi in mano la tratta si chiede
         * subito, senza passare dal tabellone e senza dover indovinare la
         * direzione da un nome scritto per esteso. Vale oggi per una corsa
         * Italo aperta da una ricerca, e valdra' domani per le tratte Italo
         * dentro un viaggio con cambi.
         */
        alightingRfi: String? = null,
    ): TrainStatus? = withContext(Dispatchers.IO) {
        /*
         * Solo per oggi.
         *
         * Il servizio non prende una data: risponde con l'ultimo stato che
         * conosce. Spacciarlo per la corsa di domani sarebbe lo stesso errore
         * che ViaggiaTreno faceva fare col REG 11813.
         */
        if (date != LocalDate.now(ROME)) return@withContext null

        val pieno = runCatching { api.treno(trainNumber) }.getOrNull()?.toTrainStatus(date)
        if (pieno != null) return@withContext pieno

        // Con i due capi del viaggio la tratta risponde senza altri passaggi.
        if (boardingRfi != null && alightingRfi != null) {
            route(boardingRfi, alightingRfi, date)
                .firstOrNull { it.number.trim() == trainNumber }
                ?.let { return@withContext it }
        }

        /*
         * Il dettaglio tace quasi sempre.
         *
         * Misurato il 27 agosto 2026: dei cinque Italo in viaggio verso Napoli
         * nessuno ha risposto, e nemmeno l'8944 che viaggiava con quindici
         * minuti di ritardo. Chiudere qui vorrebbe dire che toccare una riga
         * Italo del tabellone porta a una schermata vuota.
         *
         * Il tabellone pero' quella corsa la conosce, e sa le cose che servono a
         * chi e' in stazione: ritardo, binario, orario aggiornato. Si ricostruisce
         * di li', dichiarando che si sa solo quel passaggio.
         */
        dalTabellone(trainNumber, date, boardingRfi ?: return@withContext null, boardingName)
    }

    /**
     * Le corse Italo che il servizio sta seguendo fra due stazioni, ciascuna col
     * percorso completo.
     *
     * E' l'unico modo per avere le fermate di una corsa Italo, e sara' anche il
     * punto da cui costruire i viaggi che la comprendono: qui dentro ci sono
     * orari, ritardo e fermate di tutto quello che passa fra due punti.
     *
     * Vuoto dove Italo non arriva, e vuoto quando il loro servizio non sta
     * seguendo nulla su quella tratta.
     */
    suspend fun route(
        fromRfi: String,
        toRfi: String,
        date: LocalDate = LocalDate.now(ROME),
    ): List<TrainStatus> = withContext(Dispatchers.IO) {
        val da = ItaloStations.italoCode(fromRfi) ?: return@withContext emptyList()
        val a = ItaloStations.italoCode(toRfi) ?: return@withContext emptyList()
        if (da == a) return@withContext emptyList()

        val tratta = runCatching { api.tratta(da, a) }.getOrNull() ?: return@withContext emptyList()
        if (tratta.empty) return@withContext emptyList()
        tratta.schedules.mapNotNull { it.toTrainStatus(tratta.lastUpdate, date) }
    }

    /**
     * Le corse Italo fra due stazioni, come gambe di viaggio pronte da concatenare.
     *
     * E' [route] ridotta all'osso che serve a un viaggio misto: di ogni corsa si
     * tiene solo il tratto [fromRfi]→[toRfi], con i suoi due orari, dimenticando
     * il resto del percorso. La gamba esce etichettata [DataSource.ITALO], cosi'
     * il motore dei misti sa a chi chiederne il tempo reale.
     *
     * Ne condivide anche i limiti: risponde solo per le corse che il servizio
     * Italo sta seguendo, quindi su date lontane puo' dare poco o niente. E'
     * un'informazione, non un'assenza di treni.
     */
    suspend fun itinerario(
        fromRfi: String,
        toRfi: String,
        date: LocalDate = LocalDate.now(ROME),
    ): List<Journey> = route(fromRfi, toRfi, date).mapNotNull { ts ->
        val stopFrom = ts.stops.firstOrNull { stessaStazione(it.stationCode, fromRfi) } ?: return@mapNotNull null
        val stopTo = ts.stops.lastOrNull { stessaStazione(it.stationCode, toRfi) } ?: return@mapNotNull null
        val partenza = stopFrom.scheduledDeparture ?: stopFrom.scheduledArrival ?: return@mapNotNull null
        val arrivo = stopTo.scheduledArrival ?: stopTo.scheduledDeparture ?: return@mapNotNull null
        if (!arrivo.isAfter(partenza)) return@mapNotNull null

        val leg = Leg(
            trainNumber = ts.number,
            category = "Italo",
            from = Station(rfiCode = fromRfi, locationId = 0, name = stopFrom.stationName),
            to = Station(rfiCode = toRfi, locationId = 0, name = stopTo.stationName),
            departure = partenza,
            arrival = arrivo,
            source = DataSource.ITALO,
        )
        Journey(
            departure = partenza,
            arrival = arrivo,
            duration = java.time.Duration.between(partenza, arrivo),
            legs = listOf(leg),
        )
    }

    /** Il percorso completo di una corsa, quando la tratta la conosce. */
    private suspend fun percorso(
        trainNumber: String,
        rfiCode: String,
        direzione: String?,
        inPartenza: Boolean,
        date: LocalDate,
    ): TrainStatus? {
        val altrove = ItaloStations.codeByName(direzione)?.let { ItaloStations.rfiCode(it) } ?: return null
        val (da, a) = if (inPartenza) rfiCode to altrove else altrove to rfiCode
        return route(da, a, date).firstOrNull { it.number.trim() == trainNumber }
    }

    private suspend fun dalTabellone(
        trainNumber: String,
        date: LocalDate,
        rfiCode: String,
        stationName: String?,
    ): TrainStatus? {
        if (!covers(rfiCode)) return null

        val codice = ItaloStations.italoCode(rfiCode) ?: return null
        val risposta = runCatching { api.stazione(codice) }.getOrNull() ?: return null
        if (risposta.empty) return null

        val grezzaInPartenza = risposta.departures.firstOrNull { it.number?.trim() == trainNumber }
        val grezza = grezzaInPartenza ?: risposta.arrivals.firstOrNull { it.number?.trim() == trainNumber }
            ?: return null
        val inPartenza = grezzaInPartenza != null
        val riga = grezza.toBoardEntry(date) ?: return null

        /*
         * Prima si prova a farsi dare il percorso intero.
         *
         * `RicercaTrattaService` vuole due stazioni: qui si ha quella dove si
         * sale, e l'altra e' la direzione che il tabellone scrive per esteso —
         * "NAPOLI CENTRALE" — che [ItaloStations] sa ritradurre in sigla. Quando
         * la corsa e' fra quelle seguite tornano tutte le sue fermate, ed e' un
         * dettaglio vero invece di una riga sola.
         */
        percorso(trainNumber, rfiCode, riga.direction, inPartenza, date)?.let { return it }

        val orario = riga.scheduledTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            ?.let { date.atTime(it) }
        val previsto = orario?.plusMinutes(riga.delayMinutes.toLong())

        /*
         * Il percorso scritto nella riga stessa (`InfoRoute`): e' orario di
         * tabella, ma c'e' anche sulle corse che il servizio Italo non segue —
         * cioe' quasi tutte. Meglio le fermate vere a orario di tabella che una
         * riga sola.
         */
        conInfoRoute(grezza, riga, date, rfiCode, stationName, inPartenza, orario, previsto)?.let { return it }

        return TrainStatus(
            number = trainNumber,
            category = "Italo",
            label = riga.label,
            // Il tabellone dice una sola direzione: la destinazione se si parte,
            // la provenienza se si arriva. L'altra meta' non la sa nessuno.
            origin = if (inPartenza) null else riga.direction,
            destination = if (inPartenza) riga.direction else null,
            delayMinutes = riga.delayMinutes,
            state = riga.state,
            lastDetectionStation = null,
            lastDetectionTime = null,
            notice = "Italo pubblica solo i tabelloni: di questa corsa si conosce " +
                "il passaggio da " + (stationName ?: "questa stazione") + ".",
            stops = listOf(
                Stop(
                    index = 1,
                    stationName = stationName.orEmpty(),
                    stationCode = rfiCode,
                    scheduledArrival = if (inPartenza) null else orario,
                    actualArrival = null,
                    arrivalDelayMinutes = if (inPartenza) 0 else riga.delayMinutes,
                    scheduledDeparture = if (inPartenza) orario else null,
                    actualDeparture = null,
                    departureDelayMinutes = if (inPartenza) riga.delayMinutes else 0,
                    scheduledPlatform = null,
                    actualPlatform = riga.actualPlatform,
                    status = StopStatus.FUTURE,
                    projectedArrival = if (inPartenza) null else previsto,
                    projectedDeparture = if (inPartenza) previsto else null,
                ),
            ),
        )
    }

    /**
     * La corsa come la scrive `InfoRoute`: le fermate del percorso, a orario di
     * tabella, intorno a quella del tabellone.
     *
     * Fra le **partenze** `InfoRoute` elenca le fermate successive col loro
     * orario d'arrivo, fra gli **arrivi** quelle precedenti col loro orario di
     * partenza: la fermata dove siamo sta quindi in testa o in coda. Il ritardo
     * e' quello della riga, proiettato sulle fermate future come per ogni altra
     * corsa non ancora rilevata.
     *
     * Gli orari sono di tabella, e la corsa lo dichiara: Italo qui non rileva i
     * passaggi, e `InfoRoute` racconta l'orario, non la corsa di oggi — quando
     * una corsa e' limitata, le due cose si discostano (misurato il 19/09/2026:
     * 7 casi su 97, tutti cosi'). Null se il campo non c'e' o non si legge.
     */
    private fun conInfoRoute(
        grezza: ItaloBoardTrainDto,
        riga: BoardEntry,
        date: LocalDate,
        rfiCode: String,
        stationName: String?,
        inPartenza: Boolean,
        orario: LocalDateTime?,
        previsto: LocalDateTime?,
    ): TrainStatus? {
        val altre = fermateDaInfoRoute(grezza.infoRoute)
        if (altre.isEmpty() || orario == null) return null

        val qui = Stop(
            index = 0,
            stationName = stationName.orEmpty(),
            stationCode = rfiCode,
            scheduledArrival = if (inPartenza) null else orario,
            actualArrival = null,
            arrivalDelayMinutes = if (inPartenza) 0 else riga.delayMinutes,
            scheduledDeparture = if (inPartenza) orario else null,
            actualDeparture = null,
            departureDelayMinutes = if (inPartenza) riga.delayMinutes else 0,
            scheduledPlatform = null,
            actualPlatform = riga.actualPlatform,
            status = StopStatus.FUTURE,
            projectedArrival = if (inPartenza) null else previsto,
            projectedDeparture = if (inPartenza) previsto else null,
            detected = false,
        )

        /*
         * Il percorso puo' scavallare la mezzanotte, e `InfoRoute` scrive solo le
         * ore: si srotolano a partire dalla fermata di qui, l'unica di cui si
         * sappia il giorno. Fra le partenze le altre vengono dopo e il giorno sale
         * quando l'ora cala; fra gli arrivi vengono prima, e si risale all'indietro.
         * E' la stessa regola delle fermate vere, in `ItaloMappers`: senza, l'Italo
         * delle 23:30 arrivava alle 00:45 **dello stesso giorno**, cioe' ventitre
         * ore prima di partire.
         */
        val quandoDelle: List<LocalDateTime> = if (inPartenza) {
            var precedente: LocalDateTime = orario
            altre.map { (_, ora) ->
                precedente = precedente.toLocalDate().atTime(ora)
                    .let { if (it.isBefore(precedente)) it.plusDays(1) else it }
                precedente
            }
        } else {
            var successiva: LocalDateTime = orario
            altre.reversed().map { (_, ora) ->
                successiva = successiva.toLocalDate().atTime(ora)
                    .let { if (it.isAfter(successiva)) it.minusDays(1) else it }
                successiva
            }.reversed()
        }

        val fuori = altre.mapIndexed { i, (nome, _) ->
            val codiceRfi = ItaloStations.rfiCode(ItaloStations.codeByName(nome))
            val quando = quandoDelle[i]
            Stop(
                index = 0,
                stationName = nome,
                stationCode = codiceRfi,
                scheduledArrival = if (inPartenza) quando else null,
                actualArrival = null,
                arrivalDelayMinutes = 0,
                scheduledDeparture = if (inPartenza) null else quando,
                actualDeparture = null,
                departureDelayMinutes = 0,
                scheduledPlatform = null,
                actualPlatform = null,
                status = StopStatus.FUTURE,
                detected = false,
            )
        }

        val tutte = (if (inPartenza) listOf(qui) + fuori else fuori + listOf(qui))
            .mapIndexed { i, fermata -> fermata.copy(index = i + 1).projectedBy(riga.delayMinutes) }

        return TrainStatus(
            number = riga.trainRef.number,
            category = "Italo",
            label = riga.label,
            /*
             * Di questa corsa si conosce **meta' percorso**: fra le partenze le
             * fermate successive, fra gli arrivi le precedenti. Quindi l'origine
             * la si sa solo guardando un arrivo, e la destinazione solo guardando
             * una partenza; dall'altra parte la lista comincia (o finisce) sulla
             * stazione da cui stiamo guardando, che origine non e'. Scriverla
             * lo stesso avrebbe dato l'Italo Napoli-Milano «da Roma Termini».
             * L'altra meta' resta null: non la sa nessuno.
             */
            origin = if (inPartenza) null else tutte.first().stationName,
            destination = if (inPartenza) riga.direction ?: tutte.last().stationName else null,
            delayMinutes = riga.delayMinutes,
            state = riga.state,
            lastDetectionStation = null,
            lastDetectionTime = null,
            notice = "Fermate e orari di tabella, dal tabellone Italo. Italo non rileva i " +
                "passaggi di questa corsa: il ritardo e' quello dichiarato a " +
                (stationName ?: "questa stazione") + ".",
            stops = tutte,
        )
    }
}
