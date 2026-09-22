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

    /**
     * **Si chiede col codice di salita**, come fa l'app (`CaricatoreCorsa`).
     *
     * Il numero ARST da solo non identifica una corsa: la AT1 e' insieme quella
     * per Mandas e quella per Sorso, in due angoli opposti dell'isola. Chiesta
     * senza dire da dove si sale, il dettaglio e' `null` per scelta — meglio
     * niente che le fermate dell'altra linea, e lo presidia
     * `ArstCorseOmonimeTest`. Questo test chiedeva senza, e restava verde
     * soltanto finche' la prima corsa del tabellone aveva un numero unico: il
     * 22/09/2026 e' passato dal verde al rosso fra le 08:40 e le 08:55, con
     * l'avanzare dell'orologio, senza che nulla fosse cambiato nel codice.
     */
    @Test
    fun `una corsa ARST si apre con le fermate previste`() = runBlocking {
        val sassari = arst.search("Sassari").firstOrNull()?.rfiCode ?: return@runBlocking
        val corse = arst.board(sassari, date = LocalDate.now())
        val corsa = corse.firstOrNull()
        val numero = corsa?.trainRef?.number
        println("\n=== dettaglio ARST corsa $numero da $sassari ===")
        if (numero == null) { println("  nessuna corsa"); return@runBlocking }
        val d = arst.dettaglioCorsa(numero, LocalDate.now(), salita = sassari)
        assertTrue("il dettaglio deve esistere", d != null)
        println("  ${d!!.category} ${d.number}: ${d.origin} -> ${d.destination}, ${d.stops.size} fermate · notice: ${d.notice}")
        assertTrue(d.stops.size >= 2)
        // La corsa giusta e' quella che passa da dove si sale, non l'omonima.
        assertTrue(
            "il dettaglio non passa da $sassari: e' la corsa omonima dell'altra linea",
            d.stops.any { it.stationCode == sassari },
        )
    }
}
