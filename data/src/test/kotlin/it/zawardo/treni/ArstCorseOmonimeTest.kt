package it.zawardo.treni

import it.zawardo.treni.data.remote.arst.ArstOrario
import it.zawardo.treni.data.repository.ArstRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * **Il numero di una corsa ARST non basta a identificarla.**
 *
 * ARST numera le corse per linea, e le linee ripartono da capo: nell'orario del
 * 20/09/2026, 48 corse su 117 condividono il numero con un'altra — tutte fra la
 * Monserrato-Mandas-Isili e la Sassari-Sorso, che stanno a duecento chilometri.
 * Chiedendo «AT9» si prendeva la prima delle due, e il tabellone di Sassari
 * apriva una corsa che ferma a Senorbi': stazioni sbagliate, orari sbagliati, e
 * nessun segnale che fosse la corsa di un'altra isola.
 *
 * Qui si verifica che la stazione da cui si sale basti a scegliere quella giusta,
 * e che senza alcun indizio non si tiri a indovinare.
 */
class ArstCorseOmonimeTest {

    private val arst = ArstRepository()
    private val orario = ArstOrario.carica(null)

    /** Un numero condiviso da due corse di linee diverse, nel primo giorno utile. */
    private fun casoAmbiguo(): Triple<LocalDate, String, List<ArstOrario.Corsa>>? {
        val o = orario ?: return null
        var giorno = LocalDate.now()
        repeat(GIORNI_DA_PROVARE) {
            if (o.copre(giorno)) {
                val perNumero = o.corseDel(giorno).groupBy { it.id }
                perNumero.entries
                    .firstOrNull { (_, corse) -> corse.map { it.linea }.distinct().size > 1 }
                    ?.let { return Triple(giorno, it.key, it.value) }
            }
            giorno = giorno.plusDays(1)
        }
        return null
    }

    @Test
    fun `con la stazione di salita si apre la corsa giusta, non l'omonima`() {
        val caso = casoAmbiguo()
        assumeTrue("nessuna corsa omonima nei prossimi giorni: niente da distinguere", caso != null)
        val (giorno, numero, omonime) = caso!!
        val o = orario!!
        println("\n=== ARST: «$numero» il $giorno e' di ${omonime.size} corse ===")

        for (corsa in omonime) {
            val prima = corsa.fermate.first()
            // Il codice pubblico di una fermata ARST: lo stesso che porta il tabellone.
            val salita = "ARST" + prima.stazione
            val quando = giorno.atStartOfDay().plusMinutes(prima.partenza.toLong())
            val letta = arst.dettaglioCorsa(numero, giorno, salita, quando)
            val nome = o.stazione(prima.stazione)?.nome
            println("  da $nome (${corsa.linea}) -> ${letta?.stops?.firstOrNull()?.stationName} .. ${letta?.destination}")

            assertNotNull("salendo da $nome la corsa non si trova affatto", letta)
            assertEquals(
                "salendo da $nome si apre la corsa dell'altra linea: e' il guasto che questo test presidia",
                nome,
                letta!!.stops.first().stationName,
            )
            assertEquals("la categoria e' quella dell'altra linea", o.linee[corsa.linea] ?: corsa.linea, letta.category)
        }
    }

    @Test
    fun `senza sapere da dove si sale non si sceglie a caso`() {
        val caso = casoAmbiguo()
        assumeTrue("nessuna corsa omonima nei prossimi giorni", caso != null)
        val (giorno, numero, _) = caso!!
        // Meglio nessun dato che le fermate di un'altra linea: chi apre la corsa
        // senza sapere da dove sale vedrebbe un percorso plausibile e sbagliato.
        assertTrue(
            "senza stazione di salita si sceglie comunque una delle due corse omonime",
            arst.dettaglioCorsa(numero, giorno) == null,
        )
    }

    @Test
    fun `una corsa con numero unico si apre anche senza indizi`() {
        val o = orario
        assumeTrue("nessun orario imbarcato", o != null)
        var giorno = LocalDate.now()
        var unica: ArstOrario.Corsa? = null
        repeat(GIORNI_DA_PROVARE) {
            if (o!!.copre(giorno) && unica == null) {
                unica = o.corseDel(giorno).groupBy { it.id }
                    .entries.firstOrNull { it.value.size == 1 }?.value?.first()
                if (unica != null) return@repeat
            }
            if (unica == null) giorno = giorno.plusDays(1)
        }
        assumeTrue("nessuna corsa dal numero unico", unica != null)
        val letta = arst.dettaglioCorsa(unica!!.id, giorno)
        assertNotNull("una corsa che nessun'altra condivide deve aprirsi col solo numero", letta)
        assertTrue("percorso troppo corto per essere una corsa", letta!!.stops.size >= 2)
    }

    private companion object {
        /** Quanti giorni provare per trovare un caso: l'orario non copre tutti i calendari ogni giorno. */
        const val GIORNI_DA_PROVARE = 10
    }
}
