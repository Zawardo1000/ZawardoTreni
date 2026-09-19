package it.zawardo.treni

import it.zawardo.treni.data.remote.lefrecce.ClassificationDto
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.RichiestaSito
import it.zawardo.treni.data.remote.lefrecce.RispostaSito
import it.zawardo.treni.data.remote.lefrecce.SearchResponseDto
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.lefrecce.TransportMeanDto
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * Le soluzioni di Le Frecce si chiedono quante ne servono, e un'altra pagina
 * solo se mancano. Il 19/09/2026 `/solutions` impiegava 3,4 secondi per 10
 * soluzioni e 7-9 per 24: la quantita' chiesta decideva l'attesa della lista.
 * Vedi `JourneyRepository.unaRicercaLeFrecce`.
 */
class PaginazioneLeFrecceTest {

    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val brescia = Station("S01717", 830001717, "Brescia")
    private val quando = LocalDateTime.of(2026, 9, 19, 11, 0)

    private fun soluzione(minuti: Int, usabile: Boolean = true): SolutionDto {
        val dalle = "2026-09-19T%02d:%02d:00.000+02:00".format(11 + minuti / 60, minuti % 60)
        val alle = "2026-09-19T%02d:%02d:00.000+02:00".format(12 + minuti / 60, minuti % 60)
        val tratta = SolutionNodeDto(
            type = "SOLUTION_SEGMENT",
            departureTime = dalle,
            arrivalTime = alle,
            startLocation = LocationDto(locationId = 1, name = "Milano Centrale", bdoCode = "S01700"),
            endLocation = LocationDto(locationId = 2, name = "Brescia", bdoCode = "S01717"),
            offeredTransportMeanDeparture = TransportMeanDto(
                name = (2600 + minuti).toString(),
                classification = ClassificationDto(acronym = "RE", type = "TRAIN"),
            ),
        )
        return SolutionDto(
            departureTime = dalle,
            arrivalTime = alle,
            solutionNodes = if (usabile) listOf(tratta) else emptyList(),
        )
    }

    /** Un BFF che rispetta `offset`, come quello vero, e conta le pagine chieste. */
    private class BffPaginato(private val tutte: List<SolutionDto>) : LefrecceApi {
        val pagine = mutableListOf<Pair<Int, Int>>()

        override suspend fun locations(name: String, limit: Int, multi: Boolean, zonaFrecce: Boolean) =
            error("non serve")

        override suspend fun closest(lat: Double, lon: Double, withBdo: Boolean) = error("non serve")

        override suspend fun search(
            startLocationId: Long, endLocationId: Long, departureTime: String, arFlag: String,
            adults: Int, children: Int, direction: String, frecce: Boolean, regional: Boolean,
            intercity: Boolean,
        ) = SearchResponseDto("id", tutte.size)

        override suspend fun solutions(searchId: String, offset: Int, limit: Int): List<SolutionDto> {
            pagine += offset to limit
            return tutte.drop(offset).take(limit)
        }

        override suspend fun soluzioniDelSito(richiesta: RichiestaSito) = RispostaSito(emptyList())
    }

    @Test
    fun `se sono tutte utilizzabili basta una pagina`() = runBlocking {
        val bff = BffPaginato((0 until 40).map { soluzione(it) })
        val viaggi = JourneyRepository(bff).search(milano, brescia, quando, limit = 8)
        assertEquals(8, viaggi.size)
        assertEquals(listOf(0 to 8), bff.pagine)
    }

    @Test
    fun `se qualcuna si scarta si chiede la pagina dopo`() = runBlocking {
        val tutte = (0 until 40).map { soluzione(it, usabile = it % 4 != 0) }
        val bff = BffPaginato(tutte)
        val viaggi = JourneyRepository(bff).search(milano, brescia, quando, limit = 8)
        assertEquals(8, viaggi.size)
        assertEquals(listOf(0 to 8, 8 to 8), bff.pagine)
    }

    @Test
    fun `mai oltre il tetto di prima, anche se non bastano`() = runBlocking {
        val tutte = (0 until 40).map { soluzione(it, usabile = it % 8 == 0) }
        val bff = BffPaginato(tutte)
        val viaggi = JourneyRepository(bff).search(milano, brescia, quando, limit = 8)
        assertEquals("tre pagine da otto: una usabile ciascuna", 3, viaggi.size)
        assertEquals(listOf(0 to 8, 8 to 8, 16 to 8), bff.pagine)
    }
}
