package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.italo.ItaloApi
import it.zawardo.treni.data.remote.italo.ItaloStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
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

        val partenza = board(rfiCode, arrivals = false, date = date)
            .firstOrNull { it.trainRef.number == trainNumber }
        val arrivo = partenza ?: board(rfiCode, arrivals = true, date = date)
            .firstOrNull { it.trainRef.number == trainNumber }
        val riga = arrivo ?: return null
        val inPartenza = partenza != null

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
}
