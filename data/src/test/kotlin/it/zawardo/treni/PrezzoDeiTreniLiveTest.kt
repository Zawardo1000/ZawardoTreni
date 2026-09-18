package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.chiaveSoluzione
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.prezzoDaTrenord
import it.zawardo.treni.domain.model.soloTreni
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * «Urbano › REG 10945», Milano Centrale - Brescia via Lambrate: Le Frecce non
 * la prezza, Trenord prezza il treno. Vedi `JourneyRepository.prezziDeiTreni`.
 *
 * La sera, da Milano Centrale, Le Frecce propone queste combinazioni col tratto
 * urbano fino a Lambrate; se l'orario del giorno non ne ha, il test lo dice e
 * si ferma invece di fallire.
 */
class PrezzoDeiTreniLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)

    @Test
    fun `la parte in treno di una soluzione col tratto urbano ha il prezzo di Trenord`() = runBlocking {
        val milano = Station("S01700", 830001700, "Milano Centrale")
        val brescia = Station("S01717", 830001717, "Brescia")
        val sera = LocalDateTime.now().plusDays(1).withHour(21).withMinute(0)

        val miste = journeys.search(milano, brescia, sera, limit = 15).filter { it.prezzoDaTrenord && !it.soloTreni }
        miste.forEach { println("  senza prezzo: ${it.departure.toLocalTime()} ${it.legs.map { l -> l.label }}") }
        assumeTrue("stasera Le Frecce non propone soluzioni col tratto urbano", miste.isNotEmpty())

        val prezzi = journeys.prezziDeiTreni(miste)
        miste.forEach { println("  ${it.departure.toLocalTime()}: ${prezzi[chiaveSoluzione(it)]?.formatted ?: "-"}") }
        assertTrue("Trenord non ha prezzato nessuna parte in treno", prezzi.isNotEmpty())
        prezzi.values.forEach {
            val euro = it.amount.toDouble()
            assertTrue("prezzo fuori scala per un regionale: $euro", euro in 1.0..30.0)
        }
    }
}
