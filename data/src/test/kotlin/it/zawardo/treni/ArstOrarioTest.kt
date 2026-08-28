package it.zawardo.treni

import it.zawardo.treni.data.remote.arst.ArstGtfsUpdater
import it.zawardo.treni.data.remote.arst.ArstOrario
import it.zawardo.treni.data.repository.ArstRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * L'orario ARST imbarcato, senza toccare la rete.
 *
 * Il feed vero costa 19,7 MB e qui non serve: quello che conta e' che il file
 * compilato nell'APK ci sia, si legga, e copra il giorno in cui l'app viene
 * usata. Se uno di questi test fallisce, il rimedio e'
 * `./gradlew :data:rigeneraOrarioArst`.
 */
class ArstOrarioTest {

    private val arst = ArstRepository()

    /** Sassari, capolinea di due delle quattro linee: qualcosa c'e' sempre. */
    private val sassari: String by lazy {
        arst.search("sassari").firstOrNull()?.rfiCode ?: "ARST0"
    }

    @Test
    fun `l'orario imbarcato si legge ed e' completo`() {
        val orario = ArstOrario.carica(null)
        println("\n=== ARST IMBARCATO ===")
        assertTrue("nessun orario imbarcato: manca arst-orario.gz", orario != null)
        orario!!
        println(
            "  generato ${orario.generato}, ${orario.corse.size} corse, " +
                "${orario.linee.size} linee, ${orario.stazioni.size} stazioni, " +
                "copertura fino al ${orario.ultimoGiorno}",
        )
        orario.linee.forEach { (sigla, nome) -> println("   $sigla  $nome") }

        assertEquals("le linee ferroviarie ARST sono quattro", 4, orario.linee.size)
        assertTrue("troppe poche corse", orario.corse.size > 50)
        assertTrue("troppe poche stazioni", orario.stazioni.size > 20)
        assertTrue(
            "ci sono corse con una fermata sola: non sono viaggi",
            orario.corse.all { it.fermate.size >= 2 },
        )
        assertTrue(
            "ci sono stazioni senza coordinate: la stazione piu' vicina non funzionerebbe",
            orario.stazioni.all { it.lat != 0.0 && it.lon != 0.0 },
        )
    }

    @Test
    fun `l'orario imbarcato copre oggi`() {
        val orario = ArstOrario.carica(null) ?: return
        val oggi = LocalDate.now()
        println("\n=== COPERTURA: oggi=$oggi, ultimo giorno=${orario.ultimoGiorno} ===")
        assertTrue(
            "l'orario imbarcato non copre piu' oggi: va rigenerato " +
                "(./gradlew :data:rigeneraOrarioArst)",
            orario.copre(oggi),
        )
    }

    /**
     * La stessa soglia che usa il telefono.
     *
     * Se scatta qui, l'orario compilato ha passato i tre mesi e il primo utente
     * che apre l'app si vedrebbe scaricare venti megabyte. Rigenerarlo prima
     * della release costa nulla e glieli risparmia.
     */
    @Test
    fun `l'orario imbarcato e' dentro la soglia dei tre mesi`() = runBlocking {
        val cartella = Files.createTempDirectory("arst-test").toFile()
        val orario = ArstOrario.carica(null) ?: return@runBlocking
        val mesi = ChronoUnit.MONTHS.between(orario.generato, LocalDate.now())
        println("\n=== ETA' DELL'ORARIO: $mesi mesi (generato ${orario.generato}) ===")

        val esito = ArstGtfsUpdater(OkHttpClient(), cartella)
            .aggiornaSeVecchio(oggi = orario.generato.plusMonths(2))
        assertTrue(
            "a due mesi non doveva scaricare nulla",
            esito is ArstGtfsUpdater.Esito.AncoraBuono,
        )
        assertTrue(
            "l'orario imbarcato ha superato i tre mesi: va rigenerato " +
                "(./gradlew :data:rigeneraOrarioArst)",
            mesi < ArstGtfsUpdater.MESI_DI_VALIDITA,
        )
    }

