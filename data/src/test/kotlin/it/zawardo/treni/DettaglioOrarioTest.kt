package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Il dettaglio dall'orario per le reti senza tempo reale. */
class DettaglioOrarioTest {
    private val eav = EavRepository(NetworkModule.eavApi)
    private val arst = ArstRepository()

    @Test
    fun `una corsa EAV di domani si apre con le fermate previste`() = runBlocking {
        val domani = LocalDate.now().plusDays(1)
        // prendo un numero corsa reale dal tabellone-da-orario di Porta Nolana
        val corse = eav.board("EAV1", date = domani)
        val numero = corse.firstOrNull()?.trainRef?.number
        println("\n=== dettaglio EAV corsa $numero (domani) ===")
        if (numero == null) { println("  nessuna corsa"); return@runBlocking }
        val d = eav.dettaglioCorsa(numero, domani)
        assertTrue("il dettaglio deve esistere", d != null)
        d!!
        println("  ${d.category} ${d.number}: ${d.origin} -> ${d.destination}, ${d.stops.size} fermate")
        println("  notice: ${d.notice}")
        d.stops.take(5).forEach { println("      ${it.stationName}  arr ${it.scheduledArrival?.toLocalTime() ?: "-"} part ${it.scheduledDeparture?.toLocalTime() ?: "-"}") }
        assertTrue("almeno due fermate", d.stops.size >= 2)
        assertTrue("nessun dato reale: nulla di 'actual'", d.stops.all { it.actualArrival == null && it.actualDeparture == null })
    }

    @Test
    fun `una corsa ARST si apre con le fermate previste`() = runBlocking {
        val sassari = arst.search("Sassari").firstOrNull()?.rfiCode ?: return@runBlocking
        val corse = arst.board(sassari, date = LocalDate.now())
        val numero = corse.firstOrNull()?.trainRef?.number
        println("\n=== dettaglio ARST corsa $numero ===")
        if (numero == null) { println("  nessuna corsa"); return@runBlocking }
        val d = arst.dettaglioCorsa(numero, LocalDate.now())
        assertTrue("il dettaglio deve esistere", d != null)
        println("  ${d!!.category} ${d.number}: ${d.origin} -> ${d.destination}, ${d.stops.size} fermate · notice: ${d.notice}")
        assertTrue(d.stops.size >= 2)
    }
}
