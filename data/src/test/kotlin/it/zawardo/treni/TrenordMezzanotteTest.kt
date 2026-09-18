package it.zawardo.treni

import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Trenord oltre la mezzanotte, e i biglietti che non si vendono piu'.
 *
 * Il 18/09/2026, Milano Porta Garibaldi - Melzo delle 23:56: il REG 2987 fino a
 * Milano Centrale, 00:07, poi il REG 10911 alle 00:15. Senza il giorno delle
 * fermate il 10911 partiva alle 00:15 del 18, la pagina del viaggio ne chiedeva
 * la corsa della notte prima — gia' arrivata — e la coincidenza risultava persa
 * di 1447 minuti.
 */
class TrenordMezzanotteTest {

    private fun soluzione(json: String) = NetworkModule.json.decodeFromString<TrenordSolutionDto>(json)

    private val garibaldiMelzo = soluzione(
        """
        {"date": "20260918", "dep_time": "23:56:00", "arr_time": "00:37:00", "arr_day_offset": 1,
         "journey_list": [
          {"train": {"train_id": "2987", "train_category": "REG"},
           "pass_list": [
            {"station": {"station_id": "S01645", "station_ori_name": "MILANO PORTA GARIBALDI"},
             "type": "start", "dep_time": "23:56:00", "dep_day_offset": 0},
            {"station": {"station_id": "S01700", "station_ori_name": "MILANO CENTRALE"},
             "type": "end", "arr_time": "00:07:00", "arr_day_offset": 1}]},
          {"train": {"train_id": "10911", "train_category": "REG"},
           "pass_list": [
            {"station": {"station_id": "S01700", "station_ori_name": "MILANO CENTRALE"},
             "type": "start", "dep_time": "00:15:00", "dep_day_offset": 1},
            {"station": {"station_id": "S01705", "station_ori_name": "MELZO"},
             "type": "end", "arr_time": "00:37:00", "arr_day_offset": 1}]}
         ]}
        """,
    )

    @Test
    fun `la tratta dopo mezzanotte e' del giorno dopo`() {
        val viaggio = garibaldiMelzo.toJourney()!!
        assertEquals(LocalDateTime.of(2026, 9, 18, 23, 56), viaggio.legs[0].departure)
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 7), viaggio.legs[0].arrival)
        assertEquals(
            "il 10911 e' quello del 19, non quello della notte prima",
            LocalDateTime.of(2026, 9, 19, 0, 15),
            viaggio.legs[1].departure,
        )
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 37), viaggio.legs[1].arrival)
    }

    @Test
    fun `un ritardo che scavalca la mezzanotte cade il giorno dopo`() {
        val corsa = soluzione(
            """
            {"date": "20260918",
             "journey_list": [
              {"train": {"train_id": "2987", "train_category": "REG", "has_live_info": true},
               "pass_list": [
                {"station": {"station_id": "S01645", "station_ori_name": "MILANO PORTA GARIBALDI"},
                 "dep_time": "23:56:00", "dep_day_offset": 0,
                 "actual_data": {"dep_actual_time": "00:12:00"}},
                {"station": {"station_id": "S01700", "station_ori_name": "MILANO CENTRALE"},
                 "arr_time": "00:07:00", "arr_day_offset": 1,
                 "actual_data": {"arr_estimated_time": "00:23:00"}}]}
             ]}
            """,
        ).toTrainStatus()!!
        val (garibaldi, centrale) = corsa.stops
        assertEquals(
            "in tabella il 18 alle 23:56, partito sedici minuti dopo: le 00:12 del 19",
            LocalDateTime.of(2026, 9, 19, 0, 12),
            garibaldi.actualDeparture,
        )
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 7), centrale.scheduledArrival)
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 23), centrale.projectedArrival)
    }

    private fun conVendita(stato: Boolean, motivo: String?) = soluzione(
        """
        {"date": "20260918", "dep_time": "22:20:00", "arr_time": "22:53:00",
         "saleability": {"status": $stato, "non_saleability_motivation": ${motivo?.let { "\"$it\"" } ?: "null"}},
         "journey_list": [
          {"train": {"train_id": "2844", "train_category": "RE"},
           "pass_list": [
            {"station": {"station_id": "S01700", "station_ori_name": "MILANO CENTRALE"}, "type": "start", "dep_time": "22:20:00"},
            {"station": {"station_id": "S01524", "station_ori_name": "CALOLZIOCORTE OLGINATE"}, "type": "end", "arr_time": "22:53:00"}]}
         ]}
        """,
    ).toJourney()!!

    @Test
    fun `Trenord dice quando il biglietto non si vende piu'`() {
        assertTrue("RE 2844 del 18/09/2026, in banchina a +48", conVendita(false, "PAST_DEPARTURE_DATE").venditaChiusa)
        assertFalse("un biglietto che Trenord non vende e' un'altra cosa", conVendita(false, "OTHER_OPERATOR").venditaChiusa)
        assertFalse(conVendita(true, null).venditaChiusa)
    }
}
