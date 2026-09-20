package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.svizzera.SearchChApi
import it.zawardo.treni.data.remote.svizzera.SearchChBoardDto
import it.zawardo.treni.data.repository.SvizzeraRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * `search.ch` contro il servizio vero, e il tabellone svizzero che ne esce.
 *
 * `transport.opendata.ch` e' un involucro **non ufficiale** di `search.ch` e per
 * strada perde due cose: le **soppressioni**, che la fonte sotto segna con un
 * ritardo «X», e il **ritardo degli arrivi**, che li' non c'e' mai.
 *
 * Il loro server pero' sbaglia lo stato sugli arrivi: `mode=arrival` risponde
 * quasi sempre 404 col tabellone giusto in corpo. L'app non si fida dello stato
 * e non si fida nemmeno del corpo: **accetta solo un tabellone riconoscibile**,
 * cioe' della fermata chiesta e con delle corse. Qui si verifica tutto: che il
 * caso buono passi, e che i tre errori veri di questo servizio restino fuori.
 */
class SearchChLiveTest {

    private val searchCh = NetworkModule.searchChApi
    private val json = Json { ignoreUnknownKeys = true }

    /** Lugano, stazione SBB, e Locarno FART, capolinea della Vigezzina. */
    private val lugano = "8505300"

    /** Il corpo com'e', senza guardare lo stato: e' quel che fa il repository. */
    private suspend fun corpo(stop: String, mode: String): SearchChBoardDto? {
        val risposta = searchCh.tabellone(stop = stop, mode = mode)
        return risposta.body() ?: risposta.errorBody()?.let {
            runCatching { json.decodeFromString<SearchChBoardDto>(it.string()) }.getOrNull()
        }
    }

    @Test
    fun `le partenze rispondono, con numero, ora e ritardo`() = runBlocking {
        val risposta = searchCh.tabellone(stop = lugano)
        assertTrue("le partenze rispondevano 200: adesso ${risposta.code()}", risposta.isSuccessful)
        val partenze = risposta.body()?.connections.orEmpty()
        println("\n=== SEARCH.CH Lugano: ${partenze.size} partenze ===")
        partenze.take(6).forEach {
            println("  ${it.time} ${it.categoria}${it.linea} ${it.numero} -> ${it.ritardoPartenza ?: "-"}")
        }
        assumeTrue("a quest'ora Lugano non ha partenze", !notteFonda() || partenze.isNotEmpty())
        assertTrue("search.ch non risponde piu' con le corse", partenze.isNotEmpty())
        assertTrue(
            "nessuna corsa ha un numero (`*Z`): senza, le righe non si accoppiano",
            partenze.any { !it.numero.isNullOrBlank() },
        )
        assertTrue(
            "nessuna partenza dichiara il ritardo: `dep_delay` e' sparito, ed e' anche " +
                "il campo che segna le soppresse con «X»",
            partenze.any { !it.ritardoPartenza.isNullOrBlank() },
        )
    }

    @Test
    fun `gli arrivi arrivano lo stesso, e sono arrivi`() = runBlocking {
        val arrivi = corpo(lugano, SearchChApi.ARRIVI)
        assumeTrue("search.ch non ha risposto niente di leggibile", arrivi != null)
        val corse = arrivi!!.connections
        println("  arrivi a Lugano: ${corse.size}, con ritardo ${corse.count { !it.ritardoArrivo.isNullOrBlank() }}")
        assumeTrue("a quest'ora Lugano non ha arrivi", !notteFonda() || corse.isNotEmpty())

        assertEquals("il tabellone dev'essere quello della fermata chiesta", lugano, arrivi.stop?.id)
        assertTrue("nessuna corsa in arrivo", corse.isNotEmpty())
        assertTrue(
            "nessun arrivo dichiara il ritardo: `arr_delay` e' sparito, ed e' meta' del " +
                "motivo per cui questa fonte serve",
            corse.any { !it.ritardoArrivo.isNullOrBlank() },
        )
        // Gli arrivi devono essere arrivi: con `mode` sbagliato tornano le partenze.
        val partenze = corpo(lugano, SearchChApi.PARTENZE)!!.connections
        assertTrue(
            "arrivi e partenze coincidono: `mode=arrival` non seleziona piu' gli arrivi",
            corse.map { it.time } != partenze.map { it.time },
        )
    }

    /**
     * Gli errori veri di `search.ch`, che non devono passare per tabellone: un
     * URL inesistente (`{"error": …}`, 404) e una fermata inesistente
     * (`{"messages": …}`, 200). Il controllo del repository e' proprio questo.
     */
    @Test
    fun `un errore vero non si scambia per un tabellone`() = runBlocking {
        val inesistente = corpo("NONESISTE", SearchChApi.ARRIVI)
        println("  fermata inesistente: stop=${inesistente?.stop?.id}, corse=${inesistente?.connections?.size}")
        assertTrue(
            "una fermata inesistente non deve avere corse",
            inesistente?.connections.isNullOrEmpty(),
        )
        assertTrue(
            "ne' dichiarare di essere la fermata che abbiamo chiesto",
            inesistente?.stop?.id != "NONESISTE" || inesistente.connections.isEmpty(),
        )
    }

    @Test
    fun `il tabellone dell'app prende ritardo e soppressioni, e niente altro cambia`() = runBlocking {
        val conAggiunta = SvizzeraRepository(NetworkModule.svizzeraApi, searchCh, json)
        val senza = SvizzeraRepository(NetworkModule.svizzeraApi)

        for (stazione in listOf("CH8505300", "CH8505470")) {
            for (arrivi in listOf(false, true)) {
                val prima = senza.board(stazione, arrivals = arrivi)
                val dopo = conAggiunta.board(stazione, arrivals = arrivi)
                println(
                    "  $stazione ${if (arrivi) "arrivi" else "partenze"}: ${prima.size} righe, " +
                        "con search.ch ${dopo.size}, realtime ${dopo.count { it.realtime }}",
                )
                /*
                 * Le due letture sono due chiamate distinte a
                 * `transport.opendata.ch`: quando una delle due torna vuota — e
                 * capita — non c'e' niente da confrontare, e fallire direbbe
                 * solo che la rete ha singhiozzato.
                 */
                if (prima.isEmpty() || dopo.isEmpty()) {
                    println("    una delle due letture e' vuota: niente da confrontare")
                    continue
                }
                assertEquals("l'aggiunta non puo' cambiare quante righe ci sono", prima.size, dopo.size)
                assertEquals("ne' quali corse", prima.map { it.trainRef.number }, dopo.map { it.trainRef.number })
                assertEquals("ne' i loro orari", prima.map { it.scheduledTime }, dopo.map { it.scheduledTime })
                assertTrue(
                    "l'aggiunta non puo' togliere il tempo reale a chi ce l'aveva",
                    dopo.count { it.realtime } >= prima.count { it.realtime },
                )
                dopo.forEach {
                    assertTrue("un ritardo fuori scala: ${it.delayMinutes}", it.delayMinutes in -5..600)
                }
            }
        }
    }
}
