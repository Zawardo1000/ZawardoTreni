package it.zawardo.treni

import it.zawardo.treni.data.remote.lefrecce.ClassificationDto
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.MessaggioSito
import it.zawardo.treni.data.remote.lefrecce.NodoSito
import it.zawardo.treni.data.remote.lefrecce.PrezzoSito
import it.zawardo.treni.data.remote.lefrecce.RichiestaSito
import it.zawardo.treni.data.remote.lefrecce.RispostaSito
import it.zawardo.treni.data.remote.lefrecce.SearchResponseDto
import it.zawardo.treni.data.remote.lefrecce.SoluzioneSito
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.lefrecce.TransportMeanDto
import it.zawardo.treni.data.remote.lefrecce.TrattaDelSito
import it.zawardo.treni.data.remote.lefrecce.TrenoSito
import it.zawardo.treni.data.remote.lefrecce.VoceSito
import it.zawardo.treni.data.remote.lefrecce.avvisiDelSito
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * I messaggi delle soluzioni del sito di Le Frecce sulla scheda (`avvisiDelSito`).
 * I testi sono quelli censiti il 19/09/2026 (`data/fonti/LEFRECCE.md`).
 */
class MessaggiDelSitoTest {

    private fun info(testo: String, icona: String? = null) = MessaggioSito("INFO", icona, testo)
    private fun avviso(testo: String, icona: String?) = MessaggioSito("WARNING", icona, testo)

    private val convogli = "Il treno 721 è composto da due convogli non comunicanti tra loro (carrozze da 1 a 4 e " +
        "carrozze da 11 a 14). Verifica sul biglietto la tua carrozza e sali direttamente su quella indicata."

    @Test
    fun `restano quelli che la scheda non dice gia'`() {
        assertEquals(
            listOf("Il treno non effettua servizio viaggiatori.", "Posti Esauriti sul treno 9588.", convogli),
            avvisiDelSito(
                listOf(
                    info("Il treno non effettua servizio viaggiatori"),
                    info("Posti Esauriti sul treno 9588. Soluzione non acquistabile."),
                    avviso(convogli, "attention"),
                ),
            ),
        )
    }

    @Test
    fun `fuori il giorno dopo, l'area family, il mezzo urbano e la sola vendita`() {
        assertEquals(
            emptyList<String>(),
            avvisiDelSito(
                listOf(
                    avviso("La soluzione fa riferimento al giorno successivo", "calendar"),
                    avviso("L'orario di arrivo fa riferimento al giorno successivo", "calendar"),
                    avviso("Area Family presente in carrozza 3 su InterCity 1545", "family"),
                    info("Mezzo Urbano (non incluso nel prezzo) da Roma Ostiense a Roma Termini", "UB"),
                    info("Soluzione non acquistabile"),
                    info("Soluzione temporaneamente non acquistabile"),
                    info("Soluzione non acquistabile al momento per la data selezionata"),
                    info("Le soluzioni di viaggio regionali non sono vendibili con anticipo inferiore ai 5 minuti"),
                    info("Impossibile acquistare un viaggio precedente alla data corrente."),
                    MessaggioSito(),
                    info("   "),
                ),
            ),
        )
    }

    @Test
    fun `lo stesso messaggio due volte si dice una volta`() {
        val esaurito = info("Posti Esauriti sul treno 9588. Soluzione non acquistabile.")
        assertEquals(listOf("Posti Esauriti sul treno 9588."), avvisiDelSito(listOf(esaurito, esaurito)))
    }

    @Test
    fun `un punto dentro un nome non spezza la frase`() {
        val testo = "Il treno 9716 da Venezia S. Lucia non effettua servizio viaggiatori fino a Mestre."
        assertEquals(listOf(testo), avvisiDelSito(listOf(info(testo))))
    }

    /** La forma vera: i messaggi accanto alla soluzione, e fra loro un null. */
    @Test
    fun `la risposta del sito si legge com'e', null compresi`() {
        val risposta = Json { ignoreUnknownKeys = true }.decodeFromString<RispostaSito>(
            """
            {"searchId":"x","cartId":"c","highlightedMessages":[],"solutions":[
              {"solution":{"_type":"x","id":"s1","status":"NOT_SALEABLE","trains":[{"acronym":"FR","name":"9716"}],"nodes":[]},
               "messages":[{"imageId":null,"message":"Il treno non effettua servizio viaggiatori","status":"INFO"},null],
               "grids":[],"nextDaySolution":false},
              {"solution":{"id":"s2","status":"SALEABLE"},"messages":[null]}
            ]}
            """.trimIndent(),
        )
        val prima = risposta.solutions.first()
        assertEquals(2, prima.messages.size)
        assertEquals(
            listOf("Il treno non effettua servizio viaggiatori."),
            avvisiDelSito(prima.messages.filterNotNull()),
        )
        assertEquals(emptyList<String>(), avvisiDelSito(risposta.solutions[1].messages.filterNotNull()))
    }

