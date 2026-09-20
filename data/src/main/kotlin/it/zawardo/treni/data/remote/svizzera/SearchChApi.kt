package it.zawardo.treni.data.remote.svizzera

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * L'orario di `search.ch`, che sta sotto a `transport.opendata.ch`.
 *
 * `transport.opendata.ch` e' un involucro **non ufficiale** di questo servizio, e
 * per strada perde due cose che servono: il **ritardo degli arrivi** e le
 * **soppressioni**. Qui ci sono tutte e due: `arr_delay`/`dep_delay` valgono
 * «+8» per i minuti e **«X» per una corsa soppressa**.
 *
 * Si usa **solo come aggiunta**: le righe del tabellone restano quelle di
 * `transport.opendata.ch`, e se questo non risponde il tabellone resta com'era.
 * Il limite dichiarato e' 10.080 tabelloni al giorno, e l'app ne chiede uno per
 * tabellone aperto.
 *
 * Nessuna chiave, come l'altro. La fonte istituzionale
 * (`opentransportdata.swiss`) una chiave la vuole, e una chiave dentro un'app
 * open source e' una chiave pubblicata: resta esclusa per scelta.
 */
interface SearchChApi {

    companion object {
        /** Il percorso documentato: `timetable` e' un alias, e si comporta uguale. */
        const val BASE_URL = "https://search.ch/fahrplan/api/"

        const val PARTENZE = "depart"
        const val ARRIVI = "arrival"
    }

    /**
     * Il tabellone di una fermata: [stop] e' l'id svizzero (`8505470`), lo stesso
     * di `transport.opendata.ch`. [mode] sceglie partenze o arrivi.
     *
     * **Il loro server sbaglia lo stato sugli arrivi.** Misurato il 20/09/2026:
     * `mode=arrival` risponde quasi sempre **404** — sul percorso documentato
     * `fahrplan`, sull'alias `timetable` e sull'host `timetable.search.ch` —
     * portando pero' in corpo il tabellone giusto, con `arr_delay`; ogni tanto la
     * stessa richiesta torna 200. Le partenze rispondono sempre 200, e le altre
     * grafie di `mode` (`arr`, `ankunft`) tornano 200 con le **partenze**.
     *
     * Percio' qui torna la `Response` intera e **lo stato non decide**: decide il
     * contenuto. Gli errori veri di questo servizio si riconoscono, e sono
     * diversi: un URL inesistente da' `{"error": …}` senza `stop` ne'
     * `connections`, una fermata inesistente da' 200 con `{"messages": …}` e
     * nessuna corsa. Vedi `SvizzeraRepository.tabelloneDi`.
     */
    @GET("stationboard.json")
    suspend fun tabellone(
        @Query("stop") stop: String,
        @Query("mode") mode: String = PARTENZE,
        @Query("show_delays") ritardi: Int = 1,
        @Query("show_tracks") binari: Int = 1,
        /**
         * Quante corse: **le stesse** che chiede `transport.opendata.ch`
         * (`SvizzeraApi`, 40). Erano trenta, e le ultime righe del tabellone
         * restavano senza ritardo e senza soppressione — proprio le due cose per
         * cui questa fonte esiste.
         */
        @Query("limit") limite: Int = 40,
    ): Response<SearchChBoardDto>
}

/**
 * La risposta del tabellone: la fermata chiesta e `connections[]`, una per corsa.
 *
 * [stop] serve a riconoscere una risposta buona da un errore: vedi
 * `SearchChApi.tabellone`.
 */
@Serializable
data class SearchChBoardDto(
    val stop: SearchChFermataDto? = null,
    val connections: List<SearchChCorsaDto> = emptyList(),
)

@Serializable
data class SearchChFermataDto(
    val id: String? = null,
    val name: String? = null,
)

/**
 * Una corsa del tabellone `search.ch`.
 *
 * I nomi coi caratteri strani sono i loro: `*G` la categoria, `*L` la linea,
 * `*Z` il numero del treno — con gli zeri davanti, `000136`, come in
 * `transport.opendata.ch`.
 */
@Serializable
data class SearchChCorsaDto(
    /** `2026-09-20 09:51:00`. */
    val time: String? = null,
    @SerialName("*G") val categoria: String? = null,
    @SerialName("*L") val linea: String? = null,
    @SerialName("*Z") val numero: String? = null,
    val operator: String? = null,
    val track: String? = null,
    /** «+8» i minuti, «X» soppressa; assente se non si sa. */
    @SerialName("dep_delay") val ritardoPartenza: String? = null,
    @SerialName("arr_delay") val ritardoArrivo: String? = null,
)
