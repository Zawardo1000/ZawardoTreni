package it.zawardo.treni.data.remote.eav

/**
 * Le stazioni EAV: rete vesuviana, flegrea e suburbane napoletane.
 *
 * EAV e' una rete a se'. Le sue stazioni **non esistono nel registro RFI** che
 * il resto dell'app usa come chiave: Sorrento, Pompei Scavi o Napoli Porta
 * Nolana non hanno un codice `S…`, e cercarle su ViaggiaTreno non da' niente.
 * Italo poteva agganciarsi alle stazioni RFI perche' ci ferma sopra; qui invece
 * la rete e' separata dal binario in su, ed e' la differenza che detta tutto il
 * resto di questa classe.
 *
 * Si usa quindi un codice sintetico `EAV<id>`, dove l'id e' quello che i
 * tabelloni EAV chiamano `codLoc`. E' riconoscibile a colpo d'occhio, non
 * collide con i codici RFI — che cominciano per `S` o `Z` e proseguono con
 * cifre — e permette di riconoscere le proprie stazioni senza interrogare
 * nessuno.
 *
 * Le coordinate vengono dal GTFS ufficiale e servono alla "stazione piu'
 * vicina": in area vesuviana la fermata piu' prossima e' quasi sempre EAV, e
 * senza queste l'app manderebbe a Napoli Centrale chi sta a Ercolano.
 *
 * **Le due fonti non coprono le stesse stazioni**, ed e' il motivo per cui i
 * flag sono due invece di uno. Su 150 fermate:
 *
 *  - 102 hanno **tabellone e orario**: sono la Circumvesuviana, la Cumana e la
 *    Circumflegrea, il cuore della rete;
 *  - 24 hanno **solo il tabellone**. Sono impianti chiusi, stagionali, bivi e
 *    posti di movimento — Pozzano, Scrajo, Bivio Madonnelle, Accadia P.M. — che
 *    i monitor elencano ma che nessuna corsa dell'orario tocca: mostrarne il
 *    tabellone si puo', pianificarci un viaggio no;
 *  - 24 hanno **solo l'orario**. Sono le altre reti EAV, che i tabelloni non
 *    coprono affatto: Piscinola–Aversa, Napoli–Piedimonte Matese,
 *    Napoli–Benevento. Di queste si sa quando passa un treno ma non se e' in
 *    ritardo, e dichiararlo puntuale sarebbe una bugia.
 *
 * Generate incrociando il catalogo del sito EAV col GTFS ufficiale. La
 * corrispondenza fra i due registri e' `stop_id GTFS = codLoc + 6000`,
 * verificata su tutte quante.
 */
internal object EavStations {

    /** Prefisso dei codici sintetici. Nessun codice RFI comincia cosi'. */
    const val PREFIX = "EAV"

    data class Stazione(
        /** Id EAV: il `codLoc` di `ws_getData.php`, e nel GTFS lo stesso piu' 6000. */
        val id: Int,
        val nome: String,
        /** Sigle delle linee che ci fermano, separate da virgola: `L1,L4,L6`. */
        val linee: String,
        val lat: Double,
        val lon: Double,
        /** Vero se ha un tabellone in tempo reale. */
        val tabellone: Boolean,
        /** Vero se sta nell'orario ufficiale, quindi e' pianificabile. */
        val orario: Boolean,
    ) {
        /** Il codice con cui il resto dell'app la indirizza. */
        val codice: String get() = PREFIX + id
    }

