package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.minutesFrom
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.consolidate
import it.zawardo.treni.domain.model.matchesCategory
import it.zawardo.treni.domain.model.trainCategoryOf
import it.zawardo.treni.domain.model.trainNumberOf
import it.zawardo.treni.domain.model.stillCatchable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Verifica d'insieme, non dei singoli contratti.
 *
 * [LiveApiTest] controlla una risposta per volta; qui si controlla che i pezzi
 * stiano insieme: che i tabelloni rispondano senza doppioni, che la paginazione
 * a finestre avanzi davvero, che la ricerca fonda Le Frecce e Trenord senza
 * perdere soluzioni, e che i servizi sostitutivi restino distinti dai treni.
 *
 * Dipendono dal servizio reale del momento: quando una tratta e' ferma il test
 * lo dichiara invece di fallire, perche' un treno che non circola non e' un
 * difetto dell'app.
 */
class IntegrazioneTest {

    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi)

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Stazioni scelte per coprire capolinea, nodi e fermate del Passante. */
    private val campione = listOf(
        "S01700" to "Milano Centrale",
        "S01066" to "Milano Cadorna",
        "S01701" to "Milano Lambrate",
        "S01650" to "Milano Dateo",
        "S01649" to "Milano Porta Venezia",
    )

    @Test
    fun `i tabelloni rispondono e non hanno doppioni`() = runBlocking {
        println("\n=== TABELLONI: copertura e doppioni ===")
        println(String.format("%-30s %8s %12s", "stazione", "treni", "con ritardo"))

        for ((code, nome) in campione) {
            val rfi = runCatching { trains.departures(code) }.getOrDefault(emptyList())
            val conRitardo = rfi.count { it.delayMinutes != 0 }
            println(String.format("%-30s %8d %12d", nome, rfi.size, conRitardo))

            // Nessun doppione nella finestra restituita.
            val chiavi = rfi.map { it.trainRef.number + "|" + it.scheduledTime }
            assertTrue(
                "$nome: ${chiavi.size - chiavi.toSet().size} righe duplicate nel tabellone",
                chiavi.size == chiavi.toSet().size,
            )
        }
    }

    @Test
    fun `la paginazione del tabellone avanza nel tempo`() = runBlocking {
        val code = "S01700"
        val ora = ZonedDateTime.now()
        val primo = trains.departures(code, ora)
        val secondo = trains.departures(code, ora.plusMinutes(90))

        val chiavi = primo.map { it.trainRef.number + "|" + it.scheduledTime }.toSet()
        val nuovi = secondo.filter { it.trainRef.number + "|" + it.scheduledTime !in chiavi }

        println("\n=== PAGINAZIONE TABELLONE (Milano Centrale) ===")
        println("  finestra 1: ${primo.size} treni, ultimo ${primo.lastOrNull()?.scheduledTime}")
        println("  finestra 2: ${secondo.size} treni, ultimo ${secondo.lastOrNull()?.scheduledTime}")
        println("  nuovi nella seconda: ${nuovi.size}")

        assertTrue("la prima finestra e' vuota", primo.isNotEmpty())
        assertTrue(
            "la seconda finestra non porta nulla di nuovo: lo scorrimento non avanzerebbe",
            nuovi.isNotEmpty(),
        )
    }

    @Test
    fun `la ricerca fonde davvero le due sorgenti`() = runBlocking {
        val from = stations.search("milano centrale").first { it.trackable }
        val to = stations.search("calolziocorte").first { it.trackable }
        val quando = LocalDateTime.now()

        val soloLefrecce = runCatching { journeys.search(from, to, quando, limit = 10) }
            .getOrDefault(emptyList())
        val soloTrenord = runCatching { trenord.search(from, to, quando) }.getOrNull()
        val fuse = journeys.searchAll(from, to, quando, limit = 10)

        println("\n=== RICERCA ${from.name} -> ${to.name} ===")
        println("  solo Le Frecce : ${soloLefrecce.size}")
        println("  solo Trenord   : ${soloTrenord?.journeys?.size ?: 0}")
        println("  fuse           : ${fuse.journeys.size}  (avvisi: ${fuse.alerts.size})")
        fuse.journeys.take(6).forEach {
            println("     ${it.departure.format(hhmm)} [${it.source}] cambi=${it.changes} " +
                it.legs.joinToString(" + ") { l -> l.label })
        }

        assertTrue("la ricerca combinata non restituisce nulla", fuse.journeys.isNotEmpty())

        // Nessun doppione sulla coppia orario + treni.
        val chiavi = fuse.journeys.map {
            it.departure.toString() + "|" + it.legs.mapNotNull { l -> l.trainNumber }.sorted()
        }
        assertTrue("soluzioni duplicate nella fusione", chiavi.size == chiavi.toSet().size)

        // La fusione non deve perdere pezzi: almeno quanto la migliore singola.
        val migliore = maxOf(soloLefrecce.size, soloTrenord?.journeys?.size ?: 0)
        assertTrue(
            "la fusione ha ${fuse.journeys.size} soluzioni ma la sorgente migliore ne aveva " +
                "$migliore: si sta perdendo qualcosa",
            fuse.journeys.size >= minOf(migliore, 10),
        )
    }

    /**
     * Il caso che conta e' il treno in ritardo: ha l'orario di tabella nel
     * passato ma parte ancora, e sparire sarebbe il danno peggiore che il
     * tabellone possa fare.
     */
    /**
     * Quello che si legge deve essere quello che si puo' cercare.
     *
     * L'utente copia l'etichetta dai risultati o dal tabellone e la incolla
     * nella ricerca treno: se da "RE_8 2828" non si ricava "2828", quella corsa
     * per lui non esiste.
     */
    @Test
    fun `l'etichetta mostrata rientra nella ricerca treno`() = runBlocking {
        val da = stations.search("milano centrale").first { it.trackable }
        val a = stations.search("calolziocorte").first { it.trackable }
        val tratte = journeys.searchAll(da, a, LocalDateTime.now(), limit = 10)
            .journeys.flatMap { it.legs }.filter { it.isTrain }
        val tabellone = trains.departures("S01700")

        println("\n=== ETICHETTE -> NUMERO ===")
        (tratte.map { it.label to it.trainNumber } + tabellone.map { it.label to it.trainRef.number })
            .distinct()
            .take(12)
            .forEach { (etichetta, numero) ->
                println(String.format("  %-18s -> %-8s (atteso %s)", etichetta, trainNumberOf(etichetta), numero))
            }

        assertTrue("nessuna tratta su cui verificare", tratte.isNotEmpty())
        assertTrue("tabellone vuoto: non si puo' concludere nulla", tabellone.isNotEmpty())

        val rotte = tratte.filter { trainNumberOf(it.label) != it.trainNumber }
        assertTrue(
            "da queste etichette non si ricava il numero: " +
                rotte.joinToString { it.label + " -> " + trainNumberOf(it.label) },
            rotte.isEmpty(),
        )
        val rotteTabellone = tabellone.filter { trainNumberOf(it.label) != it.trainRef.number }
        assertTrue(
            "etichette di tabellone non ricercabili: " +
                rotteTabellone.joinToString { it.label + " -> " + trainNumberOf(it.label) },
            rotteTabellone.isEmpty(),
        )
    }

    @Test
    fun `il filtro toglie i partiti e non i ritardatari`() {
        fun riga(orario: String, ritardo: Int, stato: TrainState, inStazione: Boolean) =
            BoardEntry(
                trainRef = TrainRef("1", "S00001", 0L),
                label = "REG 1",
                category = "REG",
                direction = "Chissa'",
                scheduledTime = orario,
                delayMinutes = ritardo,
                scheduledPlatform = null,
                actualPlatform = null,
                state = stato,
                inStation = inStazione,
            )

        val ora = LocalTime.of(14, 0)
        val partito = riga("13:50", 0, TrainState.REGULAR, false)
        val fermoOltreOrario = riga("13:50", 0, TrainState.REGULAR, inStazione = true)
        val ritardatario = riga("13:50", 20, TrainState.DELAYED, false)
        val nonPartitoDaOrigine = riga("13:50", 0, TrainState.NOT_DEPARTED, false)
        val futuro = riga("14:30", 0, TrainState.REGULAR, false)
        val dopoMezzanotte = riga("00:10", 0, TrainState.REGULAR, false)

        val tenuti = listOf(
            partito, fermoOltreOrario, ritardatario, nonPartitoDaOrigine, futuro, dopoMezzanotte,
        ).stillCatchable(ora)

        assertTrue("un treno gia' partito resta nel tabellone", partito !in tenuti)
        assertTrue(
            "fermo in stazione ma oltre il proprio orario: ha chiuso le porte",
            fermoOltreOrario !in tenuti,
        )
        assertTrue("un treno in ritardo di 20 minuti e' sparito", ritardatario in tenuti)
        assertTrue("un treno non ancora partito dall'origine e' sparito", nonPartitoDaOrigine in tenuti)
        assertTrue("un treno futuro e' sparito", futuro in tenuti)
        assertTrue("le 00:10 lette alle 14:00 non sono un anticipo di 14 ore", dopoMezzanotte in tenuti)
    }

    @Test
    fun `sul tabellone vero non restano corse gia' andate`() = runBlocking {
        val ora = LocalTime.now()
        val grezzo = trains.departures("S01700", ZonedDateTime.now())
        val tenuti = grezzo.stillCatchable(ora)
        val tolti = grezzo - tenuti.toSet()

        println("\n=== FILTRO PARTENZE (Milano Centrale, ${ora.withNano(0)}) ===")
        println("  ricevuti ${grezzo.size}, tenuti ${tenuti.size}, tolti ${tolti.size}")
        tolti.take(5).forEach {
            println("     - ${it.scheduledTime} ${it.delayMinutes.let { d -> if (d > 0) "+" + d else d }}" +
                "  ${it.direction}  [${it.state}]")
        }

        assertTrue("il tabellone e' vuoto: non si puo' concludere nulla", grezzo.isNotEmpty())
        assertTrue("il filtro ha svuotato il tabellone", tenuti.isNotEmpty())
        val superstitiPassati = tenuti.filter {
            it.state != TrainState.NOT_DEPARTED && it.minutesFrom(ora) < 0
        }
        assertTrue(
            "sono rimaste ${superstitiPassati.size} corse gia' andate: " +
                superstitiPassati.joinToString { it.scheduledTime.orEmpty() },
            superstitiPassati.isEmpty(),
        )
    }

    /**
     * Il percorso deve leggersi come un viaggio: tutto quello che sta prima di
     * dove si trova il treno e' passato, e la posizione e' una sola. I dati
     * grezzi non lo garantiscono.
     */
    /**
     * Il campo della ricerca treno tiene solo cifre, ma ci si incolla dentro
     * l'etichetta letta altrove. Filtrare e basta sarebbe la trappola: da
     * "RE_8 2828" le sole cifre danno "82828".
     */
    /**
     * La sigla scritta a mano serve a scegliere fra due treni con lo stesso
     * numero, non a nasconderne: il confronto e' largo di proposito.
     */
    @Test
    fun `la sigla scritta distingue due treni omonimi`() {
        assertTrue("REG20 -> " + trainCategoryOf("REG20"), trainCategoryOf("REG20") == "REG")
        assertTrue("EC 20 -> " + trainCategoryOf("EC 20"), trainCategoryOf("EC 20") == "EC")
        assertTrue("re8 2828 -> " + trainCategoryOf("re8 2828"), trainCategoryOf("re8 2828") == "RE")
        assertTrue("senza sigla -> " + trainCategoryOf("2828"), trainCategoryOf("2828") == null)

        assertTrue("REG 20 con REG", matchesCategory("REG 20", "REG"))
        assertTrue("EC 20 con EC", matchesCategory("EC 20", "EC"))
        assertTrue("una sigla abbreviata deve bastare", matchesCategory("REG 20", "RE"))
        assertTrue(
            "una sigla di linea piu' lunga non deve escludere la corsa",
            matchesCategory("RE 2828", "RE8"),
        )
        assertTrue("REG non e' EC", !matchesCategory("EC 20", "REG"))
    }

    @Test
    fun `dall'etichetta incollata si ricava il numero, non le cifre della sigla`() {
        fun campo(incollato: String) = trainNumberOf(incollato)?.filter { it.isDigit() }.orEmpty()

        assertTrue("RE_8 2828 -> " + campo("RE_8 2828"), campo("RE_8 2828") == "2828")
        assertTrue("RE8 2828 -> " + campo("RE8 2828"), campo("RE8 2828") == "2828")
        assertTrue("RE 2874 -> " + campo("RE 2874"), campo("RE 2874") == "2874")
        assertTrue("S8 24852 -> " + campo("S8 24852"), campo("S8 24852") == "24852")
        assertTrue("REG2618 -> " + campo("REG2618"), campo("REG2618") == "2618")
        assertTrue("2828 -> " + campo("2828"), campo("2828") == "2828")
        assertTrue("digitazione a meta' -> " + campo("2"), campo("2") == "2")
        assertTrue("campo svuotato -> " + campo(""), campo("") == "")
        assertTrue("solo sigla -> " + campo("REG"), campo("REG") == "")
    }

    @Test
    fun `il percorso resta coerente anche coi buchi in mezzo`() {
        fun fermata(
            i: Int,
            nome: String,
            stato: StopStatus,
            arrivoReale: LocalDateTime? = null,
            partenzaReale: LocalDateTime? = null,
            ritardo: Int = 0,
        ) = Stop(
            index = i,
            stationName = nome,
            stationCode = null,
            scheduledArrival = LocalDateTime.of(2026, 8, 26, 20, i),
            actualArrival = arrivoReale,
            arrivalDelayMinutes = ritardo,
            scheduledDeparture = LocalDateTime.of(2026, 8, 26, 20, i + 1),
            actualDeparture = partenzaReale,
            departureDelayMinutes = ritardo,
            scheduledPlatform = null,
            actualPlatform = null,
            status = stato,
            projectedArrival = LocalDateTime.of(2026, 8, 26, 20, i).plusMinutes(ritardo.toLong()),
        )

        val quando = LocalDateTime.of(2026, 8, 26, 21, 0)
        val origine = fermata(1, "Origine", StopStatus.DONE, partenzaReale = quando)
        val senzaOrari = fermata(2, "Piacenza", StopStatus.DONE)
        val dataFutura = fermata(3, "Buco", StopStatus.FUTURE, ritardo = 9)
        val soppressa = fermata(4, "Soppressa", StopStatus.CANCELLED)
        val ultimaFatta = fermata(5, "Acireale", StopStatus.DONE, arrivoReale = quando)
        val avvenire = fermata(6, "Catania", StopStatus.FUTURE, ritardo = 9)

        val out = listOf(origine, senzaOrari, dataFutura, soppressa, ultimaFatta, avvenire)
            .consolidate()
            .associateBy { it.stationName }

        assertTrue(
            "una fermata gia' superata resta disegnata come da fare",
            out.getValue("Buco").status == StopStatus.DONE,
        )
        assertTrue(
            "sul passato non si puo' proiettare un ritardo: e' inventato",
            out.getValue("Buco").arrivalDelayMinutes == 0 &&
                out.getValue("Buco").projectedArrival == null,
        )
        assertTrue(
            "senza un solo orario reale il passaggio non e' stato rilevato",
            !out.getValue("Piacenza").detected,
        )
        assertTrue(
            "una fermata con orari reali resta un dato misurato",
            out.getValue("Acireale").detected && out.getValue("Origine").detected,
        )
        assertTrue(
            "una soppressa non e' un buco da colmare",
            out.getValue("Soppressa").status == StopStatus.CANCELLED,
        )
        assertTrue(
            "la posizione deve essere l'ultima fermata effettuata",
            out.getValue("Acireale").status == StopStatus.CURRENT,
        )
        assertTrue(
            "il treno non e' ancora a Catania",
            out.getValue("Catania").status == StopStatus.FUTURE,
        )
        assertTrue(
            "una posizione sola, non una per ogni buco",
            out.values.count { it.status == StopStatus.CURRENT } == 1,
        )
    }

    @Test
    fun `sui treni in corsa il percorso non torna indietro`() = runBlocking {
        // Si guardano gli arrivi: un treno in arrivo e' per definizione gia' in
        // viaggio, mentre le prime partenze di solito non sono ancora partite e
        // non avrebbero una sola fermata effettuata da controllare.
        val inCorsa = trains.arrivals("S01700")
            .take(10)
            .mapNotNull { runCatching { trains.status(it.trainRef) }.getOrNull() }
            .filter { s -> s.stops.any { it.status == StopStatus.DONE } }

        println("=== COERENZA PERCORSI ===")
        if (inCorsa.isEmpty()) {
            println("  nessun treno in viaggio in questo momento: niente da controllare")
            return@runBlocking
        }

        for (s in inCorsa) {
            val utili = s.stops.filter { it.status != StopStatus.CANCELLED }
            val ultimaFatta = utili.indexOfLast {
                it.status == StopStatus.DONE || it.status == StopStatus.CURRENT
            }
            val primaDaFare = utili.indexOfFirst { it.status == StopStatus.FUTURE }
            val correnti = utili.count { it.status == StopStatus.CURRENT }
            println("  " + s.label + ": " + utili.size + " fermate, ultima fatta " +
                ultimaFatta + ", prima da fare " + primaDaFare + ", correnti " + correnti)

            assertTrue(
                s.label + ": una fermata da fare (" + primaDaFare + ") prima di una fatta (" +
                    ultimaFatta + "): il percorso torna indietro",
                primaDaFare < 0 || primaDaFare > ultimaFatta,
            )
            assertTrue(
                s.label + ": " + correnti + " fermate segnate come posizione corrente",
                correnti <= 1,
            )
        }
    }

    @Test
    fun `i servizi sostitutivi sono riconosciuti come tali`() = runBlocking {
        val dateo = Station("S01650", 830001665, "Milano Dateo")
        val busto = Station("S01031", 830001031, "Busto Arsizio")
        val res = journeys.searchAll(dateo, busto, LocalDateTime.now(), limit = 8)

        val tratte = res.journeys.flatMap { it.legs }
        val bus = tratte.filter { it.kind == TransportKind.BUS }

        println("\n=== SERVIZI SOSTITUTIVI ===")
        println("  soluzioni: ${res.journeys.size}, tratte: ${tratte.size}, di cui bus: ${bus.size}")
        bus.take(4).forEach { println("     ${it.label}  ${it.from.name} -> ${it.to.name}") }
        res.alerts.take(1).forEach { println("     avviso: ${it.message.take(140)}") }

        assertTrue(
            "una tratta bus non deve essere apribile come treno: non esiste un " +
                "dettaglio corsa per i sostitutivi",
            bus.none { it.isTrain },
        )
        assertTrue(
            "i bus devono essere etichettati come tali",
            bus.all { it.label.contains("Bus", ignoreCase = true) },
        )
    }
}
