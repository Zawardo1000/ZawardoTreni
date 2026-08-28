package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.SvizzeraRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La Vigezzina - Svizzera contro l'orario svizzero.
 *
 * La sorgente e' dichiaratamente non ufficiale, quindi questi test servono a
 * due cose: accorgersi se smette di rispondere, e accorgersi se comincia a
 * rispondere **troppo** — a Domodossola il rischio non e' il vuoto, e' che
 * entrino i treni di Trenitalia che l'app ha gia' da ViaggiaTreno.
 */
class SvizzeraLiveTest {

    private val svizzera = SvizzeraRepository(NetworkModule.svizzeraApi)

    /** Domodossola, dove la linea convive con la rete nazionale. */
    private val domodossola = "CH8301003"

    /** Santa Maria Maggiore, in mezzo alla Val Vigezzo. */
    private val santaMariaMaggiore = "CH8505581"

    @Test
    fun `il tabellone di Santa Maria Maggiore risponde con corse vere`() = runBlocking {
        val righe = svizzera.board(santaMariaMaggiore)
        println("\n=== VIGEZZINA PARTENZE S. MARIA MAGGIORE (${righe.size}) ===")
        righe.take(10).forEach {
            println(
                "  %-6s %-18s %-22s bin %-4s %s  %s".format(
                    it.trainRef.number,
                    it.category ?: "-",
                    it.direction ?: "-",
                    it.actualPlatform ?: "-",
                    it.scheduledTime ?: "--:--",
                    if (it.delayMinutes > 0) "+${it.delayMinutes}" else "",
                ),
            )
        }

        assertTrue("il tabellone non ha restituito corse", righe.isNotEmpty())
        assertTrue(
            "nessuna riga ha un orario valido: il formato con offset +0200 e' cambiato",
            righe.any { it.scheduledTime?.matches(Regex("""\d{2}:\d{2}""")) == true },
        )
        assertTrue(
            "nessuna corsa ha una direzione",
            righe.any { !it.direction.isNullOrBlank() },
        )
    }

    /**
     * A Domodossola devono comparire solo i treni della Vigezzina.
     *
     * L'orario svizzero, li', risponde anche con SBB, BLS e Trenitalia: quelle
     * corse sono su rete RFI e ViaggiaTreno le pubblica gia'. Senza il filtro
     * sul vettore il tabellone le mostrerebbe due volte, con due orari che non
     * sempre coincidono.
     *
     * Il controllo si fa sulla risposta grezza, non su quella filtrata: serve
     * sapere che il filtro sta togliendo qualcosa davvero.
     */
    @Test
    fun `a Domodossola non entrano i treni di altri vettori`() = runBlocking {
        val grezzo = NetworkModule.svizzeraApi.stationboard("8301003")
        val vettori = grezzo.stationboard.mapNotNull { it.operator }.toSet()
        println("\n=== DOMODOSSOLA, vettori nella risposta grezza: $vettori ===")

        val righe = svizzera.board(domodossola)
        println("=== dopo il filtro: ${righe.size} corse su ${grezzo.stationboard.size} ===")
        righe.take(6).forEach {
            println("  ${it.label} -> ${it.direction} ${it.scheduledTime}")
        }

        assertTrue(
            "la risposta grezza non contiene piu' altri vettori: il test non prova piu' niente",
            vettori.any { !it.uppercase().startsWith("FART") && !it.uppercase().startsWith("SSIF") },
        )
        assertTrue(
            "il filtro ha lasciato passare tutto",
            righe.size < grezzo.stationboard.size,
        )
    }

    /**
     * Gli arrivi restano vuoti apposta.
     *
     * L'orario svizzero, per un arrivo, continua a dare il capolinea come
     * direzione: mostrarlo direbbe a chi aspetta che il treno viene da dove sta
     * andando. Se un giorno cominciasse a dare l'origine, questa scelta si puo'
     * rivedere — ma va rivista, non lasciata cadere.
     */
    @Test
    fun `il tabellone degli arrivi e' vuoto per scelta`() = runBlocking {
        val arrivi = svizzera.board(santaMariaMaggiore, arrivals = true)
        assertEquals(emptyList<Any>(), arrivi)
    }

    @Test
    fun `fuori dalla Vigezzina non si interroga nessuno`() = runBlocking {
        assertEquals(emptyList<Any>(), svizzera.board("S01700"))
        assertTrue("Milano Centrale non e' Vigezzina", !svizzera.covers("S01700"))
        assertTrue("Santa Maria Maggiore lo e'", svizzera.covers(santaMariaMaggiore))
    }

    @Test
    fun `per una data diversa da oggi non si inventa l'orario`() = runBlocking {
        val domani = java.time.LocalDate.now().plusDays(1)
        assertEquals(emptyList<Any>(), svizzera.board(santaMariaMaggiore, date = domani))
    }

    /**
     * In Val Vigezzo la fermata piu' vicina e' della Vigezzina.
     *
     * Da Santa Maria Maggiore la stazione RFI piu' vicina e' Domodossola, a
     * venti chilometri di valle: senza questo elenco l'app manderebbe li'.
     */
    @Test
    fun `la fermata piu' vicina a Santa Maria Maggiore e' sulla linea`() {
        val vicina = svizzera.nearest(46.1370, 8.4610)
        println("\n=== PIU' VICINA IN VAL VIGEZZO: ${vicina?.name} (${vicina?.rfiCode}) ===")
        assertTrue("nessuna fermata trovata", vicina != null)
        assertTrue("dovrebbe essere in valle, e' ${vicina?.name}", vicina!!.name.contains("Maria"))
    }

