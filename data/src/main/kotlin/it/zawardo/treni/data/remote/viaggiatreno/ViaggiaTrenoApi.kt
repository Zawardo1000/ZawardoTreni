package it.zawardo.treni.data.remote.viaggiatreno

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * ViaggiaTreno — backend del portale RFI/Trenitalia. Non ufficiale, non documentato.
 *
 * Vincoli noti e verificati:
 *  - raggiungibile **solo in HTTP**: l'HTTPS risponde 301 verso HTTP;
 *  - `andamentoTreno` risponde **204** per qualunque giorno diverso da oggi;
 *  - alcuni endpoint restituiscono `text/plain`, non JSON.
 */
interface ViaggiaTrenoApi {

    companion object {
        const val BASE_URL = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno/"
    }

    /**
     * Stato completo di una corsa.
     * Risponde 204 (corpo vuoto) se il treno non e' in circolazione oggi.
     */
    @GET("andamentoTreno/{originCode}/{trainNumber}/{departureDateMillis}")
    suspend fun andamentoTreno(
        @Path("originCode") originCode: String,
        @Path("trainNumber") trainNumber: String,
        @Path("departureDateMillis") departureDateMillis: Long,
    ): Response<AndamentoTrenoDto>

    /**
     * Risolve un numero treno nelle corse odierne.
     * Formato `text/plain`, una riga per corsa:
     * `25510 - MILANO CENTRALE - 27/08/26|25510-S01700-1787781600000`
     */
    @GET("cercaNumeroTrenoTrenoAutocomplete/{trainNumber}")
    suspend fun cercaNumeroTreno(@Path("trainNumber") trainNumber: String): ResponseBody

    /**
     * Autocompletamento stazioni, `text/plain`: `MILANO CENTRALE|S01700`.
     * Usato solo come fallback: l'autocompletamento primario e' offline su Room.
     */
    @GET("autocompletaStazione/{prefix}")
    suspend fun autocompletaStazione(@Path("prefix") prefix: String): ResponseBody

    /** Elenco stazioni per regione (0..22), usato per popolare il DB offline. */
    @GET("elencoStazioni/{regionCode}")
    suspend fun elencoStazioni(@Path("regionCode") regionCode: Int): List<StazioneDto>

    /**
     * Tabellone partenze. [dateTime] va nel formato JS
     * `EEE MMM dd yyyy HH:mm:ss 'GMT'Z` in locale inglese.
     */
    @GET("partenze/{stationCode}/{dateTime}")
    suspend fun partenze(
        @Path("stationCode") stationCode: String,
        @Path("dateTime") dateTime: String,
    ): List<TabelloneVoceDto>

    /** Tabellone arrivi, stesso formato di [partenze]. */
    @GET("arrivi/{stationCode}/{dateTime}")
    suspend fun arrivi(
        @Path("stationCode") stationCode: String,
        @Path("dateTime") dateTime: String,
    ): List<TabelloneVoceDto>
}
