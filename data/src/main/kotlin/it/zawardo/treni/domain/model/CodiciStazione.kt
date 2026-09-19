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
 * Non e' un caso scelto a mano. Lo stesso giorno si sono confrontati tabellone
 * e dettaglio in 52 nodi principali, e in 36 coppie di stazioni del registro
 * vicine fra loro o dal nome quasi uguale: Milano Porta Garibaldi e la sua
 * sotterranea, Genova Piazza Principe e la sua, Napoli Centrale e Piazza
 * Garibaldi, Roma Nomentana, Siracusa, i doppioni di confine. Il doppio codice
 * c'era soltanto a Bologna. Le altre coppie sono stazioni diverse davvero, ognuna
 * coi suoi treni, e fonderle sarebbe l'errore opposto.
 *
 * Se ne compare uno nuovo — Firenze avra' una stazione AV sua — se ne accorge
 * `StazioniDoppieLiveTest`, e la risposta e' aggiungerlo qui.
 */
private val DOPPI: Map<String, String> = mapOf(
    "S05046" to "S05043", // BOLOGNA C.LE/AV -> BOLOGNA CENTRALE
)

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
