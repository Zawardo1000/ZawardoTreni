package it.zawardo.treni

import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.SuggerimentiStazioni
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo stesso codice RFI e' la stessa stazione, e compare una volta sola col nome
 * della rete nazionale (chiesto il 19/09/2026). Vedi [SuggerimentiStazioni].
 */
class SuggerimentiCodiceRfiTest {

    @Test
    fun `stesso codice senza coordinate, una voce sola`() {
        val dalla_rete = Station("S01039", 830001039, "Rho-Fiera Milano")
        val dalla_cache = Station("S01039", 830001040, "Rho Fiera Milano")
        val uniti = SuggerimentiStazioni.unisci(emptyList(), listOf(dalla_rete, dalla_cache))
        assertEquals(listOf("Rho-Fiera Milano"), uniti.map { it.name })
    }

    /**
     * Chiasso sta anche nell'orario svizzero, col codice italiano: la voce resta
     * quella svizzera, che il tabellone lo compone di due fonti e ha l'indirizzo
     * nazionale, ma il nome e' quello della rete nazionale.
     */
    @Test
    fun `la voce di un'altra rete col codice RFI prende il nome nazionale`() {
        val svizzera = Station("S01301", 9_208_505_307L, "Chiasso (CH)")
        val nazionale = Station("S01301", 830001301, "Chiasso")
        val unita = SuggerimentiStazioni.unisci(listOf(svizzera), listOf(nazionale)).single()
        assertEquals("Chiasso", unita.name)
        assertEquals(9_208_505_307L, unita.locationId)
        assertEquals(830001301L, unita.idNazionale)
    }

    @Test
    fun `codici diversi restano stazioni diverse`() {
        val rho = Station("S01037", 830001037, "Rho")
        val fiera = Station("S01039", 830001039, "Rho-Fiera Milano")
        assertEquals(2, SuggerimentiStazioni.unisci(emptyList(), listOf(rho, fiera)).size)
    }

    /**
     * Anche a 184 metri: il Passante ha i suoi treni, che la superficie non ha.
     * Coordinate e codici come li dava Le Frecce il 19/09/2026.
     */
    @Test
    fun `due stazioni vicine coi loro codici restano due`() {
        val superficie = Station("S01645", 830001645, "Milano Porta Garibaldi", latitude = 45.484706, longitude = 9.187388)
        val passante = Station("S01647", 830001662, "Milano Porta Garibaldi Passante", latitude = 45.48423, longitude = 9.185128)
        val uniti = SuggerimentiStazioni.unisci(emptyList(), listOf(superficie, passante))
        assertEquals(listOf("S01645", "S01647"), uniti.map { it.rfiCode })
    }

    /** Lo stesso per una rete fuori-RFI: la Ferrotramviaria nel sottopiano di Bari Centrale. */
    @Test
    fun `la stazione di un'altra rete accanto a una RFI resta`() {
        val fnb = Station("FNB1110", 9_100_001_110L, "Bari Centrale FNB", latitude = 41.1177, longitude = 16.8697)
        val rfi = Station("S11119", 830011119, "Bari Centrale", latitude = 41.1178, longitude = 16.8698)
        val uniti = SuggerimentiStazioni.unisci(listOf(fnb), listOf(rfi))
        assertEquals(setOf("FNB1110", "S11119"), uniti.map { it.rfiCode }.toSet())
    }

    /** La versione senza codice, invece, sparisce ancora sotto quella vicina che ce l'ha. */
    @Test
    fun `la versione senza codice sparisce sotto quella vicina`() {
        val eav = Station("EAV62", 9_000_000_062L, "Sorrento", latitude = 40.6263, longitude = 14.3757)
        val bff = Station(null, 830099999, "SORRENTO CIRCUMVESUVIANA", latitude = 40.6264, longitude = 14.3758)
        val unita = SuggerimentiStazioni.unisci(listOf(eav), listOf(bff)).single()
        assertEquals("EAV62", unita.rfiCode)
        assertEquals(830099999L, unita.idNazionale)
    }
}
