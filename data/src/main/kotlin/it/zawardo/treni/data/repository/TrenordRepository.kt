package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toServiceAlert
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordBoardParser
import it.zawardo.treni.data.remote.trenord.TrenordCrypto
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.trenord.TrenordStationDetailsDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
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
    /** Orari di stazione gia' scaricati, con il momento in cui sono arrivati. */
    private val orari = mutableMapOf<String, Pair<Long, TrenordStationDetailsDto>>()

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
     * L'orario di stazione secondo Trenord: le corse **programmate**, comprese
     * quelle che oggi non si fanno.
     *
     * E' l'unico elenco che le contenga. Un treno soppresso ViaggiaTreno lo
     * toglie dall'esistenza: non e' in tabellone, `andamentoTreno` risponde 204 e
     * `cercaNumeroTreno` non lo trova. Confrontando questo elenco con il suo si
     * scopre chi manca, e poi lo si chiede alla corsa.
     *
     * Fuori dall'area Trenord la risposta e' vuota: Roma Termini torna con zero
     * righe, quindi chiamarlo non fa danno anche dove non serve.
     */
    suspend fun timetable(rfiCode: String, arrivals: Boolean = false): List<BoardEntry> =
        withContext(Dispatchers.IO) {
            val details = stationDetails(rfiCode) ?: return@withContext emptyList()
            val mezzanotte = LocalDate.now(ROME).atStartOfDay(ROME).toInstant().toEpochMilli()
            TrenordBoardParser.parse(
                if (arrivals) details.arrivo else details.partenza,
                mezzanotte,
            )
        }

    /**
     * La risposta contiene partenze e arrivi insieme, pesa 750 KB (17 KB
     * compressi) ed e' orario di tabella: non cambia da un minuto all'altro.
     * Tenerla qualche minuto evita di riscaricarla girando fra le due schede o
     * riaprendo lo stesso tabellone.
     */
    private suspend fun stationDetails(rfiCode: String): TrenordStationDetailsDto? {
        val chiave = rfiCode.uppercase()
        val adesso = System.currentTimeMillis()
        synchronized(orari) {
            val avuto = orari[chiave]
            if (avuto != null && adesso - avuto.first < ORARI_TTL_MS) return avuto.second
        }
        val fresco = runCatching { api.stationDetails(mirCode = chiave) }.getOrNull() ?: return null
        synchronized(orari) { orari[chiave] = adesso to fresco }
        return fresco
    }

    private inline fun <reified T> decode(bytes: ByteArray): T? {
        val plain = TrenordCrypto.decrypt(bytes) ?: return null
        return runCatching { json.decodeFromString<T>(plain) }.getOrNull()
    }

    private companion object {
        /** Quanto vale un orario di stazione gia' scaricato. */
        const val ORARI_TTL_MS = 5 * 60 * 1000L
    }
}
