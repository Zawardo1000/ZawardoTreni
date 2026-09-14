package it.zawardo.treni

import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.conBinarioDa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Il tabellone arriva dopo il dettaglio della corsa.
 *
 * Segnalato il 14/09/2026: a Catania Centrale il REG 22096 aveva il binario
 * nero sul tabellone e verde aprendolo. Letti tutti e due alle 09:50, il
 * tabellone dava il 2 programmato e nient'altro, `andamentoTreno` il 2
 * programmato e il 2 effettivo. Il materiale di questo test e' quella corsa,
 * con gli orari e i binari di quella mattina.
 */
class BinarioTabelloneTest {

    private val giorno = LocalDate.of(2026, 9, 14)
    private val catania = "S12332"

    private fun fermata(
        n: Int,
        nome: String,
        codice: String,
        arrivo: String?,
        partenza: String?,
        programmato: String?,
        effettivo: String?,
    ) = Stop(
        index = n,
        stationName = nome,
        stationCode = codice,
        scheduledArrival = arrivo?.let { giorno.atTime(LocalTime.parse(it)) },
        actualArrival = null,
        arrivalDelayMinutes = 0,
        scheduledDeparture = partenza?.let { giorno.atTime(LocalTime.parse(it)) },
        actualDeparture = null,
        departureDelayMinutes = 0,
        scheduledPlatform = programmato,
        actualPlatform = effettivo,
        status = StopStatus.FUTURE,
    )

    private fun corsa(vararg fermate: Stop) = TrainStatus(
        number = "22096",
        category = "REG",
        label = "REG 22096",
        origin = "CATANIA AEROPORTO FONTANAROSSA",
        destination = "GIARRE RIPOSTO",
        delayMinutes = -3,
        state = TrainState.REGULAR,
        lastDetectionStation = "CATANIA CENTRALE",
        lastDetectionTime = giorno.atTime(9, 46),
        notice = null,
        stops = fermate.toList(),
    )

    /** Il REG 22096 come lo dava `andamentoTreno` alle 09:50. */
    private val reg22096 = corsa(
        fermata(1, "CATANIA AEROPORTO FONTANAROSSA", "S12336", "09:38", "09:40", "2", "2"),
        fermata(2, "CATANIA CENTRALE", catania, "09:49", "09:51", "2", "2"),
        fermata(3, "EUROPA", "S12335", "09:54", "09:55", "1", "1"),
        fermata(4, "OGNINA", "S12333", "09:58", "09:59", "1", "1"),
        fermata(5, "ACIREALE", "S12328", "10:06", "10:07", "2", "2"),
        fermata(6, "GIARRE RIPOSTO", "S12324", "10:21", null, "1", null),
    )

    /** La sua riga sul tabellone delle partenze di Catania Centrale, alla stessa ora. */
    private fun riga(
        orario: String = "09:51",
        programmato: String? = "2",
        effettivo: String? = null,
    ) = BoardEntry(
        trainRef = TrainRef("22096", "S12338", 1_789_336_800_000),
        label = "REG 22096",
        category = "REG",
        direction = "Giarre Riposto",
        scheduledTime = orario,
        delayMinutes = -3,
        scheduledPlatform = programmato,
        actualPlatform = effettivo,
        state = TrainState.REGULAR,
        inStation = true,
    )

    @Test
    fun `il binario confermato nel dettaglio e' confermato anche sul tabellone`() {
        val prima = riga()
        assertFalse("il tabellone da solo: soltanto previsto", prima.platformConfirmed)

        val dopo = prima.conBinarioDa(reg22096, catania)
        assertTrue("con la corsa: confermato, come aprendola", dopo.platformConfirmed)
        assertEquals("2", dopo.platform)
    }

    @Test
    fun `vince la corsa, che e' la lettura piu' fresca`() {
        val cambiato = corsa(fermata(2, "CATANIA CENTRALE", catania, "09:49", "09:51", "2", "3"))

        val daPrevisto = riga().conBinarioDa(cambiato, catania)
        assertTrue("il cambio si vede anche sul tabellone", daPrevisto.platformChanged)
        assertEquals("3", daPrevisto.platform)

        // Un effettivo vecchio sul tabellone non copre quello nuovo della corsa.
        assertEquals("3", riga(effettivo = "2").conBinarioDa(cambiato, catania).platform)
    }

    @Test
    fun `quel che la corsa tace resta quello del tabellone`() {
        // Il REG 21507 a Catania: nessun programmato, il 4 effettivo.
        val muta = corsa(fermata(2, "CATANIA CENTRALE", catania, "10:30", "10:32", null, null))
        val tabellone = riga(orario = "10:32", programmato = null, effettivo = "4")

        val dopo = tabellone.conBinarioDa(muta, catania)
        assertEquals("4", dopo.platform)
        assertTrue(dopo.platformConfirmed)
    }

    /**
     * Costruito, non misurato: una corsa che ripassa dalla stessa stazione.
     * L'orario sceglie il passaggio, e vale sia quello di partenza sia quello
     * d'arrivo, perche' il tabellone degli arrivi scrive l'ora d'arrivo.
     */
    @Test
    fun `una corsa che ripassa dalla stessa stazione prende il passaggio giusto`() {
        val circolare = corsa(
            fermata(2, "CATANIA CENTRALE", catania, "09:49", "09:51", "2", "2"),
            fermata(5, "ACIREALE", "S12328", "10:06", "10:07", "2", "2"),
            fermata(9, "CATANIA CENTRALE", catania, "11:18", "11:20", "5", "5"),
        )

        assertEquals("5", riga(orario = "11:20").conBinarioDa(circolare, catania).platform)
        assertEquals("5", riga(orario = "11:18").conBinarioDa(circolare, catania).platform)
        assertEquals("2", riga(orario = "09:49").conBinarioDa(circolare, catania).platform)
    }

    @Test
    fun `una stazione che la corsa non tocca lascia la riga com'era`() {
        val riga = riga()
        assertSame(riga, riga.conBinarioDa(reg22096, "S01700"))
    }
}
