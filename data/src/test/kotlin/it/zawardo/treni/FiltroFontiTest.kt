package it.zawardo.treni

import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.FiltroFonti
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I due `if` che stavano dentro i ViewModel, tirati fuori e resi verificabili.
 *
 * Coprono ciò che l'utente vede: quali reti si interrogano per i suggerimenti
 * (il filtro delle fonti) e se si compongono i viaggi misti (il flag beta). La
 * logica ora e' pura, in [FiltroFonti], e non serve Android per provarla.
 */
class FiltroFontiTest {

    @Test
    fun `fontiLocali - esattamente le reti con stazioni proprie, se accese`() {
        val locali = FiltroFonti.fontiLocali(DataSource.entries.toSet())
        // Deriva dall'enum, non da una lista scritta a mano.
        assertEquals(DataSource.entries.filter { it.stazioniProprie }, locali)
        // La rete nazionale, Trenord e Italo non hanno stazioni proprie: mai locali.
        assertFalse(DataSource.TRENITALIA in locali)
        assertFalse(DataSource.TRENORD in locali)
        assertFalse(DataSource.ITALO in locali)
        // Quelle fuori-RFI ci sono.
        assertTrue(DataSource.EAV in locali)
        assertTrue(DataSource.ARST in locali)
    }

    @Test
    fun `fontiLocali - una rete spenta non si interroga`() {
        val senzaEav = DataSource.entries.toSet() - DataSource.EAV
        val locali = FiltroFonti.fontiLocali(senzaEav)
        assertFalse("EAV spenta non deve comparire", DataSource.EAV in locali)
        assertTrue("le altre locali restano", DataSource.FNB in locali)
    }

    @Test
    fun `fontiLocali - senza reti opzionali non resta nessuna locale`() {
        assertTrue(FiltroFonti.fontiLocali(setOf(DataSource.TRENITALIA)).isEmpty())
    }

    @Test
    fun `componiMisti - servono beta accesa e non solo-diretti`() {
        assertTrue("beta on, non solo-diretti", FiltroFonti.componiMisti(soloDiretti = false, betaAttivo = true))
        assertFalse("beta spenta: niente misti", FiltroFonti.componiMisti(soloDiretti = false, betaAttivo = false))
        assertFalse("solo diretti: niente misti", FiltroFonti.componiMisti(soloDiretti = true, betaAttivo = true))
        assertFalse("entrambe contro", FiltroFonti.componiMisti(soloDiretti = true, betaAttivo = false))
    }
}
