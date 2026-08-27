package it.zawardo.treni.data.remote.italo

/**
 * Le stazioni di Italo: la loro sigla, il codice RFI che usa il resto dell'app,
 * il nome come lo scrivono loro.
 *
 * Italo usa sigle proprie di tre caratteri (`RMT`, `MC_`, `NAC`) e non espone
 * alcun modo per tradurle nei codici RFI: nel dettaglio corsa allega un
 * `RfiLocationCode` numerico, ma appartiene a un altro registro — Roma Termini
 * per loro e' 2416, per ViaggiaTreno S08409. L'elenco e' quindi costruito una
 * volta: le sigle e i nomi vengono dal loro catalogo (`/api/getStations`), i
 * codici RFI da `autocompletaStazione`, verificati uno a uno.
 *
 * Il nome serve quanto il codice: il tabellone di stazione dice dove va una
 * corsa solo per esteso ("NAPOLI CENTRALE"), e per chiedere il percorso di quel
 * treno bisogna ritradurlo in sigla.
 *
 * Sono poco piu' di sessanta e cambiano di rado: Italo apre una fermata nuova
 * ogni qualche anno. Tenerle qui costa un file e nessuna chiamata; ricavarle a
 * ogni avvio costerebbe 290 KB di catalogo per un dato che sta fermo.
 */
internal object ItaloStations {

    private data class Stazione(val italo: String, val rfi: String, val nome: String)

    private val ELENCO = listOf(
        Stazione("AAV", "S05254", "Reggio Emilia AV"),
        Stazione("AGR", "S11705", "Agropoli"),
        Stazione("AVR", "S09006", "Aversa"),
        Stazione("BAC", "S11119", "Bari Centrale"),
        Stazione("BC_", "S05043", "Bologna Centrale"),
        Stazione("BEN", "S09311", "Benevento"),
        Stazione("BGM", "S01529", "Bergamo"),
        Stazione("BIG", "S11113", "Bisceglie"),
        Stazione("BLT", "S11108", "Barletta"),
        Stazione("BLZ", "S02026", "Bolzano"),
        Stazione("BSC", "S01717", "Brescia"),
        Stazione("CEA", "S09211", "Caserta"),
        Stazione("CON", "S02706", "Conegliano"),
        Stazione("DSG", "S02084", "Desenzano"),
        Stazione("FF_", "S07113", "Ancona"),
        Stazione("FG_", "S11100", "Foggia"),
        Stazione("F__", "S05712", "Ferrara"),
        Stazione("GB_", "S04702", "Genova Brignole"),
        Stazione("G__", "S04700", "Genova Piazza Principe"),
        Stazione("J__", "S05071", "Rimini"),
        Stazione("LON", "S11749", "Lamezia Terme C"),
        Stazione("LTL", "S03202", "Latisana-Lignano-Bibione"),
        Stazione("MC_", "S01700", "Milano Centrale"),
        Stazione("ML_", "S11114", "Molfetta"),
        Stazione("MNF", "S03310", "Monfalcone"),
        Stazione("MPG", "S01645", "Milano Porta Garibaldi"),
        Stazione("MRT", "S11723", "Maratea"),
        Stazione("NAC", "S09218", "Napoli Centrale"),
        Stazione("NAF", "S09988", "Napoli Afragola"),
        Stazione("OUE", "S00035", "Torino Porta Susa"),
        Stazione("PAR", "S11739", "Paola"),
        Stazione("PD_", "S02581", "Padova"),
        Stazione("PGR", "S03200", "Portogruaro-Caorle"),
        Stazione("PNE", "S02701", "Pordenone"),
        Stazione("PSY", "S02088", "Peschiera"),
        Stazione("PY_", "S07104", "Pesaro"),
        Stazione("RCE", "S11781", "Reggio Calabria"),
        Stazione("RG_", "S01820", "Milano Rogoredo"),
        Stazione("RHA", "S03213", "Trieste Airport"),
        Stazione("RMT", "S08409", "Roma Termini"),
        Stazione("RO_", "S07101", "Riccione"),
        Stazione("RRO", "S01039", "Milano Rho Fiera"),
        Stazione("RTB", "S08217", "Roma Tiburtina"),
        Stazione("RUT", "S11765", "Rosarno"),
        Stazione("RVR", "S02044", "Rovereto"),
        Stazione("R__", "S05706", "Rovigo"),
        Stazione("SAL", "S09818", "Salerno"),
        Stazione("SDC", "S11727", "Scalea"),
        Stazione("SDP", "S02666", "San Dona' -Jesolo"),
        Stazione("SMN", "S06421", "Firenze S.M.Novella"),
        Stazione("SRI", "S11721", "Sapri"),
        Stazione("TCN", "S02038", "Trento"),
        Stazione("TOP", "S00219", "Torino Porta Nuova"),
        Stazione("TR_", "S11112", "Trani"),
        Stazione("TSC", "S03317", "Trieste Centrale"),
        Stazione("TVC", "S02712", "Treviso Centrale"),
        Stazione("UDN", "S03026", "Udine"),
        Stazione("VEM", "S02589", "Venezia Mestre"),
        Stazione("VIC", "S02446", "Vicenza"),
        Stazione("VIP", "S11789", "Vibo-Pizzo"),
        Stazione("VLH", "S11709", "Vallo della Lucania"),
        Stazione("VPN", "S02430", "Verona Porta Nuova"),
        Stazione("VSG", "S11774", "Villa San Giovanni"),
        Stazione("VSL", "S02593", "Venezia S.Lucia"),
    )

