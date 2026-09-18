package it.zawardo.treni

import it.zawardo.treni.data.remote.eav.EavBoardParser
import it.zawardo.treni.domain.model.TrainState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Righe vere di `ws_getData_pis.php` del 18/09/2026, com'erano nell'HTML.
 *
 * Dazio, alle 18:12, elencava venti partenze del mattino "IN RITARDO" con
 * `RIT.` al posto dei minuti: in ritardo, senza dire di quanto. Lette dal solo
 * numero uscivano puntuali.
 */
class EavTabelloneTest {

    private fun riga(numero: String, informazioni: String, orario: String, ritardo: String) = """
        <tr> <td class="numTreno">&nbsp;$numero</td> <td class="categoria">A</td>
        <td ><div class="destinazione">TORREGAVETA</div></td>
        <td class="informazioni"><marquee scrollamount="3" scrolldelay="0">$informazioni</marquee></td>
        <td class="binario">1</td> <td class="orario">$orario</td> <td class="ritardo">$ritardo</td>
        <td class="blink"></td> </tr>
    """

    private fun leggi(vararg righe: String) =
        EavBoardParser.parse("<table>${righe.joinToString("")}</table>", 0L).associateBy { it.trainRef.number }

    @Test
    fun `in ritardo senza minuti e' in ritardo, non puntuale`() {
        val tabellone = leggi(
            riga("9170", "IN RITARDO - DELAYED", "17:33", "RIT."),
            riga("5179", "", "18:32", "2"),
            riga("9172", "", "18:03", ""),
        )
        val senzaCifra = tabellone.getValue("9170")
        assertEquals(TrainState.DELAYED, senzaCifra.state)
        assertEquals("i minuti non ci sono, e non si inventano", 0, senzaCifra.delayMinutes)

        assertEquals(TrainState.DELAYED, tabellone.getValue("5179").state)
        assertEquals(2, tabellone.getValue("5179").delayMinutes)
        assertEquals(TrainState.REGULAR, tabellone.getValue("9172").state)
    }

    @Test
    fun `lo stato non finisce fra le note di percorso`() {
        assertEquals("VIA POMPEI", EavBoardParser.noteDiPercorso("VIA POMPEI - IN RITARDO - DELAYED"))
    }
}