    /**
     * Nome esteso di ogni linea.
     *
     * **I due registri numerano le linee in modo diverso, e i numeri si
     * scontrano.** Per i tabelloni `L7` e' la Soccavo–Monte Sant'Angelo; nel
     * GTFS quella stessa linea e' la rotta `5.`, mentre la rotta `7` e' la
     * Napoli–Caserta–Piedimonte Matese, che i tabelloni non conoscono affatto.
     * Fondere le due numerazioni — cosa che qui era stata fatta e andava
     * corretta — significa attribuire a Piedimonte Matese le corse di Soccavo.
     *
     * Restano quindi separate: prefisso `L` per le sigle dei tabelloni,
     * prefisso `R` per le rotte che esistono solo nell'orario. Una stazione si
     * descrive con le sigle del registro a cui appartiene, mai con entrambe.
     */
    val LINEE: Map<String, String> = mapOf(
        // sigle dei tabelloni EAV
        "L1" to "NAPOLI-SORRENTO",
        "L4" to "NAPOLI-SCAFATI-POGGIOMARINO",
        "L5" to "CIRCUMFLEGREA",
        "L6" to "NAPOLI-OTTAVIANO-SARNO",
        "L7" to "SOCCAVO-MONTE S.ANGELO",
        "L8" to "NAPOLI-NOLA-BAIANO",
        "L8Dir" to "NAPOLI-POMIGLIANO",
        "L9" to "CUMANA",
        // rotte presenti solo nell'orario ufficiale, senza tabellone
        "R2" to "PISCINOLA-GIUGLIANO-AVERSA",
        "R7" to "NAPOLI-CASERTA-PIEDIMONTE MATESE",
    )

    private fun S(
        id: Int,
        nome: String,
        linee: String,
        lat: Double,
        lon: Double,
        tabellone: Boolean,
        orario: Boolean,
    ) = Stazione(id, nome, linee, lat, lon, tabellone, orario)

