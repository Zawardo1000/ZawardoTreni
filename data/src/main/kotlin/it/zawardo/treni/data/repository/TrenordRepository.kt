package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toServiceAlert
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordCrypto
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

    /** Stato di una corsa Trenord, per le linee che ViaggiaTreno non copre. */
    suspend fun trainStatus(trainNumber: String): TrainStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val body = api.train(trainNumber)
            // L'endpoint restituisce un array: puo' essere vuoto se il numero non esiste.
            decode<List<TrenordSolutionDto>>(body.bytes())?.firstOrNull()?.toTrainStatus()
        }.getOrNull()
    }

    private inline fun <reified T> decode(bytes: ByteArray): T? {
        val plain = TrenordCrypto.decrypt(bytes) ?: return null
        return runCatching { json.decodeFromString<T>(plain) }.getOrNull()
    }
}
