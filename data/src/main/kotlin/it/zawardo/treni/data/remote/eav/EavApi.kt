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
 * - **non risponde in GET.** Una GET torna 200 con una pagina "Lista non
 *   disponibile" e nessuna riga, il che e' piu' insidioso di un errore: sembra
 *   una stazione senza treni.
 * - **non ha nome stabile.** Il 18/09/2026 `ws_getData.php` e' diventato
 *   `ws_getData_pis.php`, e per giorni il tabellone e' rimasto vuoto: se ne
 *   accorge `EavOrarioBoardTest`, che fallisce quando oggi risponde l'orario
 *   invece del tabellone.
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
    /*
     * `ws_getData_pis.php`, e non piu' `ws_getData.php`: EAV ha rinominato il
     * file, e il vecchio nome risponde 404. Verificato il 18/09/2026 su tutte le
     * 126 stazioni col tabellone, partenze e arrivi: stessi parametri, stessi
     * `codLoc`, stesso HTML, nessuna chiave. Lo stesso sito prepara un secondo
     * endpoint, `ws_getData_moova.php`, con altri id e dati peggiori — orari di
     * domani senza data, treni gia' partiti, binari sbagliati — e il sito non lo
     * usa. Vedi `data/API-EAV.md`.
     */
    @FormUrlEncoded
    @POST("ws_getData_pis.php")
    suspend fun tabellone(
        @Field("codLoc") codLoc: Int,
        @Field("tipoLista") tipoLista: String = PARTENZE,
        @Field("visualizzazione") visualizzazione: String = LISTA_LUNGA,
    ): ResponseBody
}
