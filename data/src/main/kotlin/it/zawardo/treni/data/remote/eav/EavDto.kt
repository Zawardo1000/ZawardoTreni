package it.zawardo.treni.data.remote.eav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * La risposta del pianificatore EAV (`planner.eavsrl.it/Home/Create`).
 *
 * I nomi dei campi sono i loro, com'erano: qui dentro convivono maiuscole e
 * minuscole (`CorsePercorso`, `percorsi`), ed e' cosi' che arrivano.
 */
@Serializable
data class EavPercorsiDto(
    @SerialName("CorsePercorso") val corse: List<EavCorsaPercorsoDto> = emptyList(),
)

@Serializable
data class EavCorsaPercorsoDto(
    /** `/Date(1789887690000)/`: l'istante vero della partenza. */
    val partenza: String? = null,
    val arrivo: String? = null,
    val percorsi: List<EavTrattoDto> = emptyList(),
)

/** Un treno dentro una corsa del pianificatore. */
@Serializable
data class EavTrattoDto(
    /** Il numero del treno, come sul tabellone. */
    val codice: Int? = null,
    /** `A`, `DD`, `FAC EX`, `A fer`. */
    val tipologia: String? = null,
    /** Minuti di ritardo di oggi. Zero vuol dire in orario. */
    val ritardo: Int? = null,
    val soppressa: Boolean = false,
)
