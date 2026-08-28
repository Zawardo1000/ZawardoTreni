package it.zawardo.treni.data.remote.fnb

import it.zawardo.treni.data.remote.StationMatching

/**
 * Le fermate di Ferrotramviaria: Bari - Barletta e servizio metropolitano di
 * Bari, aeroporto compreso.
 *
 * Come EAV, e per lo stesso motivo, e' una rete a se': Bitonto, Terlizzi, Ruvo,
 * Corato e Andria **non hanno un codice RFI**, perche' sulla rete nazionale non
 * hanno stazione affatto. Cercarle su ViaggiaTreno non da' niente, oggi e
 * sempre.
 *
 * Si usa quindi un codice sintetico `FNB<id>`. Il codice nativo del portale
 * (`S01110`) **non** si puo' usare come chiave nel resto dell'app: comincia per
 * `S` seguito da cifre, cioe' ha la forma esatta di un codice RFI, e prima o poi
 * ne incontrerebbe uno vero. L'id e' la parte numerica, e da quello si
 * ricostruisce il [Stazione.codSito] da mandare al portale.
 *
 * ## Le fermate di Ferrovie Appulo Lucane non ci sono
 *
 * Il portale e' condiviso: `realtime/siti/T` elenca anche 38 fermate marcate
 * `gestore = "FAL"` — Matera Centrale, Altamura, Gravina, Potenza. Non sono in
 * questo elenco perche' **il loro tabellone non risponde**: `realtime/dati` su
 * un `codSito` FAL restituisce 500 con un errore di parsing, cioe' il portale
 * interroga un servizio a monte che non risponde JSON. Verificato su tutte le
 * fermate FAL provate e su tutti i valori di `type`.
 *
 * Metterle qui significherebbe offrire un tabellone che resta vuoto per sempre.
 * Se quel servizio tornera', bastera' aggiungerle: i codici sono `S021xx` e
 * `S022xx`, e il resto del codice non cambia di una riga.
 *
 * ## Le coordinate
 *
 * Vengono da OpenStreetMap, non dall'azienda: il GTFS di Ferrotramviaria e'
 * offline dal 2025 e l'unico URL pubblicato risponde 404. Sono dati ODbL, e per
 * questo l'app cita OpenStreetMap fra i credits. Servono alla "stazione piu'
 * vicina": senza, chi sta a Ruvo o a Corato si sentirebbe proporre Bari
 * Centrale, a quaranta chilometri.
 */
internal object FnbStations {

    /** Prefisso dei codici sintetici. Nessun codice RFI comincia cosi'. */
    const val PREFIX = "FNB"

    data class Stazione(
        /** Parte numerica del codice del portale: `S01110` -> `1110`. */
        val id: Int,
        val nome: String,
        val lat: Double,
        val lon: Double,
    ) {
        /** Il codice con cui il resto dell'app la indirizza. */
        val codice: String get() = PREFIX + id

        /**
         * Il codice nativo da mandare al portale.
         *
         * Gli zeri iniziali contano: il portale vuole `S01110`, e `S1110` gli
         * restituisce un tabellone vuoto invece di un errore.
         */
        val codSito: String get() = "S%05d".format(id)
    }

    private fun S(id: Int, nome: String, lat: Double, lon: Double) = Stazione(id, nome, lat, lon)

    /**
     * Le 25 fermate servite dal tabellone, nei nomi che usa il portale.
     *
     * I nomi sono i suoi, non quelli di OpenStreetMap, perche' sono quelli che
     * ricompaiono nelle destinazioni delle corse: usarne altri farebbe sembrare
     * due fermate diverse la stessa.
     */
    private val ELENCO: List<Stazione> = listOf(
        S(1110, "Bari Centrale FNB", 41.118309, 16.868146),
        S(1115, "Bari Quintino Sella", 41.118070, 16.862215),
        S(1120, "Bari Brigata Bari", 41.118048, 16.852018),
        S(1125, "Bari Francesco Crispi", 41.120247, 16.846477),
        S(1130, "Fesca San Girolamo", 41.129705, 16.823628),
        S(1135, "Palese", 41.149196, 16.780394),
        S(1136, "Europa", 41.135183, 16.781550),
        S(1140, "Macchie", 41.147934, 16.768981),
        S(1141, "Aeroporto K.W.", 41.132480, 16.766113),
        S(1144, "Bitonto SS Medici", 41.117432, 16.698373),
        S(1145, "Bitonto Centrale", 41.113356, 16.684596),
        S(1150, "Sovereto", 41.117789, 16.585051),
        S(1155, "Terlizzi", 41.126543, 16.548627),
        S(1160, "Ruvo di Puglia", 41.114878, 16.479063),
        S(1164, "Corato Sud", 41.145296, 16.419876),
        S(1165, "Corato Centrale", 41.156730, 16.417687),
        S(1169, "Andria Sud", 41.224696, 16.314563),
        S(1170, "Andria Centrale", 41.232326, 16.301486),
        S(1175, "Barletta Scalo", 41.308393, 16.288997),
        S(1180, "Barletta Centrale", 41.314791, 16.278154),
        S(1190, "Tesoro", 41.129755, 16.802186),
        S(1191, "Cittadella", 41.126817, 16.792978),
        S(1192, "S.Gabriele", 41.124028, 16.785353),
        S(1193, "Ospedale S.Paolo", 41.117639, 16.780002),
        S(1194, "Cecilia", 41.116677, 16.786300),
    )

    private val PER_CODICE: Map<String, Stazione> = ELENCO.associateBy { it.codice }

    /** Tutte le fermate Ferrotramviaria. */
    val tutte: List<Stazione> get() = ELENCO

    /** Vero se il codice indirizza una fermata Ferrotramviaria. */
    fun isFnb(codice: String?): Boolean =
        codice != null && codice.startsWith(PREFIX) && PER_CODICE.containsKey(codice)

    /** La fermata dietro un codice sintetico, null se non e' Ferrotramviaria. */
    fun byCodice(codice: String?): Stazione? = codice?.let { PER_CODICE[it] }

    /**
     * Il `codSito` da passare a `realtime/dati`, estratto dal codice sintetico.
     * Null quando il codice non e' di questa rete, cosi' il chiamante non spende
     * la richiesta per scoprirlo.
     */
    fun codSito(codice: String?): String? = byCodice(codice)?.codSito

    /** Cerca per nome, come fa l'autocompletamento. */
    fun cerca(query: String, limite: Int = 12): List<Stazione> =
        StationMatching.cerca(ELENCO, query, limite) { it.nome }

    /** La fermata piu' vicina a un punto, con la distanza in metri. */
    fun piuVicina(lat: Double, lon: Double): Pair<Stazione, Double>? =
        ELENCO.asSequence()
            .map { it to StationMatching.distanzaMetri(lat, lon, it.lat, it.lon) }
            .minByOrNull { it.second }
}
