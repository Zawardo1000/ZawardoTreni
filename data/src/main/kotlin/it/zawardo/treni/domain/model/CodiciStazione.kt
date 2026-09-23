package it.zawardo.treni.domain.model

/**
 * Le stazioni che ViaggiaTreno scrive con due codici, ridotte a uno.
 *
 * **Bologna Centrale e' S05043** nel tabellone, nella ricerca stazioni e in Le
 * Frecce. Le Frecce che partono dalla stazione AV sotterranea, pero', nel
 * dettaglio della corsa passano da "BOLOGNA C.LE/AV", **S05046**. Sondato il
 * 14/09/2026: sul tabellone di S05043 due Frecce su nove avevano nel dettaglio
 * soltanto S05046. Col codice diverso l'app non trovava la fermata dentro la
 * corsa, e il binario confermato — "19" e "19" nel dettaglio, pochi minuti
 * prima di partire — restava nero sul tabellone e verde aprendo il treno.
 *
 * **Napoli Afragola e' S09988**, ma il FR 8382 delle 07:00 per Lecce — che
 * dall'AV scende sulla linea per Benevento — nel dettaglio ferma a "NAPOLI
 * AFRAGOLA PES", **S09041**. Segnalato il 23/09/2026: salendo ad Afragola il
 * dettaglio non trovava la fermata, quindi niente «Sali» e niente fascia gialla
 * del tratto. E' lo stesso difetto di Bologna, ma su un treno che dal tabellone
 * di S09988 **non si vede nemmeno**: i due codici li' non sono due nomi della
 * stessa banchina, sono i due piazzali, quello AV e quello dei regionali, e ogni
 * tabellone elenca solo i suoi treni.
 *
 * **Il criterio per unire due codici e' cosa ne sa la ricerca, non la distanza.**
 * Le Frecce conosce una sola Napoli Afragola e una sola Bologna Centrale: chi
 * cerca riceve quel codice, e se la corsa ne usa un altro l'aggancio salta.
 * Conosce invece **entrambe** Milano Porta Garibaldi e la sua sotterranea, che
 * infatti sono stazioni gemelle e l'app le distingue apposta (vedi
 * `comeDistinguerlaDa`): fonderle sarebbe l'errore opposto.
 *
 * Non ci si puo' affidare alle coordinate di RFI. Sondato il catalogo intero il
 * 23/09/2026, 3.354 stazioni: "NAPOLI AFRAGOLA PES" e' dato a 1,7 km dalla sua
 * base, "VALLE DI MADDALONI PES" a 3,6 km, e "GAMBERALE-S.ANGELO DEL PESCO" ha
 * latitudine −9, in mezzo all'Atlantico. Il primo giro, il 14/09/2026, cercava
 * il doppio codice fra i treni del tabellone di un nodo e trovo' solo Bologna:
 * non poteva trovare Afragola, perche' quel treno sul tabellone di S09988 non
 * compare.
 *
 * **Il giro fatto per intero, il 23/09/2026**, e' quello che ha riempito questa
 * tabella: catalogo RFI completo, nomi normalizzati (`C.LE` e' `CENTRALE`, e i
 * suffissi tecnici `PES`, `AV`, `SOTTERRANEA` si tolgono), gruppi di codici che
 * cosi' diventano lo stesso nome, e per ogni gruppo la domanda alla ricerca.
 * Nove gruppi, e in tutti Le Frecce conosce **un codice solo**. Che il metodo
 * funzioni lo dice il fatto che Bologna la ritrova da se'.
 *
 * Due avvertenze imparate sbagliando, nello stesso giro. La prima: **il nome
 * della ricerca non e' quello di RFI** — "MILANO PORTA GARIBALDI SOTTERRANEA"
 * per Le Frecce e' "Milano Porta Garibaldi Passante", e chiedendolo col nome di
 * RFI sembrava sconosciuto, cioe' un alias da fondere. Sarebbe stato l'errore
 * opposto, perche' quelle due sono gemelle vere e l'app le distingue apposta
 * (vedi `comeDistinguerlaDa`): si interroga col nome **base**, non col nome
 * intero. La seconda: quasi tutti i `PES` la ricerca li conosce — Vigna Clara,
 * Ognina, Europa, Palermo Politeama — e Afragola e' l'unico che no. Il suffisso
 * non e' il criterio; il criterio e' cosa ne sa la ricerca.
 *
 * Se ne nasce uno nuovo lo dice `StazioniDoppieLiveTest`, che rifa' questo giro.
 */
