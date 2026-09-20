package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Il limite della prima pagina non deve **perdere** soluzioni.
 *
 * L'elenco ne chiede otto e le altre arrivano scorrendo, ma fra la fonte e la
 * riga a schermo ci sono due filtri che potrebbero mangiarne qualcuna: il
 * sovraccarico di `unaRicercaLeFrecce` (si chiede `limit * OVERFETCH` perche'
 * gli scarti non svuotino la pagina) e la fusione con Trenord. Se uno dei due
 * sbagliasse, cercando otto soluzioni ne uscirebbero meno di quelle che la
 * fonte conosce, o — peggio — non le **prime**: un treno in mezzo sparirebbe e
 * nessuno se ne accorgerebbe, perche' l'elenco sembrerebbe comunque pieno.
 *
 * Qui si chiede la stessa tratta alla stessa ora con tre limiti diversi: le
 * prime otto devono essere le stesse otto, in fila e senza buchi.
 */
class LimiteRicercaLiveTest {

    private val repo = JourneyRepository(
        NetworkModule.lefrecceApi,
        TrenordRepository(NetworkModule.trenordApi, NetworkModule.json),
    )

    /** Milano Centrale e Brescia: molte corse, due operatori, e cambi urbani. */
    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val brescia = Station("S01717", 830001717, "Brescia")

    private fun etichetta(j: Journey) =
        j.departure.toLocalTime().toString() + " " + j.legs.mapNotNull { it.trainNumber }.joinToString("+")

    @Test
    fun `le prime soluzioni non cambiano al crescere del limite`() = runBlocking {
        val quando = LocalDateTime.now().withSecond(0).withNano(0)
        val tempi = mutableMapOf<Int, Long>()
        val esiti = listOf(8, 15, 30).associateWith { limite ->
            val inizio = System.currentTimeMillis()
            val viaggi = repo.searchAll(milano, brescia, quando, limit = limite, sources = DataSource.entries.toSet())
                .journeys
            tempi[limite] = System.currentTimeMillis() - inizio
            viaggi
        }
        // Il tempo stampato e' quello della **rete**: quanto ci mette l'elenco ad
        // avere le sue righe, prima di disegnarle. Serve a distinguere una ricerca
        // lenta da una schermata lenta, quando qualcuno dira' che l'app e' lenta.
        esiti.forEach { (limite, viaggi) ->
            println("\n  limite $limite -> ${viaggi.size} in ${tempi[limite]} ms: " + viaggi.joinToString(", ") { etichetta(it) })
        }

        val larga = esiti.getValue(30)
        assumeTrue("la ricerca non ha risposto: rete o servizio giu'", larga.isNotEmpty())

        esiti.forEach { (limite, viaggi) ->
            assertTrue("con limite $limite sono uscite ${viaggi.size} soluzioni, piu' del limite", viaggi.size <= limite)
            assertEquals(
                "con limite $limite le prime soluzioni non sono le stesse della ricerca larga: " +
                    "il filtro o la fusione ne stanno perdendo una in mezzo",
                larga.take(viaggi.size).map { etichetta(it) },
                viaggi.map { etichetta(it) },
            )
        }
        // E la pagina dev'essere piena finche' ci sono corse: otto chieste, otto date.
        assertEquals(
            "la prima pagina esce corta pur essendocene altre",
            minOf(8, larga.size),
            esiti.getValue(8).size,
        )
        // Nessun doppione e in ordine: sono la stessa cosa vista due volte.
        assertEquals("doppioni nell'elenco", larga.map { etichetta(it) }.distinct().size, larga.size)
        assertEquals("elenco fuori ordine", larga.map { it.departure }.sorted(), larga.map { it.departure })
    }
}
