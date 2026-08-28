package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.ItaloRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * La primitiva del ripiego "orario previsto dalla corsa di oggi".
 *
 * La trasformazione (pulizia + spostamento data) vive nel ViewModel; qui si
 * verifica che il mattone su cui poggia esista: un treno che circola oggi, il
 * suo percorso completo per numero. Se questo c'e', il ripiego ha di che
 * lavorare.
 */
class PrevistoDaOggiTest {
    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi, trenord, italo)

    @Test
    fun `un Frecciarossa di oggi ha percorso e fermate per numero`() = runBlocking {
        // prendo un numero reale da una ricerca di oggi Milano->Roma
        val from = stations.search("Roma Termini").first()
        val to = stations.search("Firenze S. M. Novella").first()
        val journeys = it.zawardo.treni.data.repository.JourneyRepository(NetworkModule.lefrecceApi, trenord)
        val soluzioni = journeys.searchAll(from, to, LocalDateTime.now().withHour(java.time.LocalTime.now().hour)).journeys
        val leg = soluzioni.flatMap { it.legs }
            .firstOrNull { it.trainNumber != null && it.category != null }
        if (leg == null) { println("nessun FR nella finestra, test non significativo"); return@runBlocking }

        val numero = leg.trainNumber!!
        println("\n=== FR $numero da ${leg.from.name} (oggi) ===")
        val status = trains.statusByNumber(numero, LocalDate.now(), leg.from.rfiCode, leg.departure)
        if (status == null) { println("  ViaggiaTreno non risponde ora"); return@runBlocking }
        println("  ${status.stops.size} fermate: ${status.stops.take(6).joinToString { it.stationName }}")
        assertTrue("il percorso di oggi deve avere fermate intermedie", status.stops.size >= 2)
        assertTrue(
            "la stazione di salita deve comparire nel percorso",
            status.stops.any { it.stationCode == leg.from.rfiCode },
        )
    }
}
