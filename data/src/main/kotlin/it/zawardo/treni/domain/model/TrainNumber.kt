package it.zawardo.treni.domain.model

/**
 * Il numero di treno dentro a quello che l'utente ha scritto.
 *
 * Le etichette che l'app mostra portano la sigla davanti ("RE 2874", "S8 25743",
 * "REG2618") e copiarle e' il gesto naturale, quindi la ricerca deve accettarle
 * cosi' come sono.
 *
 * Il numero e' l'ultimo pezzo che contiene cifre. Se la sigla e' attaccata al
 * numero si taglia alla prima cifra: i numeri di treno cominciano sempre con una
 * cifra, e semmai finiscono con una lettera ("888A").
 */
fun trainNumberOf(input: String): String? =
    input
        .split(' ', '_', '-', '/')
        .lastOrNull { pezzo -> pezzo.any { it.isDigit() } }
        ?.let { it.substring(it.indexOfFirst { c -> c.isDigit() }) }
