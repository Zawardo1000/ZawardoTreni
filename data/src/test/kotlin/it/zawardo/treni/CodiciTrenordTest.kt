package it.zawardo.treni

import it.zawardo.treni.data.remote.trenord.CodiciTrenord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Vedi [CodiciTrenord]: la traduzione, senza rete. La verifica sul vero e' in `CodiciTrenordLiveTest`. */
class CodiciTrenordTest {

    @Test
    fun `quasi sempre il codice RFI basta`() {
        assertEquals("8301700", CodiciTrenord.hafas("S01700"))
        assertEquals("S01700", CodiciTrenord.mir("S01700"))
        assertEquals("S01700", CodiciTrenord.perApp("S01700"))
    }

    @Test
    fun `Brescia per Trenord e' S09999, e torna S01717`() {
        assertEquals("8309999", CodiciTrenord.hafas("S01717"))
        assertEquals("S09999", CodiciTrenord.mir("S01717"))
        assertEquals("S01717", CodiciTrenord.perApp("S09999"))
    }

    @Test
    fun `le stazioni di confine rispondono al numero svizzero`() {
        assertEquals("8513967", CodiciTrenord.hafas("S01110")) // Pino-Tronzano
        assertEquals("8505213", CodiciTrenord.hafas("S00300")) // Bellinzona
        assertEquals("S00300", CodiciTrenord.perApp("S05213"))
    }

    @Test
    fun `i codici delle altre reti a Trenord non si chiedono`() {
        // Prima FNB1110, Bari Centrale, diventava 8301110: Pino-Tronzano.
        listOf("FNB1110", "EAV62", "ARST22581", "CH8505300", "", null).forEach {
            assertNull("$it", CodiciTrenord.hafas(it))
            assertNull("$it", CodiciTrenord.mir(it))
        }
    }

    @Test
    fun `un codice RFI che per Trenord e' un'altra stazione non si chiede`() {
        assertNull("Osteria Nuova non e' Melide", CodiciTrenord.mir("S05302"))
        assertNull(CodiciTrenord.hafas("S05302"))
        assertNull("Piedimonte Matese non e' Brescia", CodiciTrenord.hafas("S09999"))
    }
}
