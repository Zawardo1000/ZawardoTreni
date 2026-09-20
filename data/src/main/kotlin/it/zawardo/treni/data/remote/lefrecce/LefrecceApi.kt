package it.zawardo.treni.data.remote.lefrecce

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * BFF Le Frecce — backend in produzione di lefrecce.it e dell'app Trenitalia.
 *
 * Usato **solo** per ricerca stazioni e itinerari A→B: il realtime arriva da ViaggiaTreno.
 * Richiede una CookieJar attiva (`ASESSIONID`): senza, `/solutions` risponde 410.
 */
interface LefrecceApi {

    companion object {
        const val BASE_URL = "https://app.lefrecce.it/Channels.Website.BFF.WEB/app/"
    }

    @GET("locations")
    suspend fun locations(
        @Query("name") name: String,
        @Query("limit") limit: Int = 12,
        @Query("multi") multi: Boolean = false,
        @Query("zonaFrecce") zonaFrecce: Boolean = false,
    ): List<LocationDto>

    @GET("locations/closest")
    suspend fun closest(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("withbdo") withBdo: Boolean = true,
    ): LocationDto

    /**
     * Apre una sessione di ricerca. [departureTime] in ISO **con il fuso**, es.
     * `2026-08-28T08:00:00.000+02:00`: senza, il BFF non da' errore ma cerca da
     * mezzanotte (vedi `JourneyRepository.bffFormat`). La porta del sito invece
     * lo vuole senza fuso.
     *
     * Il `searchId` restituito **scade dopo 15 minuti** (misurato il 18/09/2026).
     */
    @GET("search")
    suspend fun search(
        @Query("startlocationid") startLocationId: Long,
        @Query("endlocationid") endLocationId: Long,
        @Query("departure_time") departureTime: String,
        @Query("arflag") arFlag: String = "A",
        @Query("adultno") adults: Int = 1,
        @Query("childno") children: Int = 0,
        @Query("direction") direction: String = "A",
        @Query("frecce") frecce: Boolean = false,
        @Query("regional") regional: Boolean = false,
        @Query("intercity") intercity: Boolean = false,
    ): SearchResponseDto

    /**
     * Soluzioni per una ricerca aperta.
     *
     * Attenzione: **non** passare `group`. Con `group=ANDATA` l'endpoint risponde
     * 200 con lista vuota; senza parametro restituisce i risultati corretti.
     */
    @GET("search/{searchId}/solutions")
    suspend fun solutions(
        @Path("searchId") searchId: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 10,
    ): List<SolutionDto>

    /**
     * Le soluzioni come le chiede il **sito** di Trenitalia, che le prezza.
     *
     * Lo stesso BFF ha due porte: `/app/`, quella di [search] e [solutions], e
     * `/website/`, quella che usa lefrecce.it. Confrontate il 18/09/2026 alla
     * stessa ora sulle stesse tratte: dall'app 3 ricerche su 16 tornavano senza
     * alcun prezzo, Frecce comprese, e i regionali Trenord di Milano-Brescia
     * avevano il prezzo in una ricerca su otto; dal sito 16 su 16, e i regionali
     * otto su otto (RE a 8,40 euro). In 24 ricerche piu' nessun errore.
     *
     * Le sue tratte pero' non hanno i codici delle stazioni, solo i nomi, e
     * all'app servono per il tempo reale e per i binari: le soluzioni restano
     * quelle dell'app, e da qui si prendono i prezzi. Dieci per volta, a
     * qualunque limite: si pagina con `criteria.offset`.
     */
    @POST("https://www.lefrecce.it/Channels.Website.BFF.WEB/website/ticket/solutions")
    suspend fun soluzioniDelSito(@Body richiesta: RichiestaSito): RispostaSito

    /**
     * Le fermate di una soluzione, tratta per tratta, per il giorno di quella
     * soluzione: e' la chiamata del sito quando si apre «Dettagli». Solo il pezzo
     * percorso, senza binari; con `summary.bdoOrigin`, l'origine della corsa.
     * [cartId] vale solo nella sessione della ricerca che l'ha dato.
     */
    @GET("https://www.lefrecce.it/Channels.Website.BFF.WEB/website/stops")
    suspend fun fermateDelSito(
        @Query("cartId") cartId: String,
        @Query("solutionId") solutionId: String,
    ): List<TrattaDelSito>
}
