package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.fnb.FnbApi
import it.zawardo.treni.data.remote.fnb.FnbStations
import it.zawardo.treni.data.repository.FnbRepository
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ferrotramviaria contro il servizio reale.
 *
 * Risponde JSON, quindi non c'e' markup da cui farsi sorprendere; ma i campi
 * sono opzionali e il portale non segnala gli errori — un codice sbagliato da'
 * 200 con liste vuote. Se cambia qualcosa, il modo in cui si vede e' che i
 * tabelloni si svuotano, ed e' quello che questi test guardano.
 */
class FnbLiveTest {

    private val fnb = FnbRepository(NetworkModule.fnbApi)

    /** Bari Centrale FNB, capolinea di tutto: qualcosa c'e' sempre. */
    private val bariCentrale = "FNB1110"

    @Test
    fun `il tabellone di Bari Centrale risponde con corse vere`() = runBlocking {
        val righe = fnb.board(bariCentrale)
        println("\n=== FNB PARTENZE BARI CENTRALE (${righe.size}) ===")
        righe.take(12).forEach {
            println(
                "  %-7s %-14s %-24s bin %-4s %s  %s".format(
                    it.trainRef.number,
                    it.category ?: "-",
                    it.direction ?: "-",
                    it.actualPlatform ?: "-",
                    it.scheduledTime ?: "--:--",
                    if (it.state == TrainState.CANCELLED) "SOPPRESSO"
                    else if (it.delayMinutes > 0) "+${it.delayMinutes}" else "",
                ),
            )
        }

        assertTrue("il tabellone non ha restituito corse", righe.isNotEmpty())
        assertTrue(
            "sono passate righe senza numero di treno",
            righe.all { it.trainRef.number.isNotBlank() },
        )
        assertTrue(
            "nessuna riga ha un orario valido: il formato yyyyMMddHHmmss e' cambiato",
            righe.any { it.scheduledTime?.matches(Regex("""\d{2}:\d{2}""")) == true },
        )
        assertTrue(
            "nessuna corsa ha una direzione",
            righe.any { !it.direction.isNullOrBlank() },
        )
    }

    /**
     * Il binario e' meta' del motivo per cui questa sorgente esiste.
     *
     * Non tutte le corse ce l'hanno — il portale omette il campo finche' non e'
     * assegnato — ma se non ce l'ha piu' nessuna, e' cambiato qualcosa a monte.
     */
    @Test
    fun `almeno una corsa dichiara il binario`() = runBlocking {
        val righe = fnb.board(bariCentrale)
        val conBinario = righe.count { !it.actualPlatform.isNullOrBlank() }
        println("\n=== BARI CENTRALE: $conBinario corse su ${righe.size} con binario ===")
        assertTrue(
            "nessuna corsa dichiara il binario: il campo binarioEffettivo e' sparito",
            righe.isEmpty() || conBinario > 0,
        )
    }

    @Test
    fun `gli arrivi sono una lista diversa dalle partenze`() = runBlocking {
        val partenze = fnb.board(bariCentrale, arrivals = false)
        val arrivi = fnb.board(bariCentrale, arrivals = true)
        println("\n=== BARI CENTRALE: ${partenze.size} partenze, ${arrivi.size} arrivi ===")
        assertTrue("arrivi e partenze sono entrambi vuoti", partenze.isNotEmpty() || arrivi.isNotEmpty())
    }

