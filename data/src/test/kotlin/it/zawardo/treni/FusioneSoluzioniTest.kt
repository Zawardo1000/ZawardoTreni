package it.zawardo.treni

import it.zawardo.treni.data.repository.unisciSoluzioni
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Price
import it.zawardo.treni.domain.model.Station
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * La fusione di Le Frecce e Trenord non perde il prezzo.
 *
 * Il caso vero, misurato l'11/09/2026: l'ICN 797 Napoli-Salerno delle 09:00,
 * 9,50 € su Le Frecce, usciva senza prezzo. Trenord risponde anche fuori dalla
 * Lombardia, senza biglietti, e a parita' la sua copia vinceva portandosi via
 * la cifra.
 */
class FusioneSoluzioniTest {

    private val napoli = Station("S09218", 0L, "Napoli Centrale")
    private val salerno = Station("S09818", 0L, "Salerno")
    private val partenza = LocalDateTime.of(2026, 9, 11, 9, 0)

    private fun soluzione(
        fonte: JourneySource,
        prezzo: Price?,
        numero: String = "797",
        ritardo: Int? = null,
    ) = Journey(
        departure = partenza,
        arrival = partenza.plusMinutes(40),
        duration = Duration.ofMinutes(40),
        legs = listOf(Leg(numero, "ICN", napoli, salerno, partenza, partenza.plusMinutes(40))),
        source = fonte,
        delayMinutes = ritardo,
        price = prezzo,
    )

    @Test
    fun `la copia Trenord senza prezzo eredita quello di Le Frecce`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, null, ritardo = 3)),
            departure = partenza,
            limit = 10,
        )

        assertEquals(1, unite.size)
        // Vince ancora Trenord, col suo ritardo: si aggiunge solo il prezzo.
        assertEquals(JourneySource.TRENORD, unite[0].source)
        assertEquals(3, unite[0].delayMinutes)
        assertEquals("9.50", unite[0].price?.amount)
    }

    @Test
    fun `il prezzo di Trenord, quando c'e', resta il suo`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("8.00"))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("8.00", unite.single().price?.amount)
    }

    /**
     * La lista parte dall'ora cercata. Segnalato l'11/09/2026: cercando «adesso»
     * Dateo-Vignate, in cima usciva una S5 gia' partita, perche' il filtro
     * guardava l'arrivo e non la partenza.
     */
    @Test
    fun `un treno gia' partito non apre la lista`() {
        val partito = soluzione(JourneySource.TRENORD, null, numero = "24529").let {
            it.copy(departure = partenza.minusMinutes(5), arrival = partenza.plusMinutes(15))
        }
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(partito),
            departure = partenza,
            limit = 10,
        )

        assertEquals(listOf("797"), unite.map { it.legs.single().trainNumber })
    }

    @Test
    fun `il treno che parte in questo minuto resta, anche cercando coi secondi`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = emptyList(),
            departure = partenza.plusSeconds(31),
            limit = 10,
        )

        assertEquals(1, unite.size)
    }

    @Test
    fun `due corse diverse restano due`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, null, numero = "2601")),
            departure = partenza,
            limit = 10,
        )

        assertEquals(2, unite.size)
    }
}
