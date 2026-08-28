package it.zawardo.treni

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.remote.lefrecce.ClassificationDto
import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.lefrecce.TransportMeanDto
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.italo.ItaloBoardTrainDto
import it.zawardo.treni.data.remote.italo.ItaloDisruptionDto
import it.zawardo.treni.data.remote.italo.ItaloScheduleDto
import it.zawardo.treni.data.remote.italo.ItaloStopDto
import it.zawardo.treni.data.remote.italo.ItaloTrainDto
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.remote.trenord.TrenordJourneyDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.trenord.TrenordStationDto
import it.zawardo.treni.data.remote.trenord.TrenordStopDto
import it.zawardo.treni.data.remote.trenord.TrenordTrainDto
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.minutesFrom
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.consolidate
import it.zawardo.treni.domain.model.declaredState
import it.zawardo.treni.domain.model.matchesCategory
import it.zawardo.treni.domain.model.trainCategoryOf
import it.zawardo.treni.domain.model.trainNumberOf
import it.zawardo.treni.domain.model.stillCatchable
import it.zawardo.treni.domain.model.terminus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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
        // Un orario di punta del giorno dopo, non "adesso": a tarda sera il
        // tabellone e' quasi vuoto e la seconda finestra non porta treni nuovi,
        // facendo fallire il test per l'ora e non per un difetto. Alle 8 del
        // mattino Milano Centrale ne ha per due finestre, sempre.
        val ora = LocalDate.now(ROME).plusDays(1).atTime(8, 0).atZone(ROME)
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
        /*
         * Le due reti si chiedono per nome invece di affidarsi al default.
         *
         * Questo test verifica la fusione, non le impostazioni: da quando le
         * reti nascono quasi tutte spente, appoggiarsi al default significava
         * interrogarne una sola e poi stupirsi che la fusione non fondesse
         * niente.
         */
        val fuse = journeys.searchAll(
            from, to, quando, limit = 10,
            sources = setOf(DataSource.TRENITALIA, DataSource.TRENORD),
        )

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
    /**
     * Cercando un numero l'elenco deve bastare a scegliere.
     *
     * ViaggiaTreno di suo da' solo origine e data, quindi due treni diversi con
     * lo stesso numero uscivano identici - "Treno 20" e "Treno 20" - e per
     * sapere quale fosse quale bisognava aprirli a uno a uno.
     */
    /**
     * Fra tabellone e corsa, sulla destinazione vince la corsa.
     *
     * Il tabellone di ViaggiaTreno a volte nomina una stazione che il treno non
     * serve - il REG 12977 da Acireale risulta diretto a Bicocca mentre finisce
     * a Catania Aeroporto Fontanarossa - quindi l'app la chiede alla corsa. Qui
     * si verifica che quella fonte sia coerente con se stessa, altrimenti non
     * varrebbe piu' dell'altra.
     */
    @Test
    fun `la destinazione vera viene dalla corsa, non dal tabellone`() = runBlocking {
        val tabellone = trains.departures("S12328")
        if (tabellone.isEmpty()) {
            println("=== DESTINAZIONI: Acireale non ha partenze in questa fascia ===")
            return@runBlocking
        }

        println("=== DESTINAZIONI (Acireale) ===")
        var discordanti = 0
        var controllate = 0
        for (voce in tabellone.take(10)) {
            val stato = runCatching { trains.status(voce.trainRef) }.getOrNull() ?: continue
            val vera = stato.terminus() ?: continue
            controllate++

            // La corsa deve concordare con se stessa: il capolinea dichiarato e
            // l'ultima fermata sono lo stesso posto, altrimenti non si sa a chi
            // credere.
            assertTrue(
                voce.label + ": la corsa dichiara '" + stato.destination +
                    "' ma finisce a '" + vera + "'",
                stato.destination.orEmpty().equals(vera, ignoreCase = true),
            )
            if (!vera.equals(voce.direction, ignoreCase = true)) {
                discordanti++
                println("  " + voce.label + ": tabellone '" + voce.direction + "' -> corsa '" + vera + "'")
            }
        }
        println("  controllate " + controllate + ", tabellone in disaccordo su " + discordanti)
        assertTrue("nessuna corsa controllabile", controllate > 0)
    }

    @Test
    fun `due corse con lo stesso numero non si assomigliano`() = runBlocking {
        val numeri = trains.departures("S01700").map { it.trainRef.number }.distinct().take(12)
        assertTrue("tabellone vuoto: non si puo' concludere nulla", numeri.isNotEmpty())

        println("=== CORSE PER NUMERO ===")
        var conDoppioni = 0
        for (numero in numeri) {
            val corse = runCatching { trains.findRuns(numero) }.getOrDefault(emptyList())
            if (corse.isEmpty()) continue

            for (c in corse) {
                assertTrue(
                    "la corsa $numero esce senza sigla: " + c.label,
                    c.label != "Treno " + numero,
                )
            }
            if (corse.size == 1) continue

            conDoppioni++
            corse.forEach {
                println("  " + numero + ": " + it.label + "  " + it.origin + " -> " +
                    it.destination + "  " + it.ref.departureDateMillis)
            }
            val impronte = corse.map {
                listOf(it.label, it.origin, it.destination, it.ref.departureDateMillis).toString()
            }
            assertTrue(
                "il numero $numero ha ${corse.size} corse indistinguibili nell'elenco",
                impronte.size == impronte.toSet().size,
            )
        }
        println("  numeri con piu' di una corsa: " + conDoppioni + " su " + numeri.size)
    }

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

    @Test
    fun `una soluzione soppressa lo dichiara da sola`() {
        fun soluzione(soppressa: Boolean, ritardo: Int?) = Journey(
            departure = LocalDateTime.now(),
            arrival = LocalDateTime.now().plusMinutes(20),
            duration = Duration.ofMinutes(20),
            legs = emptyList(),
            source = JourneySource.TRENORD,
            cancelled = soppressa,
            delayMinutes = ritardo,
        )

        assertTrue(
            "una corsa soppressa deve dirlo da se': sulle linee S ViaggiaTreno non " +
                "risponde, e quel dato non arriverebbe da nessun'altra parte",
            soluzione(soppressa = true, ritardo = null).declaredState == TrainState.CANCELLED,
        )
        assertTrue(
            "il ritardo dichiarato dalla sorgente vale come stato",
            soluzione(soppressa = false, ritardo = 7).declaredState == TrainState.DELAYED,
        )
        assertTrue(
            "zero minuti dichiarati sono un'informazione: la corsa e' in orario",
            soluzione(soppressa = false, ritardo = 0).declaredState == TrainState.REGULAR,
        )
        assertTrue(
            "senza ritardo dichiarato non si inventa uno stato",
            soluzione(soppressa = false, ritardo = null).declaredState == null,
        )
    }

    @Test
    fun `la corsa di domani non eredita quella di oggi`() = runBlocking {
        val domani = LocalDate.now().plusDays(1)
        val numero = trains.departures("S01700", ZonedDateTime.now())
            .firstOrNull()?.trainRef?.number

        if (numero == null) {
            println("\n=== DATA FUTURA: Milano Centrale non ha partenze, niente da verificare ===")
            return@runBlocking
        }

        val oggi = trains.resolveFor(numero, LocalDate.now())
        val futura = trains.resolveFor(numero, domani)

        println("\n=== DATA FUTURA (treno $numero) ===")
        println("  oggi:   " + (oggi?.let { giornoDi(it) }?.toString() ?: "nessuna corsa"))
        println("  domani: " + (futura?.let { giornoDi(it) }?.toString() ?: "nessuna corsa"))

        assertTrue(
            "per una data futura ViaggiaTreno non ha nulla da dire: restituire la " +
                "corsa di oggi la dava per arrivata mentre quella di domani deve " +
                "ancora partire",
            futura == null || giornoDi(futura) == domani,
        )
    }

    /** Il giorno in cui una corsa parte, letto nel fuso in cui circola. */
    private fun giornoDi(ref: TrainRef): LocalDate =
        Instant.ofEpochMilli(ref.departureDateMillis).atZone(ROME).toLocalDate()

    @Test
    fun `la soppressione dichiarata sulle fermate non si perde`() {
        fun fermata(nome: String, soppressa: Boolean) = TrenordStopDto(
            station = TrenordStationDto(stationId = "S0170$nome", name = "Stazione $nome"),
            scheduledArrival = "08:00:00",
            scheduledDeparture = "08:01:00",
            cancelled = soppressa,
        )

        /*
         * Il flag di primo livello resta FALSO: e' il caso reale dell'S5 11862
         * del 27 agosto 2026, soppresso da Pioltello a Varese e restituito da
         * HAFAS con `cancelled = false` e tutte e diciannove le fermate
         * cancellate.
         */
        fun soluzione(fermate: List<TrenordStopDto>) = TrenordSolutionDto(
            date = "20260827",
            departureTime = "08:00:00",
            arrivalTime = "09:00:00",
            cancelled = false,
            journeys = listOf(
                TrenordJourneyDto(
                    train = TrenordTrainDto(id = "11862", category = "S5", line = "S5"),
                    stops = fermate,
                ),
            ),
        )

        val tutte = soluzione(listOf(fermata("1", true), fermata("2", true), fermata("3", true)))
        val salita = soluzione(listOf(fermata("1", true), fermata("2", false), fermata("3", false)))
        val mezzo = soluzione(listOf(fermata("1", false), fermata("2", true), fermata("3", false)))
        val nessuna = soluzione(listOf(fermata("1", false), fermata("2", false), fermata("3", false)))

        assertTrue(
            "con tutte le fermate soppresse la corsa e' soppressa, per quanto il " +
                "flag della soluzione dica di no",
            tutte.toJourney()?.declaredState == TrainState.CANCELLED,
        )
        assertTrue(
            "se salta la fermata da cui sali, quella soluzione non ti porta",
            salita.toJourney()?.declaredState == TrainState.CANCELLED,
        )
        assertTrue(
            "una fermata intermedia soppressa e' una soppressione parziale",
            mezzo.toJourney()?.declaredState == TrainState.PARTIALLY_CANCELLED,
        )
        assertTrue(
            "senza fermate soppresse non si dichiara niente",
            nessuna.toJourney()?.declaredState == null,
        )
    }

    /**
     * Regressione: la tratta che percorri non e' la corsa intera.
     *
     * L'S5 per Varese parte da Pioltello alle 19:40 anche se sali a Porta
     * Garibaldi alle 20:02. Leggendo la prima e l'ultima fermata dell'elenco la
     * soluzione diceva di partire da Pioltello, e un treno limitato che oggi
     * nasce dopo la sua origine risultava soppresso anche per chi sale piu'
     * avanti e lo prende senza accorgersi di niente.
     */
    @Test
    fun `di una corsa conta solo il pezzo che percorri`() {
        fun fermata(nome: String, tipo: String, ora: String, soppressa: Boolean = false) =
            TrenordStopDto(
                station = TrenordStationDto(stationId = "S0$nome", name = nome),
                scheduledArrival = ora,
                scheduledDeparture = ora,
                type = tipo,
                cancelled = soppressa,
            )

        fun corsa(fermate: List<TrenordStopDto>) = TrenordSolutionDto(
            date = "20260827",
            departureTime = "20:02:00",
            arrivalTime = "21:18:00",
            journeys = listOf(
                TrenordJourneyDto(
                    train = TrenordTrainDto(id = "11868", category = "S5", line = "S5"),
                    stops = fermate,
                ),
            ),
        )

        // Il treno oggi nasce dopo la sua origine: le fermate soppresse cadono
        // prima di dove sali, e a te non cambiano niente.
        val limitatoPrima = corsa(
            listOf(
                fermata("1703", "O", "19:40:00", soppressa = true),
                fermata("1701", "F", "19:50:00", soppressa = true),
                fermata("1645", "start", "20:02:00"),
                fermata("1039", "pass", "20:19:00"),
                fermata("1205", "end", "21:18:00"),
            ),
        )
        // Stesse soppressioni, ma stavolta comprendono la stazione da cui sali.
        val limitatoOltre = corsa(
            listOf(
                fermata("1703", "O", "19:40:00"),
                fermata("1701", "F", "19:50:00"),
                fermata("1645", "start", "20:02:00", soppressa = true),
                fermata("1039", "pass", "20:19:00", soppressa = true),
                fermata("1205", "end", "21:18:00", soppressa = true),
            ),
        )
        // Salta una fermata in mezzo al tuo pezzo di viaggio.
        val saltaInMezzo = corsa(
            listOf(
                fermata("1703", "O", "19:40:00"),
                fermata("1645", "start", "20:02:00"),
                fermata("1039", "pass", "20:19:00", soppressa = true),
                fermata("1205", "end", "21:18:00"),
            ),
        )

        val tratta = limitatoPrima.toJourney()?.legs?.firstOrNull()
        assertTrue(
            "la tratta deve cominciare dove sali, non dove nasce la corsa",
            tratta?.from?.rfiCode == "S01645" && tratta.departure.toLocalTime() == LocalTime.of(20, 2),
        )
        assertTrue(
            "le fermate soppresse prima della salita non riguardano questo viaggio",
            limitatoPrima.toJourney()?.declaredState == null,
        )
        assertTrue(
            "se la soppressione arriva fino alla tua fermata, quella corsa non ti porta",
            limitatoOltre.toJourney()?.declaredState == TrainState.CANCELLED,
        )
        assertTrue(
            "una fermata saltata dentro il tuo percorso e' una soppressione parziale",
            saltaInMezzo.toJourney()?.declaredState == TrainState.PARTIALLY_CANCELLED,
        )
    }

    /**
     * Regressione, senza dipendere dal servizio del giorno: il BFF Le Frecce
     * avvolge i viaggi con cambio dentro un `ROUTE_SEGMENT` e mette le tratte
     * vere in `subSegments`. Leggendo solo i `SOLUTION_SEGMENT` di primo livello
     * quelle soluzioni uscivano **senza tratte**, e venivano scartate: la lista
     * mostrava le sole Frecce, e su una tratta servita da soli regionali poteva
     * restare vuota.
     */
    @Test
    fun `le soluzioni con cambio non si perdono`() {
        fun luogo(nome: String, code: String) = LocationDto(locationId = 1, name = nome, bdoCode = code)
        fun tratta(numero: String, da: String, a: String, dalle: String, alle: String) = SolutionNodeDto(
            type = "SOLUTION_SEGMENT",
            departureTime = dalle,
            arrivalTime = alle,
            startLocation = luogo(da, "S01700"),
            endLocation = luogo(a, "S01701"),
            offeredTransportMeanDeparture = TransportMeanDto(
                name = numero,
                classification = ClassificationDto(acronym = "REG", type = "TRAIN"),
            ),
        )

        val conCambio = SolutionDto(
            departureTime = "2026-08-27T08:00:00.000+02:00",
            arrivalTime = "2026-08-27T10:00:00.000+02:00",
            solutionNodes = listOf(
                SolutionNodeDto(
                    type = "ROUTE_SEGMENT",
                    subSegments = listOf(
                        tratta("2001", "Bologna", "Prato", "2026-08-27T08:00:00.000+02:00", "2026-08-27T09:00:00.000+02:00"),
                        tratta("2002", "Prato", "Firenze", "2026-08-27T09:20:00.000+02:00", "2026-08-27T10:00:00.000+02:00"),
                    ),
                ),
            ),
        )

        val viaggio = conCambio.toJourney()
        assertTrue("la soluzione con cambio non deve sparire", viaggio != null)
        assertTrue(
            "le due tratte stanno nei subSegments: senza, la soluzione esce vuota e viene scartata",
            viaggio?.legs?.size == 2,
        )
        assertTrue("due tratte sono un cambio", viaggio?.changes == 1)
    }

    @Test
    fun `una riga del tabellone Italo diventa una corsa del nostro`() {
        val riga = ItaloBoardTrainDto(
            direction = "NAPOLI CENTRALE",
            number = "9951",
            delay = 5,
            scheduledTime = "21:01",
            actualTime = "21:06",
            platform = "4",
        )
        val voce = riga.toBoardEntry(LocalDate.of(2026, 8, 27))

        assertTrue("la riga deve diventare una voce di tabellone", voce != null)
        assertTrue("l'etichetta e' quella con cui la gente li chiama", voce?.label == "Italo 9951")
        assertTrue("il ritardo passa cosi' com'e'", voce?.delayMinutes == 5)
        assertTrue("cinque minuti di ritardo sono un ritardo", voce?.state == TrainState.DELAYED)
        assertTrue("il binario che pubblicano e' quello vero, non quello di tabella",
            voce?.actualPlatform == "4" && voce.scheduledPlatform == null)
        assertTrue("senza orario la riga non serve a niente",
            riga.copy(scheduledTime = null).toBoardEntry() == null)
    }

    /**
     * Italo manda solo `HH:mm`: la data la mettiamo noi, e su una corsa che
     * passa la mezzanotte il solo orario tornerebbe indietro nel tempo.
     */
    @Test
    fun `la corsa Italo che passa la mezzanotte non torna indietro`() {
        fun fermata(codice: String, nome: String, n: Int, arr: String?, part: String?) =
            ItaloStopDto(
                code = codice, name = nome, index = n,
                scheduledArrival = arr, actualArrival = arr,
                scheduledDeparture = part, actualDeparture = part,
            )

        val corsa = ItaloTrainDto(
            empty = false,
            lastUpdate = "23:30",
            schedule = ItaloScheduleDto(
                number = "9999",
                origin = "Milano Centrale",
                destination = "Roma Termini",
                disruption = ItaloDisruptionDto(delayMinutes = 3),
                originStop = fermata("MC_", "Milano Centrale", 0, "01:00", "23:10"),
                doneStops = listOf(fermata("BO2", "Bologna centrale", 1, "00:15", "00:17")),
                futureStops = listOf(fermata("RMT", "Roma Termini", 2, "02:40", "01:00")),
            ),
        )

        val stato = corsa.toTrainStatus(LocalDate.of(2026, 8, 27))
        assertTrue("la corsa deve esserci", stato != null)
        val fermate = stato!!.stops

        assertTrue("tre fermate, in ordine di progressivo", fermate.size == 3)
        assertTrue(
            "Bologna passa dopo mezzanotte: e' il giorno dopo, non dodici ore prima",
            fermate[1].scheduledArrival == LocalDateTime.of(2026, 8, 28, 0, 15),
        )
        assertTrue(
            "al capolinea di partenza non esiste un arrivo: il loro 01:00 e' un segnaposto",
            fermate[0].scheduledArrival == null,
        )
        assertTrue(
            "all'ultima fermata non esiste una partenza",
            fermate.last().scheduledDeparture == null,
        )
        assertTrue(
            "le fermate gia' fatte portano orari misurati",
            fermate[1].status == StopStatus.DONE && fermate[1].actualArrival != null,
        )
        assertTrue(
            "quelle da fare portano stime, non misure",
            fermate[2].status == StopStatus.FUTURE &&
                fermate[2].actualArrival == null && fermate[2].projectedArrival != null,
        )
        assertTrue(
            "le sigle Italo diventano codici RFI, o la fermata non si puo' aprire",
            fermate[2].stationCode == "S08409" && fermate[1].stationCode == "S05043",
        )
        assertTrue(
            "l'ora della fotografia va dichiarata: quei dati possono avere ore",
            stato.notice?.contains("23:30") == true,
        )
    }
}
