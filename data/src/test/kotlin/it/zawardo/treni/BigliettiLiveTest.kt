package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.chiaveSoluzione
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.prezzoTotale
import it.zawardo.treni.domain.model.tratteDaBiglietto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * I viaggi da due biglietti contro i servizi veri: Varese-Brescia, dove Trenord
 * propone R22 e RE51 fino a Milano e poi un EuroCity di Trenitalia. Vedi
 * `JourneyRepository.bigliettiSeparati`.
 *
 * Se a quell'ora Trenord non ne propone, il test lo dice e si ferma.
 */
class BigliettiLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)

    @Test
    fun `ogni parte ha il prezzo del suo venditore, e il totale e' la somma`() = runBlocking {
        val varese = Station("S01205", 830001205, "Varese")
        val brescia = Station("S01717", 830001717, "Brescia")
        val domani = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)

        val daComporre = trenord.search(varese, brescia, domani).journeys.filter { it.tratteDaBiglietto() != null }
        daComporre.forEach { println("  ${it.departure.toLocalTime()} ${it.legs.map { l -> "${l.category} ${l.trainNumber} ${l.venditore}" }}") }
        assumeTrue("domani mattina Trenord non propone viaggi da due venditori", daComporre.isNotEmpty())

        val biglietti = journeys.bigliettiSeparati(daComporre, noti = listOf(varese, brescia))
        biglietti.forEach { (chiave, lista) -> println("  $chiave: ${lista.map { "${it.venditore} ${it.prezzo.formatted}" }}") }
        assertTrue("nessun viaggio da due venditori ha avuto i suoi prezzi", biglietti.isNotEmpty())

        biglietti.values.forEach { lista ->
            assertEquals(setOf(DataSource.TRENORD, DataSource.TRENITALIA), lista.map { it.venditore }.toSet())
            lista.forEach { assertTrue("prezzo fuori scala: ${it.prezzo.amount}", it.prezzo.amount.toDouble() in 1.0..80.0) }
            val totale = lista.prezzoTotale()!!.amount.toDouble()
            assertEquals(lista.sumOf { it.prezzo.amount.toDouble() }, totale, 0.001)
        }
        assertTrue(daComporre.any { chiaveSoluzione(it) in biglietti })
    }
}
