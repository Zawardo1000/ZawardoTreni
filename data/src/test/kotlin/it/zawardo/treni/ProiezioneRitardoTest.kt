package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.StopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * La stima sulle fermate ancora da fare e' il ritardo dell'ultimo rilevamento.
 *
 * Anche quando quel numero sembra sbagliato. Il REG 2813 del 31/08/2026 era
 * ripartito da Lecco alle 06:54, un minuto tardi, e la corsa portava
 * `ritardo = -3`: le quattro fermate successive uscivano tutte "3 min in
 * anticipo", che a leggerle accanto al +1 di Lecco non tornano.
 *
 * Tornano invece guardando **dove** quel -3 e' stato misurato. Contate il
 * 31/08/2026 su 22 corse in circolazione, in tredici il punto di rilevamento
 * non era una fermata della corsa ma un posto di controllo o un bivio —
 * "PC RUBIERA", "1° BIVIO CHIUSI SUD", "BV/PC SETTEBAGNI" — dove il confronto e'
 * con un orario di transito. E' la misura piu' fresca che esista di quel treno,
 * piu' fresca dell'ultima fermata, ed e' quella giusta da portare avanti: e'
 * solo che va detto da dove viene, e infatti la scheda lo scrive sotto il
 * numero.
 *
 * Questo test sta qui perche' la tentazione di "correggere" quel -3 e'
 * ricorrente — smussarlo sull'ultima partenza rilevata, o vietare gli anticipi
 * — e ogni smussatura butta via l'unico dato piu' recente che si abbia.
 */
class ProiezioneRitardoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 8, 31)

    private fun millis(ora: String): Long =
        giorno.atTime(LocalTime.parse(ora)).atZone(roma).toInstant().toEpochMilli()

    private fun fermata(
        n: Int,
        nome: String,
        arrivo: String?,
        partenza: String?,
        fatta: Boolean,
        ritardoArrivo: Int = 0,
        ritardoPartenza: Int = 0,
    ) = FermataDto(
        stazione = nome,
        id = "S0000$n",
        progressivo = n,
        actualFermataType = if (fatta) 1 else 0,
        arrivoTeorico = arrivo?.let { millis(it) },
        arrivoReale = if (fatta) arrivo?.let { millis(it) + ritardoArrivo * 60_000L } else null,
        ritardoArrivo = if (fatta) ritardoArrivo else 0,
        partenzaTeorica = partenza?.let { millis(it) },
        partenzaReale = if (fatta) partenza?.let { millis(it) + ritardoPartenza * 60_000L } else null,
        ritardoPartenza = if (fatta) ritardoPartenza else 0,
    )

    /** Il 2813 com'era alle 06:58: oltre Lecco, con le ultime quattro da fare. */
    private fun duemilaOttocentoTredici(ritardo: Int) = AndamentoTrenoDto(
        numeroTreno = 2813,
        categoria = "REG",
        origine = "SONDRIO",
        destinazione = "MILANO CENTRALE",
        ritardo = ritardo,
        stazioneUltimoRilevamento = "LECCO MAGGIANICO",
        oraUltimoRilevamento = millis("06:56"),
        fermate = listOf(
            fermata(1, "MANDELLO DEL LARIO", "06:36", "06:37", fatta = true, ritardoArrivo = -5),
            fermata(2, "LECCO", "06:51", "06:53", fatta = true, ritardoArrivo = -5, ritardoPartenza = 1),
            fermata(3, "CALOLZIOCORTE OLGINATE", "07:02", "07:03", fatta = false),
            fermata(4, "CARNATE USMATE", "07:16", "07:17", fatta = false),
            fermata(5, "MONZA", "07:26", "07:27", fatta = false),
            fermata(6, "MILANO CENTRALE", "07:40", null, fatta = false),
        ),
    )

    @Test
    fun `le fermate da fare portano il ritardo dell'ultimo rilevamento, anticipo compreso`() {
        val stato = duemilaOttocentoTredici(ritardo = -3).toTrainStatus()
        val future = stato.stops.filter { it.status == StopStatus.FUTURE }

        assertEquals("le quattro fermate dopo Lecco sono da fare", 4, future.size)
        future.forEach {
            assertEquals(
                "${it.stationName}: la misura piu' fresca e' -3, e non si smussa",
                -3,
                it.arrivalDelayMinutes,
            )
        }
        assertEquals(
            "Calolziocorte: 07:02 di tabella meno i tre minuti misurati",
            giorno.atTime(6, 59),
            future.first().projectedArrival,
        )
        assertEquals(
            "il rilevamento va nominato, o quel -3 resta senza spiegazione",
            "LECCO MAGGIANICO",
            stato.lastDetectionStation,
        )
        assertEquals(giorno.atTime(6, 56), stato.lastDetectionTime)
    }

    @Test
    fun `sulle fermate gia' fatte restano le misure loro`() {
        val stato = duemilaOttocentoTredici(ritardo = -3).toTrainStatus()
        val lecco = stato.stops.first { it.stationName == "LECCO" }

        assertEquals("Lecco e' arrivata a -5 e quello resta", -5, lecco.arrivalDelayMinutes)
        assertEquals("e ne e' ripartita a +1, che pure resta", 1, lecco.departureDelayMinutes)
        assertTrue(
            "il passato e' misurato, non proiettato",
            lecco.projectedArrival == null && lecco.projectedDeparture == null,
        )
    }

    @Test
    fun `senza ritardo non si tocca l'orario di tabella`() {
        val future = duemilaOttocentoTredici(ritardo = 0).toTrainStatus()
            .stops.filter { it.status == StopStatus.FUTURE }

        future.forEach {
            assertTrue(
                "${it.stationName}: a zero non c'e' niente da proiettare",
                it.projectedArrival == null && it.projectedDeparture == null,
            )
        }
    }

    @Test
    fun `un treno che accumula ritardo lo porta fino in fondo`() {
        val future = duemilaOttocentoTredici(ritardo = 20).toTrainStatus()
            .stops.filter { it.status == StopStatus.FUTURE }

        assertTrue("nessuna fermata futura", future.isNotEmpty())
        future.forEach {
            assertEquals("${it.stationName}: venti minuti si portano avanti", 20, it.arrivalDelayMinutes)
            assertEquals(
                "${it.stationName}: e l'orario si sposta di altrettanto",
                it.scheduledArrival?.plusMinutes(20),
                it.projectedArrival,
            )
        }
    }
}
