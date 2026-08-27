package it.zawardo.treni.domain.model

/**
 * Il numero di treno dentro a quello che l'utente ha scritto.
 *
 * Le etichette che l'app mostra portano la sigla davanti ("RE 2874", "S8 25743",
 * "REG2618") e copiarle e' il gesto naturale, quindi la ricerca deve accettarle
 * cosi' come sono.
 *
 * Il numero e' l'ultimo pezzo che contiene cifre; se la sigla e' attaccata si
 * taglia alla prima cifra.
 *
 * Su ViaggiaTreno i numeri di treno sono interi: verificato su 373 corse di otto
 * grandi stazioni, e il suo stesso formato li dichiara tali. Le lettere che si
 * vedono in giro appartengono alle sigle di linea ("RE8", "S8") e ai bus
 * sostitutivi ("890A"), che un dettaglio corsa non ce l'hanno.
 *
 * Un eventuale suffisso resta comunque attaccato al numero invece di essere
 * buttato via: cercare "2828A" e non trovare nulla e' onesto, cercarlo e
 * mostrare il 2828 sarebbe rispondere a una domanda diversa.
 */
fun trainNumberOf(input: String): String? =
    input
        .split(' ', '_', '-', '/')
        .lastOrNull { pezzo -> pezzo.any { it.isDigit() } }
        ?.let { it.substring(it.indexOfFirst { c -> c.isDigit() }) }

/** La sigla che precede il numero, se e' stata scritta: "REG20" -> "REG". */
fun trainCategoryOf(input: String): String? =
    input.trimStart().takeWhile { it.isLetter() }.uppercase().takeIf { it.isNotEmpty() }

/**
 * Se una sigla scritta a mano indica questa corsa.
 *
 * Il confronto e' largo da entrambi i lati perche' le sigle si scrivono come
 * capita: "RE" per un "REG", "RE8" preso da un'etichetta di linea per un treno
 * che ViaggiaTreno chiama "RE". Serve a scegliere fra due corse omonime, non a
 * escluderne: chi filtra deve ignorare il risultato quando resta vuoto.
 */
fun matchesCategory(label: String, category: String): Boolean {
    val sigla = label.trim().substringBefore(' ').uppercase()
    if (sigla.isEmpty()) return false
    return sigla.startsWith(category) || category.startsWith(sigla)
}
