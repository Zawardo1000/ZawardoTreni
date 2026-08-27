package it.zawardo.treni.data.remote.lefrecce

import retrofit2.http.GET
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
     * Apre una sessione di ricerca. [departureTime] in ISO locale senza offset,
     * es. `2026-08-28T08:00:00.000`.
     *
     * Il `searchId` restituito **scade dopo circa 10 minuti**.
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
}
