package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.CodiciTrenord
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordCrypto
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * La porta in chiaro di Trenord (`mgmt/store-management-api/mia/`) contro il BFF
 * cifrato, sui servizi veri. Vedi `TrenordApi.searchInChiaro`.
 *
 * Il sito da quella porta chiama solo le notizie delle direttrici: `train/` e
 * `hafas/v2` ci sono ma potrebbero sparire. Se succede, questo test lo dice, e
 * l'app intanto continua dal BFF. Le chiamate sono distanziate: il CDN di Trenord
 * risponde "Access Denied" a una raffica.
 */
class TrenordInChiaroLiveTest {

    private val api = NetworkModule.trenordApi
    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val brescia = Station("S01717", 830001717, "Brescia")
    private val domani = LocalDateTime.now().plusDays(1).withHour(9).withMinute(0)

    private fun giorno(d: LocalDateTime) = d.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
    private fun ora(d: LocalDateTime) = d.format(DateTimeFormatter.ofPattern("HH:mm"))

    @Test
    fun `la porta in chiaro da' le stesse soluzioni e la stessa corsa del BFF`() = runBlocking {
        val orig = CodiciTrenord.hafas(milano.rfiCode)!!
        val dest = CodiciTrenord.hafas(brescia.rfiCode)!!
        val chiaro = runCatching {
            NetworkModule.json.decodeFromString(TrenordSearchDto.serializer(), api.searchInChiaro(orig, dest, giorno(domani), ora(domani)).string())
        }.getOrNull()
        delay(4_000)
        val cifrato = NetworkModule.json.decodeFromString(
            TrenordSearchDto.serializer(),
            TrenordCrypto.decrypt(api.search(orig, dest, giorno(domani), ora(domani)).bytes())!!,
        )
        assertTrue(
            "la porta in chiaro di hafas/v2 non risponde piu': l'app va avanti col BFF, ma va saputo",
            chiaro != null && chiaro.solutions.isNotEmpty(),
        )
        fun firma(d: TrenordSearchDto) = d.solutions.map { s ->
            s.departureTime + " " + s.journeys.mapNotNull { it.train?.id }.joinToString("+")
        }
        println("  chiaro:  ${firma(chiaro!!)}")
        println("  cifrato: ${firma(cifrato)}")
        assertEquals(firma(cifrato), firma(chiaro))

        val numero = cifrato.solutions.firstNotNullOfOrNull { s -> s.journeys.firstNotNullOfOrNull { it.train?.id } }
        assumeTrue("nessun treno nella ricerca di domani", numero != null)
        delay(4_000)
        val data = domani.toLocalDate().toString()
        val corsaChiaro = api.trainInChiaro(numero!!, data).string()
        delay(4_000)
        val corsaCifrata = TrenordCrypto.decrypt(api.train(numero, data).bytes())!!
        val fermate = Regex("\"station_id\"")
        println("  $numero del $data: ${fermate.findAll(corsaChiaro).count()} fermate in chiaro, ${fermate.findAll(corsaCifrata).count()} dal BFF")
        assertEquals(fermate.findAll(corsaCifrata).count(), fermate.findAll(corsaChiaro).count())
    }

    /** Senza la porta in chiaro il repository risponde lo stesso, dal BFF. */
    @Test
    fun `se la porta in chiaro muore resta il BFF`() = runBlocking {
        val senzaChiaro = object : TrenordApi by api {
            override suspend fun searchInChiaro(
                origin: String, destination: String, departureDate: String, departureHour: String,
                products: String, transfers: Int, liveData: Boolean, withRoutes: Boolean, language: String,
            ): ResponseBody = throw IOException("porta in chiaro sparita")

            override suspend fun trainInChiaro(trainId: String, date: String?): ResponseBody =
                "<html>Access Denied</html>".toResponseBody("text/html".toMediaType())
        }
        val repo = TrenordRepository(senzaChiaro, NetworkModule.json)
        val esito = repo.search(milano, brescia, domani)
        println("  dal solo BFF: ${esito.journeys.size} soluzioni")
        assertTrue("senza porta in chiaro la ricerca deve arrivare dal BFF", esito.journeys.isNotEmpty())

        val numero = esito.journeys.first().legs.first { it.isTrain }.trainNumber!!
        delay(4_000)
        val corsa = repo.trainStatus(numero, LocalDate.now().plusDays(1))
        println("  corsa $numero dal solo BFF: ${corsa?.stops?.size} fermate")
        assertTrue("una risposta in chiaro illeggibile deve far passare al BFF", (corsa?.stops?.size ?: 0) > 1)
    }
}
