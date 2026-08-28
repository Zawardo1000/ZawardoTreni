package it.zawardo.treni.data.misti

import it.zawardo.treni.data.remote.italo.ItaloStations

/**
 * Gli hub dove si prende l'alta velocita', con le loro coordinate.
 *
 * Sono le stazioni dove Italo ferma: 64 in tutta Italia. Servono a **una cosa
 * sola**, la preselezione geografica dei punti di cambio. Per andare da A a C
 * cambiando a un hub, non ha senso provarli tutti e sessantaquattro: si tengono
 * quelli grosso modo *sulla strada* fra A e C, e gli altri si scartano senza
 * spendere una chiamata.
 *
 * Le coordinate vengono dal BFF Le Frecce, generate una volta
 * (GeneraCoordItaloTest) e imbarcate: sono stazioni grandi e stabili, non
 * cambiano posto. Il codice qui e' quello RFI, la chiave comune con cui il resto
 * dell'app indirizza le stazioni; la sigla Italo si ricava da [ItaloStations].
 */
internal object HubAV {

    data class Hub(val italo: String, val lat: Double, val lon: Double) {
        /** Il codice RFI, la chiave con cui gli altri lo riconoscono. */
        val rfi: String? get() = ItaloStations.rfiCode(italo)
    }

    private fun C(italo: String, lat: Double, lon: Double) = Hub(italo, lat, lon)

    private val ELENCO: List<Hub> = listOf(
        C("AAV", 44.724898, 10.653037), // Reggio Emilia AV
        C("AGR", 40.351637, 15.001783), // Agropoli
        C("AVR", 40.973439, 14.218119), // Aversa
        C("BAC", 41.118127, 16.870135), // Bari Centrale
        C("BC_", 44.507284, 11.342948), // Bologna Centrale
        C("BEN", 41.141474, 14.770331), // Benevento
        C("BGM", 45.690656, 9.674970), // Bergamo
        C("BIG", 41.235587, 16.499602), // Bisceglie
        C("BLT", 41.315394, 16.278615), // Barletta
        C("BLZ", 46.496723, 11.358303), // Bolzano
        C("BSC", 45.532316, 10.212838), // Brescia
        C("CEA", 41.067924, 14.328458), // Caserta
        C("CON", 45.884603, 12.298919), // Conegliano
        C("DSG", 45.462749, 10.536422), // Desenzano
        C("FF_", 43.608409, 13.497375), // Ancona
        C("FG_", 41.465530, 15.555575), // Foggia
        C("F__", 44.842883, 11.603974), // Ferrara
        C("GB_", 44.406695, 8.946816), // Genova Brignole
        C("G__", 44.417078, 8.921419), // Genova Piazza Principe
        C("J__", 44.064065, 12.573854), // Rimini
        C("LON", 38.966991, 16.320030), // Lamezia Terme C
        C("LTL", 45.777961, 13.000947), // Latisana-Lignano-Bibione
        C("MC_", 45.487215, 9.205415), // Milano Centrale
        C("ML_", 41.192940, 16.596798), // Molfetta
        C("MNF", 45.807307, 13.542937), // Monfalcone
        C("MPG", 45.484706, 9.187388), // Milano Porta Garibaldi
        C("MRT", 39.995125, 15.710036), // Maratea
        C("NAC", 40.852933, 14.272898), // Napoli Centrale
        C("NAF", 40.933097, 14.330935), // Napoli Afragola
        C("OUE", 45.071751, 7.665318), // Torino Porta Susa
        C("PAR", 39.359641, 16.033061), // Paola
        C("PD_", 45.417349, 11.880451), // Padova
        C("PGR", 45.781197, 12.832184), // Portogruaro-Caorle
        C("PNE", 45.956511, 12.654272), // Pordenone
        C("PSY", 45.438501, 10.702513), // Peschiera
        C("PY_", 43.906137, 12.904870), // Pesaro
        C("RCE", 38.103770, 15.636310), // Reggio Calabria
        C("RG_", 45.433798, 9.238518), // Milano Rogoredo
        C("RHA", 45.817422, 13.486212), // Trieste Airport
        C("RMT", 41.901311, 12.501683), // Roma Termini
        C("RO_", 43.999047, 12.658407), // Riccione
        C("RRO", 45.517700, 9.081700), // Milano Rho Fiera
        C("RTB", 41.912624, 12.531477), // Roma Tiburtina
        C("RUT", 38.486412, 15.970154), // Rosarno
        C("RVR", 45.890972, 11.033528), // Rovereto
        C("R__", 45.077031, 11.781262), // Rovigo
        C("SAL", 40.675670, 14.772242), // Salerno
        C("SDC", 39.808286, 15.801275), // Scalea
        C("SDP", 45.631400, 12.565600), // San Dona' -Jesolo
        C("SMN", 43.776835, 11.247870), // Firenze S.M.Novella
        C("SRI", 40.077773, 15.627585), // Sapri
        C("TCN", 46.072113, 11.119547), // Trento
        C("TOP", 45.062710, 7.678687), // Torino Porta Nuova
        C("TR_", 41.272789, 16.417781), // Trani
        C("TSC", 45.657519, 13.772133), // Trieste Centrale
        C("TVC", 45.660077, 12.245197), // Treviso Centrale
        C("UDN", 46.055873, 13.242053), // Udine
        C("VEM", 45.482630, 12.231698), // Venezia Mestre
        C("VIC", 45.541066, 11.540763), // Vicenza
        C("VIP", 38.712188, 16.139889), // Vibo-Pizzo
        C("VLH", 40.228295, 15.158050), // Vallo della Lucania
        C("VPN", 45.429156, 10.982455), // Verona Porta Nuova
        C("VSG", 38.219600, 15.638900), // Villa San Giovanni
        C("VSL", 45.441175, 12.321044), // Venezia S.Lucia
    )

