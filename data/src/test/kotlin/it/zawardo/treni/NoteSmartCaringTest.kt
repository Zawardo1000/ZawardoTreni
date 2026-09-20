package it.zawardo.treni

import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.NotaSmartCaringDto
import it.zawardo.treni.data.remote.viaggiatreno.NotiziaInfomobilitaDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.data.remote.viaggiatreno.TrenoSmartCaringDto
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.Imprese
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Le note SmartCaring di ViaggiaTreno sulla corsa (`conNoteDelGiorno`): il
 * perche' dei regionali Trenitalia. La nota vera e' quella del REG 20175 del
 * 19/09/2026, un guasto a un passaggio a livello fra Cecchina e Pavona, con le
 * sue 18 corse.
 */
class NoteSmartCaringTest {

    private val giorno = LocalDate.of(2026, 9, 19)
    private val json = Json { ignoreUnknownKeys = true }
    private val guasto = json.decodeFromString<List<NotaSmartCaringDto>>(
        javaClass.classLoader!!.getResource("smartcaring-20175-2026-09-19.json")!!.readText(),
    )
    private val testoGuasto = guasto.single().infoNote!!

    private fun ms(g: LocalDate) = g.atStartOfDay(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()

    private class FintoViaggiaTreno(private val note: (String, String) -> List<NotaSmartCaringDto>) : ViaggiaTrenoApi {
        val chieste = mutableListOf<Pair<String, String>>()

        override suspend fun noteSmartCaring(numero: String, giorno: String): List<NotaSmartCaringDto> {
            chieste += numero to giorno
            return note(numero, giorno)
        }

        override suspend fun andamentoTreno(originCode: String, trainNumber: String, departureDateMillis: Long): Response<AndamentoTrenoDto> = error("non serve")
        override suspend fun partenze(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun arrivi(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun cercaNumeroTreno(trainNumber: String): ResponseBody = error("non serve")
        override suspend fun infomobilita(): ResponseBody = error("non serve")
        override suspend fun notizieInfomobilita(): List<NotiziaInfomobilitaDto> = error("non serve")
    }

    private fun fermata(i: Int, nome: String, codice: String, ora: Int) = Stop(
        i, nome, codice, null, null, 0, LocalDateTime.of(2026, 9, 19, ora, 0), null, 0, null, null, StopStatus.FUTURE,
    )

    /** Il REG 20127 Roma Termini-Velletri: parte da Termini, S08409. */
    private fun corsa(
        numero: String = "20127",
        categoria: String? = "REG",
        impresa: Int? = Imprese.REGIONALE_TRENITALIA,
        fermate: List<Stop> = listOf(
            fermata(0, "Roma Termini", "S08409", 10),
            fermata(1, "Ciampino", "S08650", 11),
            fermata(2, "Velletri", "S08621", 12),
        ),
        avvisi: List<String> = emptyList(),
    ) = TrainStatus(
        number = numero,
        category = categoria,
        label = listOfNotNull(categoria, numero).joinToString(" "),
        origin = fermate.first().stationName,
        destination = fermate.last().stationName,
        delayMinutes = 20,
        state = TrainState.DELAYED,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = fermate,
        avvisi = avvisi,
        impresa = impresa,
    )

    @Test
    fun `il perche' di un regionale Trenitalia arriva sulla corsa`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val spiegata = TrainStatusRepository(api).conNoteDelGiorno(corsa(), giorno)
        assertEquals(listOf(testoGuasto), spiegata.avvisi)
        assertEquals(listOf("20127" to "2026-09-19"), api.chieste)
    }

    @Test
    fun `la nota va prima degli avvisi che c'erano, e non si ripete`() = runBlocking {
        val doppia = guasto + guasto.map { it.copy(id = 1) }
        val api = FintoViaggiaTreno { _, _ -> doppia }
        val spiegata = TrainStatusRepository(api).conNoteDelGiorno(corsa(avvisi = listOf("Circolazione rallentata")), giorno)
        assertEquals(listOf(testoGuasto, "Circolazione rallentata"), spiegata.avvisi)
    }

    @Test
    fun `lo stesso numero con un'altra origine non e' questa corsa`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val repo = TrainStatusRepository(api)
        // L'origine detta da chi chiama vince sulle fermate.
        assertEquals(emptyList<String>(), repo.conNoteDelGiorno(corsa(), giorno, origine = "S01700").avvisi)
        assertEquals(listOf(testoGuasto), repo.conNoteDelGiorno(corsa(), giorno, origine = "S08409").avvisi)
        // Senza origine: il treno della nota deve partire da una delle fermate.
        val altrove = corsa(fermate = listOf(fermata(0, "Milano Centrale", "S01700", 10), fermata(1, "Monza", "S01322", 11)))
        assertEquals(emptyList<String>(), repo.conNoteDelGiorno(altrove, giorno).avvisi)
    }

    @Test
    fun `una corsa letta a pezzi trova la nota con l'origine detta da chi chiama`() = runBlocking {
        // Da Ciampino in poi: Termini non e' fra le fermate, ma e' l'origine.
        val aPezzi = corsa(impresa = null, fermate = listOf(fermata(1, "Ciampino", "S08650", 11), fermata(2, "Velletri", "S08621", 12)))
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val repo = TrainStatusRepository(api)
        assertEquals(listOf(testoGuasto), repo.conNoteDelGiorno(aPezzi, giorno, origine = "S08409").avvisi)
    }

    @Test
    fun `una nota fuori dalla sua validita' non vale, anche se il server la desse`() = runBlocking {
        val scaduta = guasto.map { it.copy(startValidity = ms(giorno.minusDays(10)), endValidity = ms(giorno.minusDays(1))) }
        val futura = guasto.map { it.copy(startValidity = ms(giorno.plusDays(2)), endValidity = ms(giorno.plusDays(6))) }
        val inCorso = guasto.map { it.copy(startValidity = ms(giorno.minusDays(4)), endValidity = ms(giorno.plusDays(6))) }
        for ((note, attese) in listOf(scaduta to 0, futura to 0, inCorso to 1)) {
            val repo = TrainStatusRepository(FintoViaggiaTreno { _, _ -> note })
            assertEquals(attese, repo.conNoteDelGiorno(corsa(), giorno).avvisi.size)
        }
    }

    @Test
    fun `Frecce, Intercity, Trenord, DB-OBB e Italo non si chiedono`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val repo = TrainStatusRepository(api)
        val senza = listOf(
            corsa(categoria = "FR", impresa = Imprese.FRECCE),
            corsa(categoria = "", impresa = null).copy(label = " FR 9653"),
            corsa(categoria = "IC", impresa = Imprese.INTERCITY),
            corsa(categoria = "ICN", impresa = Imprese.INTERCITY),
            corsa(categoria = "REG", impresa = Imprese.TRENORD),
            corsa(categoria = "EC", impresa = Imprese.DB_OBB),
            corsa(categoria = "Italo", impresa = null),
        )
        senza.forEach { assertEquals(it.avvisi, repo.conNoteDelGiorno(it, giorno).avvisi) }
        assertEquals(emptyList<Pair<String, String>>(), api.chieste)
    }