    private val ELENCO: List<Stazione> = listOf(
        S(   1, "Napoli Porta Nolana", "L1,L4,L6,L8,L8Dir", 40.849223, 14.269264, true, true),
        S(   3, "Napoli P. Garibaldi", "L1,L4,L6,L8,L8Dir", 40.851017, 14.272976, true, true),
        S(   4, "Via Gianturco", "L1,L4,L6", 40.845922, 14.287257, true, true),
        S(   5, "S. Giovanni a Teduccio", "L1,L4,L6", 40.84171, 14.300152, true, true),
        S(   6, "Barra", "L1,L4,L6", 40.844001, 14.314253, true, true),
        S(   7, "Ponticelli", "L6", 40.851023, 14.333336, true, true),
        S(   8, "Cercola", "L6", 40.856301, 14.355284, true, true),
        S(   9, "Pollena Trocchia", "L6", 40.859843, 14.380188, true, true),
        S(  10, "Guindazzi", "L6", 40.862238, 14.384253, true, true),
        S(  11, "Madonna dell'Arco", "L6", 40.867387, 14.38936, true, true),
        S(  12, "S. Anastasia", "L6", 40.870059, 14.400303, true, true),
        S(  13, "Villa Augustea", "L6", 40.873319, 14.426348, true, true),
        S(  14, "Somma Vesuviana", "L6", 40.874074, 14.438364, true, true),
        S(  15, "Rione Trieste", "L6", 40.870324, 14.461298, true, true),
        S(  16, "Ottaviano", "L6", 40.852868, 14.480637, true, true),
        S(  17, "S. Leonardo", "L6", 40.842968, 14.492379, true, true),
        S(  18, "S. Giuseppe", "L6", 40.837373, 14.501191, true, true),
        S(  19, "Casilli", "L6", 40.826259, 14.50026, true, true),
        S(  20, "Terzigno", "L6", 40.814159, 14.497272, true, true),
        S(  21, "Flocco", "L6", 40.803537, 14.532158, true, true),
        S(  22, "Poggiomarino", "L4,L6", 40.800957, 14.540077, true, true),
        S(  23, "Striano", "L6", 40.811906, 14.577021, true, true),
        S(  24, "S. Valentino Torio", "L6", 40.797129, 14.600155, true, true),
        S(  25, "Sarno", "L6", 40.81221, 14.618466, true, true),
        S(  26, "S. Maria del Pozzo", "L1,L4", 40.84018, 14.327495, true, true),
        S(  27, "San Giorgio a Cremano", "L1,L4", 40.832653, 14.337712, true, true),
        S(  28, "Cavalli Di Bronzo", "L1,L4", 0.0, 0.0, true, false),
        S(  29, "Portici Bellavista", "L1,L4", 40.82267, 14.34318, true, true),
        S(  30, "Portici Via Liberta'", "L1,L4", 40.816813, 14.346132, true, true),
        S(  31, "Ercolano Scavi", "L1,L4", 40.808913, 14.354887, true, true),
        S(  32, "Ercolano Miglio d'Oro", "L1,L4", 40.802102, 14.361347, true, true),
        S(  33, "Torre del Greco", "L1,L4", 40.793105, 14.369915, true, true),
        S(  34, "Via S. Antonio", "L1,L4", 40.784248, 14.384107, true, true),
        S(  35, "Via del Monte", "L1,L4", 40.778211, 14.392364, true, true),
        S(  36, "Via Dei Monaci", "L1,L4", 0.0, 0.0, true, false),
        S(  37, "Villa delle Ginestre", "L1,L4", 40.772198, 14.40839, true, true),
        S(  38, "Leopardi", "L1,L4", 40.766047, 14.417619, true, true),
        S(  39, "Via Viuli", "L1,L4", 0.0, 0.0, true, false),
        S(  40, "Trecase", "L1,L4", 40.761797, 14.4396, true, true),
        S(  41, "Torre Annunziata - Oplonti", "L1,L4", 40.759526, 14.451518, true, true),
        S(  42, "Boscotrecase", "L4", 40.76864, 14.461099, true, true),
        S(  43, "Boscoreale", "L4", 40.770014, 14.474444, true, true),
        S(  44, "Villa Regina", "L1", 40.757212, 14.470373, true, true),
        S(  45, "Pompei Santuario", "L4", 40.751134, 14.501608, true, true),
        S(  46, "Scafati", "L4", 40.753598, 14.524419, true, true),
        S(  47, "S. Pietro", "L4", 40.760632, 14.534388, true, true),
        S(  48, "Via Cangiani", "L4", 40.785275, 14.537658, true, true),
        S(  49, "Pompei Scavi Villa dei Misteri", "L1", 40.748639, 14.481274, true, true),
        S(  51, "Pioppaino", "L1", 40.718855, 14.491609, true, true),
        S(  52, "Stabia Scavi", "L1", 40.70282, 14.49064, true, true),
        S(  53, "Castellammare di Stabia", "L1", 40.695305, 14.483355, true, true),
        S(  54, "Castellammare Terme", "L1", 0.0, 0.0, true, false),
        S(  55, "Pozzano", "L1", 0.0, 0.0, true, false),
        S(  56, "Scrajo", "L1", 0.0, 0.0, true, false),
        S(  57, "Vico Equense", "L1", 40.662921, 14.42997, true, true),
        S(  58, "Seiano", "L1", 40.655986, 14.426703, true, true),
        S(  59, "Meta", "L1", 40.640169, 14.416313, true, true),
        S(  60, "Piano", "L1", 40.634899, 14.410347, true, true),
        S(  61, "S. Agnello", "L1", 40.631077, 14.397931, true, true),
        S(  62, "Sorrento", "L1", 40.625848, 14.379731, true, true),
        S(  63, "Poggioreale", "L1,L8,L8Dir", 0.0, 0.0, true, false),
        S(  64, "Botteghelle", "L1,L8,L8Dir", 0.0, 0.0, true, false),
        S(  66, "Casalnuovo", "L8,L8Dir", 40.907802, 14.347487, true, true),
        S(  67, "Talona", "L8,L8Dir", 40.910184, 14.36541, true, true),
        S(  68, "Pratola Ponte", "L8,L8Dir", 40.913532, 14.382974, true, true),
        S(  69, "Pomigliano d'Arco", "L8,L8Dir", 40.914691, 14.393016, true, true),
        S(  70, "Casoria Arpino - Volla", "L8,L8Dir", 40.88815, 14.32968, true, true),
        S(  71, "Castelcisterna", "L8", 40.920471, 14.412382, true, true),
        S(  72, "Brusciano", "L8", 40.91706, 14.4177, true, true),
        S(  73, "Via Vittorio Veneto", "L8", 40.919475, 14.442251, true, true),
        S(  74, "Marigliano", "L8", 40.916935, 14.456535, true, true),
        S(  75, "S. Vitaliano", "L8", 40.918332, 14.473345, true, true),
        S(  76, "Scisciano", "L8", 40.912661, 14.480314, true, true),
        S(  77, "Saviano", "L8", 40.912176, 14.509323, true, true),
        S(  78, "Nola", "L8", 40.929364, 14.526792, true, true),
        S(  79, "Cimitile", "L8", 40.93913, 14.530495, true, true),
        S(  80, "Camposano", "L8", 40.952172, 14.531389, true, true),
        S(  81, "Cicciano", "L8", 40.960562, 14.543252, true, true),
        S(  82, "Roccarainola", "L8", 40.966367, 14.56006, true, true),
        S(  83, "Avella", "L8", 40.955199, 14.602343, true, true),
        S(  84, "Baiano", "L8", 40.952895, 14.616973, true, true),
        S(  87, "La Pigna", "L8,L8Dir", 40.908854, 14.355014, true, true),
        S(  88, "Parco Piemonte", "L8,L8Dir", 0.0, 0.0, true, false),
        S(  89, "De Ruggiero", "L8", 40.916265, 14.428504, true, true),
        S(  90, "Centro Direzionale", "L1,L8,L8Dir", 0.0, 0.0, true, false),
        S(  91, "Bartolo Longo", "L1", 0.0, 0.0, true, false),
        S(  92, "Vesuvio De Meis (Sgv)", "L1", 0.0, 0.0, true, false),
        S(  93, "Villa Visconti", "L1", 0.0, 0.0, true, false),
        S(  94, "Argine-Palasport", "L1", 0.0, 0.0, true, false),
        S(  95, "Vesuvio De Meis (SA)", "L6", 40.851719, 14.339459, true, true),
        S(  96, "Madonnelle", "L1", 0.0, 0.0, true, false),
        S(  97, "Salice", "L8,L8Dir", 40.89959, 14.33768, true, true),
        S(  99, "Moregine", "L1", 0.0, 0.0, true, false),
        S( 101, "Bivio Botteghelle", "L1,L8,L8Dir", 0.0, 0.0, true, false),
        S( 102, "Bivio Madonnelle", "L1", 0.0, 0.0, true, false),
        S( 106, "Mugnano", "R2", 40.912996, 14.218187, false, true),
        S( 107, "Pozzuoli", "L9", 0.0, 0.0, true, false),
        S( 109, "Quarto", "L5", 40.88024, 14.13624, true, true),
        S( 112, "Aversa", "R2", 40.974147, 14.213685, false, true),
        S( 113, "Aversa RFI", "R7", 40.973262, 14.21818, false, true),
        S( 124, "Giugliano", "R2", 40.927877, 14.217663, false, true),
        S( 229, "Alvignano", "R7", 41.24701, 14.338036, false, true),
        S( 238, "Caiazzo", "R7", 41.179723, 14.364157, false, true),
        S( 278, "Piana di Monte Verna", "R7", 41.169866, 14.328311, false, true),
        S( 301, "Alife", "R7", 41.32354, 14.339274, false, true),
        S( 323, "Dragoni", "R7", 41.271154, 14.313066, false, true),
        S( 430, "Piedimonte Matese", "R7", 41.351067, 14.367954, false, true),
        S( 520, "Via Nocera", "L1", 0.0, 0.0, true, false),
        S( 701, "Napoli Centrale RFI", "R7", 40.852469, 14.272186, false, true),
        S( 702, "Anfiteatro", "R7", 41.086198, 14.244978, false, true),
        S( 703, "S. Angelo in Formis", "R7", 41.120232, 14.248423, false, true),
        S( 704, "Triflisco", "R7", 41.13547, 14.258175, false, true),
        S( 705, "Villa Ortensia", "R7", 41.225961, 14.35675, false, true),
        S( 706, "S. Marco", "R7", 41.265689, 14.323144, false, true),
        S( 711, "Piscinola Scampia", "R2", 40.892929, 14.239837, false, true),
        S( 712, "Aversa Ippodromo", "R2", 40.960613, 14.210765, false, true),
        S( 715, "Cancello RFI", "R7", 40.994288, 14.419593, false, true),
        S( 716, "Maddaloni Inferiore RFI", "R7", 41.035973, 14.380607, false, true),
        S( 717, "Caserta RFI", "R7", 41.068809, 14.328269, false, true),
        S( 718, "S. Maria Capua Vetere RFI", "R7", 41.073549, 14.254141, false, true),
        S( 720, "Pontelatone", "R7", 41.145241, 14.273547, false, true),
        S( 722, "Afragola Alta Velocità Rfi", "R7", 40.93129, 14.331189, false, true),
        S( 801, "Montesanto", "L5,L9", 40.84705, 14.24533, true, true),
        S( 802, "Corso V. Emanuele", "L9", 40.83655, 14.22106, true, true),
        S( 803, "Fuorigrotta", "L9", 40.82801, 14.20164, true, true),
        S( 804, "Mostra", "L9", 40.82469, 14.19321, true, true),
        S( 805, "Edenlandia", "L9", 40.82034, 14.18354, true, true),
        S( 806, "Agnano", "L9", 40.81782, 14.17654, true, true),
        S( 808, "Bagnoli", "L9", 40.81518, 14.16682, true, true),
        S( 809, "Accadia P.M.", "L9", 0.0, 0.0, true, false),
        S( 810, "Dazio", "L9", 40.81729, 14.16, true, true),
        S( 811, "Gerolomini", "L9", 40.82065, 14.1353, true, true),
        S( 812, "Cappuccini", "L9", 0.0, 0.0, true, false),
        S( 814, "Cantieri", "L9", 0.0, 0.0, true, false),
        S( 815, "Arco felice", "L9", 40.83289, 14.10147, true, true),
        S( 816, "Lucrino", "L9", 0.0, 0.0, true, false),
        S( 817, "Baia", "L9", 40.821054, 14.071459, true, true),
        S( 818, "Fusaro", "L9", 40.81865, 14.06173, true, true),
        S( 819, "Torregaveta", "L9", 40.81164, 14.04526, true, true),
        S( 820, "Piave", "L5", 40.842921, 14.206531, true, true),
        S( 821, "Soccavo", "L5,L7", 40.84391, 14.20091, true, true),
        S( 822, "Rione Traiano", "L5", 40.84514, 14.19411, true, true),
        S( 823, "La Trencia", "L5", 40.85417, 14.17165, true, true),
        S( 824, "Pianura", "L5", 40.85601, 14.16281, true, true),
        S( 825, "Pisani", "L5", 40.86523, 14.14352, true, true),
        S( 827, "Quarto Centro", "L5", 40.88005, 14.14519, true, true),
        S( 828, "Quarto Officina", "L5", 40.8791, 14.12564, true, true),
        S( 829, "Grotta del Sole", "L5", 40.87851, 14.09468, true, true),
        S( 830, "Licola", "L5", 40.87148, 14.05885, true, true),
        S( 834, "Monte Sant'Angelo", "L7", 40.84205, 14.18595, true, true),
    )