    private val PER_ITALO: Map<String, Stazione> = buildMap {
        ELENCO.forEach { put(it.italo, it) }
        /*
         * Bologna ha due sigle: `BC_` nel tabellone di stazione, `BO2` nelle
         * fermate del dettaglio corsa. E' l'unica stazione dove i due elenchi
         * non usano lo stesso codice, e senza questa riga le sue fermate
         * restavano senza tabellone da aprire.
         */
        put("BO2", getValue("BC_"))
    }

    private val PER_RFI: Map<String, Stazione> = ELENCO.associateBy { it.rfi }

    private val PER_NOME: Map<String, Stazione> = ELENCO.associateBy { chiave(it.nome) }

    /** Null dove Italo non ferma, cioe' quasi ovunque in Italia. */
    fun italoCode(rfiCode: String?): String? = rfiCode?.uppercase()?.let { PER_RFI[it]?.italo }

    fun rfiCode(italoCode: String?): String? = italoCode?.uppercase()?.let { PER_ITALO[it]?.rfi }

    /**
     * La sigla a partire dal nome esteso, come lo scrive il tabellone.
     *
     * I due elenchi non usano sempre le stesse parole — "Reggio Calabria" nel
     * catalogo, "REGGIO DI CALABRIA CENTRALE" in tabellone — quindi dopo il
     * confronto esatto si prova per parole: vale se quelle di un nome sono tutte
     * contenute nell'altro. Se restasse ambiguo si rinuncia: meglio nessuna
     * stazione che quella sbagliata.
     */
    fun codeByName(nome: String?): String? {
        val cercato = chiave(nome ?: return null).ifBlank { return null }
        PER_NOME[cercato]?.let { return it.italo }

        val parole = cercato.split(" ").filter { it.isNotBlank() }.toSet()
        val candidati = ELENCO.filter {
            val sue = chiave(it.nome).split(" ").filter { p -> p.isNotBlank() }.toSet()
            sue.isNotEmpty() && (sue.containsAll(parole) || parole.containsAll(sue))
        }
        return candidati.singleOrNull()?.italo
    }

    /** Minuscolo, senza punteggiatura e senza spazi doppi: "S.Lucia" e "S. LUCIA" sono la stessa. */
    private fun chiave(nome: String): String =
        nome.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
