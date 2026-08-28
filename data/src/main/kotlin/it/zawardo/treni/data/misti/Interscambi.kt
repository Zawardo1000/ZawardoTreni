package it.zawardo.treni.data.misti

/**
 * Dove si puo' cambiare rete, e quanto costa in tempo.
 *
 * Un viaggio misto cambia operatore per strada, e il cambio avviene in un
 * punto. Due tipi di punto:
 *
 *  - **stessa stazione fisica** — Milano Centrale e' `S01700` per Trenitalia,
 *    ViaggiaTreno, Trenord e Italo. Il cambio e' li', codice uguale, e questi
 *    interscambi non stanno in tabella: si riconoscono dal codice che combacia
 *    (vedi [stessaStazione]).
 *  - **stazioni diverse ma vicine** — Sorrento arriva a Napoli con EAV, che
 *    ferma a Garibaldi; Italo parte da Napoli Centrale, che per EAV e' un altro
 *    codice. Sono lo stesso complesso ma registri diversi, e fra i due binari
 *    ci sono minuti a piedi. **Questi** stanno in tabella, uno per uno.
 *
 * **Perche' una tabella e non un calcolo dalle coordinate.** Due stazioni a
 * trecento metri in linea d'aria possono avere in mezzo una ferrovia da
 * scavalcare: la distanza euclidea non conosce i sottopassi, e produrrebbe
 * interscambi che a piedi non esistono. In piu' il tempo di trasferimento va
 * **dichiarato** all'utente, non stimato, e la geometria non sa dire i minuti.
 * Gli interscambi fuori-RFI verso l'alta velocita' in Italia si contano sulle
 * dita: precalcolarli a mano costa poco e li rende esatti.
 */
internal object Interscambi {

    enum class Modo {
        /** Cambio nella stessa stazione, ai binari accanto. */
        STESSA_STAZIONE,

        /** Trasferimento a piedi fra due stazioni distinte dello stesso nodo. */
        A_PIEDI,
    }

    /**
     * Un punto di cambio fra due reti.
     *
     * [a] e [b] sono i codici come li usa il resto dell'app: `S…`/`Z…` per le
     * stazioni RFI, i codici sintetici (`EAV3`, `FNB1110`) per le altre. Il
     * collegamento e' **bidirezionale**: vale sia da [a] verso [b] sia
     * viceversa.
     */
    data class Punto(
        val a: String,
        val b: String,
        val minuti: Int,
        val modo: Modo,
        val nota: String,
    )

    /**
     * I trasferimenti a piedi noti, verificati a mano.
     *
     * Tenuti pochi e precisi: ogni voce e' un luogo reale dove si cambia rete a
     * piedi, con il tempo che ci vuole davvero, non quello in linea d'aria.
     */
    private val ELENCO: List<Punto> = listOf(
        // --- Napoli: la Circumvesuviana e la Cumana verso l'alta velocita' ---
        Punto(
            a = "EAV3", b = "S09218", minuti = 8, modo = Modo.A_PIEDI,
            // La Circumvesuviana di Napoli Garibaldi sta sotto i binari RFI di
            // Napoli Centrale: si cambia salendo, senza uscire.
            nota = "Circumvesuviana di Garibaldi, sotto Napoli Centrale",
        ),
        Punto(
            a = "EAV1", b = "S09218", minuti = 10, modo = Modo.A_PIEDI,
            nota = "Porta Nolana, ~700 m da Napoli Centrale",
        ),
        Punto(
            a = "EAV722", b = "S09988", minuti = 5, modo = Modo.A_PIEDI,
            // La fermata EAV di Afragola e' nella stazione AV.
            nota = "stessa stazione AV di Napoli Afragola",
        ),

        // --- Bari: la Ferrotramviaria verso l'alta velocita' ---
        Punto(
            a = "FNB1110", b = "S11119", minuti = 6, modo = Modo.A_PIEDI,
            // Ferrotramviaria e' nel sottopiano di Bari Centrale.
            nota = "Ferrotramviaria, sottopiano di Bari Centrale",
        ),
    )

    private val perCodice: Map<String, List<Punto>> = buildMap<String, MutableList<Punto>> {
        ELENCO.forEach { p ->
            getOrPut(p.a) { mutableListOf() }.add(p)
            getOrPut(p.b) { mutableListOf() }.add(p)
        }
    }

    /**
     * I punti raggiungibili a piedi da una stazione, con il tempo di ciascuno.
     *
     * Restituisce le stazioni **dall'altro lato** dell'interscambio: da `EAV3`
     * torna `S09218` con i suoi otto minuti. Vuoto dove non c'e' interscambio,
     * che e' il caso della stragrande maggioranza delle stazioni.
     */
    fun aPiediDa(codice: String): List<Vicino> =
        perCodice[codice].orEmpty().map { p ->
            val altro = if (p.a == codice) p.b else p.a
            Vicino(codice = altro, minuti = p.minuti, nota = p.nota)
        }

    data class Vicino(val codice: String, val minuti: Int, val nota: String)

    /**
     * Vero se due codici indicano la stessa stazione fisica.
     *
     * E' l'interscambio implicito: lo stesso codice RFI da due reti diverse —
     * Milano Centrale servita da un regionale e da un Italo — significa cambiare
     * ai binari accanto, senza trasferimento. Il confronto e' diretto perche'
     * le reti RFI e Italo condividono gia' il codice `S…`.
     */
    fun stessaStazione(uno: String?, due: String?): Boolean =
        !uno.isNullOrBlank() && uno == due
}