private val DOPPI: Map<String, String> = mapOf(
    // In circolazione oggi: qui il difetto si vede.
    "S05046" to "S05043", // BOLOGNA C.LE/AV            -> BOLOGNA CENTRALE
    "S09041" to "S09988", // NAPOLI AFRAGOLA PES        -> NAPOLI AFRAGOLA
    /*
     * **Nome identico**, senza qualifiche: il codice vecchio e quello nuovo
     * della stessa fermata, rimasti tutti e due nel catalogo dopo un raddoppio
     * o uno spostamento di sede. Nessuno di questi ha treni oggi, ma e' lo
     * stesso difetto in attesa di un cambio d'orario: Afragola e' stata silente
     * per mesi prima di rompersi.
     *
     * E' anche il taglio che separa questi dai casi qui sotto: **due nomi
     * identici sono una rinumerazione; un nome con una qualifica — SOTTERRANEA,
     * PASSANTE — possono essere due piani di binari veri**, e li' si guarda in
     * faccia prima di unire.
     */
    "S19007" to "S02115", // VERSCIACO-ELMO        -> VERSCIACO-ELMO        (0 m)
    "S19005" to "S13653", // MACCAGNO              -> MACCAGNO              (0 m)
    "S07553" to "S07023", // MONSAMPOLO DEL TRONTO -> MONSAMPOLO DEL TRONTO (1 m)
    "S12018" to "S12070", // LASCARI               -> LASCARI               (1 m)
    "S12128" to "S12145", // CAPACI                -> CAPACI                (4 m)
    "S12048" to "S12054", // SPADAFORA             -> SPADAFORA           (629 m)
    "S09215" to "S09042", // ACERRA                -> ACERRA              (730 m)
)

/**
 * Le coppie trovate dal giro a tappeto e **lasciate fuori apposta**, per non
 * fare l'errore opposto. Il perche' di ognuna e' qui sotto.
 *
 * Sta in produzione, e non nel test che le scopre, perche' la leggono in due: il
 * test e il controllo agganciato alla release (`controllaStazioniDoppie`). Due
 * copie della stessa decisione divergerebbero, e a divergere sarebbe proprio la
 * parte che dice «questa non si tocca».
 */
val STAZIONI_TENUTE_DISTINTE: Set<String> = setOf(
    "S04701", // Genova Piazza Principe sotterranea: gemella, non alias
    "S09304", // Valle di Maddaloni: coordinate RFI inaffidabili, prova insufficiente
)

/**
 * Perche' quelle due non si uniscono.
 *
 * Il criterio «la ricerca ne conosce uno solo» non basta da solo: Le Frecce
 * puo' ignorare un codice perche' li' non vende, non perche' sia la stessa
 * banchina. Dove il dubbio resta, non si fonde — un aggancio mancato toglie una
 * fascia gialla, un aggancio sbagliato manda la gente al binario di un'altra
 * stazione.
 *
 *  - **GENOVA PIAZZA PRINCIPE SOTTERRANEA** (S04701) e Genova Piazza Principe
 *    (S04700), a 393 m: la ricerca conosce solo il secondo, ma sono **gemelle**
 *    come Milano Porta Garibaldi e il suo Passante — un piano sopra e uno sotto,
 *    quello del passante, non due nomi della stessa banchina. Confermato
 *    dall'utente il 23/09/2026, mentre stavo per unirle. Unirle avrebbe tolto
 *    proprio il nome che distingue le due (`comeDistinguerlaDa`), cioe' avrebbe
 *    mandato al piano sbagliato chi legge il binario.
 *  - **VALLE DI MADDALONI** (S09304) e VALLE DI MADDALONI PES (S09036): RFI le
 *    da' a 3,6 km, e proprio per queste stazioni le sue coordinate sono
 *    inaffidabili. Senza una prova migliore, si lascia stare.
 *
 * Tutt'e due sono senza treni: se ricominciassero a circolare, il difetto si
 * vedrebbe e si deciderebbe con i dati davanti.
 */

/**
 * Il nome con cui due codici della stessa stazione si riconoscono: abbreviazioni
 * sciolte e qualifiche tecniche tolte.
 *
 * Che funzioni lo dice il fatto che "BOLOGNA C.LE/AV" e "BOLOGNA CENTRALE"
 * diventano lo stesso nome — cioe' il giro ritrova da solo il caso che lo aveva
 * fatto nascere.
 *
 * Sta qui, e non dove la si usa, perche' la usano in due: il controllo
 * agganciato alla release e il test che lo presidia. Scritta due volte e'
 * divergita subito: nella copia del test il confine di parola era `"\b"`, che in
 * Kotlin non e' la regex ma il carattere backspace, e quella copia non toglieva
 * nessun suffisso. Sembrava funzionare — trovava i nomi identici — ma un altro
 * caso come Napoli Afragola non l'avrebbe mai visto.
 */
fun nomeStazioneNormalizzato(nome: String): String {
    var n = nome.uppercase()
        .replace("/AV", " ")
        .replace("C.LE", "CENTRALE")
        .replace("P.TA", "PORTA")
    n = n.replace(QUALIFICHE, " ")
    n = n.replace(NON_ALFANUMERICI, " ")
    return n.split(" ").filter { it.isNotEmpty() }.joinToString(" ")
}

