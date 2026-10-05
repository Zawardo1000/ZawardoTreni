package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.conPassaggiDa
import it.zawardo.treni.domain.model.conRitardoDa
import it.zawardo.treni.domain.model.conRitardoDaFermo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Il treno che ViaggiaTreno non ha visto partire, e Trenord si'.
 *
 * Il caso da cui nasce: il 04/10/2026 alle 17:50 il REG 24860 delle 16:52 da
 * Milano Porta Garibaldi era «+58, coincidenza persa a Lecco», e il treno era
 * partito. ViaggiaTreno non l'aveva rilevato in nessuna stazione — le S8 prima e
 * dopo le aveva tutte — e il +58 era il tempo passato dalle 16:52. Le fermate
 * sono quelle vere della corsa, lette da Trenord il 05/10/2026.
 */
class PassaggiDaTrenordTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 10, 4)
    private fun ora(hhmm: String) = giorno.atTime(LocalTime.parse(hhmm))
    private fun millis(hhmm: String) = ora(hhmm).atZone(roma).toInstant().toEpochMilli()

    private val percorso = listOf(
        Triple("MILANO PORTA GARIBALDI", "S01645", null to "16:52"),
        Triple("MONZA", "S01322", "17:09" to "17:10"),
        Triple("AIRUNO", "S01515", "17:36" to "17:37"),
        Triple("CALOLZIOCORTE OLGINATE", "S01524", "17:42" to "17:43"),
        Triple("LECCO", "S01520", "17:56" to null),
    )

    /** Il 24860 come lo dava ViaggiaTreno: non partito, nessun rilevamento. */
    private fun daViaggiaTreno() = AndamentoTrenoDto(
        numeroTreno = 24860,
        categoria = "REG",
        origine = "MILANO PORTA GARIBALDI",
        destinazione = "LECCO",
        nonPartito = true,
        stazioneUltimoRilevamento = "--",
        codiceCliente = 63,
        fermate = percorso.mapIndexed { i, (nome, codice, orari) ->
            FermataDto(
                stazione = nome,
                id = codice,
                progressivo = i + 1,
                arrivoTeorico = orari.first?.let(::millis),
                partenzaTeorica = orari.second?.let(::millis),
            )
        },
    ).toTrainStatus()

    /**
     * La stessa corsa letta da Trenord, rilevata fino a [finoA] fermate con
     * [ritardo] minuti; [spostata] sposta l'orario di tabella, per fingere un
     * altro treno con lo stesso numero.
     */
    private fun daTrenord(finoA: Int, ritardo: Int = 3, spostata: Long = 0, senza: Int? = null) = TrainStatus(
        number = "24860",
        category = "S8",
        label = "S8 24860",
        origin = "MILANO PORTA GARIBALDI",
        destination = "LECCO",
        delayMinutes = ritardo,
        state = if (finoA == 0) TrainState.NOT_DEPARTED else TrainState.DELAYED,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = percorso.mapIndexed { i, (nome, codice, orari) ->
            val arrivo = orari.first?.let { ora(it).plusHours(spostata) }
            val partenza = orari.second?.let { ora(it).plusHours(spostata) }
            val fatta = i < finoA
            Stop(
                index = i + 1,
                stationName = nome,
                stationCode = codice,
                scheduledArrival = arrivo,
                actualArrival = arrivo?.takeIf { fatta }?.plusMinutes(ritardo.toLong()),
                arrivalDelayMinutes = if (fatta) ritardo else 0,
                scheduledDeparture = partenza,
                actualDeparture = partenza?.takeIf { fatta }?.plusMinutes(ritardo.toLong()),
                departureDelayMinutes = if (fatta) ritardo else 0,
                scheduledPlatform = null,
                actualPlatform = null,
                status = if (fatta) StopStatus.DONE else StopStatus.FUTURE,
            )
        }.filterIndexed { i, _ -> i != senza },
    )

    private val alle1750 = ora("17:50")

    @Test
    fun `senza rilevamenti il ritardo e' dedotto, e lo dice`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750)
        assertEquals(58, corsa.delayMinutes)
        assertTrue(corsa.ritardoDedotto)
    }

    @Test
    fun `se Trenord l'ha visto partire valgono i suoi passaggi`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750).conPassaggiDa(daTrenord(finoA = 3))
        assertFalse(corsa.ritardoDedotto)
        assertEquals(3, corsa.delayMinutes)
        assertEquals(TrainState.DELAYED, corsa.state)
        assertEquals("Airuno", corsa.lastDetectionStation)
        assertEquals(ora("17:40"), corsa.lastDetectionTime)
        assertEquals(StopStatus.DONE, corsa.stops[2].status)
        // Da li' in avanti, il ritardo misurato e non i 58 minuti.
        assertEquals(StopStatus.FUTURE, corsa.stops[3].status)
        assertEquals(ora("17:45"), corsa.stops[3].effectiveArrival)
        // L'impresa resta quella di ViaggiaTreno: la corsa e' la sua.
        assertEquals(63, corsa.impresa)
    }

    @Test
    fun `se tace anche Trenord resta il ritardo dedotto`() {
        val dedotta = daViaggiaTreno().conRitardoDaFermo(alle1750)
        assertSame(dedotta, dedotta.conPassaggiDa(daTrenord(finoA = 0)))
    }

    @Test
    fun `un altro treno con lo stesso numero non presta i suoi passaggi`() {
        val dedotta = daViaggiaTreno().conRitardoDaFermo(alle1750)
        assertSame(dedotta, dedotta.conPassaggiDa(daTrenord(finoA = 3, spostata = 5)))
    }

    @Test
    fun `dove ViaggiaTreno ha misurato qualcosa le misure non si mescolano`() {
        val misurata = daTrenord(finoA = 1, ritardo = 1)
        assertSame(misurata, misurata.conPassaggiDa(daTrenord(finoA = 3, ritardo = 7)))
    }

    @Test
    fun `arrivata secondo Trenord, arrivata`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750).conPassaggiDa(daTrenord(finoA = 5))
        assertEquals(TrainState.ARRIVED, corsa.state)
    }

    /** La riga del 24860 sul tabellone di Porta Garibaldi alle 17:50. */
    private fun riga() = BoardEntry(
        trainRef = TrainRef("24860", "S01645", millis("00:00")),
        label = "REG 24860",
        category = "REG",
        direction = "LECCO",
        scheduledTime = "16:52",
        delayMinutes = 0,
        scheduledPlatform = null,
        actualPlatform = null,
        state = TrainState.NOT_DEPARTED,
        inStation = false,
    ).conRitardoDaFermo("S01645", alle1750)

    @Test
    fun `sul tabellone il ritardo da fermo e' dedotto`() {
        assertEquals(58, riga().delayMinutes)
        assertTrue(riga().ritardoDedotto)
    }

    @Test
    fun `sul tabellone vince la corsa che Trenord ha visto partire`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750).conPassaggiDa(daTrenord(finoA = 3))
        val aggiornata = riga().conRitardoDa(corsa)
        assertEquals(3, aggiornata.delayMinutes)
        assertEquals(TrainState.DELAYED, aggiornata.state)
        assertFalse(aggiornata.ritardoDedotto)
    }

    @Test
    fun `sul tabellone una corsa ancora muta non cambia niente`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750)
        val aggiornata = riga().conRitardoDa(corsa)
        assertEquals(58, aggiornata.delayMinutes)
        assertTrue(aggiornata.ritardoDedotto)
    }

    /** Una fermata che Trenord non elenca, prima dell'ultima rilevata: passata, non rilevata. */
    @Test
    fun `una fermata senza gemella prima dell'ultima rilevata e' passata`() {
        val corsa = daViaggiaTreno().conRitardoDaFermo(alle1750).conPassaggiDa(daTrenord(finoA = 3, senza = 1))
        val monza = corsa.stops[1]
        assertEquals(StopStatus.DONE, monza.status)
        assertFalse(monza.detected)
        assertEquals(null, monza.projectedArrival)
        assertEquals("Airuno", corsa.lastDetectionStation)
    }
}
