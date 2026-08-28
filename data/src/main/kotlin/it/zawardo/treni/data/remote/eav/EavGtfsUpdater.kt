package it.zawardo.treni.data.remote.eav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Rinfresca l'orario EAV quando quello imbarcato ha fatto il suo tempo.
 *
 * **Perche' a scadenza e non a ogni avvio.** Il server EAV non offre alcun modo
 * di sapere se il feed e' cambiato senza scaricarlo tutto: ignora
 * `If-Modified-Since` e `Range`, e non manda `ETag`, `Last-Modified` ne'
 * `Content-Length` — verificato il 28/08/2026. Un controllo costa quindi
 * quanto un aggiornamento: 3,1 MB. Farlo a ogni avvio sarebbe consumo di dati
 * altrui per una risposta che nove volte su dieci e' "non e' cambiato niente".
 *
 * Si aspetta quindi che l'orario sia vecchio davvero. La soglia e' di **tre
 * mesi**, decisa una volta per tutte le sorgenti in
 * [it.zawardo.treni.data.remote.gtfs.AggiornamentoOrari]: sei sarebbero
 * abbastanza per EAV, che pubblica con largo anticipo, ma non per i feed che
 * coprono poche settimane, e due cadenze diverse per la stessa cosa sono un
 * dettaglio che prima o poi qualcuno dimentica di allineare.
 *
 * Quando l'aggiornamento fallisce — rete assente, server giu', feed illeggibile
 * — non succede niente di male: resta l'orario di prima. Un orario vecchio e'
 * molto meglio di nessun orario, e il tempo reale non ne dipende comunque,
 * perche' ritardi e soppressioni arrivano dal tabellone.
 */
internal class EavGtfsUpdater(
    private val client: OkHttpClient,
    /** Dove depositare l'orario aggiornato. La fornisce l'app: qui non c'e' Android. */
    private val cartella: File,
) {

    /**
     * Aggiorna se serve, e dice cosa ha fatto.
     *
     * [oggi] e' iniettabile perche' altrimenti la scadenza non sarebbe
     * verificabile senza aspettare sei mesi.
     */
    suspend fun aggiornaSeVecchio(
        soglia: Long = MESI_DI_VALIDITA,
        oggi: LocalDate = LocalDate.now(),
    ): Esito = withContext(Dispatchers.IO) {
        val attuale = EavOrario.carica(cartella)
            ?: return@withContext scarica(oggi) // senza orario si scarica comunque

        val mesi = ChronoUnit.MONTHS.between(attuale.generato, oggi)
        if (mesi < soglia) return@withContext Esito.AncoraBuono(attuale.generato, mesi)

        scarica(oggi)
    }

    private fun scarica(oggi: LocalDate): Esito {
        val temporaneo = File.createTempFile("eav-gtfs", ".zip")
        try {
            val richiesta = Request.Builder().url(URL_FEED).build()
            client.newCall(richiesta).execute().use { risposta ->
                if (!risposta.isSuccessful) return Esito.Fallito("HTTP ${risposta.code}")
                val corpo = risposta.body ?: return Esito.Fallito("risposta vuota")
                temporaneo.outputStream().use { out -> corpo.byteStream().copyTo(out) }
            }

            val nuovo = EavGtfsParser.parse(temporaneo)
                ?: return Esito.Fallito("il feed non contiene un orario ferroviario leggibile")

            /*
             * Un feed piu' vecchio di quello che gia' si ha non va scritto.
             *
             * Puo' capitare che EAV ripubblichi un archivio anteriore, o che una
             * cache di mezzo restituisca una copia vecchia. Sovrascrivere
             * significherebbe tornare indietro e poi riscaricare fra sei mesi,
             * cioe' peggiorare a ogni giro.
             */
            val attuale = EavOrario.carica(cartella)
            if (attuale != null && !nuovo.generato.isAfter(attuale.generato)) {
                return Esito.GiaAggiornato(attuale.generato)
            }

            if (!cartella.exists()) cartella.mkdirs()
            /*
             * Si scrive di fianco e poi si sposta: se il processo muore a meta'
             * scrittura, il file buono e' ancora al suo posto invece di essere
             * un troncone che non si apre.
             */
            val provvisorio = File(cartella, EavOrario.FILE_LOCALE + ".tmp")
            EavOrario.scrivi(nuovo, provvisorio)
            val definitivo = File(cartella, EavOrario.FILE_LOCALE)
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
         * Il feed ufficiale, pubblicato in Italian Open Data Licence v2.0, che
         * consente esplicitamente l'uso commerciale. L'indirizzo e' quello che
         * EAV dichiara nella sua pagina open data.
         */
        const val URL_FEED = "https://www.wimob.it/cfile/download.php?file=google-transit.zip"

        /** Vedi il commento in testa alla classe per il perche' di tre. */
        const val MESI_DI_VALIDITA = 3L
    }
}
