package it.zawardo.treni

import it.zawardo.treni.data.mapper.etichettaTreno
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La sigla del treno senza gli spazi con cui ViaggiaTreno la scrive per le Frecce. */
class EtichettaTrenoTest {

    @Test
    fun `la sigla delle Frecce perde lo spazio davanti`() {
        assertEquals("FR 9712", etichettaTreno(" FR 9712"))
        assertEquals("REG 2934", etichettaTreno("REG 2934"))
        assertNull(etichettaTreno("   "))
    }

    /** Milano Porta Garibaldi, 19/09/2026: `compNumeroTreno` « FR 9712», categoria vuota. */
    @Test
    fun `nel tabellone la sigla sta in colonna con le altre`() {
        val riga = TabelloneVoceDto(
            numeroTreno = 9712,
            categoria = "",
            compNumeroTreno = " FR 9712",
            codOrigine = "S01645",
            dataPartenzaTreno = 1789768800000,
        ).toBoardEntry()!!
        assertEquals("FR 9712", riga.label)
    }
}
