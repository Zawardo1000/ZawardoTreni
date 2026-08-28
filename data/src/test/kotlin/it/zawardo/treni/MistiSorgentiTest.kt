package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.ViaggiMistiRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Il flag beta abilita i misti, ma **non scavalca le fonti**.
 *
 * Chi spegne una rete non deve vederla ricomparire dentro un viaggio composto.
 * Col doppio schema — feeder + Italo, o feeder + Freccia — spegnere Italo non
 * svuota piu' i misti (resta la Freccia), ma nessuna loro gamba deve usare una
 * rete spenta. Se questo test passa, l'interruttore delle sorgenti vince sul
 * flag.
 */
class MistiSorgentiTest {

    private val eav = EavRepository(NetworkModule.eavApi)
    private val arst = ArstRepository()
    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi)
    private val misti = ViaggiMistiRepository(eav, arst, italo, stations, journeys)

    private val sorrento = Station("EAV62", 9_000_000_062, "Sorrento", 40.6258, 14.3797)
    private val roma = Station("S08409", 830_008_409, "Roma Termini", 41.9010, 12.5015)

    private fun quando() = LocalDate.now().plusDays(1).atTime(8, 0)

    @Test
    fun `con EAV spenta non si compone nessun misto`() = runBlocking {
        // Sorrento e' EAV: senza EAV non c'e' feeder, quindi niente.
        val senzaEav = setOf(DataSource.TRENITALIA, DataSource.ITALO)
        val out = misti.cerca(sorrento, roma, quando(), sources = senzaEav)
        assertTrue("EAV spenta: nessun feeder, nessun misto", out.isEmpty())
    }

    @Test
    fun `con Italo spenta i misti non la usano, resta la Freccia`() = runBlocking {
        // EAV + Trenitalia: lo schema EAV+Freccia puo' comporre, senza toccare Italo.
        val senzaItalo = setOf(DataSource.TRENITALIA, DataSource.EAV)
        val out = misti.cerca(sorrento, roma, quando(), sources = senzaItalo)
        println("\n=== misti senza Italo (EAV+Freccia): ${out.size} ===")
        assertTrue(
            "Italo spenta: nessuna gamba misto la deve usare",
            out.none { DataSource.ITALO in it.sources },
        )
        // Ma lo schema EAV+Freccia deve comporre lo stesso: Sorrento->Napoli in
        // EAV, poi il Frecciarossa. Napoli->Roma ce n'e' sempre, domani mattina.
        assertTrue("EAV+Freccia deve comporre Sorrento->Roma", out.isNotEmpty())
        assertTrue(
            "il misto senza Italo usa la Freccia (Trenitalia)",
            out.all { DataSource.TRENITALIA in it.sources },
        )
    }

    @Test
    fun `con tutte accese i misti usano solo reti accese`() = runBlocking {
        val conTutte = setOf(DataSource.TRENITALIA, DataSource.EAV, DataSource.ITALO)
        val out = misti.cerca(sorrento, roma, quando(), sources = conTutte)
        println("\n=== misti con EAV+Italo+Trenitalia: ${out.size} ===")
        out.forEach { j ->
            assertTrue("un misto usa solo reti accese", j.sources.all { it in conTutte })
        }
    }
}
