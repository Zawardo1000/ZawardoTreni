package it.zawardo.treni.data.remote.eav

import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState

/**
 * Estrae le righe del tabellone dall'HTML di EAV.
 *
 * Vale la stessa scelta fatta per Trenord: espressioni regolari invece di un
 * parser HTML, perche' aggiungere una dipendenza per due endpoint non si
 * ripaga. E vale la stessa precauzione — **fallire in silenzio**: se il markup
 * cambia, le righe non vengono trovate e il tabellone resta vuoto, senza
 * eccezioni e senza dati inventati.
 *
 * Qui pero' si ricava molto piu' che da Trenord: EAV pubblica ritardo, binario
 * e soppressione, cioe' esattamente le cose che servono a chi e' sul marciapiede.
 */
internal object EavBoardParser {

    /** Ogni corsa e' una `<tr>`. */
    private val RIGA = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Le celle non portano la classe tutte allo stesso modo.
     *
     * Quasi tutte sono `<td class="orario">`, ma la destinazione e'
     * `<td ><div class="destinazione">`: la classe sta sul figlio, e cercarla
     * sul `<td>` la faceva sparire in silenzio — il tabellone mostrava corse
     * senza dire dove andassero. Si accettano quindi entrambe le forme.
     */
    private fun cella(nome: String) = listOf(
        Regex("""<td[^>]*class="$nome"[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL),
        Regex("""<div[^>]*class="$nome"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL),
    )

    private val NUM_TRENO = cella("numTreno")
    private val CATEGORIA = cella("categoria")
    private val DESTINAZIONE = cella("destinazione")
    private val INFORMAZIONI = cella("informazioni")
    private val BINARIO = cella("binario")
    private val ORARIO = cella("orario")
    private val RITARDO = cella("ritardo")

    private val TAG = Regex("""<[^>]*>""")
    private val ORA = Regex("""^([01]?\d|2[0-3]):[0-5]\d$""")

    /**
     * Le parole con cui EAV dichiara una soppressione, in `informazioni` e in
     * `ritardo`. Compare in entrambe, e basta una delle due.
     */
    private const val SOPPRESSO = "SOPPRESSO"

    /**
     * Interpreta la risposta di `ws_getData.php`.
     *
     * [departureDateMillis] e' il giorno di riferimento: il tabellone non lo
     * dichiara mai, perche' per lui esiste solo adesso.
     */
    fun parse(html: String?, departureDateMillis: Long): List<BoardEntry> {
        if (html.isNullOrBlank()) return emptyList()
        return RIGA.findAll(html)
            .mapNotNull { riga(it.groupValues[1], departureDateMillis) }
            .toList()
    }

    private fun riga(tr: String, departureDateMillis: Long): BoardEntry? {
        /*
         * Le righe vuote non sono un caso limite, sono la norma.
         *
         * Il tabellone impagina sempre al numero richiesto e riempie il resto
         * di <tr> con tutte le celle vuote: una stazione senza servizio ne
         * restituisce quaranta. Senza questo scarto l'app mostrerebbe quaranta
         * corse fantasma — verificato su Pozzuoli.
         */
        val numero = testo(NUM_TRENO, tr)?.takeIf { it.isNotBlank() } ?: return null

        val orario = testo(ORARIO, tr)?.takeIf { ORA.matches(it) }
        val destinazione = testo(DESTINAZIONE, tr)?.takeIf { it.isNotBlank() }
        val informazioni = testo(INFORMAZIONI, tr).orEmpty()
        val ritardoGrezzo = testo(RITARDO, tr).orEmpty()

        val soppresso = informazioni.contains(SOPPRESSO, ignoreCase = true) ||
            ritardoGrezzo.contains(SOPPRESSO, ignoreCase = true)

        /*
         * Il ritardo e' un numero nudo di minuti, quando c'e'.
         *
         * Ma nella stessa cella capita di trovare `SOPPRESSO` o la stringa
         * `RIT.`, che e' un'intestazione sfuggita al markup. Si accetta solo
         * cio' che e' interamente numerico: tutto il resto vale zero, che e'
         * l'unica risposta onesta quando non si e' capito.
         */
        val ritardo = ritardoGrezzo.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val categoria = testo(CATEGORIA, tr)?.takeIf { it.isNotBlank() }
        val binario = testo(BINARIO, tr)?.takeIf { it.isNotBlank() }

        return BoardEntry(
            trainRef = TrainRef(
                number = numero,
                // EAV non espone un codice di origine: la corsa si risolve per
                // numero, che e' anche la chiave del GTFS.
                originCode = "",
                departureDateMillis = departureDateMillis,
            ),
            label = listOfNotNull(etichettaCategoria(categoria), numero).joinToString(" "),
            category = categoria,
            direction = destinazione,
            scheduledTime = orario,
            delayMinutes = ritardo,
            // EAV pubblica un solo binario, quello vero: non esiste il
            // programmato da confrontare col reale.
            scheduledPlatform = null,
            actualPlatform = binario,
            state = when {
                soppresso -> TrainState.CANCELLED
                ritardo > 0 -> TrainState.DELAYED
                else -> TrainState.REGULAR
            },
            // Il tabellone non dice se il treno e' gia' in banchina.
            inStation = false,
        )
    }

    /**
     * Le sigle di categoria EAV, sciolte.
     *
     * `A` e `DD` sono termini di mestiere che fuori dalla Circumvesuviana non
     * dicono niente a nessuno; `EXP` e' il Campania Express, il servizio
     * turistico a prezzo diverso, e confonderlo con un diretto qualunque
     * significherebbe far salire un pendolare su un treno che gli costa il
     * doppio.
     */
    private fun etichettaCategoria(sigla: String?): String? = when (sigla?.uppercase()) {
        "A" -> "Accelerato"
        "DD" -> "Direttissimo"
        "EXP" -> "Campania Express"
        else -> sigla
    }

    /**
     * Il testo di una cella, ripulito da tag e entita'.
     *
     * I `<marquee>` sono ovunque — EAV li usa per far scorrere il testo lungo —
     * e i numeri di treno arrivano preceduti da `&nbsp;`.
     */
    private fun testo(regex: List<Regex>, tr: String): String? =
        regex.firstNotNullOfOrNull { it.find(tr) }?.groupValues?.get(1)
            ?.replace(TAG, " ")
            ?.replace("&nbsp;", " ")
            ?.replace("&amp;", "&")
            ?.replace("&egrave;", "e")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()

    /**
     * Le note di percorso, separate dallo stato.
     *
     * `informazioni` impasta due cose diverse con un ` - `: l'instradamento
     * ("VIA POMPEI", "VIA SCAFATI"), che su una rete con tre modi di arrivare a
     * Torre Annunziata e' l'informazione decisiva, e lo stato della corsa
     * ("SOPPRESSO", "IN RITARDO"), che l'app rappresenta gia' per conto suo.
     * Ripetere il secondo accanto al primo sarebbe rumore.
     */
    fun noteDiPercorso(informazioni: String?): String? = informazioni
        ?.split(" - ")
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.filterNot {
            it.contains(SOPPRESSO, ignoreCase = true) ||
                it.startsWith("IN RITARDO", ignoreCase = true)
        }
        ?.joinToString(" · ")
        ?.takeIf { it.isNotBlank() }
}
