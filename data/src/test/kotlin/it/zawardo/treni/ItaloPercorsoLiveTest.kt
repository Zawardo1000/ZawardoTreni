package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Il percorso di una corsa Italo, contro il servizio vero.
 *
 * `RicercaTrenoService` tace su quasi tutte le corse — `IsEmpty` prima della
 * partenza, dopo l'arrivo e su molte in viaggio — e prima l'app, in quel caso,
 * mostrava una fermata sola: «di questa corsa si conosce il passaggio da qui».
 * Il percorso pero' e' scritto nella riga stessa del tabellone (`InfoRoute`), a
 * orario di tabella. Questo test presidia proprio quel campo: se sparisce, le
 * corse Italo tornano a una riga sola senza che nessuno se ne accorga.
 */
class ItaloPercorsoLiveTest {

    private val italo = ItaloRepository(NetworkModule.italoApi)

    /** Roma Termini, Milano Centrale, Napoli Centrale: i tre nodi di Italo. */
    private val nodi = listOf("S08409", "S01700", "S09218")

    @Test
    fun `una corsa in tabellone porta il suo percorso`() = runBlocking {
        var provate = 0
        var conPercorso = 0
        // Le corse su cui il dettaglio Italo tace: sono quelle per cui esiste
        // questo ripiego, ed erano la meta' del campione del 20/09/2026.
        var mute = 0
        var muteConPercorso = 0
        for (rfi in nodi) {
            val partenze = italo.board(rfi)
            if (partenze.isEmpty()) continue
            println("\n=== ITALO da $rfi: ${partenze.size} partenze ===")
            for (riga in partenze.take(4)) {
                val muta = runCatching { NetworkModule.italoApi.treno(riga.trainRef.number) }
                    .getOrNull()?.empty != false
                val corsa = italo.trainStatus(riga.trainRef.number, boardingRfi = rfi) ?: continue
                provate++
                if (corsa.stops.size > 1) conPercorso++
                if (muta) {
                    mute++
                    if (corsa.stops.size > 1) muteConPercorso++
                }
                println(
                    "  ${corsa.label.padEnd(12)} ${corsa.stops.size} fermate: " +
                        corsa.stops.joinToString(" › ") { it.stationName },
                )
                assertTrue("una corsa senza fermate", corsa.stops.isNotEmpty())
                assertTrue(
                    "le fermate devono essere in ordine di orario",
                    corsa.stops.mapNotNull { it.scheduledArrival ?: it.scheduledDeparture }
                        .zipWithNext().all { (a, b) -> !b.isBefore(a) },
                )
                // Col percorso intero la corsa comincia dalla sua origine, non
                // da dove siamo: quel che conta e' che la nostra fermata ci sia.
                assertTrue(
                    "la fermata di salita non e' nel percorso: ${corsa.stops.map { it.stationName }}",
                    corsa.stops.any { stessaStazione(it.stationCode, rfi) } || corsa.stops.size == 1,
                )
            }
            // Tutti e tre i nodi: le corse col dettaglio muto non stanno sempre
            // nella stessa stazione, e sono loro il motivo di questo ripiego.
        }
        println("  $provate corse, $conPercorso col percorso; $mute col dettaglio muto, di cui $muteConPercorso col percorso")
        assumeTrue("nessuna corsa Italo in partenza dai tre nodi adesso", provate > 0)
        assertTrue(
            "nessuna delle $provate corse ha un percorso: `InfoRoute` non c'e' piu', " +
                "o il dettaglio Italo ha smesso di rispondere e il tabellone non lo sostituisce",
            conPercorso > 0,
        )
        if (mute > 0) {
            assertTrue(
                "$mute corse col dettaglio muto e nessuna col percorso: senza `InfoRoute` " +
                    "tornerebbero a una fermata sola",
                muteConPercorso > 0,
            )
        }
    }
}
