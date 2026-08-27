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

        /**
         * Codice HAFAS a partire dal codice RFI.
         *
         * La regola e' `"83"` seguito dal numero MIR portato a cinque cifre:
         * `S01700` -> `8301700`, `S00039` -> `8300039`. Verificata sull'intero
         * catalogo Trenord: vale per 521 stazioni su 561.
         *
         * Le 40 eccezioni sono quasi tutte svizzere (prefisso `85`) piu' qualche
         * anomalia italiana, e stanno in [EXCEPTIONS]: sono poche e stabili, e
         * tenerle qui evita di scaricare un catalogo di 60 KB a ogni avvio.
         */
        fun hafasCode(rfiCode: String): String {
            val code = rfiCode.uppercase()
            EXCEPTIONS[code]?.let { return it }
            val digits = code.dropWhile { !it.isDigit() }
            return "83" + digits.padStart(5, '0')
        }

        private val EXCEPTIONS: Map<String, String> = mapOf(
        "S01110" to "8513967", // PINO-TRONZANO
        "S02383" to "8302383", // BONFERRARO
        "S04506" to "8304506", // TAGGIA ARMA
        "S04515" to "8304515", // ALASSIO
        "S04516" to "8304516", // ALBENGA
        "S05143" to "8305153", // SUZZARA VIE NUOVE
        "S05201" to "8505201", // AIROLO
        "S05204" to "8505204", // FAIDO
        "S05209" to "8505209", // BIASCA
        "S05212" to "8505212", // CASTIONE
        "S05213" to "8505213", // BELLINZONA
        "S05214" to "8505214", // GIUBIASCO
        "S05216" to "8505216", // RIVERA-BIRONICO
        "S05217" to "8505217", // MEZZOVICO
        "S05218" to "8505218", // TAVERNE-TORRICELLA
        "S05219" to "8505219", // LAMONE-CADEMPINO
        "S05300" to "8505300", // LUGANO
        "S05301" to "8505301", // LUGANO PARADISO
        "S05302" to "8505302", // MELIDE
        "S05303" to "8505303", // MAROGGIA-MELANO
        "S05306" to "8505306", // BALERNA
        "S05400" to "8505400", // LOCARNO
        "S05401" to "8505401", // TENERO
        "S05402" to "8505402", // GORDOLA
        "S05404" to "8505404", // CADENAZZO
        "S05405" to "8505405", // QUARTINO
        "S05406" to "8505406", // MAGADINO-VIRA
        "S05407" to "8505407", // S.NAZZARO
        "S05408" to "8513709", // RANZO-S. ABBONDIO
        "S05410" to "8505410", // GERRA
        "S05412" to "8505412", // RIAZZINO
        "S05415" to "8505415", // S.ANTONINO
        "S05417" to "8505417", // MINUSIO
        "S09998" to "8301308", // COMO CAMERLATA
        "S09999" to "8301717", // BRESCIA
        "S13143" to "8505305", // MENDRISIO
        "S19901" to "8518475", // MENDRISIO S. MARTINO
        "S19911" to "8575701", // AMBRI PIOTTA
        "S19915" to "8505205", // LAVORGO
        "Z05304" to "8505304", // CAPOLAGO-RIVA S.VITALE
        )
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
