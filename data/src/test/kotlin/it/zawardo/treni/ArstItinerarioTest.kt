package it.zawardo.treni

import it.zawardo.treni.data.remote.arst.ArstOrario
import it.zawardo.treni.data.repository.ArstRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * La ricerca A→B dentro la Sardegna, dall'orario imbarcato.
 *
 * E' l'unico modo per avere una tratta tutta ARST: il BFF quelle stazioni non le
 * conosce e i viaggi misti richiedono l'alta velocita', che in Sardegna non
 * arriva. Se questa smettesse di rispondere, cercare Macomer→Nuoro darebbe
 * «nessun collegamento» come se la ferrovia non esistesse — e nessun altro test
 * se ne accorgerebbe, perche' gli altri guardano l'orario, non la ricerca.
 *
 * Serve anche a chi legge il codice dell'elenco: e' da qui che arrivano le righe
 * che `ResultsViewModel.direttiFuoriRfi` filtra, e l'ultima corsa del giorno che
 * si mostra quando l'elenco resta vuoto.
 */
class ArstItinerarioTest {

    private val arst = ArstRepository()
    private val orario = ArstOrario.carica(null)

    /** Il codice pubblico di una fermata ARST, dal suo nome. */
    private fun codice(nome: String): String? =
        orario?.stazioni?.firstOrNull { it.nome.equals(nome, ignoreCase = true) }?.let { "ARST" + it.id }

    /** Il primo giorno coperto in cui quella coppia ha corse. */
    private fun giornoCon(da: String, a: String): Pair<LocalDate, Int>? = runBlocking {
        var giorno = LocalDate.now()
        repeat(GIORNI_DA_PROVARE) {
            val quante = arst.itinerario(da, a, giorno).size
            if (quante > 0) return@runBlocking giorno to quante
            giorno = giorno.plusDays(1)
        }
        null
    }

    @Test
    fun `fra due fermate della stessa linea la ricerca risponde`() = runBlocking {
        val da = codice("Macomer")
        val a = codice("Nuoro")
        assumeTrue("l'orario imbarcato non ha Macomer e Nuoro", da != null && a != null)

        val trovato = giornoCon(da!!, a!!)
        assertTrue(
            "nessuna corsa Macomer-Nuoro nei prossimi $GIORNI_DA_PROVARE giorni: " +
                "la ricerca dentro la Sardegna non risponde piu'",
            trovato != null,
        )
        val (giorno, quante) = trovato!!
        println("\n=== ARST Macomer-Nuoro il $giorno: $quante corse ===")

        val viaggi = arst.itinerario(da, a, giorno)
        assertTrue("una corsa senza tratte non e' un viaggio", viaggi.all { it.legs.isNotEmpty() })
        assertTrue("gli orari non sono in ordine", viaggi.all { !it.arrival.isBefore(it.departure) })
        assertTrue(
            "ARST non ha tempo reale: nessuna corsa puo' dichiarare un ritardo",
            viaggi.all { (it.delayMinutes ?: 0) == 0 },
        )
        // E' il dato su cui l'elenco costruisce «l'ultima parte alle …» quando
        // tutte le corse del giorno sono gia' passate.
        val ultima = viaggi.maxByOrNull { it.departure }!!.departure
        println("  ultima corsa del giorno: ${ultima.toLocalTime()}")
        assertTrue("l'ultima corsa non e' del giorno chiesto", ultima.toLocalDate() == giorno)
    }

    @Test
    fun `fra due fermate senza collegamento diretto non si inventa niente`() = runBlocking {
        val da = codice("Sorso")
        val a = codice("Isili")
        assumeTrue("l'orario imbarcato non ha Sorso e Isili", da != null && a != null)
        // Due linee opposte dell'isola, senza corsa diretta: la risposta giusta e'
        // nessuna, non una inventata mettendo insieme due tratte scollegate.
        assertTrue(
            "una corsa diretta Sorso-Isili non esiste e non deve comparire",
            giornoCon(da!!, a!!) == null,
        )
    }

    private companion object {
        const val GIORNI_DA_PROVARE = 8
    }
}
