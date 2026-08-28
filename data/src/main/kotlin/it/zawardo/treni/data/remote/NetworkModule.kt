package it.zawardo.treni.data.remote

import it.zawardo.treni.data.remote.svizzera.SvizzeraApi
import it.zawardo.treni.data.remote.eav.EavApi
import it.zawardo.treni.data.remote.fnb.FnbApi
import it.zawardo.treni.data.remote.italo.ItaloApi
import it.zawardo.treni.data.remote.lefrecce.LefrecceApi
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Costruzione dei client HTTP. DI manuale: l'app non ha abbastanza grafo
 * da giustificare Hilt.
 */
object NetworkModule {

    /**
     * Le API di Trenitalia rifiutano o degradano le risposte con User-Agent anomali.
     * Ne dichiariamo uno di browser mobile.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Mobile Safari/537.36"

    val json: Json = Json {
        // I payload del BFF contengono centinaia di campi commerciali che non ci servono.
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val headerInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "it-IT,it;q=0.9")
            .build()
        chain.proceed(req)
    }

    /**
     * CookieJar in memoria: il BFF Le Frecce lega il `searchId` alla `ASESSIONID`.
     * Senza cookie persistenti tra la `/search` e la `/solutions` si ottiene 410.
     */
    private class SessionCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            val jar = store.getOrPut(host) { mutableListOf() }
            cookies.forEach { fresh ->
                jar.removeAll { it.name == fresh.name && it.path == fresh.path }
                jar.add(fresh)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val jar = store[url.host] ?: return emptyList()
            jar.removeAll { it.expiresAt < now }
            return jar.filter { it.matches(url) }
        }
    }

    private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        .addInterceptor(headerInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

    private val jsonConverter by lazy {
        json.asConverterFactory("application/json".toMediaType())
    }

    val viaggiaTrenoApi: ViaggiaTrenoApi by lazy {
        Retrofit.Builder()
            .baseUrl(ViaggiaTrenoApi.BASE_URL)
            .client(baseClient().build())
            .addConverterFactory(jsonConverter)
            .build()
            .create(ViaggiaTrenoApi::class.java)
    }

    /**
     * Le risposte Trenord arrivano cifrate, quindi niente convertitore JSON:
     * i metodi restituiscono il corpo grezzo e il repository lo decifra.
     */
    val trenordApi: TrenordApi by lazy {
        Retrofit.Builder()
            .baseUrl(TrenordApi.BASE_URL)
            .client(
                baseClient()
                    .addInterceptor { chain ->
                        // Senza Referer il BFF dello store rifiuta la richiesta.
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Referer", "https://www.trenord.it/store/")
                                .build(),
                        )
                    }
                    .build(),
            )
            .addConverterFactory(jsonConverter)
            .build()
            .create(TrenordApi::class.java)
    }

    /**
     * Italo risponde JSON in chiaro: niente cifratura, niente sessione.
     *
     * Il Referer c'e' per prudenza, come per Trenord: il loro sito sta dietro a
     * un filtro anti-bot, e una richiesta che sembri arrivare dalla pagina ha
     * meno probabilita' di essere presa per quello che non e'.
     */
    val italoApi: ItaloApi by lazy {
        Retrofit.Builder()
            .baseUrl(ItaloApi.BASE_URL)
            .client(
                baseClient()
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("Referer", "https://italoinviaggio.italotreno.com/")
                                .build(),
                        )
                    }
                    .build(),
            )
            .addConverterFactory(jsonConverter)
            .build()
            .create(ItaloApi::class.java)
    }

    /**
     * EAV risponde HTML, non JSON: il convertitore serializzato qui non entra
     * mai in gioco perche' il metodo restituisce il corpo grezzo.
     *
     * Non serve Referer: l'endpoint alimenta i monitor fisici delle stazioni e
     * non sta dietro ad alcun filtro. Restano i soli header di base.
     */
    val eavApi: EavApi by lazy {
        Retrofit.Builder()
            .baseUrl(EavApi.BASE_URL)
            .client(baseClient().build())
            .addConverterFactory(jsonConverter)
            .build()
            .create(EavApi::class.java)
    }

    /**
     * Ferrotramviaria risponde JSON in chiaro, senza chiavi ne' sessione: e' la
     * piu' semplice delle sorgenti non-RFI, e non serve altro che il client base.
     */
    val fnbApi: FnbApi by lazy {
        Retrofit.Builder()
            .baseUrl(FnbApi.BASE_URL)
            .client(baseClient().build())
            .addConverterFactory(jsonConverter)
            .build()
            .create(FnbApi::class.java)
    }

    /**
     * La Vigezzina si chiede all'orario svizzero, che e' una vera API pubblica:
     * niente chiavi, niente Referer, niente filtri anti-bot da assecondare.
     */
    val svizzeraApi: SvizzeraApi by lazy {
        Retrofit.Builder()
            .baseUrl(SvizzeraApi.BASE_URL)
            .client(baseClient().build())
            .addConverterFactory(jsonConverter)
            .build()
            .create(SvizzeraApi::class.java)
    }

    /**
     * Il client per gli orari GTFS, che e' un mestiere diverso dagli altri.
     *
     * Le API rispondono in pochi KB e un timeout di 45 secondi e' generoso.
     * Qui si scaricano archivi da 3 MB (EAV) e 19,7 MB (ARST): sul cellulare in
     * movimento quel limite li interromperebbe a meta', e l'aggiornamento
     * fallirebbe sistematicamente senza che nulla sia rotto.
     */
    val orariClient: OkHttpClient by lazy {
        baseClient()
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    val lefrecceApi: LefrecceApi by lazy {
        Retrofit.Builder()
            .baseUrl(LefrecceApi.BASE_URL)
            .client(baseClient().cookieJar(SessionCookieJar()).build())
            .addConverterFactory(jsonConverter)
            .build()
            .create(LefrecceApi::class.java)
    }
}