    private val PER_CODICE: Map<String, Stazione> = ELENCO.associateBy { it.codice }
    private val PER_ID: Map<Int, Stazione> = ELENCO.associateBy { it.id }

    /** Tutte le fermate EAV. */
    val tutte: List<Stazione> get() = ELENCO

    /** Vero se il codice indirizza una stazione EAV. */
    fun isEav(codice: String?): Boolean =
        codice != null && codice.startsWith(PREFIX) && PER_CODICE.containsKey(codice)

    /** La stazione dietro un codice sintetico, null se non e' EAV. */
    fun byCodice(codice: String?): Stazione? = codice?.let { PER_CODICE[it] }

    /** La stazione dal `codLoc` del tabellone. */
    fun byId(id: Int): Stazione? = PER_ID[id]

    /**
     * L'id da passare a `ws_getData.php`, estratto dal codice sintetico.
     * Null quando il codice non e' EAV: cosi' il chiamante non spende la
     * richiesta per scoprirlo.
     */
    fun codLoc(codice: String?): Int? = byCodice(codice)?.id

    /**
     * Cerca per nome, come fa l'autocompletamento.
     *
     * Il confronto passa da [normalizza] perche' i due registri scrivono
     * diversamente la stessa cosa — il tabellone "SANT'ANASTASIA", il GTFS
     * "S. Anastasia" — e chi digita non fara' ne' l'uno ne' l'altro.
     */
    fun cerca(query: String, limite: Int = 12): List<Stazione> {
        val q = chiavi(query).filter { it.length >= 3 }
        if (q.isEmpty()) return emptyList()
        return ELENCO
            .mapNotNull { st ->
                val nomi = chiavi(st.nome)
                when {
                    nomi.any { n -> q.any(n::startsWith) } -> st to 0
                    nomi.any { n -> q.any(n::contains) } -> st to 1
                    else -> null
                }
            }
            .sortedBy { it.second }
            .map { it.first }
            .take(limite)
    }

