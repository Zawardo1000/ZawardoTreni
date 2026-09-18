package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.parseTrainRefLine
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toStation
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.lefrecce.CriteriSito
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.lefrecce.RicercaAvanzataSito
import it.zawardo.treni.data.remote.lefrecce.RichiestaSito
import it.zawardo.treni.data.remote.lefrecce.SoluzioneSito
import it.zawardo.treni.data.remote.viaggiatreno.InfomobilitaParser
import it.zawardo.treni.data.remote.viaggiatreno.NotiziaCorsa
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.NearbyStation
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainRun
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.conAvvisiDa
import it.zawardo.treni.domain.model.conBinariDa
import it.zawardo.treni.domain.model.conRitardoDaFermo
import it.zawardo.treni.domain.model.matchesCategory
import it.zawardo.treni.domain.model.prezzoDaTrenord
import it.zawardo.treni.domain.model.stessaStazione
import it.zawardo.treni.domain.model.vedePartireDa
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import retrofit2.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    /**
     * Le stazioni piu' vicine a un punto, dalla piu' vicina in poi.
     *
     * Il BFF non sa rispondere a questa domanda: `locations/closest` restituisce
     * **una sola** stazione e ignora qualunque parametro di quantita' (provato
     * con `limit`, che non cambia la risposta). L'unico modo di averne tre e'
     * chiedere piu' volte, da punti diversi: la stazione piu' vicina a un punto
     * a sette chilometri a nord non e' quasi mai la stessa piu' vicina a te.
     *
     * Quindi: una prima chiamata sul punto vero, che da' l'ancora e soprattutto
     * dice **quanto e' lontana** la stazione piu' vicina; da quella distanza si
     * dimensionano due anelli di sonde. In citta' gli anelli restano stretti e
     * pescano le stazioni urbane; in montagna si allargano da soli, dove
     * altrimenti tutte le sonde avrebbero risposto la stessa cosa.
     *
     * Le risposte si fondono per `locationId` e si riordinano per distanza vera
     * calcolata in casa: l'ordine che ne esce e' corretto anche quando il BFF,
     * che sceglie con un indice suo, non ha proposto proprio la piu' vicina.
     *
     * Costo: tredici richieste da poche centinaia di byte, tutte verso
     * `locations/closest` come prima, e solo quando l'utente tocca il mirino.
     */
    suspend fun nearest(lat: Double, lon: Double, limit: Int = 3): List<NearbyStation> =
        withContext(Dispatchers.IO) {
            val anchor = closest(lat, lon) ?: return@withContext emptyList()
            val found = linkedMapOf(anchor.locationId to anchor)

            val inner = maxOf(MIN_RING_KM, distanceKm(lat, lon, anchor.latitude, anchor.longitude) + 1.0)
            val probes = INNER_BEARINGS.map { inner to it } + OUTER_BEARINGS.map { inner * OUTER_FACTOR to it }

            val gate = Semaphore(MAX_PARALLEL)
            coroutineScope {
                probes.map { (km, bearing) ->
                    val (pLat, pLon) = offset(lat, lon, km, bearing)
                    async { gate.withPermit { closest(pLat, pLon) } }
                }.awaitAll()
            }.filterNotNull().forEach { found.putIfAbsent(it.locationId, it) }

            found.values
                .map { NearbyStation(it, distanceKm(lat, lon, it.latitude, it.longitude)) }
                .sortedBy { it.distanceKm }
                .take(limit)
        }

    private suspend fun closest(lat: Double, lon: Double): Station? =
        runCatching { lefrecce.closest(lat, lon).toStation() }
            .getOrNull()
            // Senza coordinate non e' ordinabile, e una voce fuori posto in una
            // lista che promette "in ordine di distanza" e' peggio di una in meno.
            ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }

    /** Sposta un punto di [km] lungo la direzione [bearing] (0 = nord, in gradi). */
    private fun offset(lat: Double, lon: Double, km: Double, bearing: Int): Pair<Double, Double> {
        val rad = Math.toRadians(bearing.toDouble())
        val dLat = km * cos(rad) / KM_PER_DEGREE
        val dLon = km * sin(rad) / (KM_PER_DEGREE * cos(Math.toRadians(lat)))
        return lat + dLat to lon + dLon
    }

    private companion object {
        /** Un grado di latitudine, in chilometri. */
        const val KM_PER_DEGREE = 111.32

        /**
         * Raggio minimo dell'anello interno.
         *
         * Sotto, in una stazione grande le sonde ricadrebbero tutte sulla stessa
         * banchina da cui si e' partiti.
         */
        const val MIN_RING_KM = 1.8

        /** Otto direzioni vicine: e' l'anello che decide la seconda e la terza. */
        val INNER_BEARINGS = listOf(0, 45, 90, 135, 180, 225, 270, 315)

        /** Quattro direzioni lontane: servono solo dove attorno non c'e' niente. */
        val OUTER_BEARINGS = listOf(0, 90, 180, 270)

        const val OUTER_FACTOR = 3.0

        /** Tredici richieste insieme sarebbero una raffica: si va a scaglioni. */
        const val MAX_PARALLEL = 4
    }
}

