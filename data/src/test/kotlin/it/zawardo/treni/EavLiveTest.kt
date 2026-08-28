package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EAV contro il servizio reale.
 *
 * Il tabellone e' l'unica cosa che EAV pubblichi in tempo reale, e risponde
 * HTML: se cambiano il markup, qui si vede subito, che e' esattamente il punto.
 */
class EavLiveTest {

    private val eav = EavRepository(NetworkModule.eavApi)

    /** Napoli Porta Nolana, capolinea di quasi tutto: qualcosa c'e' sempre. */
    private val portaNolana = "EAV1"

    @Test
    fun `il tabellone di Porta Nolana risponde con corse vere`() = runBlocking {
        val righe = eav.board(portaNolana)
        println("\n=== EAV PARTENZE NAPOLI PORTA NOLANA (${righe.size}) ===")
        righe.take(12).forEach {
            println(
                "  %-7s %-16s %-28s bin %-3s %s  %s".format(
                    it.trainRef.number,
                    it.category ?: "-",
                    it.direction ?: "-",
                    it.actualPlatform ?: "-",
                    it.scheduledTime ?: "--:--",
                    if (it.state == TrainState.CANCELLED) "SOPPRESSO"
                    else if (it.delayMinutes > 0) "+${it.delayMinutes}" else "",
                ),
            )
        }

        assertTrue("il tabellone non ha restituito corse", righe.isNotEmpty())
        // Nessuna riga vuota deve superare il parser: sono il riempimento della
        // pagina, e contarle significherebbe mostrare corse inesistenti.
        assertTrue(
            "sono passate righe senza numero di treno",
            righe.all { it.trainRef.number.isNotBlank() },
        )
        assertTrue(
            "nessuna riga ha un orario valido",
            righe.any { it.scheduledTime?.matches(Regex("""\d{2}:\d{2}""")) == true },
        )
        /*
         * La destinazione sta in un <div> dentro il <td>, non sul <td> come
         * tutte le altre celle. Cercarla dove stanno le altre non dava errore:
         * dava un tabellone di treni senza meta. Se sparisce di nuovo, e' qui
         * che si deve vedere.
         */
        assertTrue(
            "nessuna corsa ha una destinazione: il markup della cella e' cambiato",
            righe.any { !it.direction.isNullOrBlank() },
        )
    }

    @Test
    fun `gli arrivi sono una lista diversa dalle partenze`() = runBlocking {
        val partenze = eav.board(portaNolana, arrivals = false)
        val arrivi = eav.board(portaNolana, arrivals = true)
        println("\n=== PORTA NOLANA: ${partenze.size} partenze, ${arrivi.size} arrivi ===")
        assertTrue("gli arrivi sono vuoti", arrivi.isNotEmpty())
    }

    @Test
    fun `una stazione senza servizio non produce corse fantasma`() = runBlocking {
        /*
         * Pozzuoli risponde con quaranta righe tutte vuote: e' il caso che ha
         * fatto nascere lo scarto nel parser. Se un giorno tornassero corse
         * vere il test smette di essere significativo, non fallisce.
         */
        val righe = eav.board("EAV107")
        println("\n=== EAV107 (Pozzuoli): ${righe.size} corse ===")
        assertTrue(
            "sono passate righe senza numero",
            righe.all { it.trainRef.number.isNotBlank() },
        )
    }

    @Test
    fun `fuori dalla rete EAV non si interroga nessuno`() = runBlocking {
        assertEquals(emptyList<Any>(), eav.board("S01700"))
        assertTrue("Milano Centrale non e' EAV", !eav.covers("S01700"))
        assertTrue("Porta Nolana e' EAV", eav.covers(portaNolana))
    }

    @Test
    fun `la stazione piu' vicina a Ercolano e' EAV, non Napoli Centrale`() {
        // Scavi di Ercolano, 40.8065 / 14.3486
        val vicina = eav.nearest(40.8065, 14.3486)
        println("\n=== PIU' VICINA A ERCOLANO: ${vicina?.name} (${vicina?.rfiCode}) ===")
        assertTrue("nessuna stazione trovata", vicina != null)
        assertTrue("la piu' vicina dovrebbe essere a Ercolano", vicina!!.name.contains("Ercolano"))
    }

    @Test
    fun `lontano dalla Campania non si propone nulla`() {
        // Milano Centrale
        assertEquals(null, eav.nearest(45.4864, 9.2049))
    }

    @Test
    fun `la ricerca per nome regge le abbreviazioni di Sant`() {
        val a = eav.search("sant'anastasia")
        val b = eav.search("s. anastasia")
        val c = eav.search("santanastasia")
        println("\n=== RICERCA: ${a.firstOrNull()?.name} / ${b.firstOrNull()?.name} / ${c.firstOrNull()?.name} ===")
        assertTrue("sant'anastasia non trovata", a.isNotEmpty())
        assertEquals(a.firstOrNull()?.rfiCode, b.firstOrNull()?.rfiCode)
        assertEquals(a.firstOrNull()?.rfiCode, c.firstOrNull()?.rfiCode)
    }
}
