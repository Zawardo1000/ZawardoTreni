package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.italo.ItaloScheduleDto
import it.zawardo.treni.data.remote.italo.ItaloStopDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Regressione: certe corse Italo dirette uscivano con un arrivo "a 25 ore"
 * (05:49 -> 07:20 mostrato 25h31). Colpa di un orario REALE segnaposto ("01:00")
 * su una fermata intermedia che spingeva il riferimento del salto di mezzanotte
 * a domani, slittando di un giorno ogni orario teorico successivo. Il riferimento
 * ora lo danno solo gli orari teorici, monotoni per costruzione.
 */
class ItaloDatePlaceholderTest {
    @Test
    fun `il segnaposto reale non slitta l'arrivo al giorno dopo`() {
        val giorno = LocalDate.of(2026, 8, 29)
        val corsa = ItaloScheduleDto(
            number = "8904",
            stations = listOf(
                ItaloStopDto(
                    code = "NAC", name = "Napoli Centrale", index = 1,
                    scheduledDeparture = "05:49", actualDeparture = "05:49",
                ),
                ItaloStopDto(
                    code = "RTB", name = "Roma Tiburtina", index = 2,
                    scheduledArrival = "06:30", actualArrival = "01:00", // segnaposto
                    scheduledDeparture = "06:32", actualDeparture = "01:00",
                ),
                ItaloStopDto(
                    code = "RMT", name = "Roma Termini", index = 3,
                    scheduledArrival = "07:20", actualArrival = "07:20",
                ),
            ),
        )

        val roma = corsa.toTrainStatus(lastUpdate = null, giorno = giorno)!!.stops.last()
        assertEquals(giorno.atTime(7, 20), roma.scheduledArrival)
    }
}
