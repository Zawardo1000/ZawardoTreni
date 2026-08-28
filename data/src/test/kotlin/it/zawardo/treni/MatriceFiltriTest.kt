package it.zawardo.treni

import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.SuggerimentiStazioni
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il filtro delle fonti deve **cambiare cosa si vede**, non essere cosmetico.
 *
 * Stessa query, stesso nodo geografico (Sorrento): con EAV spenta resta la
 * versione BFF senza codice — non tracciabile, «senza tabellone»; con EAV accesa
 * vince la versione EAV, col suo tabellone. Se i due casi dessero lo stesso
 * risultato il filtro non servirebbe a niente: era proprio il difetto segnalato
 * ("stesso risultato con o senza EAV").
 *
 * Deterministico apposta — niente rete. Le due liste sono ciò che le due fonti
 * restituiscono davvero per "Sorrento" (vedi BffConosceSorrentoTest e la GTFS
 * EAV imbarcata); qui si prova solo come il filtro + [SuggerimentiStazioni.unisci]
 * le combinano. La stazione EAV e' co-locata col nodo BFF, perche' fisicamente
 * e' lo stesso posto: e' esattamente il caso in cui la dedup deve scegliere.
 */
class MatriceFiltriTest {

    /** Cosa da' il BFF per "Sorrento": quattro fermate, tutte senza codice RFI. */
    private val bff = listOf(
        Station(null, 830_013_838, "Sorrento", 40.625544, 14.375222),
        Station(null, 830_014_581, "Sorrento Circumvesuviana", 40.625544, 14.375222),
        Station(null, 830_086_700, "Sorrento Porto", 40.630118, 14.378),
        Station(null, 830_014_566, "Meta di Sorrento", 40.640530, 14.417),
    )

    /** Cosa da' EAV per "Sorrento": la stazione col suo codice, tracciabile. */
    private val eav = listOf(
        Station("EAV62", 9_000_000_062, "Sorrento", 40.625544, 14.375222),
    )

    private fun sorrento(l: List<Station>) = l.first { it.name.equals("Sorrento", true) }

    @Test
    fun `EAV spenta - Sorrento resta la versione BFF, senza tabellone`() {
        // Filtro: EAV OFF -> le locali non entrano, resta solo il BFF.
        val fuori = SuggerimentiStazioni.unisci(fuoriRfi = emptyList(), nazionali = bff)
        val s = sorrento(fuori)
        assertEquals("EAV off: Sorrento e' la versione BFF senza codice", null, s.rfiCode)
        assertFalse("EAV off: non e' tracciabile, e la nota tabellone e' giusta", s.trackable)
    }

    @Test
    fun `EAV accesa - vince la Sorrento col tabellone`() {
        // Filtro: EAV ON -> la locale entra e, stesso nodo, vince lei.
        val con = SuggerimentiStazioni.unisci(fuoriRfi = eav, nazionali = bff)
        val s = sorrento(con)
        assertEquals("EAV on: vince la versione EAV62", "EAV62", s.rfiCode)
        assertTrue("EAV on: e' tracciabile, niente nota", s.trackable)
        // Il doppione BFF co-locato e' sparito: una sola Sorrento.
        assertEquals("una sola Sorrento in lista", 1, con.count { it.name.equals("Sorrento", true) })
    }

    @Test
    fun `i due casi differiscono - il filtro non e' cosmetico`() {
        val off = sorrento(SuggerimentiStazioni.unisci(emptyList(), bff))
        val on = sorrento(SuggerimentiStazioni.unisci(eav, bff))
        assertNotEquals("filtro on vs off deve cambiare la tracciabilita'", off.trackable, on.trackable)
    }
}
