package it.zawardo.treni.data.remote.arst

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Rinfresca l'orario ARST quando quello imbarcato ha fatto il suo tempo.
 *
 * Stessa forma dell'aggiornatore EAV, e per le stesse ragioni: il feed va
 * scaricato per intero perche' non c'e' modo di sapere se e' cambiato senza
 * prenderlo, quindi lo si fa di rado e solo quando serve davvero.
 *
 * Qui pesa di piu' che per EAV — **19,7 MB contro 3,1** — perche' l'archivio
 * ARST e' quasi tutto autolinee. E' il motivo per cui questo aggiornamento non
 * parte mai da solo: lo chiede il coordinatore, e solo se l'utente ha acceso
 * ARST fra le sorgenti. Scaricare venti megabyte a chi la Sardegna non la guarda
 * mai sarebbe consumo di dati altrui per niente.
 *
 * Quando l'aggiornamento fallisce — rete assente, server giu', feed illeggibile
 * — non succede niente di male: resta l'orario di prima. Un orario vecchio e'
 * molto meglio di nessun orario, e qui non c'e' un tempo reale che possa
 * rimediare, perche' ARST non ne pubblica.
 */
internal class ArstGtfsUpdater(
    private val client: OkHttpClient,
    /** Dove depositare l'orario aggiornato. La fornisce l'app: qui non c'e' Android. */
    private val cartella: File,
) {

    /**
     * Aggiorna se serve, e dice cosa ha fatto.
     *
     * [oggi] e' iniettabile perche' altrimenti la scadenza non sarebbe
     * verificabile senza aspettare tre mesi.
     */
    suspend fun aggiornaSeVecchio(
        soglia: Long = MESI_DI_VALIDITA,
        oggi: LocalDate = LocalDate.now(),
    ): Esito = withContext(Dispatchers.IO) {
        val attuale = ArstOrario.carica(cartella)
            ?: return@withContext scarica(oggi) // senza orario si scarica comunque

        val mesi = ChronoUnit.MONTHS.between(attuale.generato, oggi)
        if (mesi < soglia) return@withContext Esito.AncoraBuono(attuale.generato, mesi)

        scarica(oggi)
    }

    private fun scarica(oggi: LocalDate): Esito {
        val temporaneo = File.createTempFile("arst-gtfs", ".zip")
        try {
            val richiesta = Request.Builder().url(URL_FEED).build()
            client.newCall(richiesta).execute().use { risposta ->
                if (!risposta.isSuccessful) return Esito.Fallito("HTTP ${risposta.code}")
                val corpo = risposta.body ?: return Esito.Fallito("risposta vuota")
                temporaneo.outputStream().use { out -> corpo.byteStream().copyTo(out) }
            }

            val nuovo = ArstGtfsParser.parse(temporaneo)
                ?: return Esito.Fallito("il feed non contiene un orario ferroviario leggibile")

            /*
             * Un feed piu' vecchio di quello che gia' si ha non va scritto.
             *
             * Puo' capitare che venga ripubblicato un archivio anteriore, o che
             * una cache di mezzo restituisca una copia vecchia. Sovrascrivere
             * significherebbe tornare indietro e poi riscaricare fra tre mesi,
             * cioe' peggiorare a ogni giro.
             */
            val attuale = ArstOrario.carica(cartella)
            if (attuale != null && !nuovo.generato.isAfter(attuale.generato)) {
                return Esito.GiaAggiornato(attuale.generato)
            }

            if (!cartella.exists()) cartella.mkdirs()
            /*
             * Si scrive di fianco e poi si sposta: se il processo muore a meta'
             * scrittura, il file buono e' ancora al suo posto invece di essere
             * un troncone che non si apre.
             */
            val provvisorio = File(cartella, ArstOrario.FILE_LOCALE + ".tmp")
            ArstOrario.scrivi(nuovo, provvisorio)
            val definitivo = File(cartella, ArstOrario.FILE_LOCALE)
            if (definitivo.exists()) definitivo.delete()
            if (!provvisorio.renameTo(definitivo)) {
                provvisorio.delete()
                return Esito.Fallito("non riesco a sostituire l'orario locale")
            }

            return Esito.Aggiornato(nuovo.generato, nuovo.corse.size, oggi)
        } catch (e: Exception) {
            return Esito.Fallito(e.message ?: e::class.java.simpleName)
        } finally {
            temporaneo.delete()
        }
    }

    sealed interface Esito {
        /** Non era il momento: l'orario e' ancora abbastanza fresco. */
        data class AncoraBuono(val generato: LocalDate, val mesi: Long) : Esito

        data class Aggiornato(val generato: LocalDate, val corse: Int, val quando: LocalDate) : Esito

        /** Scaricato, ma non era piu' recente di quello che c'era. */
        data class GiaAggiornato(val generato: LocalDate) : Esito

        /** Resta valido l'orario precedente. */
        data class Fallito(val motivo: String) : Esito
    }

    companion object {
        /**
         * Il feed ufficiale ARST.
         *
         * E' l'indirizzo che ARST dichiara come proprio GTFS e che alimenta
         * Google Maps e Moovit. Il nome del file dice "cagliari" ma l'archivio
         * copre tutta la Sardegna, ferrovie del nord comprese: verificato, ci
         * sono dentro Sassari - Alghero e Macomer - Nuoro.
         */
        const val URL_FEED = "https://www.arstspa.info/arst-cagliari-it.zip"

        /** Vedi [it.zawardo.treni.data.remote.gtfs.AggiornamentoOrari]. */
        const val MESI_DI_VALIDITA = 3L
    }
}
