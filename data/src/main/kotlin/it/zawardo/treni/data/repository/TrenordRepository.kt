package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toServiceAlert
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordBoardParser
import it.zawardo.treni.data.remote.trenord.TrenordCrypto
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Esito di una ricerca Trenord: le soluzioni e gli avvisi che le spiegano. */
data class TrenordResult(
    val journeys: List<Journey> = emptyList(),
    val alerts: List<ServiceAlert> = emptyList(),
)

/**
 * Copre il buco lasciato dalle altre due sorgenti: le linee S del Passante
 * milanese e il regionale lombardo, che ViaggiaTreno non conosce e che il BFF
 * Le Frecce non instrada.
 *
 * E' anche l'unica fonte che spieghi le situazioni eccezionali: gli avvisi
 * HAFAS riportano lavori e sospensioni di linea.
 */
class TrenordRepository(
    private val api: TrenordApi,
    private val json: Json,
) {
    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val hourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Vero quando la tratta e' interrogabile: serve il codice RFI di entrambe. */
    fun covers(from: Station, to: Station): Boolean =
        !from.rfiCode.isNullOrBlank() && !to.rfiCode.isNullOrBlank()

    suspend fun search(
        from: Station,
        to: Station,
        departure: LocalDateTime,
    ): TrenordResult = withContext(Dispatchers.IO) {
        val origin = from.rfiCode ?: return@withContext TrenordResult()
        val destination = to.rfiCode ?: return@withContext TrenordResult()

        val parsed = runCatching {
            val body = api.search(
                origin = TrenordApi.hafasCode(origin),
                destination = TrenordApi.hafasCode(destination),
                // Il formato deve essere yyyyMMdd: con yyyy-MM-dd risponde 500.
                departureDate = departure.format(dateFormat),
                departureHour = departure.format(hourFormat),
            )
            decode<TrenordSearchDto>(body.bytes())
        }.getOrNull() ?: return@withContext TrenordResult()

        TrenordResult(
            journeys = parsed.solutions.mapNotNull { it.toJourney() },
            alerts = parsed.alerts.mapNotNull { it.toServiceAlert() },
        )
    }

    /**
     * Stato di una corsa Trenord, per le linee che ViaggiaTreno non copre.
     *
     * La data va passata: senza, l'endpoint risponde con l'orario nominale e su
     * una linea deviata per lavori quelle sono fermate diverse da quelle vere.
     */
    suspend fun trainStatus(
        trainNumber: String,
        date: LocalDate = LocalDate.now(),
    ): TrainStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val body = api.train(trainNumber, date.format(isoDate))
            // L'endpoint restituisce un array: puo' essere vuoto se il numero non esiste.
            decode<List<TrenordSolutionDto>>(body.bytes())?.firstOrNull()?.toTrainStatus()
        }.getOrNull()
    }

    /**
     * Tabellone di stazione.
     *
     * Serve dove ViaggiaTreno non arriva: sulle fermate del Passante milanese
     * e' l'unica fonte che elenchi i treni del giorno. E' orario teorico, senza
     * ritardi ne' binari: il tempo reale arriva poi dal dettaglio corsa.
     */
    suspend fun board(
        rfiCode: String,
        arrivals: Boolean = false,
        enrich: Boolean = true,
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        val details = runCatching { api.stationDetails(mirCode = rfiCode) }.getOrNull()
            ?: return@withContext emptyList()
        val today = LocalDate.now()
        val midnight = today.atStartOfDay(ROME).toInstant().toEpochMilli()
        val rows = TrenordBoardParser.parse(
            if (arrivals) details.arrivo else details.partenza,
            midnight,
        )
        if (!enrich || rows.isEmpty()) rows else withRealtime(rows, today)
    }

    /**
     * Recupera il ritardo vero delle righe, una chiamata per corsa.
     *
     * Il tabellone Trenord e' orario teorico: il ritardo esiste solo nel
     * dettaglio corsa, e nemmeno sempre — misurato, e' tracciata circa una corsa
     * su tre. Le altre restano dichiarate come "previsto" invece di essere
     * mostrate a zero, che sarebbe indistinguibile da "in orario".
     *
     * Le chiamate vanno in parallelo e sono limitate a [ENRICH_LIMIT]: servono
     * per le poche fermate che ViaggiaTreno non copre, non per una stazione con
     * cinquanta partenze.
     */
    private suspend fun withRealtime(
        rows: List<BoardEntry>,
        date: LocalDate,
    ): List<BoardEntry> = coroutineScope {
        rows.take(ENRICH_LIMIT).map { row ->
            async {
                val status = trainStatus(row.trainNumber(), date) ?: return@async row
                // notice valorizzato = corsa non tracciata, lo dichiara il mapper.
                val tracked = status.notice == null
                row.copy(
                    delayMinutes = if (tracked) status.delayMinutes else 0,
                    state = if (tracked) status.state else row.state,
                    hasRealtime = tracked,
                )
            }
        }.awaitAll() + rows.drop(ENRICH_LIMIT)
    }

    private fun BoardEntry.trainNumber() = trainRef.number

    private companion object {
        /**
         * Quante righe arricchire con una chiamata dedicata. Serve alle fermate
         * scoperte da ViaggiaTreno, che ne hanno una manciata: oltre questa
         * soglia il costo supererebbe il beneficio.
         */
        const val ENRICH_LIMIT = 12
    }

    private inline fun <reified T> decode(bytes: ByteArray): T? {
        val plain = TrenordCrypto.decrypt(bytes) ?: return null
        return runCatching { json.decodeFromString<T>(plain) }.getOrNull()
    }
}
