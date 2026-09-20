package it.zawardo.treni

import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.data.remote.viaggiatreno.NotaSmartCaringDto
import it.zawardo.treni.data.remote.viaggiatreno.NotiziaInfomobilitaDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.data.repository.TrainStatusRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

/**
 * Una corsa di una rete con stazioni proprie non si chiede a ViaggiaTreno.
 *
 * Il 20/09/2026, su 25 corse EAV prese a caso dall'orario imbarcato, 3 avevano
 * il numero di un treno RFI in circolazione: il 2093 da Voghera, il 5084 da
 * Salerno, il 5136 da Roma Ostiense. `cercaNumeroTreno` ne trovava una sola, e
 * `resolveFor` la restituiva senza guardare da dove si sale: il dettaglio
 * dell'EAV 2093 da Sorrento mostrava il regionale di Voghera, e nell'elenco la
 * riga ne prendeva ritardo e binario.
 */
class SalitaFuoriRfiTest {

    private val oggi = LocalDate.now(ZoneId.of("Europe/Rome"))
    private val mezzanotte = oggi.atStartOfDay(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()

    private class FintoViaggiaTreno(private val millis: Long) : ViaggiaTrenoApi {
        val corseChieste = mutableListOf<String>()
        val numeriCercati = mutableListOf<String>()

        override suspend fun cercaNumeroTreno(trainNumber: String): ResponseBody {
            numeriCercati += trainNumber
            // La forma vera: "2093 - VOGHERA|2093-S01807-<millis>".
            return "2093 - VOGHERA|2093-S01807-$millis".toResponseBody("text/plain".toMediaType())
        }

        override suspend fun andamentoTreno(
            originCode: String,
            trainNumber: String,
            departureDateMillis: Long,
        ): Response<AndamentoTrenoDto> {
            corseChieste += trainNumber
            return Response.success(
                AndamentoTrenoDto(
                    numeroTreno = 2093,
                    categoria = "REG",
                    origine = "VOGHERA",
                    destinazione = "MILANO CENTRALE",
                    fermate = listOf(
                        FermataDto(stazione = "VOGHERA", id = "S01807", progressivo = 1, tipoFermata = "P"),
                        FermataDto(stazione = "MILANO CENTRALE", id = "S01700", progressivo = 2, tipoFermata = "A"),
                    ),
                ),
            )
        }

        override suspend fun partenze(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun arrivi(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun infomobilita(): ResponseBody = error("non serve")
        override suspend fun notizieInfomobilita(): List<NotiziaInfomobilitaDto> = emptyList()
        override suspend fun noteSmartCaring(numero: String, giorno: String): List<NotaSmartCaringDto> = emptyList()
    }

    @Test
    fun `salendo da EAV, Ferrotramviaria o ARST non si interroga ViaggiaTreno`() = runBlocking {
        for (salita in listOf("EAV12", "FNB1110", "ARST22581", "eav1")) {
            val api = FintoViaggiaTreno(mezzanotte)
            val trains = TrainStatusRepository(api)
            assertNull(salita, trains.statusByNumber("2093", oggi, boardingCode = salita))
            assertNull(salita, trains.resolveFor("2093", oggi, boardingCode = salita))
            assertEquals("nessuna chiamata per $salita", emptyList<String>(), api.numeriCercati)
            assertEquals(emptyList<String>(), api.corseChieste)
        }
    }

    @Test
    fun `salendo da una stazione RFI non cambia niente`() = runBlocking {
        val api = FintoViaggiaTreno(mezzanotte)
        val trains = TrainStatusRepository(api)
        assertNotNull(trains.resolveFor("2093", oggi, boardingCode = "S01807"))
        assertNotNull(trains.statusByNumber("2093", oggi, boardingCode = "S01807"))
        // E senza sapere da dove si sale, come nella ricerca per numero.
        assertNotNull(trains.resolveFor("2093", oggi))
    }
}
