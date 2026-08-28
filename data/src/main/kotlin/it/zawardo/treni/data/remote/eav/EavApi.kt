package it.zawardo.treni.data.remote.eav

import okhttp3.ResponseBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Il tabellone EAV: Circumvesuviana, Cumana, Circumflegrea e suburbane.
 *
 * EAV copre l'ultimo buco rimasto, e non e' piccolo. Le sue linee non passano
 * da RFI: ne' ViaggiaTreno ne' il BFF Le Frecce sanno che esistono, quindi
 * prima di questa classe l'intera rete vesuviana — Sorrento, Pompei Scavi,
 * Ercolano, Castellammare — per l'app non circolava affatto.
 *
 * C'e' un solo endpoint, ed e' quello che alimenta i monitor delle stazioni:
 * non e' documentato, ma non chiede chiavi ne' sessioni. Risponde **HTML**, non
 * JSON, percio' il corpo torna grezzo e lo interpreta [EavBoardParser].
 *
 * Quello che questo endpoint **non** fa, e va saputo prima di costruirci sopra:
 *
 * - **non accetta una data.** `data`, `giorno`, `dataRif` vengono ignorati: la
 *   risposta e' sempre "adesso". Per l'orario di domani serve il GTFS.
 * - **non dice il percorso.** Di ogni corsa da' numero, categoria, direzione,
 *   binario, orario e ritardo; le fermate no. Si ricostruiscono per numero di
 *   treno, che e' la stessa chiave del GTFS.
 * - **non risponde in GET.** Una GET torna 200 con un corpo vuoto, il che e'
 *   piu' insidioso di un errore: sembra una stazione senza treni.
 */
interface EavApi {

    companion object {
        const val BASE_URL = "https://orariotreni.eavsrl.it/teleindicatori/"

        /** Partenze. */
        const val PARTENZE = "P"

        /** Arrivi. */
        const val ARRIVI = "A"

        /**
         * L'unico valore che allunga la lista.
         *
         * `visualizzazione` sembra un formato e invece e' un interruttore:
         * `mobile` restituisce 40 corse, qualunque altro valore — compreso un
         * numero, compreso l'assenza del parametro — ne restituisce 10. Con 10
         * corse una stazione trafficata copre poco piu' di un'ora, che sul
         * Passante di Napoli e' niente.
         */
        const val LISTA_LUNGA = "mobile"
    }

    /**
     * Le corse in transito da una stazione.
     *
     * [codLoc] e' l'id EAV della stazione, quello di [EavStations].
     *
     * Il parametro `device` che il sito manda (`M01T1M`, il nome del monitor
     * fisico) e' ignorato dal server: provato con valori inventati e con
     * l'assenza, la risposta non cambia. Non lo si manda.
     */
    @FormUrlEncoded
    @POST("ws_getData.php")
    suspend fun tabellone(
        @Field("codLoc") codLoc: Int,
        @Field("tipoLista") tipoLista: String = PARTENZE,
        @Field("visualizzazione") visualizzazione: String = LISTA_LUNGA,
    ): ResponseBody
}