    /**
     * Gli hub candidati come punto di cambio fra A e C.
     *
     * Un hub H entra se sta *sulla strada*: la somma dei due tratti A-H e H-C non
     * supera di molto il tratto diretto A-C. La soglia [detour] al 40% scarta le
     * deviazioni assurde — non si passa da Napoli per andare da Torino a Milano —
     * e tiene i cambi sensati. Ordinati dal piu' in linea.
     *
     * Si escludono gli hub che coincidono con A o con C: se una delle due punte
     * e' gia' un hub Italo, non e' un punto di *cambio*, e la gamba diretta la
     * gestisce la ricerca normale.
     */
    fun candidati(
        latA: Double, lonA: Double,
        latC: Double, lonC: Double,
        detour: Double = 1.4,
        max: Int = 3,
    ): List<Hub> {
        val diretto = distanza(latA, lonA, latC, lonC)
        if (diretto <= 0.0) return emptyList()
        return ELENCO
            .mapNotNull { h ->
                val viaHub = distanza(latA, lonA, h.lat, h.lon) + distanza(h.lat, h.lon, latC, lonC)
                // scarta chi e' praticamente su una delle due punte
                val vicinoAllePunte = distanza(latA, lonA, h.lat, h.lon) < 2.0 ||
                    distanza(h.lat, h.lon, latC, lonC) < 2.0
                if (!vicinoAllePunte && viaHub <= diretto * detour) h to viaHub else null
            }
            .sortedBy { it.second }
            .take(max)
            .map { it.first }
    }

    /** L'hub con quel codice RFI, se e' un hub. */
    fun byRfi(rfi: String?): Hub? = rfi?.let { r -> ELENCO.firstOrNull { it.rfi == r } }

    /** Distanza in chilometri, emisenoverso. La preselezione non ha bisogno di piu'. */
    private fun distanza(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val f1 = Math.toRadians(lat1); val f2 = Math.toRadians(lat2)
        val df = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(df / 2) * Math.sin(df / 2) +
            Math.cos(f1) * Math.cos(f2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
