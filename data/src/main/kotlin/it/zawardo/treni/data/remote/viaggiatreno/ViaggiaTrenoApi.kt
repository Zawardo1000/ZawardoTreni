package it.zawardo.treni.data.remote.viaggiatreno

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

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
     * Tabellone partenze. [dateTime] va nel formato JS
     * `EEE MMM dd yyyy HH:mm:ss 'GMT'Z` in locale inglese.
     */
    @GET("partenze/{stationCode}/{dateTime}")
    suspend fun partenze(
        @Path("stationCode") stationCode: String,
        @Path("dateTime") dateTime: String,
    ): List<TabelloneVoceDto>

    /**
     * Le notizie di infomobilita' in corso, in HTML: vedi `InfomobilitaParser`.
     * Il `false` e' quello del loro sito, e distingue le notizie dai lavori
     * programmati (`true`). Non porta nessun dato di chi chiede.
     */
    @GET("infomobilitaRSS/false")
    suspend fun infomobilita(): ResponseBody

    /**
     * Le stesse notizie in JSON, coi numeri dei treni citati: la strada
     * principale, l'RSS in HTML resta di riserva (vedi `data/FONTI.md`).
     */
    @GET("http://www.viaggiatreno.it/infomobilita/resteasy/news/infomobility")
    suspend fun notizieInfomobilita(): List<NotiziaInfomobilitaDto>

    /**
     * Le note SmartCaring di un treno in un giorno: il perche' per corsa dei
     * regionali Trenitalia, anche per i giorni futuri. Il giorno va
     * in `yyyy-MM-dd`: senza, arriva l'intero storico (50 note e 348 KB per il 18686).
     */
    @GET("http://www.viaggiatreno.it/infomobilita/resteasy/news/smartcaring")
    suspend fun noteSmartCaring(
        @Query("commercialTrainNumber") numero: String,
        @Query("searchDate") giorno: String,
    ): List<NotaSmartCaringDto>

    /** Tabellone arrivi, stesso formato di [partenze]. */
    @GET("arrivi/{stationCode}/{dateTime}")
    suspend fun arrivi(
        @Path("stationCode") stationCode: String,
        @Path("dateTime") dateTime: String,
    ): List<TabelloneVoceDto>
}
