package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.eav.EavApi
import it.zawardo.treni.data.remote.eav.EavBoardParser
import it.zawardo.treni.data.repository.EavRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Il tabellone EAV dice i ritardi anche quando il monitor tace.
 *
 * Le due fonti EAV cadono a turno. Il 28/09/2026 il monitor rispondeva dieci
 * righe vuote su nove stazioni su nove — partenze, arrivi e perfino l'endpoint
 * di riserva `moova` — mentre il pianificatore dava le sue corse regolarmente:
 * ricerca e dettaglio avevano i ritardi, e chi stava in banchina no. Il 22/09
 * era successo l'opposto, col pianificatore vuoto e il monitor in piedi.
 *
 * Questo test guarda proprio il giorno storto: **col monitor muto le righe
 * devono comunque portare il tempo reale**, preso dal pianificatore. Nei giorni
 * in cui il monitor funziona non c'e' niente da verificare qui, e si sospende.
 *
 * Il monitor si legge **direttamente**, non attraverso `board`: passando di li'
 * non si distinguerebbe piu' chi ha risposto, ed e' esattamente la domanda.
 */
class EavTabelloneRipiegoLiveTest {

    private val eav = EavRepository(NetworkModule.eavApi)
    private val portaNolana = "EAV1"
    private val sorrento = "EAV62"

    @Test
    fun `col monitor muto i ritardi arrivano dal pianificatore`() = runBlocking {
        val mezzanotte = LocalDate.now().atStartOfDay(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()
        val corpo = runCatching {
            NetworkModule.eavApi.tabellone(codLoc = 1, tipoLista = EavApi.PARTENZE).string()
        }.getOrNull()
        val dalMonitor = EavBoardParser.parse(corpo, mezzanotte)
        assumeTrue("oggi il monitor risponde: il ripiego non e' in gioco", dalMonitor.isEmpty())

        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, LocalDateTime.now())
        assumeTrue("tace anche il pianificatore: EAV e' giu' del tutto", ritardi.isNotEmpty())

        val righe = eav.board(portaNolana, date = LocalDate.now())
        assumeTrue("nessuna corsa da Porta Nolana adesso", righe.isNotEmpty())
        val conRitardo = righe.filter { it.realtime }
        println("\n=== TABELLONE EAV COL MONITOR MUTO: ${righe.size} corse, ${conRitardo.size} col tempo reale ===")
        conRitardo.take(8).forEach {
            println("  ${it.scheduledTime} ${it.trainRef.number} -> ${it.direction}  ${it.delayMinutes} min")
        }
        assertTrue(
            "il monitor tace e il pianificatore risponde, ma nessuna riga porta il tempo reale: " +
                "il ripiego del tabellone non sta funzionando",
            conRitardo.isNotEmpty(),
        )
    }
}
