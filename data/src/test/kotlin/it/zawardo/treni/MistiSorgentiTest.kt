package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.ItaloRepository
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
 * Nasce da una segnalazione precisa: chi spegne Italo o EAV non deve vederli
 * ricomparire dentro un viaggio composto. Se questo test passa, l'interruttore
 * delle sorgenti vince sul flag, come dev'essere.
 */
class MistiSorgentiTest {

    private val eav = EavRepository(NetworkModule.eavApi)
    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val misti = ViaggiMistiRepository(eav, italo)

    private val sorrento = Station("EAV62", 9_000_000_062, "Sorrento", 40.6258, 14.3797)
    private val roma = Station("S08409", 830_008_409, "Roma Termini", 41.9010, 12.5015)

    private fun quando() = LocalDate.now().plusDays(1).atTime(8, 0)

    @Test
    fun `con Italo spenta non si compone nessun misto`() = runBlocking {
        val senzaItalo = setOf(DataSource.TRENITALIA, DataSource.EAV)
        val out = misti.cerca(sorrento, roma, quando(), sources = senzaItalo)
        assertTrue("Italo spenta: nessun misto la deve usare", out.isEmpty())
    }

    @Test
    fun `con EAV spenta non si compone nessun misto`() = runBlocking {
        val senzaEav = setOf(DataSource.TRENITALIA, DataSource.ITALO)
        val out = misti.cerca(sorrento, roma, quando(), sources = senzaEav)
        assertTrue("EAV spenta: nessun misto la deve usare", out.isEmpty())
    }

    @Test
    fun `con entrambe accese i misti possono comparire`() = runBlocking {
        val conEntrambe = setOf(DataSource.TRENITALIA, DataSource.EAV, DataSource.ITALO)
        val out = misti.cerca(sorrento, roma, quando(), sources = conEntrambe)
        println("\n=== misti con EAV+Italo accese: ${out.size} ===")
        // Non si pretende che ce ne siano (dipende da Italo), ma se ci sono
        // devono attraversare proprio quelle reti.
        out.forEach { j ->
            assertTrue("un misto usa solo reti accese", j.sources.all { it in conEntrambe })
        }
    }
}