/** Distanza in linea d'aria fra due punti, in chilometri (formula dell'emisenoverso). */
internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a).coerceAtMost(1.0))
}

private const val EARTH_RADIUS_KM = 6371.0

/** Risultato di una ricerca: le soluzioni piu' gli avvisi che le spiegano. */
data class SearchOutcome(
    val journeys: List<Journey> = emptyList(),
    val alerts: List<ServiceAlert> = emptyList(),
    /**
     * Le Frecce ha risposto senza alcun prezzo. E' intermittente, e la stessa
     * ricerca rifatta poco dopo puo' riportarli: vedi [JourneyRepository.prezziLeFrecce].
     */
    val prezziAssenti: Boolean = false,
    /**
     * Le Frecce non ha risposto, nemmeno ai nuovi tentativi: le soluzioni
     * nazionali mancano per un guasto, non perche' non ce ne siano. Chi mostra
     * la lista deve dirlo, o un elenco vuoto sembra una tratta senza treni.
     */
    val nazionaleNonRisponde: Boolean = false,
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
                // perNazionale(): una stazione fuori-RFI con gemello nazionale
                // (Sorrento-EAV) va chiesta a Le Frecce col suo id nazionale,
                // altrimenti il codice sintetico non instrada. Vedi Station.idNazionale.
                runCatching { cercaLeFrecce(from.perNazionale(), to.perNazionale(), departure, limit) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        // Un guasto si dichiara; una richiesta rifiutata resta muta, come prima.
                        RisultatoLeFrecce(nonRisponde = e.passeggero())
                    }
            } else {
                RisultatoLeFrecce()
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
            journeys = merge(fromLefrecce.journeys, fromTrenord?.journeys.orEmpty(), departure, limit),
            alerts = fromTrenord?.alerts.orEmpty(),
            prezziAssenti = fromLefrecce.senzaPrezzi,
            nazionaleNonRisponde = fromLefrecce.nonRisponde,
        )
    }

    /** Vedi [unisciSoluzioni]: sta fuori dalla classe perche' i test la raggiungano. */
    private fun merge(
        lefrecce: List<Journey>,
        trenord: List<Journey>,
        departure: LocalDateTime,
        limit: Int,
    ): List<Journey> = unisciSoluzioni(lefrecce, trenord, departure, limit)

    suspend fun search(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int = 10,
    ): List<Journey> = cercaLeFrecce(from, to, departure, limit).journeys

    /**
     * Una ricerca **nuova** su Le Frecce, per i soli prezzi, per chiave di
     * soluzione (vedi [chiaveSoluzione]).
     *
     * Serve quando la prima e' tornata tutta senza prezzi
     * ([SearchOutcome.prezziAssenti]). Misurato l'11/09/2026 su Varese-Brescia:
     * la stessa ricerca ripetuta ha dato 8 prezzi, poi 0, poi 0; per il giorno
     * dopo 0, 8, 0. Nella stessa sessione, stessa tratta e stessa ora ridanno lo
     * stesso `searchId` (verificato il 18/09/2026): a riportare i prezzi e' il
     * tempo che passa — lo stesso `searchId`, riletto a una decina di secondi di
     * distanza, e' passato da 4 prezzi a nessuno e di nuovo a 4.
     */
    suspend fun prezziLeFrecce(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int = 10,
    ): Map<String, Price> =
        cercaLeFrecce(from.perNazionale(), to.perNazionale(), departure, limit).journeys
            .mapNotNull { j -> j.price?.let { chiaveSoluzione(j) to it } }
            .toMap()

    /**
     * Il prezzo dei treni delle soluzioni Le Frecce che un prezzo non hanno,
     * chiesto a Trenord ([prezzoDaTrenord]). Per chiave di soluzione, come
     * [prezziLeFrecce].
     *
     * Il prezzo di solito lo porta gia' la ricerca, dal sito di Trenitalia (vedi
     * `LefrecceApi.soluzioniDelSito`). Qui arriva quel che resta: le tratte che
     * Trenitalia non prezza affatto — i viaggi dentro la zona urbana di Milano,
     * Milano Centrale-Lambrate zero prezzi su dieci il 18/09/2026, che Trenord
     * vende a tariffa STIBM — e le ricerche in cui il sito non ha risposto.
     *
     * Gli si chiede la parte in treno — dalla stazione del primo treno a quella
     * dell'ultimo — e si prende la sua soluzione con gli stessi treni alla stessa
     * ora. Su una soluzione di soli treni e' il prezzo di tutto; con un tratto
     * urbano e' quello del treno, e il biglietto urbano resta fuori: chi mostra il
     * prezzo lo dice.
     *
     * Una ricerca Trenord torna con piu' partenze: le soluzioni con la stessa
     * parte in treno ne condividono una finche' le copre, e se ne apre un'altra
     * solo per un treno che quella non aveva. Dove Trenord risponde senza alcun
     * prezzo — fuori dalla Lombardia non vende — per quella tratta ci si ferma.
     */
    suspend fun prezziDeiTreni(viaggi: List<Journey>): Map<String, Price> {
        val trenord = trenord ?: return emptyMap()
        val prezzi = mutableMapOf<String, Price>()
        val perTratta = viaggi.filter { it.prezzoDaTrenord }.groupBy { viaggio ->
            val treni = viaggio.legs.filter { it.isTrain }
            treni.first().from.rfiCode to treni.last().to.rfiCode
        }
        for (gruppo in perTratta.values) {
            val treniDelPrimo = gruppo.first().legs.filter { it.isTrain }
            val da = treniDelPrimo.first().from
            val a = treniDelPrimo.last().to
            if (!trenord.covers(da, a)) continue
            val trovate = mutableListOf<Journey>()
            for (viaggio in gruppo.sortedBy { it.departure }) {
                val treni = viaggio.legs.filter { it.isTrain }
                var stessa = trovate.firstOrNull { it.haGliStessiTreniDi(treni) }
                if (stessa == null) {
                    val nuove = runCatching { trenord.search(da, a, treni.first().departure).journeys }
                        .getOrDefault(emptyList())
                    // Trenord che non risponde, che non ha niente o che qui non
                    // vende: inutile insistere su questa tratta.
                    if (nuove.none { it.price != null }) break
                    trovate += nuove
                    stessa = trovate.firstOrNull { it.haGliStessiTreniDi(treni) }
                }
                stessa?.price?.let { prezzi[chiaveSoluzione(viaggio)] = it }
            }
        }
        return prezzi
    }

    /** Stessi treni, nello stesso ordine, in partenza entro un paio di minuti. */
    private fun Journey.haGliStessiTreniDi(treni: List<Leg>): Boolean {
        val suoi = legs.filter { it.isTrain }
        return suoi.size == treni.size && suoi.zip(treni).all { (suo, altro) ->
            suo.trainNumber == altro.trainNumber &&
                abs(Duration.between(suo.departure, altro.departure).toMinutes()) <= 2
        }
    }

    /**
     * Una ricerca Le Frecce, rifatta quando il BFF ha un guasto di passaggio.
     *
     * Una ricerca che torna vuota senza che la tratta lo sia ha sempre la stessa
     * firma: `/search` risponde bene, con decine di soluzioni dichiarate, e
     * `/solutions` risponde **500** — vuoto, o con un messaggio generico in una
     * lingua a caso. Misurato il 18/09/2026 su circa 190 ricerche: il 7,5% delle
     * ricerche nuove, a grappoli, quattro guasti su cinque in un minuto e mezzo.
     * Fino ad allora l'errore finiva inghiottito, e la schermata diceva
     * "Nessun collegamento trovato".
     *
     * Si rifa' solo cio' che e' passeggero ([passeggero]): un 5xx, un errore di
     * rete, una risposta monca. Mai un vuoto vero, che e' `totalSolutions` a
     * zero e `/solutions` a lista vuota; e mai un 4xx, che e' una richiesta
     * sbagliata e sbagliata resta.
     *
     * Rifarla subito non serve: sullo stesso `searchId`, entro due secondi,
     * riesce una volta su cinque; dopo due secondi tre su quattro, dopo cinque
     * quattro su quattro. Di qui le attese di [RIPROVE_LE_FRECCE].
     */
    private suspend fun cercaLeFrecce(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int,
    ): RisultatoLeFrecce {
        var tentativi = 0
        while (true) {
            try {
                return unaRicercaLeFrecce(from, to, departure, limit)
            } catch (e: Exception) {
                if (e is CancellationException || !e.passeggero() || tentativi == RIPROVE_LE_FRECCE.size) throw e
                delay(RIPROVE_LE_FRECCE[tentativi++])
            }
        }
    }

    /**
     * La `/search` e la sua `/solutions`, sempre in coppia. Il `searchId` scade
     * 15 minuti dopo, e nella stessa sessione stessa tratta e stessa ora ridanno
     * lo stesso: le due chiamate restano insieme qui dentro e non si separano mai.
     */
    private suspend fun unaRicercaLeFrecce(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        limit: Int,
    ): RisultatoLeFrecce = withContext(Dispatchers.IO) {
        // I prezzi dal sito, in parallelo, tutte le pagine che servono a [limit]:
        // vedi [conPrezziDelSito].
        val pagineDelSito = (0 until pagineDelSitoPer(limit)).map { pagina ->
            async { paginaDelSito(from, to, departure, offset = pagina * SOLUZIONI_PER_PAGINA) }
        }

        val session = lefrecce.search(
            startLocationId = from.locationId,
            endLocationId = to.locationId,
            departureTime = departure.atZone(ROME).format(bffFormat),
        )
        // Anche una ricerca vera senza treni ha il suo searchId: senza, e' un guasto.
        if (session.searchId.isBlank()) throw RispostaMonca("searchId vuoto")

        /*
         * Si chiede piu' del necessario e si tronca dopo il filtro.
         *
         * Alcune soluzioni non producono tratte utilizzabili e vengono scartate:
         * chiedendone esattamente [limit] il risultato si assottigliava, e nei
         * casi peggiori restava vuoto. Da fuori sembrava che la ricerca non
         * trovasse nulla, e bastava spostare l'orario di un minuto perche'
         * tornassero soluzioni diverse e "funzionasse".
         */
        val soluzioni = lefrecce.solutions(searchId = session.searchId, offset = 0, limit = limit * OVERFETCH)
        if (soluzioni.isEmpty() && session.totalSolutions > 0) {
            throw RispostaMonca("dichiarate ${session.totalSolutions} soluzioni, arrivate nessuna")
        }
        val dellApp = soluzioni
            .mapNotNull { it.toJourney() }
            .filter { it.legs.isNotEmpty() }
            .take(limit)
        // Coi prezzi gia' tutti, il sito non aggiungerebbe niente: non lo si aspetta.
        val viaggi = if (dellApp.none { it.price == null }) {
            pagineDelSito.forEach { it.cancel() }
            dellApp
        } else {
            conPrezziDelSito(dellApp, from, to, departure, pagineDelSito.map { it.await() })
        }
        RisultatoLeFrecce(
            journeys = viaggi,
            /*
             * La ricerca senza prezzi ha una firma precisa: `showPrice` falso su
             * **ogni** soluzione, Frecce comprese, e nessun prezzo rimasto. Una
             * soluzione sola a falso e' invece un biglietto non in vendita, e
             * rifare la ricerca non la cambierebbe.
             */
            senzaPrezzi = viaggi.isNotEmpty() &&
                viaggi.none { it.price != null } &&
                soluzioni.all { it.totalAmount?.showPrice == false },
        )
    }

    /**
     * I prezzi del sito sulle soluzioni dell'app che non ne hanno.
     *
     * Le soluzioni sono quelle dell'app, che ha i codici delle stazioni; i
     * prezzi quelli del sito, che li ha tutti: vedi `LefrecceApi.soluzioniDelSito`.
     * Si accoppiano per partenza al minuto e numeri dei treni — il tratto urbano
     * non conta, il sito non gli da' un numero. Il sito risponde dieci soluzioni
     * alla volta: le pagine che servono al numero chiesto partono insieme, subito,
     * e se l'app ne ha date di piu' tarde se ne chiede un'altra, fino a
     * [PAGINE_DEL_SITO]. Misurato il 18/09/2026 su 12 ricerche da quindici
     * soluzioni: 178 prezzi su 180, contro circa sei su dieci dalla sola app.
     *
     * Il sito che non risponde non toglie niente: restano i prezzi che l'app
     * aveva, e gli altri li cerca chi viene dopo.
     */
    private suspend fun conPrezziDelSito(
        viaggi: List<Journey>,
        from: Station,
        to: Station,
        departure: LocalDateTime,
        pagineArrivate: List<List<SoluzioneSito>?>,
    ): List<Journey> {
        // Una pagina che non risponde interrompe la fila: le successive non si sanno accostare.
        val arrivate = pagineArrivate.takeWhile { it != null }.filterNotNull()
        if (viaggi.none { it.price == null } || arrivate.isEmpty()) return viaggi
        val delSito = arrivate.flatten().toMutableList()
        var pagine = arrivate.size
        val ultima = viaggi.maxOf { it.departure }
        while (pagine < PAGINE_DEL_SITO && delSito.size == pagine * SOLUZIONI_PER_PAGINA &&
            delSito.mapNotNull { it.partenza() }.maxOrNull()?.isBefore(ultima) == true
        ) {
            val altra = paginaDelSito(from, to, departure, offset = pagine * SOLUZIONI_PER_PAGINA) ?: break
            delSito += altra
            pagine++
        }
        val prezzi = delSito.mapNotNull { sito ->
            val partenza = sito.partenza() ?: return@mapNotNull null
            val prezzo = sito.prezzo() ?: return@mapNotNull null
            chiavePrezzo(partenza, sito.trains.mapNotNull { it.name }) to prezzo
        }.toMap()
        return viaggi.map { viaggio ->
            if (viaggio.price != null) return@map viaggio
            val chiave = chiavePrezzo(viaggio.departure, viaggio.legs.filter { it.isTrain }.mapNotNull { it.trainNumber })
            prezzi[chiave]?.let { viaggio.copy(price = it) } ?: viaggio
        }
    }

    /**
     * Una pagina del sito; null se non risponde, o non in tempo, che qui non e'
     * un errore. Il tempo e' contato ([ATTESA_SITO_MS]): la ricerca aspetta i
     * suoi prezzi, e un sito lento non deve rallentare la lista.
     */
    private suspend fun paginaDelSito(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        offset: Int,
    ): List<SoluzioneSito>? = try {
        withTimeoutOrNull(ATTESA_SITO_MS) { richiestaDelSito(from, to, departure, offset) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun richiestaDelSito(
        from: Station,
        to: Station,
        departure: LocalDateTime,
        offset: Int,
    ): List<SoluzioneSito> =
        lefrecce.soluzioniDelSito(
            RichiestaSito(
                departureLocationId = from.locationId,
                arrivalLocationId = to.locationId,
                departureTime = departure.format(orarioDelSito),
                adults = 1,
                children = 0,
                criteria = CriteriSito(
                    frecceOnly = false,
                    regionalOnly = false,
                    intercityOnly = false,
                    tourismOnly = false,
                    noChanges = false,
                    order = "DEPARTURE_DATE",
                    offset = offset,
                    limit = SOLUZIONI_PER_PAGINA,
                ),
                advancedSearchRequest = RicercaAvanzataSito(bestFare = false),
            ),
        ).solutions.mapNotNull { it.solution }

    /**
     * Le pagine del sito da chiedere subito per [limit] soluzioni. Una basta alla
     * prima ricerca, da otto; per le pagine larghe ne serve una in piu' del
     * conto, perche' il sito elenca anche soluzioni che l'app scarta: su Roma -
     * Firenze, il 18/09/2026, quindici soluzioni dell'app chiedevano la terza
     * pagina, e chiederla dopo le altre portava la ricerca a nove secondi.
     */
    private fun pagineDelSitoPer(limit: Int): Int =
        if (limit <= SOLUZIONI_PER_PAGINA) 1
        else ((limit + SOLUZIONI_PER_PAGINA - 1) / SOLUZIONI_PER_PAGINA + 1).coerceAtMost(PAGINE_DEL_SITO)

    /** Il sito scrive l'ora locale senza fuso, al contrario della `/search` dell'app. */
    private val orarioDelSito: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    private fun SoluzioneSito.partenza(): LocalDateTime? = departureTime?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ROME).toLocalDateTime() }.getOrNull()
    }

    private fun SoluzioneSito.prezzo(): Price? {
        val p = price ?: return null
        val euro = p.amount?.takeIf { it > 0.0 && !p.hideAmount } ?: return null
        return Price(
            amount = "%.2f".format(Locale.US, euro),
            currency = "EUR",
            saleable = status == "SALEABLE",
        )
    }

    /**
     * Partenza al minuto e numeri dei treni: quanto basta a riconoscere la stessa
     * soluzione dall'app e dal sito. Solo i numeri veri — il tratto urbano l'app
     * lo chiama "Urb" e il sito null.
     */
    private fun chiavePrezzo(partenza: LocalDateTime, numeri: List<String>): String =
        partenza.withSecond(0).withNano(0).toString() + "|" +
            numeri.filter { n -> n.isNotEmpty() && n.all { it.isDigit() } }.sorted().joinToString(",")

    /** Le soluzioni di Le Frecce, se sono arrivate senza prezzi, e se il BFF non ha risposto. */
    private data class RisultatoLeFrecce(
        val journeys: List<Journey> = emptyList(),
        val senzaPrezzi: Boolean = false,
        val nonRisponde: Boolean = false,
    )

    /** Una risposta a cui manca un pezzo che una risposta vera ha sempre. */
    private class RispostaMonca(motivo: String) : IOException("Le Frecce: $motivo")

    /** Un guasto che la stessa ricerca, poco dopo, puo' non avere: vedi [cercaLeFrecce]. */
    private fun Throwable.passeggero(): Boolean = when (this) {
        is HttpException -> code() >= 500
        is IOException -> true
        else -> false
    }

    private companion object {
        const val OVERFETCH = 3

        /** Quante soluzioni da' il sito per pagina, qualunque sia il limite chiesto. */
        const val SOLUZIONI_PER_PAGINA = 10

        /** Fin dove si pagina il sito per coprire le soluzioni dell'app. */
        const val PAGINE_DEL_SITO = 3

        /**
         * Quanto si aspetta una pagina del sito: il 18/09/2026 rispondeva in
         * 1-3 secondi, 4 al piu' lento su 40 richieste.
         */
        const val ATTESA_SITO_MS = 6_000L

        /** Le attese prima di ogni nuovo tentativo: vedi [cercaLeFrecce]. */
        val RIPROVE_LE_FRECCE = listOf(2_000L, 5_000L)
    }
}

