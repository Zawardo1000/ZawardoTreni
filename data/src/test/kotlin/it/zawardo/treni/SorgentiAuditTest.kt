package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.FnbRepository
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.SvizzeraRepository
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * L'audit trasversale delle sorgenti.
 *
 * Non verifica una sorgente alla volta — per quello ci sono i test dedicati —
 * ma le proprieta' che devono valere per **tutte insieme**, e che nessun test
 * di una singola sorgente puo' cogliere:
 *
 *  - chi dichiara di avere il tempo reale ce l'ha davvero, e chi non ce l'ha lo
 *    dice invece di lasciar credere che i suoi treni siano puntuali;
 *  - ogni rete risponde solo per le stazioni sue, e non ne rivendica di altre;
 *  - i codici sintetici delle reti fuori RFI non si scontrano fra loro.
 *
 * E' l'insieme di controlli che, se salta, produce il difetto peggiore che
 * questa app possa avere: dati veri attribuiti alla corsa sbagliata.
 */
class SorgentiAuditTest {

    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val eav = EavRepository(NetworkModule.eavApi)
    private val fnb = FnbRepository(NetworkModule.fnbApi)
    private val svizzera = SvizzeraRepository(NetworkModule.svizzeraApi)
    private val arst = ArstRepository()

    /** Una stazione rappresentativa per ciascuna rete fuori dalla nazionale. */
    private val campioni = mapOf(
        DataSource.EAV to "EAV1",
        DataSource.ARST to arstCampione(),
        DataSource.FNB to fnbCampione(),
    )

    /**
     * Sassari, non Cagliari: la rete ARST a scartamento ridotto e' quella
     * Sassari-Alghero, Macomer-Nuoro e Monserrato-Isili. A Cagliari ci si
     * arriva con Trenitalia, e cercarla qui darebbe zero facendo passare a
     * vuoto il controllo sul tempo reale invece di eseguirlo.
     */
    private fun arstCampione(): String =
        arst.search("Sassari").firstOrNull()?.rfiCode ?: "ARST22602"

    private fun fnbCampione(): String =
        fnb.search("Bari").firstOrNull()?.rfiCode ?: "FNB1110"

    // ------------------------------------------------------------ territori

    @Test
    fun `nessuna rete rivendica le stazioni di un'altra`() {
        val codici = campioni.values.toList() + listOf("S01700", "S09218")
        println("\n=== TERRITORI ===")
        for (c in codici) {
            val chi = buildList {
                if (eav.covers(c)) add("EAV")
                if (fnb.covers(c)) add("FNB")
                if (arst.covers(c)) add("ARST")
                if (svizzera.soloSvizzera(c)) add("SVIZZERA")
            }
            println("  %-12s -> %s".format(c, chi.ifEmpty { listOf("rete nazionale") }))
            assertTrue(
                "il codice $c e' rivendicato da piu' reti: $chi",
                chi.size <= 1,
            )
        }
    }

    @Test
    fun `i codici RFI non finiscono a una rete fuori RFI`() {
        // Milano Centrale e Napoli Centrale sono RFI pure: nessuna rete
        // sintetica deve riconoscerle, altrimenti il tabellone nazionale
        // verrebbe spento proprio dove ha tutto da dire.
        for (rfi in listOf("S01700", "S09218", "S05043", "S08409")) {
            assertTrue("EAV rivendica $rfi", !eav.covers(rfi))
            assertTrue("FNB rivendica $rfi", !fnb.covers(rfi))
            assertTrue("ARST rivendica $rfi", !arst.covers(rfi))
        }
    }

    @Test
    fun `i prefissi sintetici sono distinti`() {
        /*
         * Ogni rete fuori RFI ha un prefisso suo. Se due si sovrapponessero, un
         * codice verrebbe risolto dalla rete sbagliata e il tabellone di
         * Sorrento mostrerebbe treni sardi. Il controllo e' banale proprio
         * perche' l'errore sarebbe catastrofico e silenzioso.
         */
        val prefissi = campioni.values.map { it.takeWhile { c -> !c.isDigit() } }
        println("\n=== PREFISSI: ${prefissi.joinToString()} ===")
        assertEquals("due reti usano lo stesso prefisso", prefissi.size, prefissi.distinct().size)
    }

    // ------------------------------------------------------------- realtime

    @Test
    fun `ARST dichiara di non avere tempo reale, su ogni riga`() = runBlocking {
        val codice = campioni[DataSource.ARST]!!
        val righe = arst.board(codice, date = LocalDate.now())
        println("\n=== ARST $codice: ${righe.size} righe ===")
        if (righe.isEmpty()) return@runBlocking
        assertTrue(
            "ARST non pubblica tempo reale: nessuna riga puo' dichiararlo",
            righe.none { it.realtime },
        )
        assertTrue(
            "una riga senza tempo reale non puo' portare un ritardo misurato",
            righe.all { it.delayMinutes == 0 },
        )
    }

