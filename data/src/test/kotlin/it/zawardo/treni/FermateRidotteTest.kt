package it.zawardo.treni

import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.dopoLaDiscesa
import it.zawardo.treni.domain.model.indiceFermata
import it.zawardo.treni.domain.model.primaDellaSalita
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Il treno che prendi finisce dove scendi tu.
 *
 * Su una soluzione con cambio, ogni treno tranne l'ultimo prosegue senza di te:
 * il REG 2874 su cui sali a Milano Centrale e lasci a Monza continua per altre
 * dodici fermate fino a Lecco, e quelle dodici righe stanno **sopra** il treno
 * della coincidenza, che e' la cosa che stai cercando. Qui si decide quali si
 * possono chiudere: quelle fra la discesa e il capolinea, mai la discesa e mai
 * il capolinea.
 */
class FermateRidotteTest {

    private val giorno = LocalDate.of(2026, 9, 9)

    private fun fermata(
        n: Int,
        nome: String,
        codice: String,
        ora: String,
        stato: StopStatus = StopStatus.FUTURE,
    ): Stop {
        val quando = giorno.atTime(LocalTime.parse(ora))
        return Stop(
            index = n,
            stationName = nome,
            stationCode = codice,
            scheduledArrival = quando,
            actualArrival = null,
            arrivalDelayMinutes = 0,
            scheduledDeparture = quando,
            actualDeparture = null,
            departureDelayMinutes = 0,
            scheduledPlatform = null,
            actualPlatform = null,
            status = stato,
        )
    }

    /** Milano Centrale → Lecco, con la discesa a Monza: sei fermate. */
    private val corsa = TrainStatus(
        number = "2874",
        category = "REG",
        label = "REG 2874",
        origin = "MILANO CENTRALE",
        destination = "LECCO",
        delayMinutes = 0,
        state = TrainState.REGULAR,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = listOf(
            fermata(0, "MILANO CENTRALE", "S01700", "17:50"),
            fermata(1, "MONZA", "S01084", "18:04"),
            fermata(2, "ARCORE", "S01088", "18:14"),
            fermata(3, "CARNATE USMATE", "S01090", "18:20"),
            fermata(4, "CALOLZIOCORTE", "S01098", "18:38"),
            fermata(5, "LECCO", "S01100", "18:47"),
        ),
    )

    // ------------------------------------------------- dove sali e dove scendi

    @Test
    fun `la fermata si trova per codice`() {
        assertEquals(1, corsa.indiceFermata("S01084"))
        assertEquals(0, corsa.indiceFermata("s01700"))
    }

    @Test
    fun `una stazione che la corsa non serve non ha indice`() {
        assertEquals(-1, corsa.indiceFermata("S09999"))
        assertEquals(-1, corsa.indiceFermata(null))
        assertEquals(-1, corsa.indiceFermata("  "))
    }

    /**
     * Una corsa puo' ripassare dalla stessa stazione: e' l'orario a dire di
     * quale dei due passaggi si parli. Senza, la discesa finirebbe sul
     * passaggio sbagliato e con lei tutto il taglio delle fermate.
     */
    @Test
    fun `fra due passaggi dalla stessa stazione decide l'orario`() {
        val circolare = corsa.copy(
            stops = corsa.stops + fermata(6, "MONZA", "S01084", "19:30"),
        )
        assertEquals(1, circolare.indiceFermata("S01084", giorno.atTime(LocalTime.of(18, 4))))
        assertEquals(6, circolare.indiceFermata("S01084", giorno.atTime(LocalTime.of(19, 30))))
    }

    // ------------------------------------------------------- fermate da chiudere

    @Test
    fun `dopo la discesa si chiude tutto tranne il capolinea`() {
        // Scendi a Monza (1): si chiudono Arcore, Carnate e Calolziocorte;
        // Lecco resta, perche' e' il nome che quel treno porta scritto sopra.
        assertEquals(2..4, corsa.stops.dopoLaDiscesa(1))
    }

    @Test
    fun `senza discesa non si chiude niente`() {
        assertNull(corsa.stops.dopoLaDiscesa(-1))
    }

    @Test
    fun `scendendo al capolinea non c'e' niente da chiudere`() {
        assertNull(corsa.stops.dopoLaDiscesa(5))
        assertNull(corsa.stops.dopoLaDiscesa(4))
    }

    /**
     * Una fermata sola non si nasconde: i tre puntini per riaprirla occupano la
     * riga che farebbero risparmiare.
     */
    @Test
    fun `una fermata sola resta scritta`() {
        assertNull(corsa.stops.dopoLaDiscesa(3))
    }

    // ------------------------------------------------ prima della salita

    @Test
    fun `prima della salita si chiude tutto tranne il capolinea di partenza`() {
        // Sali a Calolziocorte (4): si chiudono Monza, Arcore e Carnate; Milano
        // Centrale resta, perche' dice da dove viene quel treno.
        assertEquals(1..3, corsa.stops.primaDellaSalita(4))
    }

    @Test
    fun `salendo al capolinea, o subito dopo, non c'e' niente da chiudere`() {
        assertNull(corsa.stops.primaDellaSalita(0))
        assertNull(corsa.stops.primaDellaSalita(1))
        assertNull(corsa.stops.primaDellaSalita(-1))
    }

    @Test
    fun `prima della salita una fermata sola resta scritta`() {
        assertNull(corsa.stops.primaDellaSalita(2))
    }
}
