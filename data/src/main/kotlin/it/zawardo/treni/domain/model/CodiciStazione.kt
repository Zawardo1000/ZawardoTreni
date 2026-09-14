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