private val QUALIFICHE = Regex("\\b(PES|AV|SOTTERRANEA|SUPERFICIE|SMISTAMENTO|SCALO)\\b")
private val NON_ALFANUMERICI = Regex("[^A-Z0-9 ]")

/** Il codice con cui si confronta una stazione: pulito, e uno solo per ogni stazione. */
fun codiceStazione(codice: String?): String? {
    val pulito = codice?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    return DOPPI[pulito] ?: pulito
}

/**
 * Vero se i due codici indicano la stessa stazione: uguali, oppure due codici
 * della stessa (vedi [DOPPI]). Due codici assenti non sono la stessa stazione.
 */
fun stessaStazione(uno: String?, altro: String?): Boolean {
    val a = codiceStazione(uno) ?: return false
    return a == codiceStazione(altro)
}

/**
 * Come distinguere la stazione di salita da quella cercata, o null se sono la
 * stessa.
 *
 * Cercando Milano Porta Garibaldi, le S5 e le S6 partono dal Passante, sotto la
 * superficie: le fonti ci arrivano da sole con una camminata, che in testa al
 * viaggio non si conta (vedi `senzaCamminateAgliEstremi`). Ma il binario che
 * l'elenco mostra e' quello del Passante, e «binario 1» cercando Garibaldi manda
 * al binario 1 di superficie. Chiesto il 19/09/2026: accanto al binario va detto
 * di quale stazione e'.
 *
 * Si mostra la parte del nome che l'altra non ha: «Passante» cercando
 * Garibaldi, «Nord» cercando Varese, «Piazza Garibaldi» cercando Napoli
 * Centrale, «Lambrate» cercando Milano Centrale. Quando il nome della salita e'
 * tutto dentro quello cercato — il treno di superficie cercando il Passante —
 * di parti in piu' non ce ne sono, e si mostra il nome intero. Vale per
 * qualunque coppia, senza un elenco di stazioni gemelle e senza sapere quale
 * delle due sia la principale.
 */
fun Station.comeDistinguerlaDa(cercata: Station): String? {
    if (stessaStazione(rfiCode, cercata.rfiCode)) return null
    val parole = name.trim().split(SPAZI).filter { it.isNotEmpty() }
    val sue = parole.map(::pulita)
    val altre = cercata.name.trim().split(SPAZI).filter { it.isNotEmpty() }.map(::pulita)
    if (sue == altre) return null
    val comuni = sue.zip(altre).takeWhile { (a, b) -> a == b }.size
    val resto = parole.drop(comuni)
    return (if (resto.isEmpty()) parole else resto).joinToString(" ")
}

private val SPAZI = Regex("\\s+")

/** Una parola del nome senza maiuscole ne' punteggiatura: «C.le» e «c.le» sono uguali. */
private fun pulita(parola: String): String = parola.lowercase().filter { it.isLetterOrDigit() }

/**
 * Come si chiama un cambio: la stazione dove si scende e, se si risale in
 * un'altra, anche quella.
 *
 * Fra due gemelle la camminata non e' un cambio in piu' (vedi `Journey.mezzi`),
 * ma il nome della stazione cambia, e va detto (chiesto il 19/09/2026): «Napoli
 * P. Garibaldi › Centrale» dalla Circumvesuviana al Frecciarossa, «Milano Porta
 * Garibaldi › Passante» dal regionale alla S5. Della seconda la sola parte che la
 * distingue, come accanto al binario: vedi [comeDistinguerlaDa]. Nella stessa
 * stazione, il suo nome e basta.
 */
fun nomeDelCambio(scesa: Station, salita: Station): String =
    salita.comeDistinguerlaDa(scesa)?.let { "${scesa.name} › $it" } ?: scesa.name

/**
 * I prefissi delle reti che hanno **stazioni proprie**, fuori dal registro RFI:
 * EAV, Ferrotramviaria, ARST.
 *
 * Non ci sono le svizzere: Chiasso e Bellinzona un codice RFI ce l'hanno, e per
 * loro ViaggiaTreno ha qualcosa da dire.
 */
private val RETI_CON_STAZIONI_PROPRIE = listOf("EAV", "FNB", "ARST")

/**
 * Vero se il codice indirizza una rete con stazioni proprie.
 *
 * A questa domanda il codice rispondeva in tre modi diversi — un confronto di
 * prefissi nel repository, `covers()` nel caricatore della corsa, `covers()` piu'
 * il caso svizzero nel tabellone — e le tre risposte potevano divergere. Divergere
 * qui vuol dire chiedere a ViaggiaTreno una corsa che non e' sua e aprirne
 * un'altra con lo stesso numero: il guasto dell'EAV 2093, che mostrava il
 * regionale di Voghera.
 */
fun reteConStazioniProprie(codice: String?): Boolean {
    val pulito = codice?.trim()?.uppercase() ?: return false
    return RETI_CON_STAZIONI_PROPRIE.any { pulito.startsWith(it) }
}
