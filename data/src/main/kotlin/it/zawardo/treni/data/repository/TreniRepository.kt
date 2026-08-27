package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.parseTrainRefLine
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toStation
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ricerca stazioni. */
class StationRepository(
    private val lefrecce: LefrecceApi,
) {
    suspend fun search(query: String, limit: Int = 12): List<Station> =
        withContext(Dispatchers.IO) {
            if (query.length < 2) return@withContext emptyList()
            lefrecce.locations(name = query, limit = limit)
                .filter { it.visible }
                .map { it.toStation() }
        }

    suspend fun closest(lat: Double, lon: Double): Station? =
        withContext(Dispatchers.IO) {
            runCatching { lefrecce.closest(lat, lon).toStation() }.getOrNull()
        }
}

/** Ricerca itinerari A→B sul BFF Le Frecce. */
class JourneyRepository(
    private val lefrecce: LefrecceApi,
) {
    private val bffFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ITALY)

    /**
     * Il `searchId` restituito dalla `/search` scade in circa 10 minuti, quindi
     * le due chiamate restano accoppiate qui dentro e non vengono mai separate.
     */
    suspend fun search(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int = 10,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val session = lefrecce.search(
            startLocationId = from.locationId,
            endLocationId = to.locationId,
            departureTime = departure.format(bffFormat),
        )
        if (session.searchId.isBlank()) return@withContext emptyList()
        lefrecce.solutions(searchId = session.searchId, offset = 0, limit = limit)
            .mapNotNull { it.toJourney() }
            .filter { it.legs.isNotEmpty() }
    }
}

/** Stato realtime delle corse, da ViaggiaTreno. */
class TrainStatusRepository(
    private val viaggiaTreno: ViaggiaTrenoApi,
) {
    /**
     * Formato data accettato dai tabelloni: stile `Date.toString()` di JavaScript,
     * obbligatoriamente in locale inglese.
     */
    private val boardFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH)

    /** Risolve un numero treno nelle corse odierne. Puo' restituirne piu' di una. */
    suspend fun resolve(trainNumber: String): List<TrainRef> = withContext(Dispatchers.IO) {
        val body = runCatching { viaggiaTreno.cercaNumeroTreno(trainNumber).string() }
            .getOrElse { return@withContext emptyList() }
        body.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { parseTrainRefLine(it) }
            .toList()
    }

    /**
     * Stato di una corsa.
     *
     * Restituisce null quando ViaggiaTreno risponde 204: succede sempre per date
     * diverse da oggi, e talvolta per corse soppresse o riprogrammate.
     */
    suspend fun status(ref: TrainRef): TrainStatus? = withContext(Dispatchers.IO) {
        val resp = runCatching {
            viaggiaTreno.andamentoTreno(ref.originCode, ref.number, ref.departureDateMillis)
        }.getOrElse { return@withContext null }
        if (!resp.isSuccessful || resp.code() == 204) return@withContext null
        resp.body()?.toTrainStatus()
    }

    /**
     * Stato di un treno di cui si conosce solo il numero e la data di partenza.
     * Utile per le tratte restituite dal BFF, che non espongono origine/millis.
     */
    suspend fun statusByNumber(trainNumber: String, date: LocalDate): TrainStatus? {
        val refs = resolve(trainNumber)
        if (refs.isEmpty()) return null
        val target = refs.firstOrNull { ref ->
            Instant.ofEpochMilli(ref.departureDateMillis).atZone(ROME).toLocalDate() == date
        } ?: refs.first()
        return status(target)
    }

    suspend fun departures(stationCode: String, at: ZonedDateTime = ZonedDateTime.now()): List<BoardEntry> =
        withContext(Dispatchers.IO) {
            runCatching { viaggiaTreno.partenze(stationCode, at.format(boardFormat)) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toBoardEntry() }
        }

    suspend fun arrivals(stationCode: String, at: ZonedDateTime = ZonedDateTime.now()): List<BoardEntry> =
        withContext(Dispatchers.IO) {
            runCatching { viaggiaTreno.arrivi(stationCode, at.format(boardFormat)) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toBoardEntry() }
        }
}
