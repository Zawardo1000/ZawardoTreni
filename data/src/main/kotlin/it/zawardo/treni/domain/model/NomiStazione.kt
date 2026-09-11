package it.zawardo.treni.domain.model

/*
 * Il nome di una stazione come si scrive, non come arriva.
 *
 * ViaggiaTreno manda i nomi tutti in maiuscolo — «VENEZIA S.LUCIA», «CUZZAGO
 * (MI)», «PESCHIERA DEL GARDA» — e l'app li mostrava cosi'. Nel dettaglio di una
 * corsa sono venti righe in maiuscolo, un muro che si legge male e fa sembrare
 * tutta l'app l'uscita di un terminale.
 *
 * **Cambiano solo le maiuscole, mai le lettere.** Niente spazi aggiunti dopo i
 * punti, niente abbreviazioni sciolte: «S.LUCIA» diventa «S.Lucia», non
 * «Santa Lucia». Le fonti si confrontano fra loro ignorando le maiuscole, e un
 * nome che cambiasse anche di un carattere smetterebbe di combaciare in
 * silenzio. `NomiStazioneTest` lo verifica su ogni esempio.
 *
 * Un nome che ha gia' delle minuscole e' stato scritto da qualcuno (Le Frecce,
 * l'orario svizzero) e resta com'e': chi lo ha scritto sapeva meglio di noi.
 */

/** Preposizioni e articoli che in mezzo al nome restano minuscoli: «Peschiera del Garda». */
private val PARTICELLE = setOf(
    "di", "del", "della", "delle", "dei", "degli", "dello",
    "da", "dal", "dalla", "dalle", "dai", "dagli",
    "a", "al", "alla", "alle", "allo", "ai", "agli",
    "in", "nel", "nella", "nei", "sul", "sulla", "sui", "su",
    "e", "ed", "per", "con", "tra", "fra",
    "il", "lo", "la", "le", "i", "gli",
)

/** Le stesse, quando si elidono: «Cassano d'Adda», «Isola dell'Asinara». */
private val PARTICELLE_ELISE = setOf("d", "dell", "dall", "all", "nell", "sull", "l")

/**
 * Sigle che restano maiuscole: alta velocita', posti di comunicazione e bivi
 * (compaiono come punti di rilevamento: «BV/PC SETTEBAGNI»), le reti.
 */
private val SIGLE = setOf("AV", "AC", "FS", "FN", "FNM", "RFI", "SFM", "PC", "BV", "PM")

/** Numeri romani di due o piu' lettere: una «I» o una «V» da sole sono piu' spesso altro. */
private val ROMANI = Regex("^(?=[IVXLC]{2,}$)C{0,3}(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")

fun nomeLeggibile(nome: String): String {
    if (nome.any { it.isLowerCase() }) return nome

    val out = StringBuilder(nome.length)
    var i = 0
    var primaParola = true
    while (i < nome.length) {
        if (!nome[i].isLetter()) {
            out.append(nome[i])
            i++
            continue
        }
        val inizio = i
        while (i < nome.length && nome[i].isLetter()) i++
        val parola = nome.substring(inizio, i)
        val prima = nome.getOrNull(inizio - 1)
        val dopo = nome.getOrNull(i)
        out.append(scriviParola(parola, prima, dopo, primaParola))
        primaParola = false
    }
    return out.toString()
}

private fun scriviParola(parola: String, prima: Char?, dopo: Char?, primaParola: Boolean): String {
    val minuscola = parola.lowercase()
    return when {
        // La provincia fra parentesi: «CUZZAGO (MI)».
        prima == '(' && dopo == ')' && parola.length <= 2 -> parola
        parola in SIGLE -> parola
        ROMANI.matches(parola) -> parola
        // Un'iniziale puntata: la «M» di «S.M.N.».
        parola.length == 1 && dopo == '.' -> parola
        // La coda di un'abbreviazione: «P.TA» → «P.ta», «C.LE» → «C.le».
        prima == '.' && parola.length <= 2 -> minuscola
        !primaParola && dopo == '\'' && minuscola in PARTICELLE_ELISE -> minuscola
        !primaParola && dopo != '\'' && minuscola in PARTICELLE -> minuscola
        else -> minuscola.replaceFirstChar { it.uppercaseChar() }
    }
}
