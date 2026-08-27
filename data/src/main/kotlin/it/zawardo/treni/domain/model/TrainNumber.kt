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
