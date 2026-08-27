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
