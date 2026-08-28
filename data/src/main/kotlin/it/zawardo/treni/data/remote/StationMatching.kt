package it.zawardo.treni.data.remote

/**
 * Confronto fra nomi di stazione e distanze, per i registri che l'app si porta
 * dentro.
 *
 * Le reti fuori da RFI non hanno un catalogo da interrogare: l'elenco delle
 * fermate viaggia con l'app, e ogni registro deve saper rispondere a due
 * domande — quale stazione sta scrivendo l'utente, e quale gli e' piu' vicina.
 * La logica e' identica per tutte, e sta qui invece che ricopiata in ognuna.
 *
 * EAV ha la propria copia dentro `EavStations`, nata prima di questo file.
 * Non e' stata spostata qui per non toccare codice in lavorazione: quando quel
 * lavoro sara' chiuso, quella copia puo' sparire in favore di questa.
 */
internal object StationMatching {

    /** Le forme del santo che i registri e chi digita usano a caso. */
    private val SANTO = Regex("""\bSANTA\s+|\bSANT'\s*|\bSANT\s+|\bSAN\s+|\bS\.\s*""")

    private val NON_ALFANUM = Regex("""[^A-Z0-9]""")

    /**
     * Le forme confrontabili di un nome. Una sola non basta.
     *
     * "S. Gabriele" e "San Gabriele" sono la stessa fermata ma, tolti punti e
     * spazi, diventano `SGABRIELE` e `SANGABRIELE`: nessuna riduzione a stringa
     * unica le fa coincidere, perche' l'abbreviazione `S.` e' ambigua di suo.
     * Si generano quindi piu' chiavi e basta che una combaci.
     *
     * Le varianti di troppo sono innocue: non corrispondono a nessuna stazione
     * e muoiono li'.
     */
    fun chiavi(s: String): Set<String> {
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
     * Cerca per nome dentro un elenco locale, come fa l'autocompletamento.
     *
     * Chi comincia col prefisso giusto viene prima di chi lo contiene e basta:
     * digitando "bar" si vuole Barletta, non "Fesca San Girolamo (Bari)".
     *
     * Sotto i tre caratteri non risponde: due lettere pescano mezza rete e
     * l'elenco che ne esce non aiuta a scegliere.
     */
    fun <T> cerca(elenco: List<T>, query: String, limite: Int, nome: (T) -> String): List<T> {
        val q = chiavi(query).filter { it.length >= 3 }
        if (q.isEmpty()) return emptyList()
        return elenco
            .mapNotNull { st ->
                val nomi = chiavi(nome(st))
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
     * Distanza in metri con la formula dell'emisenoverso.
     *
     * Queste reti stanno in una manciata di chilometri e le fermate sono fitte:
     * un'approssimazione piu' rozza sbaglierebbe la stazione, che e' l'unica
     * cosa che qui interessi.
     */
    fun distanzaMetri(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
