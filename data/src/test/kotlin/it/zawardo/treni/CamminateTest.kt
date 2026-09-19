package it.zawardo.treni

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.lefrecce.ClassificationDto
import it.zawardo.treni.data.remote.lefrecce.LocationDto
import it.zawardo.treni.data.remote.lefrecce.SolutionDto
import it.zawardo.treni.data.remote.lefrecce.SolutionNodeDto
import it.zawardo.treni.data.remote.lefrecce.TransportMeanDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.domain.model.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

/**
 * Le camminate non sono un mezzo (deciso con l'utente il 19/09/2026).
 *
 * Fra due stazioni gemelle nello stesso luogo — la superficie di Porta Garibaldi
 * e il suo Passante — in testa o in coda al viaggio non si contano: la stazione
 * e' una sola, e il viaggio parte col treno. In un cambio restano, perche' il
 * loro tempo conta, ma il cambio e' uno: e si chiamano «a piedi», non «Urbano».
 */
class CamminateTest {

    private fun luogo(nome: String, codice: String) = LocationDto(locationId = 1, name = nome, bdoCode = codice)

    private fun nodo(nome: String, sigla: String, tipo: String, da: Pair<String, String>, a: Pair<String, String>, dalle: String, alle: String) =
        SolutionNodeDto(
            type = "SOLUTION_SEGMENT",
            departureTime = "2026-09-19T$dalle:00.000+02:00",
            arrivalTime = "2026-09-19T$alle:00.000+02:00",
            startLocation = luogo(da.first, da.second),
            endLocation = luogo(a.first, a.second),
            offeredTransportMeanDeparture = TransportMeanDto(
                name = nome,
                classification = ClassificationDto(acronym = sigla, type = tipo),
            ),
        )

    private val garibaldi = "Milano Porta Garibaldi" to "S01645"
    private val passante = "Milano Porta Garibaldi Passante" to "S01647"
    private val melzo = "Melzo" to "S01705"
    private val centrale = "Milano Centrale" to "S01700"
    private val lambrate = "Milano Lambrate" to "S01701"

    /** Come la porta dell'app di Le Frecce dava Garibaldi-Melzo delle 11:49. */
    @Test
    fun `Le Frecce, la camminata in testa sparisce e il viaggio parte col treno`() {
        val soluzione = SolutionDto(
            departureTime = "2026-09-19T11:49:00.000+02:00",
            arrivalTime = "2026-09-19T12:29:00.000+02:00",
            solutionNodes = listOf(
                nodo("Urb", "WK", "UNCLASSIFIED", garibaldi, passante, "11:49", "11:50"),
                nodo("24537", "SU", "TRAIN", passante, melzo, "11:55", "12:29"),
            ),
        )
        val viaggio = soluzione.toJourney()!!
        assertEquals(LocalDateTime.of(2026, 9, 19, 11, 55), viaggio.departure)
        assertEquals(listOf("24537"), viaggio.legs.map { it.trainNumber })
        assertTrue("una stazione sola: e' diretto", viaggio.isDirect)
        assertEquals(Duration.ofMinutes(34), viaggio.duration)
    }

    @Test
    fun `Le Frecce, la camminata in un cambio resta, a piedi, e non e' un cambio in piu'`() {
        val soluzione = SolutionDto(
            departureTime = "2026-09-19T11:00:00.000+02:00",
            arrivalTime = "2026-09-19T12:29:00.000+02:00",
            solutionNodes = listOf(
                nodo("2519", "RE", "TRAIN", "Varese" to "S01205", garibaldi, "11:00", "11:40"),
                nodo("Urb", "WK", "UNCLASSIFIED", garibaldi, passante, "11:40", "11:45"),
                nodo("24537", "SU", "TRAIN", passante, melzo, "11:55", "12:29"),
            ),
        )
        val viaggio = soluzione.toJourney()!!
        assertEquals(3, viaggio.legs.size)
        assertEquals(TransportKind.WALK, viaggio.legs[1].kind)
        assertEquals(null, viaggio.legs[1].trainNumber)
        assertEquals(1, viaggio.changes)
    }

    /** Il trasporto urbano vero, Centrale-Lambrate in metropolitana, resta un mezzo. */
    @Test
    fun `Le Frecce, il trasporto urbano vero resta`() {
        val soluzione = SolutionDto(
            departureTime = "2026-09-19T13:36:00.000+02:00",
            arrivalTime = "2026-09-19T15:00:00.000+02:00",
            solutionNodes = listOf(
                nodo("Urb", "UB", "UNCLASSIFIED", centrale, lambrate, "13:36", "13:56"),
                nodo("10925", "RE", "TRAIN", lambrate, "Brescia" to "S01717", "14:01", "15:00"),
            ),
        )
        val viaggio = soluzione.toJourney()!!
        assertEquals(LocalDateTime.of(2026, 9, 19, 13, 36), viaggio.departure)
        assertTrue(viaggio.legs.first().urbano)
        assertEquals(1, viaggio.changes)
    }

    @Test
    fun `Trenord, la camminata in un cambio diventa una tratta a piedi`() {
        val viaggio = NetworkModule.json.decodeFromString<TrenordSolutionDto>(
            """
            {"date": "20260919", "dep_time": "11:00:00", "arr_time": "12:29:00",
             "journey_list": [
              {"journey_type": "train", "train": {"train_id": "2519", "train_category": "RE"},
               "pass_list": [
                {"station": {"station_id": "S01205", "station_ori_name": "VARESE"}, "type": "start", "dep_time": "11:00:00", "is_journey": true},
                {"station": {"station_id": "S01645", "station_ori_name": "MILANO PORTA GARIBALDI"}, "type": "end", "arr_time": "11:40:00", "is_journey": true}]},
              {"journey_type": "walk", "train": {"train_category": "MXP"},
               "walk": {"length": "214", "duration": "00:05:00", "direction": "MILANO PORTA GARIBALDI PASSANTE"}},
              {"journey_type": "train", "train": {"train_id": "24537", "train_category": "S5"},
               "pass_list": [
                {"station": {"station_id": "S01647", "station_ori_name": "MILANO PORTA GARIBALDI PASSANTE"}, "type": "start", "dep_time": "11:55:00", "is_journey": true},
                {"station": {"station_id": "S01705", "station_ori_name": "MELZO"}, "type": "end", "arr_time": "12:29:00", "is_journey": true}]}
             ]}
            """,
        ).toJourney()!!
        assertEquals(3, viaggio.legs.size)
        val piedi = viaggio.legs[1]
        assertEquals(TransportKind.WALK, piedi.kind)
        assertEquals(LocalDateTime.of(2026, 9, 19, 11, 40), piedi.departure)
        assertEquals(LocalDateTime.of(2026, 9, 19, 11, 45), piedi.arrival)
        assertEquals("S01647", piedi.to.rfiCode)
        assertEquals(1, viaggio.changes)
    }
}
