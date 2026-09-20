package it.zawardo.treni

import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.svizzera.SvizzeraBoardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'orario svizzero, senza rete: il numero della corsa sta in `name`, `number` e'
 * la linea; negli arrivi `to` e' l'origine e il tempo reale non vale. Risposte
 * come quelle di `transport.opendata.ch` del 19/09/2026 (vedi `data/fonti/MINORI.md`).
 */
class SvizzeraMappersTest {

    private fun tabellone(json: String) = NetworkModule.json.decodeFromString(SvizzeraBoardDto.serializer(), json)

    @Test
    fun `il numero della corsa e' in name, la linea resta sulla scritta`() {
        val righe = tabellone(
            """
            {"station": {"id": "8301003", "name": "Domodossola (I)"},
             "stationboard": [
              {"category": "PE", "number": "72", "name": "000041", "operator": "FART", "to": "Locarno",
               "stop": {"departure": "2026-09-20T09:25:00+0200", "delay": null, "platform": "1"}},
              {"category": "PE", "number": "72", "name": "000045", "operator": "FART", "to": "Locarno",
               "stop": {"departure": "2026-09-20T11:25:00+0200", "delay": 3, "platform": "1"}},
              {"category": "RE", "number": "80", "name": "025535", "operator": "SBB", "to": "Milano Centrale",
               "stop": {"departure": "2026-09-20T12:05:00+0200", "delay": 0, "platform": "3"}}
             ]}
            """,
        ).stationboard.mapNotNull { it.toBoardEntry() }
        assertEquals(listOf("41", "45", "25535"), righe.map { it.trainRef.number })
        assertEquals(listOf("Panoramic Express 72", "Panoramic Express 72", "RE80"), righe.map { it.label })
        assertEquals(3, righe[1].delayMinutes)
        assertTrue(righe.all { it.realtime })
    }

    @Test
    fun `negli arrivi to e' l'origine, e il tempo reale non conta`() {
        val riga = tabellone(
            """
            {"station": {"id": "8505470", "name": "Locarno FART"},
             "stationboard": [
              {"category": "R", "number": "70", "name": "000333", "operator": "FART", "to": "Camedo",
               "stop": {"departure": "2026-09-19T21:10:00+0200", "arrival": null, "delay": null, "platform": "2",
                        "prognosis": {"platform": null, "departure": "2026-09-19T19:21:42+0200"}}}
             ]}
            """,
        ).stationboard.single().toBoardEntry(arrivo = true)!!
        assertEquals("333", riga.trainRef.number)
        assertEquals("Camedo", riga.direction)
        assertEquals("21:10", riga.scheduledTime)
        assertFalse("il tempo reale degli arrivi e' finto", riga.realtime)
        assertEquals(0, riga.delayMinutes)
    }
}
