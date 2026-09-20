package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.lefrecce.CriteriSito
import it.zawardo.treni.data.remote.lefrecce.RicercaAvanzataSito
import it.zawardo.treni.data.remote.lefrecce.RichiestaSito
import it.zawardo.treni.data.remote.lefrecce.avvisiDelSito
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * I messaggi delle soluzioni del sito di Le Frecce, contro il sito vero.
 *
 * Genova Piazza Principe - La Spezia la mattina: gli Intercity della tratta
 * portano sempre l'area family in carrozza 3, e il 19/09/2026 ce l'avevano 9
 * soluzioni su 10. Se nessuna soluzione ha piu' un messaggio, e' piu' probabile
 * che il campo abbia cambiato posto che che gli Intercity siano spariti: i
 * messaggi utili (servizio viaggiatori, posti esauriti) sparirebbero con lui,
 * in silenzio.
 */
class MessaggiDelSitoLiveTest {

    @Test
    fun `le soluzioni del sito portano ancora i loro messaggi`() = runBlocking {
        val domani = LocalDate.now().plusDays(1).atTime(7, 0)
        val risposta = NetworkModule.lefrecceApi.soluzioniDelSito(
            RichiestaSito(
                departureLocationId = 830004700,
                arrivalLocationId = 830006000,
                departureTime = domani.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")),
                adults = 1,
                children = 0,
                criteria = CriteriSito(
                    frecceOnly = false, regionalOnly = false, intercityOnly = false, tourismOnly = false,
                    noChanges = false, order = "DEPARTURE_DATE", offset = 0, limit = 10,
                ),
                advancedSearchRequest = RicercaAvanzataSito(bestFare = false),
            ),
        )
        val messaggi = risposta.solutions.flatMap { it.messages.filterNotNull() }
        println("\n=== MESSAGGI: ${risposta.solutions.size} soluzioni, ${messaggi.size} messaggi ===")
        messaggi.forEach { println("  ${it.status} ${it.imageId} | ${it.message}") }
        risposta.solutions.forEach { v ->
            avvisiDelSito(v.messages.filterNotNull()).forEach { println("  sulla scheda: $it") }
        }
        assertTrue("il sito non ha risposto soluzioni", risposta.solutions.isNotEmpty())
        assertTrue(
            "nessuna delle ${risposta.solutions.size} soluzioni ha un messaggio: il campo `messages` ha cambiato posto?",
            messaggi.isNotEmpty(),
        )
    }
}
