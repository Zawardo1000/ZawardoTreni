package it.zawardo.treni.data.remote.svizzera

import it.zawardo.treni.data.remote.StationMatching

/**
 * Le stazioni che l'app chiede all'orario svizzero.
 *
 * Sono due servizi transfrontalieri diversi, tenuti insieme perche' la fonte e'
 * una sola: la stessa API, la stessa chiamata, lo stesso formato. Dividerli in
 * due sorgenti avrebbe voluto dire due interruttori per la stessa cosa.
 *
 * - **Vigezzina - Centovalli** (Domodossola - Locarno), esercita da SSIF sul
 *   versante italiano e FART su quello svizzero. Non e' rete RFI, e la Svizzera
 *   la pubblica per intero, fermate italiane comprese.
 * - **TILO**, il regionale del Ticino: le linee S che arrivano a Chiasso, Lugano,
 *   Bellinzona e Locarno. Qui l'app guadagna il **lato svizzero**, quello che
 *   ViaggiaTreno non ha.
 *
 * A differenza di EAV, Ferrotramviaria e ARST, i codici **non sono inventati**:
 * sono gli id dell'orario svizzero, quelli che l'endpoint accetta davvero. Il
 * prefisso [PREFIX] serve solo a non confonderli con i codici RFI, e si toglie
 * prima di chiamare.
 *
 * ## Le stazioni che l'Italia ha gia'
 *
 * Chiasso e Bellinzona esistono anche nel registro RFI — verificato:
 * `S01301` e `S00300`. Per quelle **non si crea una seconda stazione**: si tiene
 * il codice italiano in [Stazione.rfi], la stazione resta una sola in ricerca, e
 * il suo tabellone si compone di due fonti — ViaggiaTreno per i treni verso
 * l'Italia, l'orario svizzero per le linee S verso nord. E' lo stesso meccanismo
 * con cui a Roma Termini convivono Trenitalia e Italo.
 *
 * Il caso vale la pena di distinguerlo: a Chiasso ViaggiaTreno risponde con
 * quattordici corse, a Bellinzona con **zero** pur avendone il codice. La
 * seconda, senza questa fonte, sarebbe una stazione muta.
 *
 * Le stazioni italiane della linea Varese - Mendrisio (Arcisate, Induno Olona,
 * Cantello) non ci sono affatto: sono su rete italiana e le coprono gia'
 * ViaggiaTreno e Trenord.
 *
 * ## Domodossola e Locarno hanno due stazioni ciascuna
 *
 * A Domodossola la Vigezzina parte da un impianto sotterraneo, sotto la stazione
 * RFI; a Locarno la stazione FART e' distinta da quella FFS. Sono davvero due
 * impianti diversi con treni diversi, quindi qui restano due voci, e il nome
 * porta il vettore perche' chi cerca possa capire quale sta scegliendo.
 */
internal object SvizzeraStations {

    /** Prefisso dei codici sintetici. Nessun codice RFI comincia cosi'. */
    const val PREFIX = "CH"

    /**
     * Quale servizio serve una stazione, e quindi **quali vettori tenere**.
     *
     * Non e' un'etichetta: e' il filtro. A Domodossola l'orario svizzero
     * risponde anche con SBB e BLS, che sono gli EuroCity su rete RFI gia'
     * pubblicati da ViaggiaTreno; in Ticino invece SBB e' esattamente cio' che
     * si vuole. Lo stesso vettore va tenuto in un posto e scartato nell'altro,
     * quindi il filtro non puo' che stare sulla stazione.
     */
    enum class Rete(val vettori: List<String>) {
        VIGEZZINA(listOf("FART", "SSIF")),
        TICINO(listOf("SBB", "TILO")),
    }

    data class Stazione(
        /** L'id dell'orario svizzero, quello che l'endpoint accetta. */
        val id: String,
        val nome: String,
        val lat: Double,
        val lon: Double,
        val rete: Rete,
        /**
         * Il codice RFI, quando la stazione esiste anche nel registro italiano.
         *
         * Quando c'e', questa voce non entra in ricerca ne' fra le stazioni
         * vicine: la stazione la propone gia' il catalogo Trenitalia, e
         * proporla due volte sarebbe solo confusione. Serve unicamente perche'
         * il tabellone di quel codice riceva anche le corse svizzere.
         */
        val rfi: String? = null,
    ) {
        /** Il codice con cui il resto dell'app la indirizza. */
        val codice: String get() = rfi ?: (PREFIX + id)

        /** Vero se la stazione esiste solo qui, quindi va offerta in ricerca. */
        val propria: Boolean get() = rfi == null
    }

    private fun V(id: String, nome: String, lat: Double, lon: Double) =
        Stazione(id, nome, lat, lon, Rete.VIGEZZINA)

    private fun T(id: String, nome: String, lat: Double, lon: Double, rfi: String? = null) =
        Stazione(id, nome, lat, lon, Rete.TICINO, rfi)

