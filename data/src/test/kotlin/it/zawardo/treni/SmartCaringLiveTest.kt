package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Le note SmartCaring contro il servizio vero: i regionali del prossimo giorno
 * feriale, alle 8 a Roma Termini e a Napoli Centrale, dal tabellone di quel giorno.
 *
 * Una mattina senza note non fa fallire niente: le note ci sono quando c'e' un
 * guasto o un cantiere. Fallisce se il servizio smette di rispondere in JSON, o
 * se risponde note che non elencano il treno chiesto: vorrebbe dire che il
 * filtro per numero non c'e' piu', e ogni corsa riceverebbe le note di tutte.
 */
class SmartCaringLiveTest {

    private val api = NetworkModule.viaggiaTrenoApi
    private val trains = TrainStatusRepository(api)

    @Test
    fun `le note di un regionale sono sue, e arrivano sulla corsa`() = runBlocking {
        // Il primo giorno feriale: la domenica i regionali e i cantieri sono meno.
        val domani = generateSequence(LocalDate.now().plusDays(1)) { it.plusDays(1) }
            .first { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
        val alle = domani.atTime(8, 0).atZone(ZoneId.of("Europe/Rome"))
        val regionali = listOf("S08409", "S09218")
            .flatMap { trains.departures(it, alle) }
            .filter { it.category == "REG" }
            .distinctBy { it.trainRef.number }
            .take(12)
        assumeTrue("nessun regionale domani mattina nei due tabelloni", regionali.isNotEmpty())

        var conNote = 0
        for (riga in regionali) {
            val numero = riga.trainRef.number
            val note = api.noteSmartCaring(numero, domani.toString())
            println("  REG $numero da ${riga.trainRef.originCode}: ${note.size} note")
            note.forEach { nota ->
                println("    ${nota.id} | ${nota.infoNote?.take(120)}")
                assertTrue("nota senza testo per il $numero", !nota.infoNote.isNullOrBlank())
                assertTrue(
                    "la nota ${nota.id} non elenca il $numero: il filtro per numero non c'e' piu'",
                    nota.trains.any { it.commercialTrainNumber == numero },
                )
            }
            val sue = note.filter { n -> n.trains.any { it.commercialTrainNumber == numero && it.originCode == riga.trainRef.originCode } }
            if (sue.isNotEmpty()) {
                conNote++
                val corsa = TrainStatus(
                    number = numero,
                    category = "REG",
                    label = "REG $numero",
                    origin = riga.trainRef.originName,
                    destination = riga.direction,
                    delayMinutes = 0,
                    state = TrainState.NOT_DEPARTED,
                    lastDetectionStation = null,
                    lastDetectionTime = null,
                    notice = null,
                    stops = listOf(
                        Stop(0, riga.trainRef.originName.orEmpty(), riga.trainRef.originCode, null, null, 0, null, null, 0, null, null, StopStatus.FUTURE),
                    ),
                    realtime = false,
                )
                val spiegata = trains.conNoteDelGiorno(corsa, domani)
                assertTrue(
                    "la corsa del $numero non ha ricevuto le sue note",
                    sue.mapNotNull { it.infoNote?.trim() }.all { it in spiegata.avvisi },
                )
            }
            delay(300)
        }
        println("=== SMARTCARING: ${regionali.size} regionali di domani, $conNote con note proprie ===")
    }
}
