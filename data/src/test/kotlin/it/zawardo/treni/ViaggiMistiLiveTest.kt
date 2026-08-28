package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.ViaggiMistiRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * I viaggi misti contro le API reali.
 *
 * Dipende da cosa Italo sta seguendo in questo momento: se non trova la gamba
 * Italo non e' un fallimento del codice, ma va visto. Stampa quel che compone.
 */
class ViaggiMistiLiveTest {

    private val eav = EavRepository(NetworkModule.eavApi)
    private val arst = ArstRepository()
    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi)
    private val misti = ViaggiMistiRepository(eav, arst, italo, stations, journeys)

    // Sorrento (EAV) con le sue coordinate; Roma Termini (Italo)
    private val sorrento = Station("EAV62", 9_000_000_062, "Sorrento", 40.6258, 14.3797)
    private val roma = Station("S08409", 830_008_409, "Roma Termini", 41.9010, 12.5015)

    @Test
    fun `Sorrento-Roma prova a comporre EAV piu Italo`() = runBlocking {
        val quando = LocalDate.now().plusDays(1).atTime(8, 0)
        val cronoInizio = System.currentTimeMillis()
        val out = misti.cerca(sorrento, roma, quando)
        val ms = System.currentTimeMillis() - cronoInizio

        println("\n=== SORRENTO -> ROMA (misti) in ${ms} ms: ${out.size} soluzioni ===")
        out.forEach { j ->
            println("  ${j.departure.toLocalTime()} -> ${j.arrival.toLocalTime()}  (${j.duration.toMinutes()} min)")
            j.legs.forEach { l ->
                println("      ${l.from.name} ${l.departure.toLocalTime()} -> ${l.to.name} ${l.arrival.toLocalTime()}  ${l.label} ${l.source ?: ""}")
            }
        }
        if (out.isEmpty()) {
            println("  (nessun misto: probabilmente Italo non sta seguendo corse Napoli->Roma ora)")
        }
        // Non asserisce sulla presenza: dipende da Italo. Verifica solo che, se
        // c'e', sia ben formato.
        out.forEach { j ->
            assert(j.assembled) { "un misto deve risultare assemblato" }
            assert(j.legs.size >= 2) { "un misto ha almeno due gambe" }
            assert(j.multiOperator) { "un misto attraversa piu' operatori" }
        }
    }

    @Test
    fun `misurazione grezza del solo feeder EAV`() = runBlocking {
        val quando = LocalDate.now().plusDays(1).atTime(8, 0)
        val t0 = System.currentTimeMillis()
        val feeder = eav.itinerario("EAV62", "EAV3", quando.toLocalDate())
        val t1 = System.currentTimeMillis()
        println("\n=== feeder EAV Sorrento->Garibaldi: ${feeder.size} corse in ${t1 - t0} ms ===")
        feeder.take(4).forEach { println("  ${it.departure.toLocalTime()} -> ${it.arrival.toLocalTime()}  ${it.legs.first().label}") }
    }
}