    @Test
    fun `lontano dalla linea non si propone nulla`() {
        assertEquals(null, svizzera.nearest(45.4864, 9.2049))
    }

    /**
     * Domodossola compare col vettore nel nome.
     *
     * Ce ne sono due, e sono due tabelloni con treni diversi: quella RFI arriva
     * da ViaggiaTreno, questa dall'orario svizzero. Senza distinzione nel nome,
     * chi cerca non sa quale sta scegliendo.
     */
    @Test
    fun `Domodossola si distingue da quella RFI`() {
        val trovate = svizzera.search("domodossola")
        println("\n=== RICERCA 'domodossola': ${trovate.joinToString { it.name }} ===")
        assertTrue("Domodossola non trovata", trovate.isNotEmpty())
        assertTrue(
            "il nome non distingue le due stazioni",
            trovate.first().name.contains("Vigezzina"),
        )
    }

    @Test
    fun `la ricerca trova i paesi della Val Vigezzo`() {
        for (nome in listOf("malesco", "druogno", "intragna", "camedo")) {
            val trovate = svizzera.search(nome)
            println("  '$nome' -> ${trovate.joinToString { it.name }}")
            assertTrue("$nome non trovata", trovate.isNotEmpty())
        }
    }

    // ---------------------------------------------------------------- TILO

    /** Lugano: la stazione che nessun'altra sorgente dell'app conosce. */
    private val lugano = "CH8505300"

    @Test
    fun `il tabellone di Lugano risponde con le linee S`() = runBlocking {
        val righe = svizzera.board(lugano)
        println("\n=== TILO PARTENZE LUGANO (${righe.size}) ===")
        righe.take(8).forEach {
            println(
                "  %-10s %-24s bin %-4s %s  %s".format(
                    it.label,
                    (it.direction ?: "-").take(22),
                    it.actualPlatform ?: "-",
                    it.scheduledTime ?: "--:--",
                    if (it.delayMinutes > 0) "+${it.delayMinutes}" else "",
                ),
            )
        }
        assertTrue("il tabellone di Lugano e' vuoto", righe.isNotEmpty())
        assertTrue(
            "nessuna riga ha un orario valido",
            righe.any { it.scheduledTime?.matches(Regex("""\d{2}:\d{2}""")) == true },
        )
    }

    /**
     * Lo stesso vettore, tenuto in un posto e scartato nell'altro.
     *
     * E' la ragione per cui il filtro sta sulla stazione e non e' globale: a
     * Domodossola SBB sono gli EuroCity su rete RFI, che ViaggiaTreno pubblica
     * gia'; a Lugano SBB sono le linee S, che non ha nessun altro. Un filtro
     * unico avrebbe dovuto sbagliare da una delle due parti.
     */
    @Test
    fun `SBB si scarta a Domodossola e si tiene a Lugano`() = runBlocking {
        val aDomodossola = svizzera.board(domodossola)
        val aLugano = svizzera.board(lugano)
        println(
            "\n=== SBB: ${aDomodossola.size} corse a Domodossola, " +
                "${aLugano.size} a Lugano ===",
        )
        assertTrue("a Lugano non passa nulla: il filtro scarta anche SBB", aLugano.isNotEmpty())
        assertTrue("a Domodossola non passa nulla", aDomodossola.isNotEmpty())
    }

    /**
     * Chiasso resta una stazione sola.
     *
     * Ha un codice RFI vero (`S01301`) e ViaggiaTreno ci risponde con quattordici
     * corse verso l'Italia. La fonte svizzera ci aggiunge le linee S verso nord,
     * ma **non deve** proporre una seconda "Chiasso" in ricerca.
     */
    @Test
    fun `Chiasso e Bellinzona non diventano stazioni doppie`() = runBlocking {
        for (codice in listOf("S01301", "S00300")) {
            assertTrue("$codice dovrebbe essere coperto", svizzera.covers(codice))
            assertTrue(
                "$codice non deve risultare esclusiva della fonte svizzera",
                !svizzera.soloSvizzera(codice),
            )
        }
        val trovate = svizzera.search("chiasso") + svizzera.search("bellinzona")
        println("\n=== RICERCA LOCALE 'chiasso'/'bellinzona': ${trovate.size} risultati ===")
        assertTrue(
            "la fonte svizzera propone stazioni che il catalogo Trenitalia ha gia'",
            trovate.isEmpty(),
        )
    }

    @Test
    fun `il tabellone di Chiasso riceve anche le corse svizzere`() = runBlocking {
        val righe = svizzera.board("S01301")
        println("\n=== CHIASSO, quota svizzera: ${righe.size} corse ===")
        righe.take(6).forEach { println("  ${it.label} -> ${it.direction} ${it.scheduledTime}") }
        assertTrue("a Chiasso la fonte svizzera non aggiunge nulla", righe.isNotEmpty())
    }

    @Test
    fun `la ricerca trova le stazioni ticinesi che l'Italia non ha`() {
        for (nome in listOf("lugano", "mendrisio", "giubiasco", "melide")) {
            val trovate = svizzera.search(nome)
            println("  '$nome' -> ${trovate.joinToString { it.name }}")
            assertTrue("$nome non trovata", trovate.isNotEmpty())
        }
    }
}
