package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.StopStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi)
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
