package it.zawardo.treni.data.remote.fnb

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Il tabellone di Ferrotramviaria, le Ferrovie del Nord Barese.
 *
 * La Bari - Barletta non e' su RFI: passa per Bitonto, Terlizzi, Ruvo, Corato e
 * Andria, cioe' cinque comuni che sulla rete nazionale non hanno stazione. Ne'
 * ViaggiaTreno ne' il BFF Le Frecce sanno che quei treni esistono. Ci passa
 * anche il collegamento per l'aeroporto di Bari, che e' il modo in cui la gran
 * parte di chi atterra raggiunge la citta'.
 *
 * L'endpoint alimenta il portale di vendita e non chiede chiavi ne' sessioni.
 * Fra le sorgenti non-RFI e' la piu' generosa: risponde **JSON**, e in un solo
 * giro da' ritardo, binario e soppressione, cioe' esattamente cio' che serve a
 * chi sta sul marciapiede. Non c'e' HTML da interpretare.
 *
 * Quello che invece non fa, e va saputo prima di costruirci sopra:
 *
 * - **non accetta una data.** La risposta e' sempre "adesso": non c'e' modo di
 *   chiedere l'orario di domani.
 * - **non dice il percorso.** Di ogni corsa da' numero, destinazione, binario,
 *   orario e ritardo; le fermate intermedie no.
 * - **non distingue programmato ed effettivo.** Il binario e' uno solo, quello
 *   vero.
 * - **non segnala gli errori.** Un `codSito` inesistente non da' 404: da' 200
 *   con arrivi e partenze vuoti, che e' indistinguibile da una stazione senza
 *   treni. Per questo il registro delle fermate sta in [FnbStations] e si
 *   controlla prima di chiedere.
 */
interface FnbApi {

    companion object {
        const val BASE_URL = "https://eticket.ferrovienordbarese.it/b2c/json/"

        /**
         * Il servizio ferroviario.
         *
         * `type` non sceglie fra arrivi e partenze — quelli arrivano insieme —
         * ma fra ferro e gomma: con `B` lo stesso endpoint restituisce le
         * autolinee, centinaia di fermate su strada che non c'entrano con un
         * tabellone dei treni.
         */
        const val FERRO = "T"
    }

    /**
     * Il registro delle fermate servite dal tabellone.
     *
     * Non si usa a ogni avvio: l'elenco e' in [FnbStations], perche' la ricerca
     * per nome deve funzionare prima e a prescindere dalla rete. Serve a
     * verificare che quella copia non sia andata alla deriva — ed e' quello che
     * fa il test.
     *
     * Restituisce anche le fermate di Ferrovie Appulo Lucane, marcate
     * `gestore = "FAL"`: il portale e' condiviso fra le due aziende. Vedi
     * [FnbStations] per il motivo per cui non sono utilizzabili.
     */
    @GET("realtime/siti/{tipo}")
    suspend fun siti(@Path("tipo") tipo: String = FERRO): List<FnbSitoDto>

    /**
     * Arrivi e partenze di una fermata, in una sola chiamata.
     *
     * [codSito] e' il codice nativo del portale (`S01110`), non quello sintetico
     * con cui il resto dell'app indirizza la stazione: la conversione la fa
     * [FnbStations].
     */
    @GET("realtime/dati")
    suspend fun tabellone(
        @Query("codSito") codSito: String,
        @Query("type") type: String = FERRO,
    ): FnbBoardDto
}