    /** Le 23 fermate della Vigezzina, da ovest a est come corre la linea. */
    private val VIGEZZINA: List<Stazione> = listOf(
        V("8301003", "Domodossola Vigezzina", 46.115288, 8.296225),
        V("8505593", "Masera", 46.131898, 8.323789),
        V("8505588", "Creggio", 46.124650, 8.324538),
        V("8505597", "Trontano", 46.122596, 8.333988),
        V("8505599", "Verigo", 46.121727, 8.351504),
        V("8505590", "Gagnone-Orcesco", 46.128596, 8.419600),
        V("8505585", "Druogno", 46.133088, 8.433643),
        V("8505581", "S. Maria Maggiore", 46.136437, 8.460582),
        V("8505594", "Prestinone", 46.133034, 8.479502),
        V("8505578", "Zornasco", 46.129691, 8.490799),
        V("8505584", "Malesco", 46.129183, 8.498889),
        V("8505580", "Re", 46.127254, 8.538794),
        V("8505589", "Folsogno-Dissimo", 46.134360, 8.558102),
        V("8505499", "Camedo", 46.154741, 8.610937),
        V("8505497", "Palagnedra", 46.161092, 8.630792),
        V("8505496", "Verdasio", 46.165196, 8.649191),
        V("8505495", "Corcapolo", 46.168758, 8.675815),
        V("8505494", "Intragna", 46.177651, 8.702257),
        V("8505493", "Cavigliano", 46.183754, 8.721192),
        V("8505492", "Verscio", 46.184550, 8.729475),
        V("8505491", "Tegna", 46.186017, 8.742761),
        V("8505467", "Solduno S. Martino", 46.171993, 8.770359),
        V("8505470", "Locarno FART", 46.172633, 8.802634),
    )

    /** Le stazioni ticinesi delle linee S, da sud a nord. */
    private val TICINO: List<Stazione> = listOf(
        T("8505307", "Chiasso", 45.832162, 9.031446, rfi = "S01301"),
        T("8505306", "Balerna", 45.846740, 9.005027),
        T("8517519", "Stabio", 45.849708, 8.943932),
        T("8505305", "Mendrisio", 45.869103, 8.978606),
        T("8518475", "Mendrisio S. Martino", 45.877211, 8.983085),
        T("8505304", "Capolago-Riva S. Vitale", 45.902829, 8.978897),
        T("8505303", "Maroggia-Melano", 45.932425, 8.973940),
        T("8505302", "Melide", 45.955704, 8.948355),
        T("8505301", "Paradiso", 45.989364, 8.946883),
        T("8505300", "Lugano", 46.005494, 8.946993),
        T("8505219", "Lamone-Cadempino", 46.039710, 8.932124),
        T("8505218", "Taverne-Torricella", 46.056675, 8.930326),
        T("8505217", "Mezzovico", 46.094270, 8.928586),
        T("8505216", "Rivera-Bironico", 46.123968, 8.925282),
        T("8505404", "Cadenazzo", 46.152613, 8.941689),
        T("8505415", "S. Antonino", 46.159041, 8.969509),
        T("8505214", "Giubiasco", 46.173806, 9.003597),
        T("8505213", "Bellinzona", 46.195425, 9.029509, rfi = "S00300"),
        T("8505212", "Castione-Arbedo", 46.222914, 9.041460),
        T("8505209", "Biasca", 46.351970, 8.974166),
        T("8505412", "Riazzino", 46.175325, 8.886391),
        T("8505402", "Gordola", 46.179033, 8.865767),
        T("8505401", "Tenero", 46.177456, 8.850222),
        T("8505417", "Minusio", 46.173993, 8.819556),
        T("8505400", "Locarno", 46.172415, 8.801359),
    )

    private val ELENCO: List<Stazione> = VIGEZZINA + TICINO

    private val PER_CODICE: Map<String, Stazione> =
        ELENCO.associateBy { it.codice.uppercase() }

    /** Tutte le fermate raggiungibili da questa fonte. */
    val tutte: List<Stazione> get() = ELENCO

    /**
     * Solo quelle che esistono unicamente qui.
     *
     * Chiasso e Bellinzona restano fuori: le pubblica gia' il catalogo
     * Trenitalia, e offrirle di nuovo produrrebbe due voci identiche in ricerca.
     */
    val proprie: List<Stazione> get() = ELENCO.filter { it.propria }

    /** Vero se il codice indirizza una fermata servita da questa fonte. */
    fun isSvizzera(codice: String?): Boolean =
        codice != null && PER_CODICE.containsKey(codice.uppercase())

    /** La fermata dietro un codice, sintetico o RFI che sia. */
    fun byCodice(codice: String?): Stazione? = codice?.let { PER_CODICE[it.uppercase()] }

    /**
     * L'id svizzero da passare a `stationboard`, estratto dal codice.
     * Null quando il codice non e' di questa fonte, cosi' il chiamante non
     * spende la richiesta per scoprirlo.
     */
    fun idSvizzero(codice: String?): String? = byCodice(codice)?.id

    /** I vettori da tenere in quella stazione. Vuoto se non e' delle nostre. */
    fun vettori(codice: String?): List<String> = byCodice(codice)?.rete?.vettori.orEmpty()

    /** Cerca per nome, fra le sole stazioni che l'app non abbia gia' altrove. */
    fun cerca(query: String, limite: Int = 12): List<Stazione> =
        StationMatching.cerca(proprie, query, limite) { it.nome }

    /** La fermata piu' vicina a un punto, con la distanza in metri. */
    fun piuVicina(lat: Double, lon: Double): Pair<Stazione, Double>? =
        proprie.asSequence()
            .map { it to StationMatching.distanzaMetri(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }
}
