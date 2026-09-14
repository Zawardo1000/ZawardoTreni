package it.zawardo.treni

import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.conBinarioDa
import it.zawardo.treni.domain.model.fermataA
import it.zawardo.treni.domain.model.indiceFermata
import it.zawardo.treni.domain.model.stessaStazione
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Bologna Centrale ha due codici, e il binario confermato stava sotto l'altro.
 *
 * Il FR 9587 del 14/09/2026, alle 12:38, a un minuto dalla partenza: sul
 * tabellone di Bologna Centrale (S05043) "19 AV" programmato e " AV"
 * effettivo, un segnaposto che ViaggiaTreno mette su ogni Freccia della
 * giornata; nel dettaglio della corsa, alla fermata "BOLOGNA C.LE/AV" (S05046),
 * "19" e "19". L'app cercava S05043 dentro la corsa, non lo trovava, e il
 * tabellone restava nero su un binario gia' confermato.
 */
class StazioniDoppieTest {

    private val giorno = LocalDate.of(2026, 9, 14)

    private val fr9587 = TrainStatus(
        number = "9587",
        category = "FR",
        label = "FR 9587",
        origin = "MILANO CENTRALE",
        destination = "REGGIO DI CALABRIA CENTRALE",
        delayMinutes = 13,
        state = TrainState.DELAYED,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = listOf(
            Stop(
                index = 5,
                stationName = "BOLOGNA C.LE/AV",
                stationCode = "S05046",
                scheduledArrival = null,
                actualArrival = null,
                arrivalDelayMinutes = 0,
                scheduledDeparture = giorno.atTime(12, 27),
                actualDeparture = null,
                departureDelayMinutes = 0,
                scheduledPlatform = "19",
                actualPlatform = "19",
                status = StopStatus.DONE,
            ),
        ),
    )

    /** La riga del tabellone di Bologna Centrale, passata per il mapper come tutte. */
    private val riga = BoardEntry(
        trainRef = TrainRef("9587", "S01700", 1_789_336_800_000),
        label = "FR 9587",
        category = "FR",
        direction = "Reggio di Calabria Centrale",
        scheduledTime = "12:27",
        delayMinutes = 13,
        scheduledPlatform = binarioPulito("19 AV"),
        actualPlatform = binarioPulito(" AV"),
        state = TrainState.DELAYED,
        inStation = true,
    )

    @Test
    fun `Bologna Centrale e Bologna C_le AV sono la stessa stazione`() {
        assertTrue(stessaStazione("S05043", "S05046"))
        assertTrue(stessaStazione(" s05046", "S05043"))
        assertFalse("Bologna Ravone e' un'altra stazione", stessaStazione("S05043", "S05042"))
        assertFalse("due codici assenti non sono una stazione", stessaStazione(null, null))
    }

    /**
     * Vicine, ma stazioni diverse con treni diversi: provato lo stesso giorno,
     * ognuna ha i suoi. Fonderle sarebbe l'errore opposto.
     */
    @Test
    fun `le stazioni vicine ma diverse restano diverse`() {
        assertFalse(stessaStazione("S01645", "S01647")) // Milano Porta Garibaldi e la sotterranea
        assertFalse(stessaStazione("S09218", "S09109")) // Napoli Centrale e Piazza Garibaldi
        assertFalse(stessaStazione("S04700", "S04701")) // Genova Piazza Principe e la sotterranea
    }

    @Test
    fun `sul tabellone di Bologna Centrale la Freccia prende il binario confermato`() {
        assertFalse("dal tabellone solo il segnaposto", riga.platformConfirmed)

        val dopo = riga.conBinarioDa(fr9587, "S05043")
        assertTrue("come aprendo la corsa: confermato", dopo.platformConfirmed)
        assertEquals("19", dopo.platform)
    }

    @Test
    fun `chi sale a Bologna Centrale trova la sua fermata nella corsa`() {
        assertNotNull(fr9587.fermataA("S05043", LocalTime.of(12, 27)))
        assertEquals(0, fr9587.indiceFermata("S05043"))
    }

    /** La corsa con i binari del dettaglio alla fermata AV, e nient'altro di cambiato. */
    private fun conBinari(programmato: String?, effettivo: String?) =
        fr9587.copy(stops = listOf(fr9587.stops.single().copy(scheduledPlatform = programmato, actualPlatform = effettivo)))

    /**
     * Il FR 9645 per Roma Termini, alle 17:10 dello stesso giorno: il tabellone
     * "19 AV" e " AV", il dettaglio "19" e ancora nessun effettivo. Unite, il
     * programmato del dettaglio e il segnaposto del tabellone uscivano in rosso:
     * "AV, nuovo: era 19".
     */
    @Test
    fun `il segnaposto del tabellone non contraddice il programmato del dettaglio`() {
        val dopo = riga.conBinarioDa(conBinari("19", null), "S05043")
        assertFalse(dopo.platformChanged)
        assertFalse(dopo.platformConfirmed)
        assertEquals("19", dopo.platform)
    }

    /**
     * Il FR 9642: il tabellone "AV" programmato e "16 AV" effettivo, il dettaglio
     * nessun programmato e "16". E' la prima assegnazione, e si legge confermata.
     */
    @Test
    fun `la prima assegnazione non e' un cambio dal segnaposto`() {
        val tabellone = riga.copy(scheduledPlatform = binarioPulito(" AV"), actualPlatform = binarioPulito("16 AV"))
        val dopo = tabellone.conBinarioDa(conBinari(null, "16"), "S05043")
        assertFalse(dopo.platformChanged)
        assertTrue(dopo.platformConfirmed)
        assertEquals("16", dopo.platform)
    }
}
