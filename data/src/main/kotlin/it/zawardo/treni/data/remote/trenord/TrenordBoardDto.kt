package it.zawardo.treni.data.remote.trenord

import kotlinx.serialization.Serializable

/**
 * Risposta di `/rest/render/station-details`, il tabellone di stazione Trenord.
 *
 * A differenza del resto del BFF **non e' cifrata**, ma non e' nemmeno dati:
 * sono frammenti di HTML gia' renderizzati, gli stessi che il sito inietta
 * nella pagina. Vanno estratti con delle espressioni regolari.
 *
 * Serve per una cosa sola, ma che nessun'altra fonte sa fare: elencare le corse
 * **programmate**, comprese quelle soppresse. Di un treno soppresso ViaggiaTreno
 * non ha nulla — non compare in tabellone, `cercaNumeroTreno` non lo trova e
 * `andamentoTreno` risponde 204 — quindi senza questo elenco quel treno per
 * l'app non esiste, e sparisce invece di risultare cancellato.
 *
 * Il corpo e' voluminoso (Milano Lambrate: 750 KB), ma viaggia compresso e in
 * rete sono 17 KB.
 */
@Serializable
data class TrenordStationDetailsDto(
    val partenza: String? = null,
    val arrivo: String? = null,
)
