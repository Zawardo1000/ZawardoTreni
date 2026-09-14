package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ogni treno del tabellone passa dalla stazione del tabellone, contro le API vere.
 *
 * Sembra ovvio e non lo e'. Il 14/09/2026 le Frecce in partenza da Bologna
 * Centrale (S05043) nel dettaglio della corsa fermavano a "BOLOGNA C.LE/AV"
 * (S05046): stessa stazione, codice diverso. L'app cercava S05043 dentro la
 * corsa e non lo trovava, e il binario confermato restava nero sul tabellone.
 * Vedi `CodiciStazione.kt`.
 *
 * Quel giorno si sono provati 52 nodi e 36 coppie di stazioni vicine, e il
 * doppio codice c'era solo li'. Ma e' il genere di cosa che nasce da un giorno
 * all'altro — una stazione AV nuova, una sotterranea rinumerata — senza che
 * niente si rompa in modo visibile: sparisce solo un binario, o una salita.
 * Questo test lo dice per primo, e la risposta e' aggiungere il codice a
 * `DOPPI`.
 *
 * Come gli altri `…LiveTest`: senza rete o senza treni in giro si sospende.
 */
class StazioniDoppieLiveTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val boardFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH)
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Capolinea e snodi AV: dove un doppio codice farebbe piu' danno. */
    private val nodi = listOf(
        "S01700", // Milano Centrale
        "S01645", // Milano Porta Garibaldi
        "S01820", // Milano Rogoredo
        "S00219", // Torino Porta Nuova
        "S00035", // Torino Porta Susa
        "S02593", // Venezia S. Lucia
        "S02589", // Venezia Mestre
        "S02430", // Verona Porta Nuova
        "S05043", // Bologna Centrale
        "S05254", // Reggio Emilia AV Mediopadana
        "S06421", // Firenze S. M. Novella
        "S08409", // Roma Termini
        "S08217", // Roma Tiburtina
        "S09218", // Napoli Centrale
        "S09988", // Napoli Afragola
        "S09818", // Salerno
        "S04700", // Genova Piazza Principe
    )

    @Test
    fun `ogni treno del tabellone passa dalla stazione del tabellone`() = runBlocking {
        val quando = ZonedDateTime.now().format(boardFormat)
        val sconosciute = mutableListOf<String>()
        var controllati = 0

        for (codice in nodi) {
            val voci = runCatching { NetworkModule.viaggiaTrenoApi.partenze(codice, quando) }
                .getOrDefault(emptyList())
            for (v in voci.take(6)) {
                val origine = v.codOrigine ?: continue
                val millis = v.dataPartenzaTreno ?: continue
                val corsa = runCatching {
                    NetworkModule.viaggiaTrenoApi
                        .andamentoTreno(origine, v.numeroTreno.toString(), millis)
                        .body()
                }.getOrNull() ?: continue
                controllati++
                if (corsa.fermate.any { stessaStazione(it.id, codice) }) continue

                // Dove passa invece, all'ora del tabellone: e' il codice da aggiungere.
                val orario = v.compOrarioPartenza
                val invece = corsa.fermate
                    .filter { f ->
                        listOfNotNull(f.partenzaTeorica, f.arrivoTeorico)
                            .any { Instant.ofEpochMilli(it).atZone(roma).format(hhmm) == orario }
                    }
                    .map { "${it.id} ${it.stazione}" }
                sconosciute += "${v.numeroTreno} da $codice alle $orario: nel dettaglio passa da $invece"
            }
        }

        assumeTrue("nessuna corsa letta: rete assente o nessun treno in giro", controllati > 0)
        println("\n=== STAZIONI DOPPIE: $controllati corse controllate in ${nodi.size} nodi ===")
        sconosciute.forEach { println("  $it") }
        assertTrue(
            "treni che il tabellone mette in una stazione e il dettaglio in un'altra: " +
                "$sconosciute. Se e' la stessa stazione con due codici, va aggiunta a " +
                "`DOPPI` in CodiciStazione.kt.",
            sconosciute.isEmpty(),
        )
    }
}
