package it.zawardo.treni.data.remote.svizzera

import kotlinx.serialization.Serializable

/**
 * Le risposte dell'orario svizzero, ritagliate su cio' che serve al tabellone.
 *
 * La risposta vera porta molto di piu' — occupazione delle carrozze, geometria
 * del percorso, prognosi dettagliata — e resta fuori: `ignoreUnknownKeys` la
 * lascia cadere senza che nessuno se ne accorga.
 */

/** `stationboard?id=…`. */
@Serializable
data class SvizzeraBoardDto(
    val station: SvizzeraStationDto? = null,
    val stationboard: List<SvizzeraJourneyDto> = emptyList(),
)

@Serializable
data class SvizzeraStationDto(
    val id: String? = null,
    val name: String? = null,
)

/** Una corsa in tabellone. */
@Serializable
data class SvizzeraJourneyDto(
    /** `R`, `RE`, `PE`: regionale, regio-express, Panoramic Express. */
    val category: String? = null,
    /**
     * **La linea, non la corsa**, per i regionali: `72` per tutte le Panoramic
     * Express della Vigezzina, `80` per tutte le RE80. Per le corse senza linea
     * (EC, IR) e' il numero. Va sulla scritta, come sui cartelli svizzeri.
     */
    val number: String? = null,
    /**
     * Il numero della corsa, con gli zeri davanti (`000041`, `025535`).
     *
     * E' l'unico che distingua una corsa dall'altra: fino al 19/09/2026 l'app
     * leggeva [number], e ogni Panoramic Express della Vigezzina era la corsa
     * «72» (vedi `data/fonti/MINORI.md`).
     */
    val name: String? = null,
    /** Il vettore: `FART` per la Vigezzina, ma a Domodossola anche altri. */
    val operator: String? = null,
    /**
     * Il capolinea fra le partenze; **l'origine fra gli arrivi** (`type=arrival`):
     * a Locarno FART, capolinea della Vigezzina, gli arrivi hanno
     * `to = "Domodossola (I)"`. Verificato il 19/09/2026.
     */
    val to: String? = null,
    val stop: SvizzeraStopDto? = null,
)

/** Il passaggio da questa fermata. */
@Serializable
data class SvizzeraStopDto(
    /**
     * L'orario, `yyyy-MM-dd'T'HH:mm:ssZ`.
     *
     * E' l'unico campo che porti l'ora: nei tabelloni `arrival` resta null
     * anche quando si chiedono gli arrivi, e qui c'e' l'ora d'arrivo.
     */
    val departure: String? = null,
    val arrival: String? = null,
    /**
     * Minuti di ritardo, oppure **null quando non si sa ancora**.
     *
     * Sono due cose diverse e vanno tenute diverse: `0` e' una misura, `null`
     * e' assenza di misura, e capita su tutte le corse abbastanza lontane. Chi
     * legge non deve vedere "in orario" un treno che nessuno ha ancora visto.
     */
    val delay: Int? = null,
    /** Il binario di tabella. Quello vero, se cambia, sta in [prognosis]. */
    val platform: String? = null,
    val prognosis: SvizzeraPrognosisDto? = null,
)

/**
 * La previsione aggiornata, quando c'e'.
 *
 * Serve per il binario: l'orario svizzero tiene separato quello di tabella da
 * quello effettivo, ed e' l'unica delle sorgenti non-RFI che lo faccia. Le
 * altre pubblicano un binario solo e non permettono di dire "cambiato".
 */
@Serializable
data class SvizzeraPrognosisDto(
    val platform: String? = null,
    val departure: String? = null,
)
