package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Le eccezioni di `CodiciTrenord`, contro la ricerca vera di Trenord.
 *
 * Sono poche e scritte a mano, e il giorno che Trenord rinumera una stazione la
 * ricerca risponde "missing destination station" senza che niente si rompa in
 * modo visibile: la soluzione Trenord sparisce e basta, col suo prezzo. Brescia
 * e' rimasta cosi' per settimane. Questo test lo dice il giorno stesso.
 *
 * Fra una ricerca e l'altra si aspetta: il CDN di Trenord, a una raffica di
 * richieste, risponde "Access Denied" per qualche minuto.
 */
class CodiciTrenordLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val domani = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)

    /** Le stazioni dell'app che per Trenord hanno un altro codice. */
    private val eccezioni = listOf(
        Station("S01717", 830001717, "Brescia"),
        Station("S01763", 830025432, "Como Camerlata"),
        Station("S01110", 830001110, "Pino-Tronzano"),
        Station("S00300", 0, "Bellinzona"),
    )

    @Test
    fun `ogni eccezione porta a soluzioni, e torna col codice dell'app`() = runBlocking {
        eccezioni.forEach { stazione ->
            val soluzioni = trenord.search(milano, stazione, domani).journeys
            val arrivi = soluzioni.map { it.legs.last().to.rfiCode }.distinct()
            println("  Milano Centrale -> ${stazione.name}: ${soluzioni.size} soluzioni, arrivo $arrivi")
            assertTrue(
                "Trenord non riconosce piu' ${stazione.name}: il suo codice e' cambiato",
                soluzioni.isNotEmpty(),
            )
            assertEquals(
                "${stazione.name} deve tornare col codice dell'app, o non si accoppia col resto",
                listOf(stazione.rfiCode),
                arrivi,
            )
            delay(PAUSA_MS)
        }
    }

    @Test
    fun `Milano-Brescia porta il prezzo dei regionali`() = runBlocking {
        val soluzioni = trenord.search(milano, eccezioni.first(), domani).journeys
        val prezzi = soluzioni.mapNotNull { it.price?.amount?.toDoubleOrNull() }
        println("  Milano Centrale -> Brescia: ${prezzi.size} prezzi su ${soluzioni.size}: $prezzi")
        assertTrue("i regionali Milano-Brescia sono tornati senza prezzo", prezzi.isNotEmpty())
        // Una corsa semplice sta sotto i venti euro: sopra sarebbe un abbonamento.
        prezzi.forEach { assertTrue("prezzo fuori scala: $it", it in 1.0..20.0) }
        delay(PAUSA_MS)
    }

    @Test
    fun `il tabellone Trenord di Brescia risponde`() = runBlocking {
        val righe = trenord.timetable("S01717")
        println("  tabellone Trenord di Brescia: ${righe.size} corse")
        // Di notte l'orario di stazione e' vuoto per tutti: vedi `notteFonda`.
        org.junit.Assume.assumeTrue("a quest'ora la rete e' ferma", !notteFonda() || righe.isNotEmpty())
        assertTrue("il tabellone Trenord di Brescia e' vuoto: il MIR e' cambiato?", righe.isNotEmpty())
    }

    private companion object {
        const val PAUSA_MS = 3_000L
    }
}
