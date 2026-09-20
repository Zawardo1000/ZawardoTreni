package it.zawardo.treni.data.remote.trenord

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * I cantieri di Trenord: i lavori programmati, con i periodi, le linee e le
 * stazioni toccate.
 *
 * Stanno su `cantieri.trenord.it`, che e' un sito fatto con Yext Pages: i dati
 * veri li serve Yext, e la pagina li chiede con una **chiave pubblica di
 * lettura** scritta nella pagina stessa. E' la stessa chiave che usa il browser
 * di chiunque apra quel sito, vale solo per quella ricerca e non apre nient'altro:
 * non e' un segreto sottratto, e' il modo in cui quel sito pubblica i suoi dati.
 *
 * Servono a dire una cosa che l'app altrimenti non sa: che su quella tratta c'e'
 * un cantiere, con le date. Gli `hafas_alerts` della ricerca Trenord dicono gia'
 * qualcosa, ma solo se la corsa di oggi ne e' toccata; qui c'e' anche quel che
 * comincia domani.
 *
 * Se Yext non risponde, o la chiave smette di valere, non cambia niente: la
 * ricerca resta quella di prima, e lo dice `CantieriLiveTest`.
 */
interface CantieriApi {

    companion object {
        const val BASE_URL = "https://prod-cdn.us.yextapis.com/v2/accounts/me/search/vertical/"

        /** La chiave pubblica di lettura, scritta nella pagina di `cantieri.trenord.it`. */
        const val CHIAVE = "c10d9d4db778680fe1b9721cae11829b"

        /** L'esperienza di ricerca e la vertical, come le chiama Yext. */
        const val ESPERIENZA = "moodifica-circolazione"
        const val PER_STAZIONI = "ricercaperstazioni"

        /** La versione dell'API, come la manda la pagina. */
        const val VERSIONE = "20220511"
    }

    @GET("query")
    suspend fun cantieri(
        @Query("experienceKey") esperienza: String = ESPERIENZA,
        @Query("verticalKey") vertical: String = PER_STAZIONI,
        @Query("api_key") chiave: String = CHIAVE,
        @Query("v") versione: String = VERSIONE,
        @Query("version") ambiente: String = "PRODUCTION",
        @Query("locale") lingua: String = "it",
        @Query("input") testo: String = "",
        @Query("limit") limite: Int = 50,
        @Query("offset") salta: Int = 0,
    ): CantieriRispostaDto
}

@Serializable
data class CantieriRispostaDto(
    val response: CantieriElencoDto? = null,
)

@Serializable
data class CantieriElencoDto(
    val resultsCount: Int = 0,
    val results: List<CantiereVoceDto> = emptyList(),
)

@Serializable
data class CantiereVoceDto(
    val data: CantiereDto? = null,
)

/** Un cantiere: com'e' scritto nei dati di Trenord. */
@Serializable
data class CantiereDto(
    /** «Lavori tra Bergamo e Ponte San Pietro». */
    val name: String? = null,
    @SerialName("c_periodiLavori") val periodi: List<PeriodoCantiereDto> = emptyList(),
    @SerialName("c_lineaCantiere") val linee: List<NomeYextDto> = emptyList(),
    @SerialName("c_stazioniConCantieriAttivi") val stazioni: List<NomeYextDto> = emptyList(),
    /*
     * Il resto dei campi non si legge di proposito: la descrizione lunga
     * (`c_descrizioneInterventoCantiere`) e' testo formattato, un oggetto con
     * dentro il suo albero, e per un avviso di due righe non serve.
     */
    /** Gli avvisi in PDF, quando ci sono: una lista, non un file solo. */
    @SerialName("c_avvisoCantiere") val avvisi: List<AllegatoCantiereDto> = emptyList(),
)

/** Un allegato: il PDF dell'avviso. */
@Serializable
data class AllegatoCantiereDto(
    val url: String? = null,
    val name: String? = null,
)

@Serializable
data class PeriodoCantiereDto(
    /** `2026-02-23`. */
    val dataInizio: String? = null,
    val dataFine: String? = null,
)

/** Una linea o una stazione, come le nomina Yext. */
@Serializable
data class NomeYextDto(
    val name: String? = null,
)