    @Test
    fun `fuori dalla rete RFI non si chiede`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val eav = corsa(impresa = null, fermate = listOf(fermata(0, "Sorrento", "EAV1", 10), fermata(1, "Napoli Porta Nolana", "EAV62", 11)))
        TrainStatusRepository(api).conNoteDelGiorno(eav, giorno)
        assertEquals(emptyList<Pair<String, String>>(), api.chieste)
    }

    @Test
    fun `se il servizio non risponde la corsa resta com'era`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> throw java.io.IOException("rete caduta") }
        val prima = corsa(avvisi = listOf("Circolazione rallentata"))
        assertEquals(prima, TrainStatusRepository(api).conNoteDelGiorno(prima, giorno))
    }

    @Test
    fun `una lettura vale cinque minuti, per numero e giorno`() = runBlocking {
        val api = FintoViaggiaTreno { _, _ -> guasto }
        val repo = TrainStatusRepository(api)
        repeat(3) { repo.conNoteDelGiorno(corsa(), giorno) }
        repo.conNoteDelGiorno(corsa(), giorno.plusDays(1))
        assertEquals(listOf("20127" to "2026-09-19", "20127" to "2026-09-20"), api.chieste)
    }

    @Test
    fun `una nota il cui treno non ha origine vale per il numero`() = runBlocking {
        val senzaOrigine = listOf(NotaSmartCaringDto(id = 7, infoNote = "Lavori", trains = listOf(TrenoSmartCaringDto("20127", null))))
        val repo = TrainStatusRepository(FintoViaggiaTreno { _, _ -> senzaOrigine })
        assertEquals(listOf("Lavori"), repo.conNoteDelGiorno(corsa(), giorno).avvisi)
        // Ma non per un altro numero: la nota elenca le sue corse.
        assertEquals(emptyList<String>(), repo.conNoteDelGiorno(corsa(numero = "20129"), giorno).avvisi)
    }

    @Test
    fun `la nota vera elenca 18 corse, tutte col loro numero e la loro origine`() {
        val treni = guasto.single().trains
        assertEquals(18, treni.size)
        assertTrue(treni.all { !it.commercialTrainNumber.isNullOrBlank() && it.originCode!!.startsWith("S") })
    }
}