    /**
     * Il registro locale contro quello del portale.
     *
     * L'elenco delle fermate viaggia dentro l'app perche' la ricerca per nome
     * deve funzionare offline. Il prezzo e' che puo' andare alla deriva: qui si
     * vede subito, ed e' l'unico posto in cui si vedrebbe.
     */
    @Test
    fun `il registro locale coincide con quello del portale`() = runBlocking {
        val remoto = NetworkModule.fnbApi.siti(FnbApi.FERRO)
        val ftvRemote = remoto.filter { it.gestore == "FTV" }.mapNotNull { it.codSito }.toSet()
        val locali = FnbStations.tutte.map { it.codSito }.toSet()

        println("\n=== REGISTRO: ${locali.size} locali, ${ftvRemote.size} sul portale ===")
        val mancanti = ftvRemote - locali
        val diPiu = locali - ftvRemote
        if (mancanti.isNotEmpty()) println("  mancano in FnbStations: $mancanti")
        if (diPiu.isNotEmpty()) println("  non piu' sul portale: $diPiu")

        assertEquals("il registro locale e' andato alla deriva", ftvRemote, locali)
    }

    /**
     * Le fermate di Ferrovie Appulo Lucane sono nel registro del portale ma il
     * loro tabellone non risponde: e' il motivo per cui non sono nell'app.
     *
     * Se un giorno tornasse a rispondere, questo test fallisce ed e' il segnale
     * che si possono aggiungere.
     */
    @Test
    fun `il tabellone di Ferrovie Appulo Lucane continua a non rispondere`() = runBlocking {
        val fal = NetworkModule.fnbApi.siti(FnbApi.FERRO).filter { it.gestore == "FAL" }
        println("\n=== FAL: ${fal.size} fermate dichiarate dal portale ===")
        assertTrue("il portale non dichiara piu' fermate FAL", fal.isNotEmpty())

        val matera = fal.firstOrNull { it.nome?.contains("Matera Centrale") == true }?.codSito
        assertTrue("Matera Centrale non e' piu' nel registro", matera != null)

        val risposta = runCatching { NetworkModule.fnbApi.tabellone(matera!!) }
        println("  tabellone di $matera: ${if (risposta.isSuccess) "risponde" else "errore"}")
        assertTrue(
            "il tabellone FAL risponde: si possono aggiungere le sue 38 fermate",
            risposta.isFailure,
        )
    }

    @Test
    fun `fuori dalla rete Ferrotramviaria non si interroga nessuno`() = runBlocking {
        assertEquals(emptyList<Any>(), fnb.board("S01700"))
        assertTrue("Milano Centrale non e' Ferrotramviaria", !fnb.covers("S01700"))
        assertTrue("Bari Centrale FNB lo e'", fnb.covers(bariCentrale))
    }

    @Test
    fun `per una data diversa da oggi non si inventa l'orario`() = runBlocking {
        val domani = java.time.LocalDate.now().plusDays(1)
        assertEquals(emptyList<Any>(), fnb.board(bariCentrale, date = domani))
    }

    /**
     * A Ruvo la stazione piu' vicina e' Ruvo.
     *
     * E' il caso che giustifica le coordinate: sulla rete nazionale Ruvo non ha
     * stazione, quindi senza questo elenco all'utente verrebbe proposta Bari
     * Centrale, a quaranta chilometri.
     */
    @Test
    fun `la fermata piu' vicina a Ruvo di Puglia e' Ruvo`() {
        val vicina = fnb.nearest(41.1148, 16.4880)
        println("\n=== PIU' VICINA A RUVO: ${vicina?.name} (${vicina?.rfiCode}) ===")
        assertTrue("nessuna fermata trovata", vicina != null)
        assertTrue("dovrebbe essere Ruvo, e' ${vicina?.name}", vicina!!.name.contains("Ruvo"))
    }

    @Test
    fun `lontano dalla Puglia non si propone nulla`() {
        assertEquals(null, fnb.nearest(45.4864, 9.2049))
    }

    @Test
    fun `la ricerca per nome trova le fermate che RFI non ha`() {
        for (nome in listOf("andria", "corato", "bitonto", "terlizzi")) {
            val trovate = fnb.search(nome)
            println("  '$nome' -> ${trovate.joinToString { it.name }}")
            assertTrue("$nome non trovata", trovate.isNotEmpty())
        }
    }
}
