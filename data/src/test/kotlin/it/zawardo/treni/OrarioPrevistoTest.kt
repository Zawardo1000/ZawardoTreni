package it.zawardo.treni

import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.soloOrarioPrevistoPer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Il giorno che non e' oggi porta con se' solo fermate e orari.
 *
 * Il caso vero da cui nasce: il REG 2813 di domani si apriva come "Arrivato",
 * ultimo rilevamento a Lecco alle 06:48, con i ritardi e i binari di ogni
 * fermata. Erano i dati della corsa di stamattina, arrivati in risposta a una
 * domanda che parlava di domani.
 */
class OrarioPrevistoTest {

    private val oggi = LocalDate.of(2026, 8, 30)
    private val domani = oggi.plusDays(1)

    /** Una corsa gia' arrivata, come la restituisce una fonte in tempo reale. */
    private fun corsaArrivata(giorno: LocalDate = oggi) = TrainStatus(
        number = "2813",
        category = "REG",
        label = "REG 2813",
        origin = "SONDRIO",
        destination = "LECCO",
        delayMinutes = 1,
        state = TrainState.ARRIVED,
        lastDetectionStation = "LECCO",
        lastDetectionTime = giorno.atTime(6, 48),
        notice = "Il treno viaggia con 1 minuto di ritardo",
        stops = listOf(
            Stop(
                index = 1,
                stationName = "SONDRIO",
                stationCode = "S01234",
                scheduledArrival = null,
                actualArrival = null,
                arrivalDelayMinutes = 0,
                scheduledDeparture = giorno.atTime(5, 28),
                actualDeparture = giorno.atTime(5, 29),
                departureDelayMinutes = 1,
                scheduledPlatform = "3",
                actualPlatform = "1",
                status = StopStatus.DONE,
            ),
            Stop(
                index = 2,
                stationName = "MORBEGNO",
                stationCode = "S01235",
                scheduledArrival = giorno.atTime(5, 45),
                actualArrival = giorno.atTime(5, 45),
                arrivalDelayMinutes = 0,
                scheduledDeparture = giorno.atTime(5, 46),
                actualDeparture = giorno.atTime(5, 47),
                departureDelayMinutes = 1,
                scheduledPlatform = "1",
                actualPlatform = "1",
                status = StopStatus.DONE,
            ),
            Stop(
                index = 3,
                stationName = "LECCO",
                stationCode = "S01236",
                scheduledArrival = giorno.atTime(6, 51),
                actualArrival = giorno.atTime(6, 48),
                arrivalDelayMinutes = -3,
                scheduledDeparture = null,
                actualDeparture = null,
                departureDelayMinutes = 0,
                scheduledPlatform = "2",
                actualPlatform = "2",
                status = StopStatus.DONE,
                projectedArrival = giorno.atTime(6, 48),
            ),
        ),
    )

    @Test
    fun `di un treno di domani non si dice ne' il ritardo ne' lo stato di oggi`() {
        val previsto = corsaArrivata().soloOrarioPrevistoPer(domani)

        assertFalse("dichiarata senza tempo reale", previsto.realtime)
        assertEquals("nessun ritardo da dichiarare", 0, previsto.delayMinutes)
        assertEquals("non e' arrivato: deve ancora partire", TrainState.NOT_DEPARTED, previsto.state)
        assertNull("l'ultimo rilevamento e' di un altro giorno", previsto.lastDetectionStation)
        assertNull(previsto.lastDetectionTime)
        assertEquals("nessuna fermata gia' fatta", -1, previsto.currentStopIndex)
        assertTrue(
            "niente orari reali, ritardi, proiezioni o binari",
            previsto.stops.all {
                it.status == StopStatus.FUTURE &&
                    it.actualArrival == null && it.actualDeparture == null &&
                    it.arrivalDelayMinutes == 0 && it.departureDelayMinutes == 0 &&
                    it.projectedArrival == null && it.projectedDeparture == null &&
                    it.scheduledPlatform == null && it.actualPlatform == null
            },
        )
    }

    @Test
    fun `fermate e orari di tabella restano tutti`() {
        val previsto = corsaArrivata().soloOrarioPrevistoPer(domani)

        assertEquals(listOf("SONDRIO", "MORBEGNO", "LECCO"), previsto.stops.map { it.stationName })
        assertEquals(domani.atTime(5, 28), previsto.stops.first().scheduledDeparture)
        assertEquals(domani.atTime(6, 51), previsto.stops.last().scheduledArrival)
        assertEquals("SONDRIO", previsto.origin)
        assertEquals("LECCO", previsto.destination)
    }

    @Test
    fun `la fonte che risponde col proprio giorno viene riportata a quello chiesto`() {
        // La fonte a cui si chiede domani e che torna la corsa di oggi.
        val previsto = corsaArrivata(giorno = oggi).soloOrarioPrevistoPer(domani)
        assertTrue(
            "ogni orario cade nel giorno chiesto",
            previsto.stops.all { f ->
                listOfNotNull(f.scheduledArrival, f.scheduledDeparture)
                    .all { it.toLocalDate() == domani }
            },
        )
    }

    @Test
    fun `la fonte che risponde gia' per il giorno chiesto non si sposta`() {
        val previsto = corsaArrivata(giorno = domani).soloOrarioPrevistoPer(domani)
        assertEquals(domani.atTime(5, 28), previsto.stops.first().scheduledDeparture)
    }

    @Test
    fun `un treno che scavalla la mezzanotte resta lungo una notte sola`() {
        val notturno = corsaArrivata().copy(
            stops = listOf(
                corsaArrivata().stops.first().copy(scheduledDeparture = oggi.atTime(23, 50)),
                corsaArrivata().stops.last().copy(scheduledArrival = oggi.plusDays(1).atTime(6, 20)),
            ),
        )
        val previsto = notturno.soloOrarioPrevistoPer(domani)

        assertEquals(domani.atTime(23, 50), previsto.stops.first().scheduledDeparture)
        assertEquals(
            "l'arrivo resta il mattino dopo la partenza",
            domani.plusDays(1).atTime(6, 20),
            previsto.stops.last().scheduledArrival,
        )
    }

    @Test
    fun `il perche' si puo' riscrivere, altrimenti resta quello di prima`() {
        val corsa = corsaArrivata()
        assertEquals(
            "l'avviso di oggi non vale per domani",
            "solo orario",
            corsa.soloOrarioPrevistoPer(domani, notice = "solo orario").notice,
        )
        assertEquals(corsa.notice, corsa.soloOrarioPrevistoPer(domani).notice)
    }
}
