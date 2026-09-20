package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toServiceAlert
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.trenord.CantiereDto
import it.zawardo.treni.data.remote.trenord.CantieriApi
import it.zawardo.treni.data.remote.trenord.CodiciTrenord
import it.zawardo.treni.data.remote.trenord.NotiziaTrenord
import it.zawardo.treni.data.remote.trenord.NotizieDirettrici
import it.zawardo.treni.data.remote.trenord.TrenordApi
import it.zawardo.treni.data.remote.trenord.TrenordBoardParser
import it.zawardo.treni.data.remote.trenord.TrenordCrypto
import it.zawardo.treni.data.remote.trenord.TrenordSearchDto
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.trenord.TrenordStationDetailsDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Esito di una ricerca Trenord: le soluzioni e gli avvisi che le spiegano. */
data class TrenordResult(
    val journeys: List<Journey> = emptyList(),
    val alerts: List<ServiceAlert> = emptyList(),
)

/**
 * Copre il buco lasciato dalle altre due sorgenti: le linee S del Passante
 * milanese e il regionale lombardo, che ViaggiaTreno non conosce e che il BFF
 * Le Frecce non instrada.
 *
 * E' anche l'unica fonte che spieghi le situazioni eccezionali: gli avvisi
 * HAFAS riportano lavori e sospensioni di linea.
 */
