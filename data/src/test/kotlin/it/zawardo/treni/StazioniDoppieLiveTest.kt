package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import it.zawardo.treni.domain.model.STAZIONI_TENUTE_DISTINTE
import it.zawardo.treni.domain.model.codiceStazione
import it.zawardo.treni.domain.model.nomeStazioneNormalizzato
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

    /**
     * Il giro a tappeto: tutto il catalogo RFI, non i treni di qualche nodo.
     *
     * Il test qui sopra guarda le corse **del tabellone di un nodo**, e cosi'
     * non poteva vedere Napoli Afragola: il FR 8382 che ferma a "NAPOLI
     * AFRAGOLA PES" sul tabellone di S09988 non compare affatto, perche' i due
     * codici sono i due piazzali e ognuno elenca solo i suoi treni. Segnalato
     * dall'utente il 23/09/2026, dopo che il primo giro — 14/09/2026 — aveva
     * concluso che il doppio codice c'era solo a Bologna.
     *
     * Il metodo non dipende ne' dai treni in giro ne' dalle coordinate di RFI,
     * che per queste stazioni sono sbagliate di chilometri. Si prende il
     * catalogo intero, si normalizzano i nomi (`C.LE` e' `CENTRALE`; i suffissi
     * tecnici `PES`, `AV`, `SOTTERRANEA` si tolgono), e i codici che cosi'
     * diventano lo stesso nome formano un gruppo. Per ogni gruppo si chiede
     * alla ricerca quanti ne conosce: **se ne conosce uno solo, gli altri sono
     * alias** e vanno in `DOPPI`, altrimenti sono stazioni gemelle e fonderle
     * sarebbe l'errore opposto.
     *
     * Si interroga col nome **base**, non col nome intero: Le Frecce chiama
     * S01647 "Milano Porta Garibaldi Passante" mentre RFI lo chiama
     * "SOTTERRANEA", e col nome di RFI sembrerebbe sconosciuto, cioe' un alias
     * da fondere — che e' proprio il contrario del vero.
     */
    @Test
    fun `nessuna stazione del catalogo ha due codici non mappati`() = runBlocking {
        val catalogo = leggiCatalogo()
        assumeTrue("catalogo non letto: rete assente", catalogo.size > 500)

        val gruppi = catalogo.entries.groupBy { nomeStazioneNormalizzato(it.value) }.filterValues { it.size > 1 }
        println()
        println("=== STAZIONI DOPPIE: ${catalogo.size} stazioni, ${gruppi.size} nomi con piu' codici ===")

        val daAggiungere = mutableListOf<String>()
        for ((nome, voci) in gruppi) {
            val codici = voci.map { it.key }
            // Gia' risolto: tutti i codici del gruppo si riducono allo stesso.
            if (codici.map { codiceStazione(it) }.distinct().size == 1) continue

            val note = runCatching { NetworkModule.lefrecceApi.locations(nome) }
                .getOrDefault(emptyList())
                .mapNotNull { it.bdoCode?.uppercase() }
                .toSet()
            val conosciuti = codici.filter { it in note }
            val ignoti = codici.filter { it !in note && it !in STAZIONI_TENUTE_DISTINTE }
            if (conosciuti.size == 1 && ignoti.isNotEmpty()) {
                /*
                 * La riga gia' pronta da incollare, ma **da guardare prima**:
                 * questo giro trova le coppie, non decide. Su Genova Piazza
                 * Principe diceva «unisci» e la risposta giusta era «sono
                 * gemelle»: vedi `TENUTE_DISTINTE` e `DOPPI`.
                 */
                daAggiungere += ignoti.joinToString("; ") { ignoto ->
                    "\"$ignoto\" to \"${conosciuti.first()}\", // $nome"
                }
            }
        }
        daAggiungere.forEach { println("  $it") }
        assertTrue(
            "stazioni con due codici che la ricerca non distingue. Le righe sono gia' " +
                "pronte per `DOPPI` in CodiciStazione.kt, ma prima si guarda **cosa sono**: " +
                "due nomi identici sono una rinumerazione e si uniscono, un nome con una " +
                "qualifica (SOTTERRANEA, PASSANTE) possono essere due piani di binari, e " +
                "unirli manderebbe al piano sbagliato. Se sono gemelle vanno in " +
                "`STAZIONI_TENUTE_DISTINTE` in CodiciStazione.kt, col perche'. Trovate: $daAggiungere",
            daAggiungere.isEmpty(),
        )
    }

    /**
     * Il catalogo RFI, regione per regione, letto qui e non dall'API di
     * produzione: e' un endpoint che l'app non usa, e allargare l'interfaccia
     * per un test vorrebbe dire allargare anche i quattro finti ViaggiaTreno
     * che la implementano.
     */
    private fun leggiCatalogo(): Map<String, String> {
        val json = Json { ignoreUnknownKeys = true }
        val fuori = mutableMapOf<String, String>()
        for (regione in 0..22) {
            val corpo = runCatching {
                java.net.URI("http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/elencoStazioni/$regione")
                    .toURL().readText()
            }.getOrNull() ?: continue
            val righe = runCatching { json.decodeFromString<List<StazioneCatalogo>>(corpo) }
                .getOrDefault(emptyList())
            for (r in righe) {
                val codice = r.codiceStazione?.takeIf { it.startsWith("S") } ?: continue
                val nome = r.localita?.nomeLungo?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                fuori.putIfAbsent(codice, nome)
            }
        }
        return fuori
    }

    @Serializable
    private data class StazioneCatalogo(
        val codiceStazione: String? = null,
        val localita: LocalitaCatalogo? = null,
    )

    @Serializable
    private data class LocalitaCatalogo(val nomeLungo: String? = null)

}
