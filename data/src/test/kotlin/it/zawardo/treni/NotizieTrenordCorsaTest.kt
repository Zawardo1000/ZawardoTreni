package it.zawardo.treni

import it.zawardo.treni.data.remote.trenord.DirettriceDto
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordStationDetailsDto
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.NotaSmartCaringDto
import it.zawardo.treni.data.remote.viaggiatreno.NotiziaInfomobilitaDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.Imprese
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate
import java.time.LocalTime

/**
 * Le notizie delle direttrici Trenord sulla corsa (`notizieDiTrenord`): a chi si
 * chiedono, e quante volte.
 */
class NotizieTrenordCorsaTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val direttrici = json.decodeFromString<List<DirettriceDto>>(
        javaClass.classLoader!!.getResource("direttrici-trenord-2026-09-19.json")!!.readText(),
    )
    private val domenica = LocalDate.of(2026, 9, 20)
    private val testo = "Il treno 20909 (ISEO 05:36 - BRESCIA 06:13), il 20 settembre non ferma a Borgonato-Adro."

    private class FintoTrenord(private val risposta: () -> List<DirettriceDto>) : TrenordApi {
        var chieste = 0
        override suspend fun direttrici(): List<DirettriceDto> {
            chieste++
            return risposta()
        }

        override suspend fun search(
            origin: String, destination: String, departureDate: String, departureHour: String,
            products: String, transfers: Int, liveData: Boolean, withRoutes: Boolean, language: String,
        ): ResponseBody = error("non serve")

        override suspend fun train(trainId: String, date: String?): ResponseBody = error("non serve")
        override suspend fun searchInChiaro(
            origin: String, destination: String, departureDate: String, departureHour: String,
            products: String, transfers: Int, liveData: Boolean, withRoutes: Boolean, language: String,
        ): ResponseBody = error("non serve")

        override suspend fun trainInChiaro(trainId: String, date: String?): ResponseBody = error("non serve")
        override suspend fun stationDetails(mirCode: String, language: String, mxp: Boolean, mapZoom: Int): TrenordStationDetailsDto =
            error("non serve")
    }

    private object NienteViaggiaTreno : ViaggiaTrenoApi {
        override suspend fun andamentoTreno(originCode: String, trainNumber: String, departureDateMillis: Long): Response<AndamentoTrenoDto> = error("non serve")
        override suspend fun partenze(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun arrivi(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun cercaNumeroTreno(trainNumber: String): ResponseBody = error("non serve")
        override suspend fun infomobilita(): ResponseBody = error("non serve")
        override suspend fun notizieInfomobilita(): List<NotiziaInfomobilitaDto> = error("non serve")
        override suspend fun noteSmartCaring(numero: String, giorno: String): List<NotaSmartCaringDto> = error("non serve")
    }

    private fun repo(api: FintoTrenord) = TrainStatusRepository(NienteViaggiaTreno, TrenordRepository(api, json))

    private fun corsa(
        categoria: String? = "REG",
        impresa: Int? = Imprese.TRENORD,
        codici: List<String> = listOf("S02085", "S02087", "S02091"),
    ) = TrainStatus(
        number = "20909",
        category = categoria,
        label = listOfNotNull(categoria, "20909").joinToString(" "),
        origin = "Iseo",
        destination = "Brescia",
        delayMinutes = 0,
        state = TrainState.NOT_DEPARTED,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = listOf("Iseo" to "05:36", "Borgonato-Adro" to "05:43", "Brescia" to "06:13").mapIndexed { i, (nome, ora) ->
            Stop(i, nome, codici[i], null, null, 0, domenica.atTime(LocalTime.parse(ora)), null, 0, null, null, StopStatus.FUTURE)
        },
        realtime = false,
        impresa = impresa,
    )

    @Test
    fun `la notizia arriva sulla corsa Trenord di quel giorno`() = runBlocking {
        val api = FintoTrenord { direttrici }
        assertEquals(listOf(testo), repo(api).notizieDiTrenord(corsa(), domenica))
        // Anche quando la corsa non dice l'impresa: viene da Trenord o da Le Frecce.
        assertEquals(listOf(testo), repo(api).notizieDiTrenord(corsa(impresa = null), domenica))
    }

    @Test
    fun `non si chiede per chi Trenord non nomina`() = runBlocking {
        val api = FintoTrenord { direttrici }
        val r = repo(api)
        listOf(
            corsa(impresa = Imprese.REGIONALE_TRENITALIA),
            corsa(categoria = "FR", impresa = null),
            corsa(categoria = "EC", impresa = null),
            corsa(categoria = "Italo", impresa = null),
            corsa(impresa = null, codici = listOf("EAV1", "EAV2", "EAV3")),
        ).forEach { assertEquals(emptyList<String>(), r.notizieDiTrenord(it, domenica)) }
        assertEquals(0, api.chieste)
    }

    @Test
    fun `una lettura sola per tante corse, e se Trenord cade niente`() = runBlocking {
        val api = FintoTrenord { direttrici }
        val r = repo(api)
        repeat(4) { r.notizieDiTrenord(corsa(), domenica) }
        assertEquals(1, api.chieste)

        val caduto = FintoTrenord { throw java.io.IOException("giu'") }
        assertEquals(emptyList<String>(), repo(caduto).notizieDiTrenord(corsa(), domenica))
    }
}