class TrenordRepository(
    private val api: TrenordApi,
    private val json: Json,
    /** I cantieri, che stanno su un altro servizio: vedi [cantieriPer]. Null = non si chiedono. */
    private val cantieri: CantieriApi? = null,
) {
    /** Orari di stazione gia' scaricati, con il momento in cui sono arrivati. */
    private val orari = mutableMapOf<String, Pair<Long, TrenordStationDetailsDto>>()

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val isoDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val hourFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Vero se **almeno una** delle due stazioni e' nel catalogo Trenord: basta
     * questo perche' un cantiere suo possa riguardare il viaggio, mentre per
     * cercare soluzioni servono tutte e due ([covers]).
     */
    fun conosce(from: Station, to: Station): Boolean =
        CodiciTrenord.hafas(from.rfiCode) != null || CodiciTrenord.hafas(to.rfiCode) != null

    /**
     * Vero quando la tratta e' interrogabile: servono **tutte e due** le stazioni
     * nel catalogo Trenord. Un codice qualunque non basta — vedi [CodiciTrenord].
     */
    fun covers(from: Station, to: Station): Boolean =
        CodiciTrenord.hafas(from.rfiCode) != null && CodiciTrenord.hafas(to.rfiCode) != null

    suspend fun search(
        from: Station,
        to: Station,
        departure: LocalDateTime,
    ): TrenordResult = withContext(Dispatchers.IO) {
        val origin = CodiciTrenord.hafas(from.rfiCode) ?: return@withContext TrenordResult()
        val destination = CodiciTrenord.hafas(to.rfiCode) ?: return@withContext TrenordResult()

        // Il formato deve essere yyyyMMdd: con yyyy-MM-dd risponde 500.
        val giorno = departure.format(dateFormat)
        val ora = departure.format(hourFormat)
        val parsed = inChiaroOCifrato<TrenordSearchDto>(
            inChiaro = { api.searchInChiaro(origin, destination, giorno, ora) },
            cifrato = { api.search(origin, destination, giorno, ora) },
        ) ?: return@withContext TrenordResult()

        TrenordResult(
            journeys = parsed.solutions.mapNotNull { it.toJourney() },
            alerts = parsed.alerts.mapNotNull { it.toServiceAlert() },
        )
    }

    /** I cantieri gia' letti, e quando: vedi [cantieriPer]. */
    @Volatile
    private var cantieriLetti: Pair<Long, List<CantiereDto>>? = null

    /**
     * I cantieri che toccano una di queste stazioni, nel giorno indicato.
     *
     * Dice una cosa che l'app da sola non sa: che su quella tratta si lavora, e
     * fino a quando. Gli `hafas_alerts` della ricerca avvisano solo quando la
     * corsa di oggi ne e' toccata; qui c'e' anche quel che comincia domani.
     *
     * Le stazioni si riconoscono **per nome**, perche' e' l'unica cosa che le
     * due fonti abbiano in comune: Yext le scrive maiuscole («MILANO PORTA
     * GARIBALDI»), l'app come si leggono. Il confronto e' su lettere e cifre, e
     * quel che non si riconosce resta fuori.
     *
     * Basta **una** delle due stazioni, non tutte e due: gli elenchi di Yext
     * contengono le stazioni *con lavori attivi*, non tutte quelle della linea,
     * e il 20/09/2026 nessun cantiere nominava insieme i due capi di una
     * ricerca. Ne segue che un avviso puo' riguardare la linea e non il tuo
     * treno — «Lavori tra Bergamo e Ponte San Pietro» esce anche cercando
     * Garibaldi-Melzo, perche' quei treni finiscono a Garibaldi — e per questo
     * il titolo del cantiere si mostra sempre: dice subito di cosa si tratta.
     *
     * L'elenco e' uno solo per tutta la rete — 25 cantieri, 24 KB — e si tiene
     * un'ora: i lavori non cambiano nel pomeriggio. Se il servizio non risponde,
     * la lista e' vuota e la ricerca resta quella di prima.
     */
    suspend fun cantieriPer(
        stazioni: List<String>,
        giorno: LocalDate = LocalDate.now(ROME),
    ): List<ServiceAlert> = withContext(Dispatchers.IO) {
        val cantieri = cantieri ?: return@withContext emptyList()
        val nomi = stazioni.mapNotNull { it.lettereECifre().takeIf { n -> n.isNotEmpty() } }
        if (nomi.isEmpty()) return@withContext emptyList()

        val adesso = System.currentTimeMillis()
        val tutti = cantieriLetti?.takeIf { adesso - it.first < CANTIERI_VALIDI_MS }?.second
            ?: try {
                cantieri.cantieri().response?.results?.mapNotNull { it.data }.orEmpty()
                    .also { cantieriLetti = adesso to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Se non risponde resta l'ultima lettura, se c'e'; altrimenti niente.
                cantieriLetti?.second.orEmpty()
            }

        /*
         * Pochi e corti: gli avvisi stanno in cima ai risultati, e due cantieri
         * con l'elenco completo delle linee spingevano i treni sotto lo schermo.
         * Prima quelli che finiscono prima: sono i piu' vicini a riguardarti.
         */
        tutti
            .filter { cantiere -> cantiere.attivoIl(giorno) && cantiere.tocca(nomi) }
            .sortedBy { it.fineDelPeriodo(giorno) ?: LocalDate.MAX }
            .mapNotNull { it.toServiceAlert(giorno) }
            // Due cantieri diversi possono finire lo stesso giorno sulle stesse
            // linee: a distinguerli e' il titolo, che dice dove si lavora.
            .distinctBy { it.title to it.message }
            .take(CANTIERI_IN_CIMA)
    }

    /** Quando finisce il periodo aperto in quel giorno, se lo dice. */
    private fun CantiereDto.fineDelPeriodo(giorno: LocalDate): LocalDate? = periodi
        .mapNotNull { periodo ->
            val da = periodo.dataInizio?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val a = periodo.dataFine?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if ((da == null || !giorno.isBefore(da)) && (a == null || !giorno.isAfter(a))) a else null
        }
        .minOrNull()

    /** Vero se il cantiere e' aperto in quel giorno: uno dei suoi periodi lo comprende. */
    private fun CantiereDto.attivoIl(giorno: LocalDate): Boolean = periodi.any { periodo ->
        val da = periodo.dataInizio?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val a = periodo.dataFine?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        (da == null || !giorno.isBefore(da)) && (a == null || !giorno.isAfter(a))
    }

    /** Vero se fra le stazioni del cantiere c'e' una di quelle che interessano. */
    private fun CantiereDto.tocca(nomi: List<String>): Boolean =
        stazioni.any { sua -> sua.name?.lettereECifre()?.let { it in nomi } == true }

    private fun CantiereDto.toServiceAlert(giorno: LocalDate): ServiceAlert? {
        val titolo = name?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val fino = fineDelPeriodo(giorno)?.format(GIORNO_LEGGIBILE)
        val linee = linee.mapNotNull { it.name?.trim()?.takeIf { n -> n.isNotBlank() } }.distinct()
        return ServiceAlert(
            title = titolo,
            message = buildString {
                append(if (fino != null) "Lavori in corso fino al $fino" else "Lavori in corso")
                if (linee.isNotEmpty()) {
                    append(", sulle linee ").append(linee.take(LINEE_NELL_AVVISO).joinToString(", "))
                    // Un cantiere del nodo di Milano ne elenca cinque: oltre le
                    // prime, il conto dice quante sono senza occupare lo schermo.
                    val altre = linee.size - LINEE_NELL_AVVISO
                    if (altre > 0) append(" e altre ").append(altre)
                }
                append(".")
            },
            // I lavori non sono un guasto: si annunciano, non allarmano.
            severe = false,
        )
    }

    /** Il nome ridotto a lettere e cifre: e' l'unico modo di confrontare due cataloghi diversi. */
    private fun String.lettereECifre(): String = uppercase().filter(Char::isLetterOrDigit)

    /** Le notizie delle direttrici gia' lette, e quando: vedi [notizieDelleCorse]. */
    @Volatile
    private var notizie: Pair<Long, List<NotiziaTrenord>>? = null

    /**
     * Le notizie delle direttrici che nominano una corsa: vedi [NotizieDirettrici].
     * Una chiamata sola per tutte le corse, tenuta cinque minuti; se non risponde
     * resta la lettura di prima, o niente.
     */
    suspend fun notizieDelleCorse(): List<NotiziaTrenord> = withContext(Dispatchers.IO) {
        val adesso = System.currentTimeMillis()
        notizie?.let { (quando, lette) -> if (adesso - quando < NOTIZIE_VALIDE_MS) return@withContext lette }
        val lette = try {
            NotizieDirettrici.perCorsa(api.direttrici())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext notizie?.second.orEmpty()
        }
        notizie = adesso to lette
        lette
    }

    /**
     * Stato di una corsa Trenord, per le linee che ViaggiaTreno non copre.
     *
     * La data va passata: senza, l'endpoint risponde con l'orario nominale e su
     * una linea deviata per lavori quelle sono fermate diverse da quelle vere.
     */
    suspend fun trainStatus(
        trainNumber: String,
        date: LocalDate = LocalDate.now(ROME),
    ): TrainStatus? = withContext(Dispatchers.IO) {
        val data = date.format(isoDate)
        // L'endpoint restituisce un array: puo' essere vuoto se il numero non esiste.
        inChiaroOCifrato<List<TrenordSolutionDto>>(
            inChiaro = { api.trainInChiaro(trainNumber, data) },
            cifrato = { api.train(trainNumber, data) },
        )?.firstOrNull()?.toTrainStatus()
    }

    /**
     * L'orario di stazione secondo Trenord: le corse **programmate**, comprese
     * quelle che oggi non si fanno.
     *
     * E' l'unico elenco che le contenga. Un treno soppresso ViaggiaTreno lo
     * toglie dall'esistenza: non e' in tabellone, `andamentoTreno` risponde 204 e
     * `cercaNumeroTreno` non lo trova. Confrontando questo elenco con il suo si
     * scopre chi manca, e poi lo si chiede alla corsa.
     *
     * Fuori dall'area Trenord la risposta e' vuota: Roma Termini torna con zero
     * righe, quindi chiamarlo non fa danno anche dove non serve.
     */
    suspend fun timetable(rfiCode: String, arrivals: Boolean = false): List<BoardEntry> =
        withContext(Dispatchers.IO) {
            val details = stationDetails(rfiCode) ?: return@withContext emptyList()
            val mezzanotte = LocalDate.now(ROME).atStartOfDay(ROME).toInstant().toEpochMilli()
            val righe = TrenordBoardParser.parse(
                if (arrivals) details.arrivo else details.partenza,
                mezzanotte,
            )
            /*
             * Negli arrivi Trenord scrive la destinazione, non la provenienza
             * ("Diretto a MILANO CENTRALE" per tutti gli arrivi a Centrale): come
             * origine sarebbe falsa, e resta vuota. Vedi `data/fonti/TRENORD.md`.
             */
            if (arrivals) righe.map { it.copy(direction = null) } else righe
        }

    /**
     * La risposta contiene partenze e arrivi insieme, pesa 750 KB (17 KB
     * compressi) ed e' orario di tabella: non cambia da un minuto all'altro.
     * Tenerla qualche minuto evita di riscaricarla girando fra le due schede o
     * riaprendo lo stesso tabellone.
     */
    private suspend fun stationDetails(rfiCode: String): TrenordStationDetailsDto? {
        // Il tabellone vuole il MIR: per Brescia `S09999`, e per Osteria Nuova
        // niente, perche' con `S05302` Trenord risponderebbe Melide.
        val chiave = CodiciTrenord.mir(rfiCode) ?: return null
        val adesso = System.currentTimeMillis()
        synchronized(orari) {
            val avuto = orari[chiave]
            if (avuto != null && adesso - avuto.first < ORARI_TTL_MS) return avuto.second
        }
        val fresco = runCatching { api.stationDetails(mirCode = chiave) }.getOrNull() ?: return null
        synchronized(orari) { orari[chiave] = adesso to fresco }
        return fresco
    }

    /**
     * La risposta dalla porta in chiaro, e se quella non risponde o risponde
     * qualcosa che non si sa leggere, dal BFF cifrato: stessi dati, dieci volte il
     * peso (vedi [TrenordApi.searchInChiaro]). La porta in chiaro puo' sparire da
     * un giorno all'altro, e allora questo ripiego e' tutto cio' che resta.
     */
    private suspend inline fun <reified T> inChiaroOCifrato(
        crossinline inChiaro: suspend () -> okhttp3.ResponseBody,
        crossinline cifrato: suspend () -> okhttp3.ResponseBody,
    ): T? {
        try {
            val testo = inChiaro().string()
            json.decodeFromString<T>(testo).let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Porta in chiaro assente o cambiata: si prova il BFF.
        }
        return try {
            decode<T>(cifrato().bytes())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T? {
        val plain = TrenordCrypto.decrypt(bytes) ?: return null
        return runCatching { json.decodeFromString<T>(plain) }.getOrNull()
    }

    private companion object {
        /** Quanto vale un orario di stazione gia' scaricato. */
        const val ORARI_TTL_MS = 5 * 60 * 1000L

        /** Quanto vale una lettura delle notizie delle direttrici. */
        const val NOTIZIE_VALIDE_MS = 5 * 60 * 1000L

        /** Quanto vale una lettura dei cantieri: i lavori non cambiano nel pomeriggio. */
        const val CANTIERI_VALIDI_MS = 60 * 60 * 1000L

        /** Quanti avvisi di cantiere mostrare: stanno in cima ai risultati. */
        const val CANTIERI_IN_CIMA = 2

        /** E quante linee elencare dentro un avviso. */
        const val LINEE_NELL_AVVISO = 3

        /** «26/09/2026», come si legge. */
        val GIORNO_LEGGIBILE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
