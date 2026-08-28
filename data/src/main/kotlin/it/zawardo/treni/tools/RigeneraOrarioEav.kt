package it.zawardo.treni.tools

import it.zawardo.treni.data.remote.eav.EavGtfsParser
import it.zawardo.treni.data.remote.eav.EavGtfsUpdater
import it.zawardo.treni.data.remote.eav.EavOrario
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Rigenera l'orario EAV imbarcato, scaricando il GTFS ufficiale.
 *
 * **Perche' un eseguibile e non uno script.** Il telefono, quando l'orario
 * scade, fa esattamente questo: scarica lo stesso zip, lo passa allo stesso
 * [EavGtfsParser] e ne scrive lo stesso formato con lo stesso serializzatore.
 * Se la rigenerazione vivesse in uno script di build separato ci sarebbero due
 * implementazioni della stessa cosa, e il giorno in cui divergessero l'app
 * comincerebbe a leggere sul telefono dati diversi da quelli compilati — un
 * difetto che si manifesterebbe solo dopo sei mesi, sui dispositivi altrui.
 *
 * Qui dentro non c'e' logica di importazione: c'e' solo il download e la
 * scrittura del file. Tutto il resto e' condiviso.
 *
 * Sta nel sorgente di test e non in quello principale perche' e' uno strumento
 * di sviluppo: nell'APK non deve finire.
 *
 * Si invoca dal task Gradle `rigeneraOrarioEav`, che gira da solo prima di ogni
 * build di release.
 */
fun main(args: Array<String>) {
    val destinazione = File(args.firstOrNull() ?: "src/main/resources/eav-orario.gz")

    val precedente = runCatching {
        destinazione.takeIf { it.isFile }?.inputStream()?.use { EavOrario.leggi(it) }
    }.getOrNull()

    println("[eav] orario attuale: " + (precedente?.generato?.toString() ?: "assente"))

    val zip = File.createTempFile("eav-gtfs", ".zip")
    try {
        val client = OkHttpClient()
        client.newCall(Request.Builder().url(EavGtfsUpdater.URL_FEED).build()).execute().use { r ->
            if (!r.isSuccessful) {
                return fallisci("il feed ha risposto HTTP ${r.code}", precedente)
            }
            val corpo = r.body ?: return fallisci("risposta senza corpo", precedente)
            zip.outputStream().use { out -> corpo.byteStream().copyTo(out) }
        }
        println("[eav] scaricati ${zip.length() / 1024} KB")

        val nuovo = EavGtfsParser.parse(zip)
            ?: return fallisci("il feed non contiene un orario ferroviario leggibile", precedente)

        /*
         * Un feed piu' vecchio di quello che gia' si ha non va scritto: la
         * stessa regola che vale sul telefono. Capita che venga ripubblicato un
         * archivio anteriore, e sovrascrivere significherebbe tornare indietro.
         */
        if (precedente != null && nuovo.generato.isBefore(precedente.generato)) {
            println(
                "[eav] il feed scaricato (${nuovo.generato}) e' anteriore a quello " +
                    "presente (${precedente.generato}): non lo sostituisco",
            )
            return
        }

        destinazione.parentFile?.mkdirs()
        EavOrario.scrivi(nuovo, destinazione)

        val mesi = ChronoUnit.MONTHS.between(nuovo.generato, LocalDate.now())
        println(
            "[eav] scritto ${destinazione.path}: ${destinazione.length() / 1024} KB, " +
                "${nuovo.corse.size} corse, generato il ${nuovo.generato} " +
                "($mesi mesi fa), copertura fino al ${nuovo.ultimoGiorno}",
        )
        if (precedente != null && nuovo.generato == precedente.generato) {
            println("[eav] identico al precedente: EAV non ha ancora ripubblicato")
        }
    } catch (e: Exception) {
        fallisci(e.message ?: e::class.java.simpleName, precedente)
    } finally {
        zip.delete()
    }
}

/**
 * Un aggiornamento fallito non deve fermare una release.
 *
 * Il feed sta su un hosting condiviso che ogni tanto non risponde. Bloccare la
 * pubblicazione dell'app perche' EAV e' irraggiungibile sarebbe sproporzionato:
 * l'orario che c'e' resta valido per mesi, e il telefono sa comunque rimediare
 * da solo. Si urla e si va avanti — ma si urla, perche' un aggiornamento che
 * fallisce in silenzio a ogni release e' peggio che non averlo.
 */
private fun fallisci(motivo: String, precedente: EavOrario?) {
    System.err.println("[eav] AGGIORNAMENTO FALLITO: $motivo")
    if (precedente != null) {
        System.err.println("[eav] resta l'orario del ${precedente.generato}, ancora valido")
    } else {
        System.err.println("[eav] ATTENZIONE: non c'e' nessun orario imbarcato")
    }
}
