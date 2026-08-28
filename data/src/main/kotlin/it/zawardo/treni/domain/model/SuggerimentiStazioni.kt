package it.zawardo.treni.domain.model

/**
 * Unisce i suggerimenti di stazione di piu' fonti, togliendo i doppioni.
 *
 * Il BFF Le Frecce **duplica** molte stazioni: cercando "Sorrento" restituisce
 * la sua "Sorrento" e "SORRENTO CIRCUMVESUVIANA" con codice RFI nullo — cioe'
 * non tracciabili — accanto alla vera stazione EAV, che il tabellone ce l'ha.
 * Mostrarle tutte confonde, e fa scrivere "senza dati in tempo reale" su una
 * stazione che i dati li ha, solo dalla versione sbagliata.
 *
 * La regola: **dove piu' versioni coincidono per posizione, se ne tiene una
 * sola, la migliore.** Migliore significa piu' tracciabile:
 *
 *  1. codice RFI vero (`S…`/`Z…`): la rete nazionale, il caso piu' ricco;
 *  2. codice sintetico di una rete fuori-RFI (`EAV…`, `ARST…`): ha il suo
 *     tabellone;
 *  3. nessun codice: una fermata che il BFF conosce ma non traccia, l'ultima
 *     scelta.
 *
 * Le versioni senza codice vanno anche **in fondo** alla lista: restano
 * selezionabili, ma sotto quelle che danno qualcosa.
 */
object SuggerimentiStazioni {

    fun unisci(
        fuoriRfi: List<Station>,
        nazionali: List<Station>,
        sogliaMetri: Double = 250.0,
    ): List<Station> {
        // Prima le versioni migliori: cosi', scorrendo, ogni nodo geografico e'
        // rappresentato dalla piu' tracciabile e le altre vengono scartate.
        val ordinate = (fuoriRfi + nazionali).sortedByDescending { qualita(it) }

        val tenute = mutableListOf<Station>()
        for (s in ordinate) {
            if (tenute.none { coincidono(it, s, sogliaMetri) }) tenute.add(s)
        }

        return tenute
            .distinctBy { it.locationId }
            .sortedWith(compareByDescending<Station> { it.trackable }.thenBy { it.name.lowercase() })
    }

    /** Quanto e' "ricca" una versione della stazione: piu' alto, meglio e'. */
    private fun qualita(s: Station): Int = when {
        s.rfiCode == null -> 0
        s.rfiCode.startsWith("S") || s.rfiCode.startsWith("Z") -> 2
        else -> 1 // codice sintetico fuori-RFI
    }

    /** Due versioni sono la stessa stazione se sono vicine: stesso nodo, fonti diverse. */
    private fun coincidono(a: Station, b: Station, sogliaMetri: Double): Boolean {
        if ((a.latitude == 0.0 && a.longitude == 0.0) ||
            (b.latitude == 0.0 && b.longitude == 0.0)
        ) {
            return false
        }
        return distanzaMetri(a.latitude, a.longitude, b.latitude, b.longitude) <= sogliaMetri
    }

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
