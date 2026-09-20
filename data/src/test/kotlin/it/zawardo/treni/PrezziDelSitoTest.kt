package it.zawardo.treni

import it.zawardo.treni.data.remote.lefrecce.ClassificationDto
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.PrezzoSito
import it.zawardo.treni.data.remote.lefrecce.RichiestaSito
import it.zawardo.treni.data.remote.lefrecce.RispostaSito
import it.zawardo.treni.data.remote.lefrecce.SearchResponseDto
import it.zawardo.treni.data.remote.lefrecce.SoluzioneSito
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.lefrecce.TransportMeanDto
import it.zawardo.treni.data.remote.lefrecce.TrenoSito
import it.zawardo.treni.data.remote.lefrecce.VoceSito
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime

/**
 * Le soluzioni dall'app, i prezzi dal sito: vedi `LefrecceApi.soluzioniDelSito`.
 *
 * Le due risposte del 18/09/2026 alle 11:00, Milano Centrale - Brescia, ridotte a
 * cio' che conta: dall'app il RE 2623 e «Urbano › RE 10925» senza prezzo, dal sito
 * tutti e due a 8,40 euro.
 */
class PrezziDelSitoTest {

    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val brescia = Station("S01717", 830001717, "Brescia")
    private val quando = LocalDateTime.of(2026, 9, 19, 11, 0)

    private fun luogo(nome: String, codice: String) = LocationDto(locationId = 1, name = nome, bdoCode = codice)

    private fun tratta(numero: String, sigla: String, tipo: String, da: String, a: String, dalle: String, alle: String) =
        SolutionNodeDto(
            type = "SOLUTION_SEGMENT",
            departureTime = "2026-09-19T$dalle:00.000+02:00",
            arrivalTime = "2026-09-19T$alle:00.000+02:00",
            startLocation = luogo(da, "S01700"),
            endLocation = luogo(a, "S01717"),
            offeredTransportMeanDeparture = TransportMeanDto(
                name = numero,
                classification = ClassificationDto(acronym = sigla, type = tipo),
            ),
        )

    private fun soluzione(dalle: String, alle: String, vararg tratte: SolutionNodeDto) = SolutionDto(
        departureTime = "2026-09-19T$dalle:00.000+02:00",
        arrivalTime = "2026-09-19T$alle:00.000+02:00",
        solutionNodes = tratte.toList(),
    )

    private val dellApp = listOf(
        soluzione("11:25", "12:31", tratta("2623", "RE", "TRAIN", "Milano Centrale", "Brescia", "11:25", "12:31")),
        soluzione(
            "11:36", "13:13",
            tratta("Urb", "UB", "UNCLASSIFIED", "Milano Centrale", "Milano Lambrate", "11:36", "11:56"),
            tratta("10925", "RE", "TRAIN", "Milano Lambrate", "Brescia", "12:01", "13:13"),
        ),
    )

    private val delSito = listOf(
        SoluzioneSito(
            departureTime = "2026-09-19T11:25:00.000+02:00", status = "SALEABLE",
            trains = listOf(TrenoSito("RE", "2623")), price = PrezzoSito(8.4),
        ),
        SoluzioneSito(
            departureTime = "2026-09-19T11:36:00.000+02:00", status = "SALEABLE",
            trains = listOf(TrenoSito("UB", null, urban = true), TrenoSito("RE", "10925")),
            price = PrezzoSito(8.4),
        ),
    )

    private class BffFinto(
        private val app: List<SolutionDto>,
        private val sito: () -> List<SoluzioneSito>,
    ) : LefrecceApi {
        override suspend fun locations(name: String, limit: Int, multi: Boolean, zonaFrecce: Boolean) =
            error("non serve")

        override suspend fun closest(lat: Double, lon: Double, withBdo: Boolean) = error("non serve")

        override suspend fun search(
            startLocationId: Long, endLocationId: Long, departureTime: String, arFlag: String,
            adults: Int, children: Int, direction: String, frecce: Boolean, regional: Boolean,
            intercity: Boolean,
        ) = SearchResponseDto("id", app.size)

        override suspend fun solutions(searchId: String, offset: Int, limit: Int) = app

        override suspend fun soluzioniDelSito(richiesta: RichiestaSito) =
            RispostaSito(sito().map { VoceSito(it) })

        override suspend fun fermateDelSito(cartId: String, solutionId: String) =
            emptyList<it.zawardo.treni.data.remote.lefrecce.TrattaDelSito>()
    }

    @Test
    fun `i prezzi che l'app non ha li porta il sito`() = runBlocking {
        val viaggi = JourneyRepository(BffFinto(dellApp) { delSito }).search(milano, brescia, quando)
        assertEquals(listOf("8.40", "8.40"), viaggi.map { it.price?.amount })
    }

    @Test
    fun `il tratto urbano non conta per riconoscere la soluzione`() = runBlocking {
        val conUrbano = JourneyRepository(BffFinto(dellApp) { delSito }).search(milano, brescia, quando)
            .single { it.legs.size == 2 }
        assertEquals("8.40", conUrbano.price?.amount)
    }

    @Test
    fun `un prezzo che l'app ha gia' resta il suo`() = runBlocking {
        val conPrezzo = dellApp.first().copy(totalPrice = "9.99")
        val viaggi = JourneyRepository(BffFinto(listOf(conPrezzo)) { delSito }).search(milano, brescia, quando)
        assertEquals("9.99", viaggi.single().price?.amount)
    }

    @Test
    fun `il sito che non risponde non toglie niente`() = runBlocking {
        val viaggi = JourneyRepository(BffFinto(dellApp) { throw IOException("giu'") }).search(milano, brescia, quando)
        assertEquals("le soluzioni restano", 2, viaggi.size)
        viaggi.forEach { assertNull(it.price) }
    }

    /**
     * Esaurito e non vendibile non sono la stessa cosa. Il 19/09/2026 il sito
     * dava `SOLD_OUT` a «Urbano › RE 36 › Urbano › FR 9723» da Varese; e
     * `NOT_SALEABLE` dice che quel biglietto adesso non si vende, non che i posti
     * siano finiti. L'app scriveva «esaurito» su tutti e due.
     */
    @Test
    fun `esaurito solo quando il sito dice SOLD_OUT`() = runBlocking {
        fun conStato(stato: String) = JourneyRepository(BffFinto(dellApp) { delSito.map { it.copy(status = stato) } })
        val esauriti = conStato("SOLD_OUT").search(milano, brescia, quando).map { it.price!! }
        assertTrue(esauriti.all { !it.saleable && it.esaurito })
        val nonInVendita = conStato("NOT_SALEABLE").search(milano, brescia, quando).map { it.price!! }
        assertTrue(nonInVendita.all { !it.saleable && !it.esaurito })
    }
}
