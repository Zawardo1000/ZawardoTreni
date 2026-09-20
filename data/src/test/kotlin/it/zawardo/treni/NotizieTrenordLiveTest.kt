package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.NotizieDirettrici
import it.zawardo.treni.data.repository.TrenordRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Le notizie delle direttrici Trenord contro il servizio vero.
 *
 * Due guardie. Che `mia/direttrici/` risponda ancora, con le sue direttrici:
 * se sparisce, il perche' dei treni Trenord torna muto e nessuno lo vede. E che
 * una notizia letta per una corsa riconosca **la corsa vera** di quel numero e
 * di quel giorno, chiesta a Trenord: e' la prova che il confronto per ora di
 * partenza dall'origine regge sui dati veri, non solo sugli esempi.
 *
 * Una sera senza notizie per corsa non fa fallire niente: lo si dice e basta.
 */
class NotizieTrenordLiveTest {

    private val api = NetworkModule.trenordApi
    private val trenord = TrenordRepository(api, NetworkModule.json)
    private val corsaScritta = Regex("""\btren[oi]\s+\d{3,5}\s*\(""", RegexOption.IGNORE_CASE)

    @Test
    fun `le notizie per corsa riconoscono la corsa vera`() = runBlocking {
        val direttrici = api.direttrici()
        println("\n=== DIRETTRICI: ${direttrici.size}, ${direttrici.sumOf { it.news.size }} notizie ===")
        assertTrue("Trenord non ha risposto nessuna direttrice", direttrici.size >= 10)

        val scritte = direttrici.flatMap { it.news }.count { corsaScritta.containsMatchIn(it.description.orEmpty()) }
        val lette = NotizieDirettrici.perCorsa(direttrici)
        lette.forEach { println("  ${it.numero} ${it.origine} ${it.partenza} ${it.giorni} ${it.pubblicata} | ${it.testo.take(140)}") }
        if (scritte == 0) {
            println("  nessuna notizia nomina una corsa: niente da verificare")
            return@runBlocking
        }
        assertTrue("$scritte notizie nominano una corsa ma non se ne legge nessuna: e' cambiata la forma", lette.isNotEmpty())

        val oggi = LocalDate.now()
        for (notizia in lette.take(6)) {
            val giorno = notizia.giorni.firstOrNull()?.start ?: notizia.pubblicata?.toLocalDate() ?: continue
            // Trenord risponde per le date di tabella, non per il passato lontano.
            if (giorno.isBefore(oggi.minusDays(1))) continue
            val corsa = trenord.trainStatus(notizia.numero, giorno)
            println("  ${notizia.numero} del $giorno: ${corsa?.stops?.firstOrNull()?.let { "${it.stationName} ${it.scheduledDeparture}" } ?: "Trenord non la conosce"}")
            if (corsa != null) {
                assertTrue(
                    "la notizia del ${notizia.numero} (${notizia.origine} ${notizia.partenza}) non riconosce la sua corsa del $giorno",
                    notizia.riguarda(corsa, giorno),
                )
            }
            delay(2_500)
        }
    }
}
