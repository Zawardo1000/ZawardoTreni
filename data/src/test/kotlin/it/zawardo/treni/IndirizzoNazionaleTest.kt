package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Il doppio indirizzo, provato dove conta: sulla ricerca nazionale.
 *
 * La Sorrento-EAV col solo codice sintetico non si instrada su Le Frecce (il bug
 * che ha fatto sparire il bus+Freccia); con l'[Station.idNazionale] del gemello
 * BFF, le stesse soluzioni tornano. E' la conferma end-to-end del fix.
 */
class IndirizzoNazionaleTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)

    private val eavSenza = Station("EAV62", 9_000_000_062, "Sorrento", 40.6255, 14.3752)
    private val eavCon = eavSenza.copy(idNazionale = 830_013_838)
    private val roma = Station("S08409", 830_008_409, "Roma Termini", 41.9010, 12.5015)

    @Test
    fun `con l'id nazionale Le Frecce torna a instradare Sorrento-EAV`() = runBlocking {
        val quando = LocalDate.now().plusDays(1).atTime(8, 0)
        val soloRfi = setOf(DataSource.TRENITALIA)
        val senza = journeys.searchAll(eavSenza, roma, quando, sources = soloRfi).journeys
        val con = journeys.searchAll(eavCon, roma, quando, sources = soloRfi).journeys
        println("\n=== Sorrento-EAV -> Roma: senza id=${senza.size}, con id=${con.size} ===")
        assertTrue("senza id nazionale il codice sintetico non si instrada", senza.isEmpty())
        assertTrue("con id nazionale tornano le soluzioni (bus+Freccia)", con.isNotEmpty())
    }
}
