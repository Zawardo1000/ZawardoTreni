package it.zawardo.treni.data.remote.trenord

import kotlinx.serialization.Serializable

/**
 * Risposta di `/rest/render/station-details`, il tabellone di stazione Trenord.
 *
 * A differenza del resto del BFF **non e' cifrata**, ma non e' nemmeno dati:
 * sono frammenti di HTML gia' renderizzati, gli stessi che il sito inietta
 * nella pagina. Vanno estratti con delle espressioni regolari.
 *
 * E' l'unica fonte corretta per le fermate del Passante milanese: ViaggiaTreno
 * non le pubblica affatto, e la ricerca itinerari HAFAS su quelle stazioni
 * restituisce i giorni successivi saltando l'odierno.
 */
@Serializable
data class TrenordStationDetailsDto(
    val partenza: String? = null,
    val arrivo: String? = null,
)
