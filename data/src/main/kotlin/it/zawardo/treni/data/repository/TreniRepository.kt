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
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainRun
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.matchesCategory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

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

/** Risultato di una ricerca: le soluzioni piu' gli avvisi che le spiegano. */
data class SearchOutcome(
    val journeys: List<Journey> = emptyList(),
    val alerts: List<ServiceAlert> = emptyList(),
)

/**
 * Ricerca itinerari A→B interrogando **entrambe** le sorgenti.
 *
 * Nessuna delle due basta da sola: il BFF Le Frecce non instrada il servizio
 * urbano e suburbano lombardo (una ricerca Milano Dateo → Lambrate tornava con
 * due bus notturni), Trenord non conosce le lunghe percorrenze fuori regione.
 * Insieme coprono entrambi i casi, e Trenord porta anche gli avvisi di lavori
 * e sospensione che altrove non esistono.
 */
class JourneyRepository(
    private val lefrecce: LefrecceApi,
    private val trenord: TrenordRepository? = null,
) {
    /**
     * L'offset di fuso e' OBBLIGATORIO.
     *
     * Senza, il BFF non da' errore: ignora del tutto l'ora e fa ripartire la
     * ricerca da mezzanotte. Una richiesta per le 14:00 tornava con i treni
     * dell'alba. Il pattern `XXX` produce il "+02:00" che serve.
     */
    private val bffFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ITALY)

    /**
     * Il `searchId` restituito dalla `/search` scade in circa 10 minuti, quindi
     * le due chiamate restano accoppiate qui dentro e non vengono mai separate.
     */
    /**
     * Interroga le due sorgenti **in parallelo** e ne fonde i risultati.
     *
     * In serie si sommerebbero i tempi di due backend lenti. Se una fallisce si
     * tiene l'altra: meglio una lista parziale che una schermata vuota.
     */
    suspend fun searchAll(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int = 10,
        /** Le reti da interrogare: quelle spente dall'utente non si chiamano. */
        sources: Set<DataSource> = DataSource.defaultEnabled,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val lefrecceJob = async {
            if (DataSource.TRENITALIA in sources) {
                runCatching { search(from, to, departure, limit) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
        val trenordJob = async {
            if (DataSource.TRENORD in sources && trenord?.covers(from, to) == true) {
                runCatching { trenord.search(from, to, departure) }.getOrNull()
            } else {
                null
            }
        }

        val fromLefrecce = lefrecceJob.await()
        val fromTrenord = trenordJob.await()

        SearchOutcome(
            journeys = merge(fromLefrecce, fromTrenord?.journeys.orEmpty(), departure, limit),
            alerts = fromTrenord?.alerts.orEmpty(),
        )
    }

    /**
     * Unisce le due liste eliminando i doppioni.
     *
     * La stessa corsa puo' arrivare da entrambe: si riconosce dall'orario di
     * partenza e dai numeri dei treni. A parita', vince Trenord, che espone
     * ritardo e soppressione mentre il BFF no.
     */
    private fun merge(
        lefrecce: List<Journey>,
        trenord: List<Journey>,
        departure: LocalDateTime,
        limit: Int,
    ): List<Journey> {
        fun key(j: Journey) = j.departure.withSecond(0).withNano(0).toString() + "|" +
            j.legs.mapNotNull { it.trainNumber }.sorted().joinToString(",")

        val byKey = LinkedHashMap<String, Journey>()
        trenord.forEach { byKey[key(it)] = it }
        lefrecce.forEach { byKey.putIfAbsent(key(it), it) }

        return byKey.values
            .filter { !it.arrival.isBefore(departure.minusHours(1)) }
            .sortedBy { it.departure }
            .take(limit)
    }

    suspend fun search(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int = 10,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val session = lefrecce.search(
            startLocationId = from.locationId,
            endLocationId = to.locationId,
            departureTime = departure.atZone(ROME).format(bffFormat),
        )
        if (session.searchId.isBlank()) return@withContext emptyList()

        /*
         * Si chiede piu' del necessario e si tronca dopo il filtro.
         *
         * Alcune soluzioni non producono tratte utilizzabili e vengono scartate:
         * chiedendone esattamente [limit] il risultato si assottigliava, e nei
         * casi peggiori restava vuoto. Da fuori sembrava che la ricerca non
         * trovasse nulla, e bastava spostare l'orario di un minuto perche'
         * tornassero soluzioni diverse e "funzionasse".
         */
        lefrecce.solutions(searchId = session.searchId, offset = 0, limit = limit * OVERFETCH)
            .mapNotNull { it.toJourney() }
            .filter { it.legs.isNotEmpty() }
            .take(limit)
    }

    private companion object {
        const val OVERFETCH = 3
    }
}

/** Stato realtime delle corse, da ViaggiaTreno. */
class TrainStatusRepository(
    private val viaggiaTreno: ViaggiaTrenoApi,
    /**
     * Serve per i soppressi, che ViaggiaTreno non conosce affatto: di una corsa
     * cancellata non ha il record, quindi cercarla per numero non da' nulla.
     */
    private val trenord: TrenordRepository? = null,
    /** E per Italo, che ViaggiaTreno non pubblica proprio: nessuna corsa, mai. */
    private val italo: ItaloRepository? = null,
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
     * Le corse di un numero, gia' riconoscibili.
     *
     * L'elenco che ViaggiaTreno restituisce cercando un numero da' solo origine
     * e data: due treni diversi con lo stesso numero ne escono identici, e per
     * distinguerli bisognerebbe aprirli. La sigla e i capolinea stanno nel
     * dettaglio, quindi si chiede quello - in parallelo, una chiamata per corsa,
     * e le corse sono quasi sempre una o due.
     *
     * Se una sigla e' stata scritta restringe la scelta: "REG20" apre il
     * regionale, "EC20" l'eurocity. Se non lascia nulla viene ignorata, perche'
     * deve restringere, mai nascondere.
     */
    suspend fun findRuns(
        trainNumber: String,
        category: String? = null,
        /**
         * Se ViaggiaTreno non trova nulla, chiedere anche a Trenord.
         *
         * Vale la pena solo per una ricerca chiesta davvero: quella che parte da
         * sola mentre si digita passerebbe da qui a ogni cifra, e per numeri
         * ancora a meta' che non esistono.
         */
        askTrenord: Boolean = true,
    ): List<TrainRun> =
        withContext(Dispatchers.IO) {
            val refs = resolve(trainNumber)
            val corse = refs
                .map { ref -> async { ref to status(ref) } }
                .map { it.await() }
                .map { (ref, stato) ->
                    TrainRun(
                        ref = ref,
                        label = stato?.label ?: "Treno " + ref.number,
                        origin = stato?.origin ?: ref.originName,
                        destination = stato?.destination,
                    )
                }
            if (corse.isNotEmpty()) {
                return@withContext if (category.isNullOrBlank() || corse.size <= 1) corse
                else corse.filter { matchesCategory(it.label, category) }.ifEmpty { corse }
            }

            /*
             * Niente da ViaggiaTreno: puo' essere un numero che non esiste, ma
             * puo' anche essere una corsa soppressa, che li' viene tolta di
             * mezzo del tutto. Chiederlo a Trenord distingue i due casi, e nel
             * secondo la corsa si apre e si legge "Soppresso" invece di "nessun
             * treno con questo numero".
             */
            if (!askTrenord) return@withContext emptyList()

            /*
             * Le altre due sorgenti, in ordine di probabilita'. Trenord per il
             * regionale lombardo e per i soppressi, Italo per le sue corse, che
             * qui non arriverebbero mai: ViaggiaTreno non le pubblica.
             */
            val altrove = trenord?.let { runCatching { it.trainStatus(trainNumber) }.getOrNull() }
                ?: italo?.let { runCatching { it.trainStatus(trainNumber) }.getOrNull() }
                ?: return@withContext emptyList()

            listOf(
                TrainRun(
                    // Senza codice origine: il dettaglio risolve per numero e data.
                    ref = TrainRef(
                        number = trainNumber,
                        originCode = "",
                        departureDateMillis = LocalDate.now(ROME)
                            .atStartOfDay(ROME).toInstant().toEpochMilli(),
                    ),
                    label = altrove.label.ifBlank { "Treno " + trainNumber },
                    origin = altrove.origin,
                    destination = altrove.destination,
                ),
            )
        }

    /**
     * Sceglie la corsa giusta fra quelle che condividono lo stesso numero.
     *
     * I doppioni nascono in due modi, e vogliono risposte diverse.
     *
     * Due treni diversi con lo stesso numero nello stesso giorno: il 20 e'
     * insieme l'EC Milano Centrale - Chiasso e il REG Cocquio Trevisago - Milano
     * Cadorna. Qui basta la stazione da cui si sale, perche' i percorsi non si
     * somigliano.
     *
     * La stessa corsa in due giorni consecutivi, tutte e due in viaggio: un ICN
     * per Siracusa parte la sera e arriva il pomeriggio dopo, quindi a meta'
     * giornata ne circolano due. Qui la stazione non distingue niente, perche'
     * il percorso e' lo stesso: distingue l'orario di passaggio.
     *
     * Senza contesto di salita resta il criterio della data, ed e' il caso della
     * ricerca per numero, dove la scelta la fa l'utente su un elenco.
     *
     * Per una data futura non restituisce nulla: ViaggiaTreno conosce solo la
     * giornata in corso, e l'orario di domani non e' la corsa di oggi.
     */
    suspend fun resolveFor(
        trainNumber: String,
        date: LocalDate,
        boardingCode: String? = null,
        boardingAt: LocalDateTime? = null,
    ): TrainRef? {
        val refs = resolve(trainNumber)
        if (refs.isEmpty()) return null

        val delGiorno = refs.filter { it.departureDateInRome() == date }

        /*
         * Per una data futura non c'e' corsa da restituire.
         *
         * `cercaNumeroTreno` elenca soltanto le corse in circolazione adesso:
         * prenderne una per il giorno chiesto significa raccontare la giornata
         * sbagliata. Il REG 11813 di domani mattina risultava "arrivato" perche'
         * quello di oggi lo era davvero, alle 6:28.
         *
         * All'indietro il ripiego resta valido, e serve: una corsa notturna
         * parte ieri e riguarda chi sale stamattina.
         */
        if (delGiorno.isEmpty() && date.isAfter(LocalDate.now(ROME))) return null

        if (refs.size == 1) return refs.first()

        val ripiego = delGiorno.firstOrNull() ?: refs.first()
        if (boardingCode.isNullOrBlank()) return ripiego

        // Si entra nelle corse solo qui, dove serve davvero sapere dove passano.
        val passanti = refs.mapNotNull { ref ->
            val fermata = status(ref)?.stops?.firstOrNull {
                it.stationCode.equals(boardingCode, ignoreCase = true)
            }
            fermata?.let { ref to it }
        }
        if (passanti.isEmpty()) return ripiego
        if (passanti.size == 1 || boardingAt == null) {
            return passanti.firstOrNull { it.first in delGiorno }?.first ?: passanti.first().first
        }

        // Passano tutte di li': vince quella che ci passa all'ora giusta.
        return passanti.minByOrNull { (_, fermata) ->
            val quando = fermata.scheduledDeparture ?: fermata.scheduledArrival
            if (quando == null) Long.MAX_VALUE
            else abs(Duration.between(boardingAt, quando).toMinutes())
        }!!.first
    }

    private fun TrainRef.departureDateInRome(): LocalDate =
        Instant.ofEpochMilli(departureDateMillis).atZone(ROME).toLocalDate()

    /**
     * Stato di una corsa di cui si conosce numero e data. Stazione e orario di
     * salita servono a non aprire il treno di qualcun altro: vedi [resolveFor].
     */
    suspend fun statusByNumber(
        trainNumber: String,
        date: LocalDate,
        boardingCode: String? = null,
        boardingAt: LocalDateTime? = null,
    ): TrainStatus? = resolveFor(trainNumber, date, boardingCode, boardingAt)?.let { status(it) }

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