    /**
     * La fermata EAV piu' vicina a un punto, con la distanza in metri.
     *
     * Le stazioni senza coordinate — quelle fuori dal GTFS — restano fuori:
     * hanno 0.0/0.0, che cadrebbe nel Golfo di Guinea e vincerebbe sempre.
     */
    fun piuVicina(lat: Double, lon: Double): Pair<Stazione, Double>? =
        ELENCO.asSequence()
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .map { it to distanzaMetri(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }

    /**
     * Riduce un nome alla forma su cui si puo' confrontare: maiuscole, senza
     * accenti, senza punteggiatura, con `S.`/`San`/`Sant'`/`Santa` uniformati.
     */
    /** Le forme del santo che i due registri e chi digita usano a caso. */
    private val SANTO = Regex("""\bSANTA\s+|\bSANT'\s*|\bSANT\s+|\bSAN\s+|\bS\.\s*""")

    private val NON_ALFANUM = Regex("""[^A-Z0-9]""")

    /**
     * Le forme confrontabili di un nome. Una sola non basta.
     *
     * Il problema e' che "S. Anastasia" e "Sant'Anastasia" sono la stessa
     * stazione ma, tolti punti e spazi, diventano `SANASTASIA` e
     * `SANTANASTASIA`: nessuna riduzione a stringa unica le fa coincidere,
     * perche' l'abbreviazione `S.` e' ambigua di suo. Si generano quindi piu'
     * chiavi e basta che una combaci.
     *
     * Le chiavi sono tre piu' le varianti del prefisso:
     *  - il nome cosi' com'e', senza punteggiatura;
     *  - col santo ridotto a `S` (`S. Anastasia` -> `SANASTASIA`);
     *  - **senza il santo** (`ANASTASIA`), che e' quella che salva il caso di
     *    chi digita tutto attaccato: `santanastasia` non ha spazi su cui
     *    ancorare il confronto, quindi le si toglie il prefisso a forza e si
     *    incontra la stazione su `ANASTASIA`.
     *
     * Le varianti di troppo — `TANASTASIA` da togliere `SAN` a `SANTANASTASIA` —
     * sono innocue: non corrispondono a nessuna stazione e muoiono li'.
     */
    internal fun chiavi(s: String): Set<String> {
        val pulito = s.uppercase()
            .replace('À', 'A').replace('È', 'E').replace('É', 'E')
            .replace('Ì', 'I').replace('Ò', 'O').replace('Ù', 'U')

        val nudo = pulito.replace(NON_ALFANUM, "")
        val out = mutableSetOf(
            nudo,
            pulito.replace(SANTO, "S").replace(NON_ALFANUM, ""),
            pulito.replace(SANTO, "").replace(NON_ALFANUM, ""),
        )
        for (p in listOf("SANTA", "SANT", "SAN")) {
            if (nudo.startsWith(p) && nudo.length > p.length) out += nudo.removePrefix(p)
        }
        return out.filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    /**
     * Distanza in metri con la formula dell'emisenoverso.
     *
     * La rete EAV sta in una manciata di chilometri e le fermate sono fitte:
     * un'approssimazione piu' rozza sbaglierebbe la stazione, che e' l'unica
     * cosa che qui interessi.
     */
    private fun distanzaMetri(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val f1 = Math.toRadians(lat1)
        val f2 = Math.toRadians(lat2)
        val df = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(df / 2) * Math.sin(df / 2) +
            Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
