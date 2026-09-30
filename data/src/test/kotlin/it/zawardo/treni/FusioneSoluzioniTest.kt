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
    fun `il prezzo di Trenord, quando e' il piu' basso, resta il suo`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("8.00"))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("8.00", unite.single().price?.amount)
    }

    /**
     * Il caso di Milano Centrale-Calolziocorte, il 30/09/2026: il RE 2836 delle
     * 18:20 usciva a 6,30 € e le soluzioni col cambio delle 18:43 a 5,40 €, sulla
     * stessa linea e con lo stesso biglietto. Trenord tariffa ricostruendo il
     * percorso dalle fermate che la soluzione nomina, e il 2836 ferma solo a
     * Monza: gli attribuiva 57 km, cioe' una via che non fa, mentre il sito di
     * Trenitalia diceva 5,40 € a ogni riga. Vedi `ilPrezzoPiuBasso`.
     */
    @Test
    fun `fra i due prezzi della stessa corsa resta il piu' basso`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("5.40"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("6.30"), ritardo = 4)),
            departure = partenza,
            limit = 10,
        )

        assertEquals("5.40", unite.single().price?.amount)
        // Del prezzo si prende solo la cifra: la riga resta quella di Trenord,
        // col ritardo che Le Frecce non ha.
        assertEquals(JourneySource.TRENORD, unite.single().source)
        assertEquals(4, unite.single().delayMinutes)
    }

    @Test
    fun `un prezzo che non si compra non vince per essere piu' basso`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("5.40", saleable = false))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("6.30"))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("6.30", unite.single().price?.amount)
    }

    @Test
    fun `nemmeno un prezzo esaurito`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("5.40", esaurito = true))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("6.30"))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("6.30", unite.single().price?.amount)
    }

    /**
     * Il confronto sta fra due prezzi dell'intero viaggio. Le Frecce il tratto
     * urbano lo lascia fuori dichiarandolo, e li' la sua cifra e' piu' bassa
     * perche' comprende di meno: prenderla direbbe meno del vero.
     */
    @Test
    fun `col tratto urbano non si confronta niente`() {
        val conUrbano = soluzione(JourneySource.LEFRECCE, Price("2.20")).let {
            it.copy(legs = it.legs + Leg(null, "UB", napoli, salerno, partenza, partenza.plusMinutes(10)))
        }
        val unite = unisciSoluzioni(
            lefrecce = listOf(conUrbano),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("4.40"))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("4.40", unite.single().price?.amount)
    }

    /**
     * Il rovescio: se a non vendere e' Trenord, il prezzo del sito vale anche
     * essendo il piu' caro. Altrimenti la riga direbbe «vendita chiusa» avendo
     * accanto un biglietto in vendita.
     */
    @Test
    fun `se a non vendere e' Trenord, vale il prezzo del sito`() {
        val unite = unisciSoluzioni(
            lefrecce = listOf(soluzione(JourneySource.LEFRECCE, Price("9.50"))),
            trenord = listOf(soluzione(JourneySource.TRENORD, Price("8.00", saleable = false))),
            departure = partenza,
            limit = 10,
        )

        assertEquals("9.50", unite.single().price?.amount)
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
