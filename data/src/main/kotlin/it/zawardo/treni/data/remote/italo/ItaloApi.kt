package it.zawardo.treni.data.remote.italo

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Il servizio con cui NTV pubblica lo stato di Italo.
 *
 * E' quello che alimenta `italoinviaggio.italotreno.com`: risponde JSON in
 * chiaro, senza chiavi ne' sessioni. Come le altre due fonti dell'app non e'
 * documentato, quindi puo' cambiare senza preavviso.
 *
 * I due endpoint non hanno la stessa affidabilita', ed e' bene saperlo prima di
 * costruirci sopra:
 *
 * - [stazione] risponde sempre, ed e' la fonte buona: numero, destinazione,
 *   ritardo, binario e orario aggiornato.
 * - [treno] risponde **solo per alcune corse**. Interrogando i cinque Italo in
 *   viaggio verso Napoli alle 20:50 del 27 agosto 2026, tutti e cinque hanno
 *   risposto `IsEmpty`; l'8907, che aveva un avviso attivo, ha risposto con i
 *   dati delle 08:11, ore dopo il suo arrivo. Va quindi trattato come un extra
 *   che a volte c'e', mai come la fonte su cui contare.
 */
interface ItaloApi {

    companion object {
        const val BASE_URL = "https://italoinviaggio.italotreno.com/api/"
    }

    /** Tabellone di stazione. [code] e' la sigla Italo, es. `RMT`, non quella RFI. */
    @GET("RicercaStazioneService")
    suspend fun stazione(@Query("CodiceStazione") code: String): ItaloStationDto

    /** Stato di una corsa. Puo' rispondere `IsEmpty` anche su treni in viaggio. */
    @GET("RicercaTrenoService")
    suspend fun treno(@Query("TrainNumber") number: String): ItaloTrainDto

    /**
     * Le corse seguite su una tratta, ciascuna col percorso completo.
     *
     * Vuole tutte e due le sigle: con una sola risponde con la pagina del sito
     * invece che con i dati. E' l'unico modo per avere le fermate di una corsa
     * Italo, perche' [treno] tace quasi sempre.
     */
    @GET("RicercaTrattaService")
    suspend fun tratta(
        @Query("Departure") departure: String,
        @Query("Arrival") arrival: String,
    ): ItaloRouteDto
}