    // --- Dalla ricerca: sia quando risponde il sito, sia quando serve l'app.

    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val brescia = Station("S01717", 830001717, "Brescia")
    private val quando = LocalDateTime.of(2026, 9, 19, 11, 0)
    private val esaurito = info("Posti Esauriti sul treno 2623. Soluzione non acquistabile.")

    private fun alle(ora: String) = "2026-09-19T$ora:00.000+02:00"

    private val delSito = listOf(
        VoceSito(
            SoluzioneSito(
                departureTime = alle("11:25"), arrivalTime = alle("12:31"), status = "SOLD_OUT",
                trains = listOf(TrenoSito("RE", "2623")), price = PrezzoSito(8.4),
                nodes = listOf(
                    NodoSito(
                        origin = "Milano Centrale", destination = "Brescia", bdoOrigin = "S01700",
                        departureTime = alle("11:25"), arrivalTime = alle("12:31"), train = TrenoSito("RE", "2623"),
                    ),
                ),
            ),
            messages = listOf(null, esaurito, avviso("La soluzione fa riferimento al giorno successivo", "calendar")),
        ),
    )

    private val dellApp = listOf(
        SolutionDto(
            departureTime = alle("11:25"),
            arrivalTime = alle("12:31"),
            totalPrice = "8.40",
            solutionNodes = listOf(
                SolutionNodeDto(
                    type = "SOLUTION_SEGMENT",
                    departureTime = alle("11:25"),
                    arrivalTime = alle("12:31"),
                    startLocation = LocationDto(locationId = 1, name = "Milano Centrale", bdoCode = "S01700"),
                    endLocation = LocationDto(locationId = 2, name = "Brescia", bdoCode = "S01717"),
                    offeredTransportMeanDeparture = TransportMeanDto(
                        name = "2623",
                        classification = ClassificationDto(acronym = "RE", type = "TRAIN"),
                    ),
                ),
            ),
        ),
    )

    private class BffFinto(
        private val app: List<SolutionDto>,
        private val sito: List<VoceSito>,
    ) : LefrecceApi {
        override suspend fun locations(name: String, limit: Int, multi: Boolean, zonaFrecce: Boolean) = error("non serve")
        override suspend fun closest(lat: Double, lon: Double, withBdo: Boolean) = error("non serve")
        override suspend fun search(
            startLocationId: Long, endLocationId: Long, departureTime: String, arFlag: String,
            adults: Int, children: Int, direction: String, frecce: Boolean, regional: Boolean,
            intercity: Boolean,
        ) = SearchResponseDto("id", app.size)

        override suspend fun solutions(searchId: String, offset: Int, limit: Int) = app
        override suspend fun soluzioniDelSito(richiesta: RichiestaSito) = RispostaSito(sito)
        override suspend fun fermateDelSito(cartId: String, solutionId: String) = emptyList<TrattaDelSito>()
    }

    @Test
    fun `dal sito, il messaggio arriva sulla soluzione`() = runBlocking {
        val viaggio = JourneyRepository(BffFinto(emptyList(), delSito)).search(milano, brescia, quando).single()
        assertEquals("2623", viaggio.legs.single().trainNumber)
        assertEquals(listOf("Posti Esauriti sul treno 2623."), viaggio.avvisi)
    }

    /**
     * Quando le soluzioni vengono dall'app, i messaggi del sito si accoppiano
     * come i prezzi, anche se i prezzi l'app li aveva gia' tutti: prima, in quel
     * caso, il sito non si guardava affatto.
     */
    @Test
    fun `dall'app, il messaggio del sito arriva lo stesso, anche coi prezzi gia' tutti`() = runBlocking {
        // Senza tratte il sito non basta da solo: risponde l'app.
        val sitoMonco = delSito.map { it.copy(solution = it.solution!!.copy(nodes = emptyList())) }
        val viaggio = JourneyRepository(BffFinto(dellApp, sitoMonco)).search(milano, brescia, quando).single()
        assertEquals("il prezzo resta quello dell'app", "8.40", viaggio.price?.amount)
        assertEquals(listOf("Posti Esauriti sul treno 2623."), viaggio.avvisi)
    }

    @Test
    fun `senza messaggi, niente avvisi`() = runBlocking {
        val muto = delSito.map { it.copy(messages = emptyList()) }
        val viaggio = JourneyRepository(BffFinto(emptyList(), muto)).search(milano, brescia, quando).single()
        assertTrue(viaggio.avvisi.isEmpty())
    }
}