    @Test
    fun `EAV dichiara il tempo reale solo quando viene dal tabellone`() = runBlocking {
        val oggi = eav.board("EAV1", date = LocalDate.now())
        val domani = eav.board("EAV1", date = LocalDate.now().plusDays(1))
        val senzaMonitor = eav.board("EAV430", date = LocalDate.now())

        println("\n=== EAV ===")
        println("  oggi (tabellone):        ${oggi.size} righe, realtime=${oggi.map { it.realtime }.distinct()}")
        println("  domani (orario):         ${domani.size} righe, realtime=${domani.map { it.realtime }.distinct()}")
        println("  senza monitor (orario):  ${senzaMonitor.size} righe, realtime=${senzaMonitor.map { it.realtime }.distinct()}")

        assertTrue("domani non puo' essere tempo reale", domani.none { it.realtime })
        assertTrue("una stazione senza monitor non ha tempo reale", senzaMonitor.none { it.realtime })
        assertTrue(
            "una riga d'orario non puo' portare un ritardo misurato",
            (domani + senzaMonitor).all { it.delayMinutes == 0 },
        )
    }

    @Test
    fun `le reti in tempo reale non marcano le righe come previsioni`() = runBlocking {
        /*
         * Il rovescio del controllo precedente: chi il tempo reale ce l'ha non
         * deve dichiararsi incerto, altrimenti l'app nasconderebbe ritardi che
         * conosce. Si guarda una stazione per rete; se una non risponde in
         * questo momento non e' un fallimento, ma se risponde deve dirlo.
         */
        val campione = fnb.board(campioni[DataSource.FNB]!!, arrivals = false)
        println("\n=== FNB: ${campione.size} righe ===")
        if (campione.isNotEmpty()) {
            assertTrue(
                "Ferrotramviaria pubblica ritardo e binario: le righe sono tempo reale",
                campione.all { it.realtime },
            )
        }

        val ntv = italo.board("S01700", arrivals = false)
        println("=== ITALO Milano Centrale: ${ntv.size} righe ===")
        if (ntv.isNotEmpty()) {
            assertTrue("Italo pubblica il tabellone: e' tempo reale", ntv.all { it.realtime })
        }
    }

    // ------------------------------------------------------- coerenza righe

    @Test
    fun `nessuna riga esce senza numero di treno o senza orario`() = runBlocking {
        val tutte = buildList<BoardEntry> {
            addAll(eav.board("EAV1"))
            addAll(eav.board("EAV1", date = LocalDate.now().plusDays(1)))
            addAll(arst.board(campioni[DataSource.ARST]!!))
            addAll(runCatching { fnb.board(campioni[DataSource.FNB]!!) }.getOrDefault(emptyList()))
        }
        println("\n=== COERENZA su ${tutte.size} righe di quattro fonti ===")
        assertTrue("ci sono righe senza numero", tutte.all { it.trainRef.number.isNotBlank() })
        /*
         * L'orario puo' mancare — qualche fonte non lo pubblica su tutte le
         * righe — ma quando c'e' deve essere un orario, non una stringa
         * qualunque: e' quello su cui si ordina il tabellone.
         */
        val orariStorti = tutte.mapNotNull { it.scheduledTime }
            .filterNot { it.matches(Regex("""([01]\d|2[0-3]):[0-5]\d""")) }
        assertTrue("orari non interpretabili: $orariStorti", orariStorti.isEmpty())
    }

    @Test
    fun `ogni rete opzionale ha una etichetta e un dettaglio da mostrare`() {
        /*
         * L'elenco delle fonti nelle impostazioni si costruisce da qui: una
         * voce senza descrizione sarebbe un interruttore anonimo, e chi deve
         * decidere se accenderlo non avrebbe su cosa decidere.
         */
        println("\n=== SORGENTI ===")
        DataSource.entries.forEach {
            println(
                "  %-12s opzionale=%-5s default=%-5s  %s".format(
                    it.name, it.opzionale, it.accesaDiDefault, it.detail,
                ),
            )
            assertTrue("${it.name} senza etichetta", it.label.isNotBlank())
            assertTrue("${it.name} senza dettaglio", it.detail.isNotBlank())
        }
        assertEquals(
            "dovrebbe esserci una sola rete non spegnibile",
            1,
            DataSource.entries.count { !it.opzionale },
        )
        assertTrue(
            "la rete non spegnibile deve essere sempre attiva",
            DataSource.sempreAttive.all { !it.opzionale },
        )
        assertTrue(
            "le reti sempre attive devono stare fra quelle accese di default",
            DataSource.defaultEnabled.containsAll(DataSource.sempreAttive),
        )
    }
}