/**
 * Come la stessa soluzione si riconosce in due sorgenti: l'orario di partenza al
 * minuto e i numeri dei treni.
 */
fun chiaveSoluzione(j: Journey): String =
    j.departure.withSecond(0).withNano(0).toString() + "|" +
        j.legs.mapNotNull { it.trainNumber }.sorted().joinToString(",")

/**
 * Unisce le soluzioni di Le Frecce e di Trenord eliminando i doppioni.
 *
 * La stessa corsa puo' arrivare da entrambe: si riconosce con [chiaveSoluzione].
 * A parita', vince Trenord, che espone ritardo e soppressione mentre il BFF no.
 *
 * **Ma il prezzo non si perde.** Trenord risponde anche fuori dalla Lombardia,
 * dove non vende biglietti, e la sua copia senza prezzo prendeva il posto di
 * quella di Le Frecce che il prezzo ce l'aveva. Misurato l'11/09/2026: l'ICN
 * 797 Napoli-Salerno a 9,50 € usciva senza cifra, e cosi' due RV Torino-Milano.
 * Ora la copia Trenord vince ancora, ma eredita il prezzo. Se il prezzo ce l'ha
 * gia', resta il suo.
 */
internal fun unisciSoluzioni(
    lefrecce: List<Journey>,
    trenord: List<Journey>,
    departure: LocalDateTime,
    limit: Int,
): List<Journey> {
    val byKey = LinkedHashMap<String, Journey>()
    trenord.forEach { byKey[chiaveSoluzione(it)] = it }
    lefrecce.forEach { lf ->
        val chiave = chiaveSoluzione(lf)
        val tn = byKey[chiave]
        when {
            tn == null -> byKey[chiave] = lf
            tn.price == null && lf.price != null -> byKey[chiave] = tn.copy(price = lf.price)
        }
    }

    /*
     * Dall'ora cercata in avanti, al minuto.
     *
     * Il filtro teneva tutto cio' che *arrivava* non prima di un'ora fa: un
     * treno gia' partito ma non ancora arrivato passava, e cercando «adesso»
     * Dateo-Vignate l'11/09/2026 in cima alla lista usciva una S5 gia' partita.
     * Le corse di prima restano raggiungibili con «Corse precedenti», che cerca
     * partendo da ore prima. Al minuto, perche' «adesso» porta anche i secondi:
     * un treno che parte alle 11:45 non va scartato alle 11:45:31.
     */
    val daQuando = departure.withSecond(0).withNano(0)
    return byKey.values
        .filter { !it.departure.isBefore(daQuando) }
        .sortedBy { it.departure }
        .take(limit)
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

    /**
     * Le corse per cui Trenord ha risposto a vuoto, e quando.
     *
     * Serve a [completaBinari]: un numero fuori dalla Lombardia dara' sempre la
     * stessa lista vuota, e continuare a chiederlo a ogni aggiornamento
     * raddoppia il traffico della schermata per non aggiungere niente.
     *
     * Si riprova dopo [RICHIEDI_DOPO_MS] e non mai piu', perche' quel null puo'
     * anche essere stata una rete caduta per un attimo: una dimenticanza
     * definitiva trasformerebbe un intoppo di un secondo in una funzione spenta
     * per tutta la sessione.
     *
     * Concorrente perche' il repository e' condiviso: la schermata del treno e
     * il servizio «Segui treno» ci arrivano da coroutine diverse.
     */
    private val senzaTrenord = ConcurrentHashMap<String, Long>()

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
     *
     * Un treno fermo all'origine oltre la sua ora esce col ritardo che ha
     * davvero, non con lo zero di ViaggiaTreno: vedi `conRitardoDaFermo`. Qui e
     * non nel mapper, perche' e' il solo punto da cui passano tutte le corse
     * ViaggiaTreno e perche' vuole l'ora di adesso. Non dove la corsa di ieri
     * prova il contrario: vedi [nonPartitoVuolDireFermo].
     */
    suspend fun status(ref: TrainRef): TrainStatus? = withContext(Dispatchers.IO) {
        val resp = runCatching {
            viaggiaTreno.andamentoTreno(ref.originCode, ref.number, ref.departureDateMillis)
        }.getOrElse { return@withContext null }
        if (!resp.isSuccessful || resp.code() == 204) return@withContext null
        val stato = resp.body()?.toTrainStatus() ?: return@withContext null
        val daFermo = stato.conRitardoDaFermo(LocalDateTime.now(ROME))
        if (daFermo.delayMinutes != stato.delayMinutes && nonPartitoVuolDireFermo(ref)) daFermo else stato
    }

    /**
     * Le risposte di [nonPartitoVuolDireFermo], per corsa: la chiave porta il
     * giorno. Non di piu', perche' la risposta dipende da com'e' andata ieri, e
     * un tratto fatto in bus per lavori prima o poi torna in treno. Concorrente
     * come [senzaTrenord].
     */
    private val partenzeViste = ConcurrentHashMap<String, Boolean>()

    /**
     * Se il «non partito» di ViaggiaTreno, per questa corsa, puo' voler dire
     * fermo all'origine. Falso solo con la prova del contrario.
     *
     * A Milano Centrale lo vuol dire: il RE 2824 del 17/09/2026 era in banchina.
     * Il REG 2987 del 18/09/2026 invece era dato in partenza da Gallarate alle
     * 22:54, e Gallarate quella sera non l'ha mai visto: Saronno-Malpensa-
     * Gallarate era chiusa e fatta in bus, e il treno partiva da Saronno. Lo
     * diceva la ricerca di Trenord, che lo proponeva solo «Saronno 23:36 →
     * Milano Centrale»; la corsa no, ne' `andamentoTreno` ne' quella di Trenord
     * segnavano soppresso il tratto. ViaggiaTreno l'ha tenuto «non partito» fino
     * a Saronno, alle 23:43: contato da Gallarate, alle 23:44 era a +50, e da
     * Saronno era partito con sette minuti.
     *
     * La prova e' la corsa di ieri ([vedePartireDa]): se ieri ViaggiaTreno il
     * treno l'ha visto solo piu' avanti, e mai partire dall'origine, il silenzio
     * di oggi non dice che sia fermo li'. Il 2987 e il 2989 non avevano l'orario
     * reale a Gallarate ne' il 18 ne' il 17.
     *
     * **Quella prova c'e' di rado, e senza si fa come prima.** ViaggiaTreno la
     * corsa di ieri la da' solo se e' arrivata oggi, cioe' se ha passato la
     * mezzanotte: il 19/09/2026 alle 00:30 nove corse diurne del 18 — il RE 2824,
     * il FR 9303 fra le altre — rispondevano 204, il 2987 del 18 no. Per un treno
     * di giorno quindi vale la regola di [conRitardoDaFermo] com'era, ed e' il
     * caso del RE 2824, fermo davvero. La prova copre le corse serali, che sono
     * anche quelle dei lavori notturni.
     *
     * Una chiamata in piu', solo sui treni «non partiti» oltre la loro ora, e una
     * volta per corsa: la risposta si ricorda, anche vuota. Un errore di rete no,
     * e la volta dopo si richiede.
     */
    private suspend fun nonPartitoVuolDireFermo(ref: TrainRef): Boolean {
        val chiave = "${ref.number}|${ref.originCode}|${ref.departureDateMillis}"
        partenzeViste[chiave]?.let { return it }
        val ieri = ref.departureDateInRome().minusDays(1).atStartOfDay(ROME).toInstant().toEpochMilli()
        val risposta = try {
            viaggiaTreno.andamentoTreno(ref.originCode, ref.number, ieri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        if (!risposta.isSuccessful) return true
        val ieriVisto = risposta.takeIf { it.code() != 204 }?.body()?.toTrainStatus()?.vedePartireDa(ref.originCode)
        // Senza prova, anche con la corsa di ieri che non c'e': come prima.
        val fermo = ieriVisto != false
        partenzeViste[chiave] = fermo
        return fermo
    }

    /**
     * Aggiunge a una corsa i binari che ViaggiaTreno non ha, chiedendoli a Trenord.
     *
     * ViaggiaTreno pubblica binario programmato ed effettivo, ma non dappertutto:
     * fuori RFI non ne ha nessuno — sulla Milano-Malpensa lascia vuote otto
     * fermate su undici — e su RFI capita che manchi anche il programmato,
     * proprio dove serve. Il REG 2874 delle 17:50, il 04/09/2026, non aveva
     * alcun binario a Milano Centrale mentre tutte le fermate minori ce
     * l'avevano: il capolinea dove sali era l'unica riga senza.
     *
     * Trenord, sulle sue corse, riempie quei buchi. Non li riempie tutti — a
     * Milano Centrale il binario esiste da quando viene assegnato, un quarto
     * d'ora prima, non ore prima — ma quel che ha e' esattamente cio' che
     * all'altra manca.
     *
     * **Si chiede solo se serve, e non si insiste.** Se ogni fermata ha gia' i
     * suoi due binari non parte alcuna chiamata. E un numero che Trenord non
     * conosce — un Frecciarossa per Napoli — non gli si richiede a ogni
     * aggiornamento: senza questo, una schermata aperta mezz'ora avrebbe
     * bussato trenta volte per sentirsi rispondere trenta volte la stessa lista
     * vuota. Vedi [senzaTrenord].
     *
     * Con la stessa lettura arriva, gratis, il **perche'** di una variazione e
     * gli avvisi di circolazione, che ViaggiaTreno non pubblica: vedi
     * [conAvvisiDa]. Su una corsa in viaggio la chiamata parte comunque, perche'
     * alle fermate da fare il binario vero non c'e' ancora.
     *
     * Il chiamante deve comunque rispettare l'interruttore della sorgente: qui
     * non si leggono le impostazioni.
     */
    suspend fun completaBinari(status: TrainStatus, date: LocalDate): TrainStatus {
        val trenord = trenord ?: return status
        // Su una corsa che viene dall'orario i binari sono inconoscibili, non
        // mancanti: riempirli con quelli di oggi sarebbe inventare.
        if (!status.realtime) return status
        val numero = status.number.takeIf { it.isNotBlank() } ?: return status
        if (status.stops.none { it.scheduledPlatform == null || it.actualPlatform == null }) return status

        val chiave = "$numero|$date"
        val adesso = System.currentTimeMillis()
        senzaTrenord[chiave]?.let { if (adesso - it < RICHIEDI_DOPO_MS) return status }

        val altra = runCatching { trenord.trainStatus(numero, date) }.getOrNull()
        if (altra == null) {
            senzaTrenord[chiave] = adesso
            return status
        }
        senzaTrenord.remove(chiave)
        return status.conBinariDa(altra).conAvvisiDa(altra)
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
                stessaStazione(it.stationCode, boardingCode)
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
            val adesso = LocalDateTime.now(ROME)
            val righe = runCatching { viaggiaTreno.partenze(stationCode, at.format(boardFormat)) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toBoardEntry() }
            // Come per la corsa, vedi [nonPartitoVuolDireFermo]; in parallelo, perche'
            // su un tabellone i treni fermi oltre la loro ora possono essere piu' d'uno.
            coroutineScope {
                righe.map { riga ->
                    async {
                        val daFermo = riga.conRitardoDaFermo(stationCode, adesso)
                        val cambia = daFermo.delayMinutes != riga.delayMinutes
                        if (cambia && nonPartitoVuolDireFermo(riga.trainRef)) daFermo else riga
                    }
                }.awaitAll()
            }
        }

    suspend fun arrivals(stationCode: String, at: ZonedDateTime = ZonedDateTime.now()): List<BoardEntry> =
        withContext(Dispatchers.IO) {
            runCatching { viaggiaTreno.arrivi(stationCode, at.format(boardFormat)) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toBoardEntry() }
        }

    /** Le notizie di ViaggiaTreno gia' lette, e quando: vedi [conNotizie]. */
    @Volatile
    private var notizie: Pair<Long, List<NotiziaCorsa>>? = null

    /**
     * Aggiunge a una corsa quel che ne dicono le notizie di ViaggiaTreno: il
     * perche' di un ritardo o di una variazione, che la corsa non dice mai.
     * Vedi `InfomobilitaParser`.
     *
     * La pagina e' una sola per tutta Italia, 17 KB, e cambia di rado: si tiene
     * per [NOTIZIE_VALIDE_MS], cosi' aprire dieci treni costa una chiamata sola.
     * Se non risponde la corsa resta com'era.
     *
     * Quando una notizia vale per la corsa lo decide [NotiziaCorsa.riguarda].
     */
    suspend fun conNotizie(status: TrainStatus, date: LocalDate): TrainStatus {
        if (!status.realtime) return status
        if (status.number.isBlank()) return status
        val oggi = LocalDate.now(ROME)
        val suoi = notizieDiOggi()
            .filter { it.riguarda(status, date, oggi) }
            .map { it.testo }
        if (suoi.isEmpty()) return status
        return status.copy(avvisi = (suoi + status.avvisi).distinct())
    }

    private suspend fun notizieDiOggi(): List<NotiziaCorsa> = withContext(Dispatchers.IO) {
        val adesso = System.currentTimeMillis()
        notizie?.let { (quando, lette) -> if (adesso - quando < NOTIZIE_VALIDE_MS) return@withContext lette }
        val lette = runCatching { InfomobilitaParser.parse(viaggiaTreno.infomobilita().string()) }
            .getOrElse { return@withContext notizie?.second.orEmpty() }
        notizie = adesso to lette
        lette
    }

    private companion object {
        /** Quanto si aspetta prima di richiedere a Trenord una corsa che non conosceva. */
        const val RICHIEDI_DOPO_MS = 15 * 60_000L

        /** Per quanto vale una lettura delle notizie di ViaggiaTreno. */
        const val NOTIZIE_VALIDE_MS = 5 * 60_000L
    }
}