    @Test
    fun `il tabellone di Sassari mostra le partenze previste`() = runBlocking {
        val righe = arst.board(sassari)
        println("\n=== ARST PARTENZE ${arst.stationName(sassari)} (${righe.size}) ===")
        righe.take(10).forEach {
            println(
                "  %-8s %-28s %-22s %s".format(
                    it.trainRef.number,
                    (it.category ?: "-").take(26),
                    (it.direction ?: "-").take(20),
                    it.scheduledTime ?: "--:--",
                ),
            )
        }
        assertTrue("nessuna partenza da Sassari", righe.isNotEmpty())
        assertTrue(
            "gli orari non sono nel formato HH:mm",
            righe.all { it.scheduledTime?.matches(Regex("""\d{2}:\d{2}""")) == true },
        )
        assertTrue("le righe sono in disordine", righe == righe.sortedBy { it.scheduledTime })
    }

    /**
     * Il punto piu' importante del lavoro su ARST.
     *
     * Queste corse non hanno tempo reale, e una riga che non lo dichiara viene
     * letta come un treno confermato in orario. Se questo test cade, l'app sta
     * dando per puntuale un treno di cui nessuno sa nulla.
     */
    @Test
    fun `nessuna riga ARST si spaccia per tempo reale`() = runBlocking {
        val righe = arst.board(sassari)
        assertTrue("nessuna riga da controllare", righe.isNotEmpty())
        assertTrue(
            "una riga ARST dichiara di essere in tempo reale",
            righe.none { it.realtime },
        )
        assertTrue(
            "una riga ARST dichiara un binario che ARST non pubblica",
            righe.all { it.actualPlatform == null && it.scheduledPlatform == null },
        )
    }

    /**
     * ARST e' l'unica sorgente che sappia rispondere per domani.
     *
     * Tutte le altre hanno un tabellone, che conosce solo adesso; questa ha un
     * orario. E' il motivo per cui esiste.
     */
    @Test
    fun `risponde anche per una data futura`() = runBlocking {
        val fraUnaSettimana = LocalDate.now().plusDays(7)
        val righe = arst.board(sassari, date = fraUnaSettimana)
        println("\n=== PARTENZE DEL $fraUnaSettimana: ${righe.size} ===")
        assertTrue("l'orario non risponde per fra una settimana", righe.isNotEmpty())
    }

    @Test
    fun `oltre la copertura dell'orario non si inventa niente`() = runBlocking {
        val oltre = (arst.ultimoGiorno() ?: LocalDate.now()).plusDays(1)
        assertEquals(emptyList<Any>(), arst.board(sassari, date = oltre))
    }

    @Test
    fun `gli arrivi sono una lista diversa dalle partenze`() = runBlocking {
        val partenze = arst.board(sassari, arrivals = false)
        val arrivi = arst.board(sassari, arrivals = true)
        println("\n=== SASSARI: ${partenze.size} partenze, ${arrivi.size} arrivi ===")
        assertTrue("arrivi e partenze sono entrambi vuoti", partenze.isNotEmpty() || arrivi.isNotEmpty())
        /*
         * A un capolinea la differenza si vede: le corse che partono non sono
         * quelle che arrivano, e prenderle per le stesse vorrebbe dire mostrare
         * una partenza come se fosse un treno in arrivo.
         */
        assertTrue(
            "arrivi e partenze coincidono: il capolinea non viene scartato",
            partenze.map { it.trainRef.number }.toSet() !=
                arrivi.map { it.trainRef.number }.toSet(),
        )
    }

    @Test
    fun `fuori dalla rete ARST non si risponde`() = runBlocking {
        assertEquals(emptyList<Any>(), arst.board("S01700"))
        assertTrue("Milano Centrale non e' ARST", !arst.covers("S01700"))
        assertTrue("Sassari lo e'", arst.covers(sassari))
    }

    @Test
    fun `la ricerca trova i paesi che RFI non serve`() {
        for (nome in listOf("mandas", "isili", "sorso", "alghero", "nuoro")) {
            val trovate = arst.search(nome)
            println("  '$nome' -> ${trovate.joinToString { it.name }}")
            assertTrue("$nome non trovata", trovate.isNotEmpty())
        }
    }

    @Test
    fun `in Sardegna interna la stazione piu' vicina e' ARST`() {
        // Mandas, 39.6486 / 9.1275
        val vicina = arst.nearest(39.6486, 9.1275)
        println("\n=== PIU' VICINA A MANDAS: ${vicina?.name} (${vicina?.rfiCode}) ===")
        assertTrue("nessuna stazione trovata", vicina != null)
    }

    @Test
    fun `lontano dalla Sardegna non si propone nulla`() {
        assertEquals(null, arst.nearest(45.4864, 9.2049))
    }
}
