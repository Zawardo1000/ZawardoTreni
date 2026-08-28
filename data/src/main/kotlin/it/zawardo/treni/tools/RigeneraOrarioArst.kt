package it.zawardo.treni.tools

import it.zawardo.treni.data.remote.arst.ArstGtfsParser
import it.zawardo.treni.data.remote.arst.ArstGtfsUpdater
import it.zawardo.treni.data.remote.arst.ArstOrario
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Rigenera l'orario ARST imbarcato, scaricando il GTFS ufficiale.
 *
 * Vale parola per parola quello che vale per l'equivalente EAV: il telefono,
 * quando l'orario scade, fa esattamente questo — scarica lo stesso zip, lo passa
 * allo stesso [ArstGtfsParser], ne scrive lo stesso formato. Se la rigenerazione
 * vivesse in uno script separato ci sarebbero due implementazioni della stessa
 * cosa, e il giorno in cui divergessero l'app leggerebbe sul telefono dati
 * diversi da quelli compilati — un difetto che si manifesta solo mesi dopo, sui
 * dispositivi altrui.
 *
 * Sta nel sorgente di test e non in quello principale perche' e' uno strumento
 * di sviluppo: nell'APK non deve finire.
 *
 * Si invoca dal task Gradle `rigeneraOrarioArst`, che gira da solo prima di ogni
 * build di release.
 */
fun main(args: Array<String>) {
    val destinazione = File(args.firstOrNull() ?: "src/main/resources/arst-orario.gz")

    val precedente = runCatching {
        destinazione.takeIf { it.isFile }?.inputStream()?.use { ArstOrario.leggi(it) }
    }.getOrNull()

    println("[arst] orario attuale: " + (precedente?.generato?.toString() ?: "assente"))

    val zip = File.createTempFile("arst-gtfs", ".zip")
    try {
        /*
         * Il feed ARST sono 19,7 MB: con i timeout di default di OkHttp, su una
         * linea lenta, la lettura scade a meta' scaricamento e la release esce
         * con l'orario vecchio senza che sia colpa di nessuno.
         */
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()

        client.newCall(Request.Builder().url(ArstGtfsUpdater.URL_FEED).build()).execute().use { r ->
            if (!r.isSuccessful) {
                return fallisci("il feed ha risposto HTTP ${r.code}", precedente)
            }
            val corpo = r.body ?: return fallisci("risposta senza corpo", precedente)
            zip.outputStream().use { out -> corpo.byteStream().copyTo(out) }
        }
        println("[arst] scaricati ${zip.length() / 1024} KB")

        val nuovo = ArstGtfsParser.parse(zip)
            ?: return fallisci("il feed non contiene un orario ferroviario leggibile", precedente)

        /*
         * Un feed piu' vecchio di quello che gia' si ha non va scritto: la stessa
         * regola che vale sul telefono. Capita che venga ripubblicato un archivio
         * anteriore, e sovrascrivere significherebbe tornare indietro.
         */
        if (precedente != null && nuovo.generato.isBefore(precedente.generato)) {
            println(
                "[arst] il feed scaricato (${nuovo.generato}) e' anteriore a quello " +
                    "presente (${precedente.generato}): non lo sostituisco",
            )
            return
        }

        destinazione.parentFile?.mkdirs()
        ArstOrario.scrivi(nuovo, destinazione)

        val mesi = ChronoUnit.MONTHS.between(nuovo.generato, LocalDate.now())
        println(
            "[arst] scritto ${destinazione.path}: ${destinazione.length() / 1024} KB, " +
                "${nuovo.corse.size} corse su ${nuovo.linee.size} linee, " +
                "${nuovo.stazioni.size} stazioni, generato il ${nuovo.generato} " +
                "($mesi mesi fa), copertura fino al ${nuovo.ultimoGiorno}",
        )
        if (precedente != null && nuovo.generato == precedente.generato) {
            println("[arst] identico al precedente: ARST non ha ancora ripubblicato")
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
 * L'orario che c'e' resta valido per mesi, e il telefono sa comunque rimediare
 * da solo. Si urla e si va avanti — ma si urla, perche' un aggiornamento che
 * fallisce in silenzio a ogni release e' peggio che non averlo.
 */
private fun fallisci(motivo: String, precedente: ArstOrario?) {
    System.err.println("[arst] AGGIORNAMENTO FALLITO: $motivo")
    if (precedente != null) {
        System.err.println("[arst] resta l'orario del ${precedente.generato}, ancora valido")
    } else {
        System.err.println("[arst] ATTENZIONE: non c'e' nessun orario imbarcato")
    }
}
