package it.zawardo.treni.data.remote.trenord

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * BFF Trenord, che copre cio' che a ViaggiaTreno manca: le linee S del Passante
 * milanese e in generale il servizio regionale lombardo.
 *
 * Le risposte arrivano **cifrate** (vedi [TrenordCrypto]), quindi qui si
 * restituisce il corpo grezzo: la decifratura e la deserializzazione avvengono
 * nel repository.
 */
interface TrenordApi {

    companion object {
        const val BASE_URL = "https://www.trenord.it/mia/bff/"
    }

    /**
     * Ricerca itinerari.
     *
     * [departureDate] va in formato `yyyyMMdd`: con `yyyy-MM-dd` l'endpoint
     * risponde 500. [departureHour] e' `HH:mm`.
     */
    @GET("hafas/v2")
    suspend fun search(
        @Query("orig") origin: String,
        @Query("dest") destination: String,
        @Query("departure_date") departureDate: String,
        @Query("departure_hour") departureHour: String,
        @Query("products") products: String = "tickets",
        @Query("transfers") transfers: Int = 1,
        @Query("live_data") liveData: Boolean = true,
        @Query("with_routes") withRoutes: Boolean = true,
        @Query("language") language: String = "it",
    ): ResponseBody

    /**
     * Dettaglio corsa con fermate e dati in tempo reale.
     * [date] va in formato `yyyy-MM-dd`: altri formati danno 400.
     * Senza data l'endpoint risponde con l'orario nominale, non con la corsa
     * del giorno, che su una linea deviata e' un'informazione diversa.
     */
    @GET("train/{id}")
    suspend fun train(
        @Path("id") trainId: String,
        @Query("date") date: String? = null,
    ): ResponseBody

    /**
     * La stessa ricerca di [search], **in chiaro**.
     *
     * Il gemello sotto `mgmt/store-management-api/mia/` risponde gli stessi dati
     * del BFF — verificati campo per campo il 19/09/2026, prezzi compresi — ma in
     * JSON compresso: 2-3 KB invece dei ~35 del BFF, che cifrato non si comprime.
     * Il sito pero' da li' chiama solo `direttrici/`, quindi questa porta puo'
     * sparire senza avviso: si usa per prima, col BFF come riserva, e la presidia
     * `TrenordInChiaroLiveTest`. Vedi `data/fonti/TRENORD.md`.
     */
    @GET("https://www.trenord.it/mgmt/store-management-api/mia/hafas/v2")
    suspend fun searchInChiaro(
        @Query("orig") origin: String,
        @Query("dest") destination: String,
        @Query("departure_date") departureDate: String,
        @Query("departure_hour") departureHour: String,
        @Query("products") products: String = "tickets",
        @Query("transfers") transfers: Int = 1,
        @Query("live_data") liveData: Boolean = true,
        @Query("with_routes") withRoutes: Boolean = true,
        @Query("language") language: String = "it",
    ): ResponseBody

    /** La stessa corsa di [train], in chiaro: 1,5-2,4 KB invece di 8-25. Vedi [searchInChiaro]. */
    @GET("https://www.trenord.it/mgmt/store-management-api/mia/train/{id}")
    suspend fun trainInChiaro(
        @Path("id") trainId: String,
        @Query("date") date: String? = null,
    ): ResponseBody

    /**
     * Le notizie di circolazione per direttrice, in chiaro: 42 direttrici, 6 KB
     * compressi, le stesse per tutte le corse. E' la chiamata che fa il sito
     * stesso (widget `tn-wc-train-info`). Vedi [NotizieDirettrici].
     */
    @GET("https://www.trenord.it/mgmt/store-management-api/mia/direttrici/")
    suspend fun direttrici(): List<DirettriceDto>

    /**
     * Tabellone di stazione: l'elenco delle corse **programmate**.
     *
     * Sta fuori dal BFF e non e' cifrato — risponde JSON con dentro l'HTML gia'
     * renderizzato del sito — quindi l'URL e' assoluto e la risposta si
     * deserializza normalmente.
     *
     * E' l'unica fonte che elenchi anche le corse soppresse: ViaggiaTreno le
     * toglie del tutto, e senza questo confronto un treno cancellato non sparisce
     * dal tabellone perche' e' cancellato, sparisce e basta.
     */
    @GET("https://www.trenord.it/rest/render/station-details")
    suspend fun stationDetails(
        @Query("mirCode") mirCode: String,
        @Query("L") language: String = "it",
        @Query("mxp") mxp: Boolean = false,
        @Query("map_zoom") mapZoom: Int = 14,
    ): TrenordStationDetailsDto

}
