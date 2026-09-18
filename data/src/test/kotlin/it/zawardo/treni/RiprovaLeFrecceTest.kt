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
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDateTime

/**
 * Le Frecce che si inceppa, e come la ricerca lo tratta.
 *
 * Le firme vengono dalle 190 ricerche del 18/09/2026: il guasto e' un 500 su
 * `/solutions` dopo una `/search` riuscita; il vuoto vero e' `totalSolutions` a
 * zero e `/solutions` a lista vuota; una stazione sbagliata e' un 400.
 */
class RiprovaLeFrecceTest {

    private val da = Station("S01700", 830001700, "Milano Centrale")
    private val a = Station("S01717", 830001717, "Brescia")
    private val quando = LocalDateTime.of(2026, 9, 19, 8, 0)

    private val soluzione = SolutionDto(
        departureTime = "2026-09-19T08:15:00.000+02:00",
        arrivalTime = "2026-09-19T08:51:00.000+02:00",
        solutionNodes = listOf(
            SolutionNodeDto(
                type = "SOLUTION_SEGMENT",
                departureTime = "2026-09-19T08:15:00.000+02:00",
                arrivalTime = "2026-09-19T08:51:00.000+02:00",
                startLocation = LocationDto(locationId = 830001700, name = "Milano Centrale", bdoCode = "S01700"),
                endLocation = LocationDto(locationId = 830001717, name = "Brescia", bdoCode = "S01717"),
                offeredTransportMeanDeparture = TransportMeanDto(
                    name = "9709",
                    classification = ClassificationDto(acronym = "FR", type = "TRAIN"),
                ),
            ),
        ),
    )

    private fun errore(codice: Int) = HttpException(Response.error<Any>(codice, "".toResponseBody()))

    /** Un BFF che risponde come gli si dice, una `/solutions` alla volta. */
    private class BffFinto(
        private val dichiarate: Int,
        risposte: List<() -> List<SolutionDto>>,
        private val search: () -> SearchResponseDto = { SearchResponseDto("id", dichiarate) },
    ) : LefrecceApi {
        private val coda = ArrayDeque(risposte)
        var chiamate = 0

        override suspend fun locations(name: String, limit: Int, multi: Boolean, zonaFrecce: Boolean) =
            error("non serve")

        override suspend fun closest(lat: Double, lon: Double, withBdo: Boolean) = error("non serve")

        override suspend fun search(
            startLocationId: Long, endLocationId: Long, departureTime: String, arFlag: String,
            adults: Int, children: Int, direction: String, frecce: Boolean, regional: Boolean,
            intercity: Boolean,
        ) = search()

        override suspend fun solutions(searchId: String, offset: Int, limit: Int): List<SolutionDto> {
            chiamate++
            return coda.removeFirst()()
        }

        // Il sito qui non c'entra: risponde vuoto, e i prezzi restano quelli dell'app.
        override suspend fun soluzioniDelSito(richiesta: RichiestaSito) = RispostaSito()
    }

    @Test
    fun `un 500 si rifa' dopo due secondi, e la ricerca riesce`() = runTest {
        val bff = BffFinto(60, listOf({ throw errore(500) }, { listOf(soluzione) }))
        val viaggi = JourneyRepository(bff).search(da, a, quando)
        assertEquals(1, viaggi.size)
        assertEquals(2, bff.chiamate)
        assertEquals("rifarla subito riesce una volta su cinque", 2_000L, currentTime)
    }

    @Test
    fun `dopo tre guasti di fila la ricerca si arrende`() = runTest {
        val bff = BffFinto(60, List(3) { { throw errore(500) } })
        try {
            JourneyRepository(bff).search(da, a, quando)
            fail("tre 500 di fila non sono una tratta senza treni")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
        assertEquals(3, bff.chiamate)
        assertEquals("due secondi, poi cinque", 7_000L, currentTime)
    }

    @Test
    fun `una tratta senza treni non si rifa'`() = runTest {
        val bff = BffFinto(0, listOf({ emptyList() }))
        assertTrue(JourneyRepository(bff).search(da, a, quando).isEmpty())
        assertEquals(1, bff.chiamate)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `soluzioni dichiarate e non arrivate sono un guasto`() = runTest {
        val bff = BffFinto(60, listOf({ emptyList() }, { listOf(soluzione) }))
        assertEquals(1, JourneyRepository(bff).search(da, a, quando).size)
        assertEquals(2, bff.chiamate)
    }

    @Test
    fun `una richiesta rifiutata non si rifa'`() = runTest {
        val bff = BffFinto(60, listOf({ throw errore(400) }))
        try {
            JourneyRepository(bff).search(da, a, quando)
            fail("un 400 e' una richiesta sbagliata")
        } catch (e: HttpException) {
            assertEquals(400, e.code())
        }
        assertEquals(1, bff.chiamate)
    }

    @Test
    fun `se Trenitalia non risponde lo si dice, se rifiuta no`() = runBlocking {
        // Qui le attese sono vere: searchAll lavora sul dispatcher di I/O.
        val guasto = JourneyRepository(BffFinto(60, List(3) { { throw errore(503) } }))
            .searchAll(da, a, quando, sources = setOf(DataSource.TRENITALIA))
        assertTrue(guasto.journeys.isEmpty())
        assertTrue("un guasto va detto", guasto.nazionaleNonRisponde)

        val rifiuto = JourneyRepository(BffFinto(60, listOf({ throw errore(400) })))
            .searchAll(da, a, quando, sources = setOf(DataSource.TRENITALIA))
        assertFalse("una stazione sbagliata non e' un guasto del servizio", rifiuto.nazionaleNonRisponde)
    }
}
