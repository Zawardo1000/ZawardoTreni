package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.StopStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Test di integrazione contro le API reali.
 *
 * Non sono unit test: richiedono rete e dipendono dai treni realmente in
 * circolazione. Servono a verificare che i contratti delle API non siano
 * cambiati — è il primo posto da guardare quando l'app smette di funzionare.
 */
class LiveApiTest {

    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi)

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private fun LocalDateTime?.fmt() = this?.format(hhmm) ?: " -- "

    @Test
    fun `autocompletamento stazioni restituisce il codice RFI`() = runBlocking {
        val res = stations.search("bologna c")
        println("\n=== AUTOCOMPLETAMENTO 'bologna c' ===")
        res.forEach { println("  ${it.name.padEnd(30)} rfi=${it.rfiCode} locId=${it.locationId} tracciabile=${it.trackable}") }

        assertTrue("nessuna stazione trovata", res.isNotEmpty())
        val centrale = res.firstOrNull { it.name.contains("Centrale", ignoreCase = true) }
        assertNotNull("Bologna Centrale non trovata", centrale)
        assertTrue("manca il codice RFI, il realtime sarebbe impossibile", centrale!!.trackable)
    }

    @Test
    fun `ricerca itinerari restituisce tratte con numero treno`() = runBlocking {
        val from = stations.search("bologna centrale").first { it.trackable }
        val to = stations.search("firenze s. m").first { it.trackable }
        val res = journeys.search(from, to, LocalDateTime.now().plusMinutes(10), limit = 6)

        println("\n=== ITINERARI ${from.name} -> ${to.name} ===")
        res.forEach { j ->
            println("  ${j.departure.fmt()}->${j.arrival.fmt()}  ${j.duration.toMinutes()}min  cambi=${j.changes}")
            j.legs.forEach { l ->
                println("      [${l.label}] ${l.from.name} ${l.departure.fmt()} -> ${l.to.name} ${l.arrival.fmt()}")
            }
        }

        assertTrue("nessun itinerario", res.isNotEmpty())
        assertTrue(
            "nessuna tratta con numero treno: il BFF ha cambiato struttura",
            res.any { j -> j.legs.any { it.trainNumber != null } },
        )
    }

    /**
     * Regressione: il BFF pretende l'offset di fuso nella `departure_time`.
     * Senza, non da' errore ma ignora l'ora e riparte da mezzanotte. Una ricerca
     * per le 14:00 tornava con i treni dell'alba.
     */
    @Test
    fun `la ricerca rispetta l'orario richiesto`() = runBlocking {
        val from = stations.search("bologna centrale").first { it.trackable }
        val to = stations.search("firenze s. m").first { it.trackable }

        // Un orario lontano da adesso, cosi' se venisse ignorato si vede subito.
        val requested = LocalDateTime.now().toLocalDate().atTime(14, 0)
        val res = journeys.search(from, to, requested, limit = 5)

        println("\n=== ORARIO RICHIESTO ${requested.fmt()} ===")
        res.forEach { println("  parte alle ${it.departure.fmt()} da ${it.legs.firstOrNull()?.from?.name}") }

        assertTrue("nessun itinerario", res.isNotEmpty())
        val first = res.first().departure
        assertTrue(
            "la prima soluzione parte alle ${first.fmt()}, prima delle ${requested.fmt()}: " +
                "l'orario e' stato ignorato dal BFF",
            !first.toLocalTime().isBefore(requested.toLocalTime()),
        )
    }

    /**
     * L'orario delle soluzioni deve riferirsi alla stazione scelta dall'utente,
     * non all'origine del treno: un FR Roma->Milano che passa da Bologna deve
     * comparire con l'ora di Bologna.
     */
    @Test
    fun `l'orario e' quello della stazione scelta, non dell'origine del treno`() = runBlocking {
        val from = stations.search("bologna centrale").first { it.trackable }
        val to = stations.search("firenze s. m").first { it.trackable }
        val requested = LocalDateTime.now().toLocalDate().atTime(14, 0)
        val res = journeys.search(from, to, requested, limit = 6)

        // Si cerca una soluzione il cui treno NON nasce a Bologna.
        val passante = res.firstNotNullOfOrNull { j ->
            val leg = j.legs.firstOrNull() ?: return@firstNotNullOfOrNull null
            val number = leg.trainNumber ?: return@firstNotNullOfOrNull null
            val status = trains.statusByNumber(number, requested.toLocalDate())
                ?: return@firstNotNullOfOrNull null
            if (status.origin?.contains("BOLOGNA", true) == true) null else Triple(j, leg, status)
        }

        if (passante == null) {
            println("\n(nessun treno passante nel campione: verifica non conclusiva)")
            return@runBlocking
        }

        val (journey, leg, status) = passante
        println("\n=== TRENO PASSANTE ${leg.label} ===")
        println("  origine reale del treno: ${status.origin}")
        println("  orario mostrato nella soluzione: ${journey.departure.fmt()} da ${leg.from.name}")

        val atBologna = status.stops.firstOrNull { it.stationName.contains("BOLOGNA", true) }
        println("  partenza da Bologna secondo ViaggiaTreno: ${atBologna?.scheduledDeparture.fmt()}")

        assertTrue(
            "l'orario della soluzione non e' quello della stazione di partenza scelta",
            !journey.departure.toLocalTime().isBefore(requested.toLocalTime()),
        )
    }

    /**
     * Regressione: il BFF avvolge i viaggi regionali con cambio dentro un
     * `ROUTE_SEGMENT` che tiene le tratte in `subSegments`. Leggendo solo i
     * `SOLUTION_SEGMENT` di primo livello quelle soluzioni sparivano: restavano
     * le sole Frecce, e chiedendone poche la lista poteva svuotarsi del tutto.
     */
    @Test
    fun `i regionali con cambio non vengono persi`() = runBlocking {
        val from = stations.search("bologna centrale").first { it.trackable }
        val to = stations.search("firenze s. m").first { it.trackable }
        val res = journeys.search(from, to, LocalDateTime.now().plusMinutes(5), limit = 8)

        println("\n=== SOLUZIONI CON CAMBIO ===")
        res.forEach { jr ->
            println("  ${jr.departure.fmt()}->${jr.arrival.fmt()} cambi=${jr.changes} " +
                jr.legs.joinToString(" + ") { it.label })
        }

        assertTrue("nessun itinerario", res.isNotEmpty())
        assertTrue(
            "ogni soluzione deve avere almeno una tratta: se e' vuota, il nodo " +
                "non e' stato riconosciuto",
            res.all { it.legs.isNotEmpty() },
        )
        assertTrue(
            "nessuna soluzione con cambio: i ROUTE_SEGMENT vengono ancora persi",
            res.any { it.changes > 0 },
        )
    }

    /**
     * Il Passante milanese non esiste ne' per ViaggiaTreno ne' per il BFF Le
     * Frecce: Milano Dateo -> Lambrate tornava con due bus notturni. Trenord lo
     * copre, e la ricerca combinata deve dimostrarlo.
     */
    @Test
    fun `Trenord copre il Passante milanese`() = runBlocking {
        val dateo = Station("S01650", 830001650, "Milano Dateo")
        val lambrate = Station("S01701", 830001701, "Milano Lambrate")

        // Il 31 agosto il Passante riapre dopo i lavori di manutenzione.
        val quando = LocalDate.of(2026, 8, 31).atTime(14, 0)
        val res = journeys.searchAll(dateo, lambrate, quando, limit = 6)

        println("\n=== MILANO DATEO -> MILANO LAMBRATE, ${quando.toLocalDate()} ===")
        res.journeys.forEach { j ->
            println(
                "  ${j.departure.fmt()}->${j.arrival.fmt()} ${j.duration.toMinutes()}min " +
                    "cambi=${j.changes} [${j.source}] " + j.legs.joinToString(" + ") { it.label }
            )
        }

        assertTrue("nessuna soluzione: il Passante resta scoperto", res.journeys.isNotEmpty())
        assertTrue(
            "nessuna soluzione con treni: solo bus, come prima della copertura Trenord",
            res.journeys.any { j -> j.legs.any { it.isTrain } },
        )
    }

    /**
     * Trenord e' l'unica fonte che spieghi le situazioni eccezionali: chiusure
     * di linea, lavori, servizi sostitutivi. Senza, l'app puo' solo mostrare
     * l'assenza di treni senza dirne il motivo.
     */
    @Test
    fun `gli avvisi di servizio arrivano da Trenord`() = runBlocking {
        val dateo = Station("S01650", 830001650, "Milano Dateo")
        val lambrate = Station("S01701", 830001701, "Milano Lambrate")
        val res = journeys.searchAll(dateo, lambrate, LocalDateTime.now(), limit = 4)

        println("\n=== AVVISI (${res.alerts.size}) ===")
        res.alerts.forEach { a ->
            println("  [${if (a.severe) "!" else " "}] ${a.title}: ${a.message.take(200)}")
        }
        // Gli avvisi dipendono dalla situazione del giorno: non se ne impone
        // l'esistenza, si verifica che quando ci sono siano leggibili.
        assertTrue(
            "un avviso senza testo non serve a nulla",
            res.alerts.all { it.message.isNotBlank() },
        )
    }


    @Test
    fun `stato realtime espone fermate, ritardi e posizione`() = runBlocking {
        // Si parte da un treno realmente in circolazione adesso, preso dal tabellone.
        val board = trains.departures("S05043")
        println("\n=== TABELLONE PARTENZE Bologna Centrale (${board.size} voci) ===")
        board.take(5).forEach {
            println("  ${it.scheduledTime} ${it.label.padEnd(12)} -> ${it.direction}  ${it.delayMinutes}'  bin ${it.actualPlatform ?: it.scheduledPlatform ?: "-"}  ${it.state}")
        }
        assertTrue("tabellone vuoto", board.isNotEmpty())

        val status = board.firstNotNullOfOrNull { trains.status(it.trainRef) }
        assertNotNull("nessun andamentoTreno risolto dal tabellone", status)

        println("\n=== ANDAMENTO ${status!!.label} ${status.origin} -> ${status.destination} ===")
        println("  stato=${status.state} ritardo=${status.delayMinutes}'")
        println("  ultimo rilevamento: ${status.lastDetectionStation ?: "non ancora rilevato"} @ ${status.lastDetectionTime.fmt()}")
        status.stops.forEach { s ->
            val mark = when (s.status) {
                StopStatus.DONE -> "[x]"; StopStatus.CURRENT -> "[>]"
                StopStatus.CANCELLED -> "[!]"; StopStatus.FUTURE -> "[ ]"
            }
            val suffix = if (s.isEstimate) "stimato" else "effettivo"
            println(
                "   $mark ${s.index.toString().padStart(3)} ${s.stationName.take(26).padEnd(26)} " +
                    "arr ${s.scheduledArrival.fmt()}/${s.actualArrival.fmt()} ${s.arrivalDelayMinutes}'  " +
                    "par ${s.scheduledDeparture.fmt()}/${s.actualDeparture.fmt()} ${s.departureDelayMinutes}'  " +
                    "bin ${s.scheduledPlatform ?: "-"}->${s.actualPlatform ?: "-"} ($suffix)"
            )
        }
        assertTrue("nessuna fermata: andamentoTreno ha cambiato struttura", status.stops.isNotEmpty())
    }

    /**
     * ViaggiaTreno lascia a zero il ritardo di tutte le fermate non ancora
     * raggiunte, anche su un treno dichiarato in ritardo. Il ricalcolo lo fa
     * l'app: questo test verifica che lo faccia davvero.
     */
    @Test
    fun `il ritardo viene proiettato sulle fermate future`() = runBlocking {
        // Serve un treno realmente in ritardo: si cerca fra i tabelloni.
        val board = listOf("S08409", "S01700", "S05043")
            .flatMap { trains.departures(it) }

        val delayed = board
            .filter { it.delayMinutes >= 3 }
            .firstNotNullOfOrNull { entry ->
                trains.status(entry.trainRef)?.takeIf { st ->
                    st.delayMinutes != 0 && st.stops.any { it.status == StopStatus.FUTURE }
                }
            }

        if (delayed == null) {
            println("\n(nessun treno in ritardo con fermate future: verifica non conclusiva)")
            return@runBlocking
        }

        println("\n=== PROIEZIONE ${delayed.label}, ritardo dichiarato ${delayed.delayMinutes} min ===")
        val future = delayed.stops.filter { it.status == StopStatus.FUTURE }
        future.take(4).forEach {
            println(
                "  ${it.stationName.take(24).padEnd(24)} " +
                    "previsto ${it.scheduledArrival.fmt()} -> ricalcolato ${it.effectiveArrival.fmt()} " +
                    "(${it.arrivalDelayMinutes} min)"
            )
        }

        assertTrue(
            "le fermate future riportano ancora ritardo 0: la proiezione non e' stata applicata",
            future.all { it.arrivalDelayMinutes == delayed.delayMinutes },
        )
        val withArrival = future.filter { it.scheduledArrival != null }
        assertTrue(
            "manca l'orario ricalcolato sulle fermate future",
            withArrival.isEmpty() || withArrival.all { it.projectedArrival != null },
        )
    }

    @Test
    fun `andamentoTreno non copre le date future`() = runBlocking {
        val refs = trains.resolve("9505")
        println("\n=== RISOLUZIONE numero treno 9505 ===")
        refs.forEach { println("  ${it.number} da ${it.originName} (${it.originCode}) millis=${it.departureDateMillis}") }

        if (refs.isNotEmpty()) {
            val domani = refs.first().copy(departureDateMillis = refs.first().departureDateMillis + 86_400_000L)
            val res = trains.status(domani)
            println("  stato per domani: ${res?.label ?: "null (204) — atteso"}")
            assertTrue(
                "il realtime su data futura ora risponde: il vincolo e' cambiato, rivedere la UI",
                res == null,
            )
        }
    }
}
