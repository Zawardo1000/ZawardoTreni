package it.zawardo.treni.data.remote.arst

import it.zawardo.treni.data.remote.gtfs.GtfsCsv
import it.zawardo.treni.data.remote.gtfs.GtfsCsv.campo
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

/**
 * L'orario ferroviario ARST: le quattro linee a scartamento ridotto sarde.
 *
 * Monserrato - Mandas - Isili, Macomer - Nuoro, Sassari - Alghero e
 * Sassari - Sorso. Sono l'unica ferrovia della Sardegna che non sia RFI, e prima
 * di questa classe per l'app la Sardegna interna non esisteva: Mandas, Isili,
 * Sorso e Bortigali non hanno un codice RFI perche' sulla rete nazionale non
 * hanno stazione affatto.
 *
 * ## Qui c'e' solo l'orario, e nessun tempo reale
 *
 * Perche' ARST non lo pubblica: non c'e' un tabellone, non c'e' un'API, non c'e'
 * un GTFS-Realtime. C'e' il GTFS e basta. Di queste corse si sa **quando sono
 * previste**, mai se sono in ritardo o soppresse — ed e' una differenza che
 * l'app deve dire a chi guarda, non nascondere dietro un orario che sembra
 * quello di ViaggiaTreno.
 *
 * ## Perche' imbarcato e non scaricato all'occorrenza
 *
 * Il feed ARST pesa 19,7 MB perche' e' quasi tutto autolinee: 315 rotte su gomma
 * contro 4 su ferro. Il ferroviario sono 117 corse e 859 passaggi, che compressi
 * stanno in una ventina di KB. Scaricarlo a ogni consultazione vorrebbe dire
 * 19,7 MB per venti KB di dati utili.
 *
 * Sta quindi imbarcato e si rinfresca da solo quando invecchia, che e' il
 * compito di [ArstGtfsUpdater].
 *
 * ## Le stazioni viaggiano con l'orario
 *
 * A differenza di EAV, Ferrotramviaria e Vigezzina — che hanno un registro di
 * fermate scritto a mano — qui l'elenco delle stazioni sta **dentro questo
 * stesso file**, perche' il GTFS lo pubblica gia' con nomi e coordinate. Un
 * registro separato potrebbe divergere dall'orario a ogni aggiornamento; cosi'
 * i due non possono disallinearsi, perche' sono la stessa cosa.
 */
internal class ArstOrario(
    /** Data di generazione del feed. */
    val generato: LocalDate,
    val stazioni: List<Stazione>,
    /** Sigla di linea -> nome esteso, per le etichette. */
    val linee: Map<String, String>,
    val corse: List<Corsa>,
    /** Per ogni calendario, i giorni in cui e' attivo. */
    private val calendari: Map<Int, Set<LocalDate>>,
) {

    /** Una stazione, con le coordinate che il GTFS pubblica. */
    data class Stazione(
        /** Parte numerica dello stop_id ARST: `F_22561` diventa `22561`. */
        val id: Int,
        val nome: String,
        val lat: Double,
        val lon: Double,
    )

    /**
     * Una corsa dell'orario.
     *
     * [id] non e' un numero di treno: **ARST non numera i suoi treni**, o almeno
     * non lo pubblica. E' il codice interno della corsa nel GTFS (`AT1`), ed e'
     * l'unica cosa che distingua due partenze della stessa linea. Va mostrato
     * per quello che e', non spacciato per un numero da cercare su un tabellone
     * che non esiste.
     */
    data class Corsa(
        val id: String,
        /** Sigla di linea: `TCA`, `TMA`, `TSS1`, `TSS2`. */
        val linea: String,
        val destinazione: String,
        val calendario: Int,
        val fermate: List<Fermata>,
    )

    /**
     * Un passaggio, in minuti dalla mezzanotte.
     *
     * Possono superare 1440: il GTFS esprime cosi' le corse che scavalcano la
     * mezzanotte, e riportarle a zero farebbe arrivare un treno prima di essere
     * partito.
     */
    data class Fermata(
        val stazione: Int,
        val arrivo: Int,
        val partenza: Int,
    )

    private val perId: Map<Int, Stazione> = stazioni.associateBy { it.id }

    /** La stazione dietro un id, se esiste. */
    fun stazione(id: Int): Stazione? = perId[id]

    private fun attiva(corsa: Corsa, giorno: LocalDate): Boolean =
        calendari[corsa.calendario]?.contains(giorno) == true

    /** Le corse che circolano in una certa data. */
    fun corseDel(giorno: LocalDate): List<Corsa> = corse.filter { attiva(it, giorno) }

    /**
     * I passaggi da una stazione in un giorno, ordinati per orario.
     *
     * E' quello che serve al tabellone. Con [partenze] falso da' gli arrivi, e
     * la differenza non e' solo quale orario si guarda: si scarta il capolinea
     * di partenza invece di quello d'arrivo, perche' una corsa che da li' parte
     * non e' un arrivo e mostrarla fra gli arrivi sarebbe un errore.
     */
    fun passaggi(stazione: Int, giorno: LocalDate, partenze: Boolean = true): List<Passaggio> =
        corse.asSequence()
            .filter { attiva(it, giorno) }
            .mapNotNull { corsa ->
                val i = corsa.fermate.indexOfFirst { it.stazione == stazione }
                if (i < 0) return@mapNotNull null
                if (partenze && i == corsa.fermate.lastIndex) return@mapNotNull null
                if (!partenze && i == 0) return@mapNotNull null
                Passaggio(
                    corsa = corsa,
                    fermata = corsa.fermate[i],
                    // Fra gli arrivi la direzione utile e' da dove la corsa viene.
                    origine = perId[corsa.fermate.first().stazione]?.nome,
                )
            }
            .sortedBy { if (partenze) it.fermata.partenza else it.fermata.arrivo }
            .toList()

    data class Passaggio(val corsa: Corsa, val fermata: Fermata, val origine: String?)

    /**
     * Le corse dirette fra due stazioni in un giorno, con orari.
     *
     * La ricerca A→B che ARST non pubblica: la si ricava dall'orario, tenendo le
     * corse che fermano prima a [da] e poi ad [a]. Serve alla ricerca di
     * itinerario dentro la Sardegna, dove non c'e' un tabellone di stazione a cui
     * chiedere una tratta.
     */
    fun collegamenti(da: Int, a: Int, giorno: LocalDate): List<Collegamento> =
        corse.asSequence()
            .filter { attiva(it, giorno) }
            .mapNotNull { corsa ->
                val iDa = corsa.fermate.indexOfFirst { it.stazione == da }
                val iA = corsa.fermate.indexOfLast { it.stazione == a }
                if (iDa < 0 || iA <= iDa) return@mapNotNull null
                Collegamento(corsa, corsa.fermate[iDa].partenza, corsa.fermate[iA].arrivo)
            }
            .sortedBy { it.partenza }
            .toList()

    data class Collegamento(val corsa: Corsa, val partenza: Int, val arrivo: Int)

    /** Il giorno piu' lontano che l'orario copre: oltre non si sa nulla. */
    val ultimoGiorno: LocalDate? = calendari.values.flatten().maxOrNull()

    /** Vero se l'orario ha qualcosa da dire su quel giorno. */
    fun copre(giorno: LocalDate): Boolean = calendari.values.any { it.contains(giorno) }

    // ------------------------------------------------------------- formato

    /**
     * Il formato compatto in cui l'orario viene imbarcato.
     *
     * Lo stesso impianto dell'orario EAV — testo a righe, niente JSON, niente
     * deserializzatori — con due tipi di riga in piu', perche' qui viaggiano
     * anche le stazioni e i nomi delle linee:
     *
     * ```
     * V|1|20260828                        versione e data del feed
     * S|22561|Alghero|40.574959|8.322167  stazione
     * L|TCA|Monserrato - Mandas - Isili   nome esteso di una linea
     * C|0|20260101,20260102,...           i giorni di un calendario
     * T|AT1|TCA|Mandas|0|22561:302:302    corsa
     * ```
     */
    fun serializza(): String = buildString {
        append("V|1|").append(giornoCompatto(generato)).append('\n')
        for (s in stazioni) {
            append("S|").append(s.id).append('|').append(s.nome.replace('|', ' ')).append('|')
            append(s.lat).append('|').append(s.lon).append('\n')
        }
        for ((sigla, nome) in linee) {
            append("L|").append(sigla).append('|').append(nome.replace('|', ' ')).append('\n')
        }
        for ((idx, giorni) in calendari) {
            append("C|").append(idx).append('|')
            append(giorni.sorted().joinToString(",") { giornoCompatto(it) })
            append('\n')
        }
        for (c in corse) {
            append("T|").append(c.id).append('|').append(c.linea).append('|')
            append(c.destinazione.replace('|', ' ')).append('|').append(c.calendario).append('|')
            append(c.fermate.joinToString(",") { "${it.stazione}:${it.arrivo}:${it.partenza}" })
            append('\n')
        }
    }

    companion object {
        /** Nome della risorsa imbarcata nel modulo. */
        const val RISORSA = "/arst-orario.gz"

        /** Nome del file locale che, quando esiste, ha la precedenza. */
        const val FILE_LOCALE = "arst-orario.gz"

        private fun giornoCompatto(d: LocalDate): String =
            "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)

        /** Rilegge il formato compatto. */
        fun deserializza(testo: String): ArstOrario? {
            var generato: LocalDate? = null
            val stazioni = mutableListOf<Stazione>()
            val linee = mutableMapOf<String, String>()
            val calendari = mutableMapOf<Int, Set<LocalDate>>()
            val corse = mutableListOf<Corsa>()

            testo.lineSequence().forEach { riga ->
                if (riga.isBlank()) return@forEach
                val p = riga.split('|')
                when (p.firstOrNull()) {
                    "V" -> if (p.size >= 3) generato = GtfsCsv.data(p[2])
                    "S" -> if (p.size >= 5) {
                        val id = p[1].toIntOrNull() ?: return@forEach
                        val lat = p[3].toDoubleOrNull() ?: return@forEach
                        val lon = p[4].toDoubleOrNull() ?: return@forEach
                        stazioni += Stazione(id, p[2], lat, lon)
                    }
                    "L" -> if (p.size >= 3) linee[p[1]] = p[2]
                    "C" -> if (p.size >= 3) {
                        val idx = p[1].toIntOrNull() ?: return@forEach
                        calendari[idx] = p[2].split(',')
                            .mapNotNullTo(mutableSetOf()) { GtfsCsv.data(it) }
                    }
                    "T" -> if (p.size >= 6) {
                        val fermate = p[5].split(',').mapNotNull { f ->
                            val q = f.split(':')
                            if (q.size < 3) return@mapNotNull null
                            Fermata(
                                stazione = q[0].toIntOrNull() ?: return@mapNotNull null,
                                arrivo = q[1].toIntOrNull() ?: return@mapNotNull null,
                                partenza = q[2].toIntOrNull() ?: return@mapNotNull null,
                            )
                        }
                        if (fermate.isNotEmpty()) {
                            corse += Corsa(
                                id = p[1],
                                linea = p[2],
                                destinazione = p[3],
                                calendario = p[4].toIntOrNull() ?: 0,
                                fermate = fermate,
                            )
                        }
                    }
                }
            }
            val g = generato ?: return null
            if (corse.isEmpty() || stazioni.isEmpty()) return null
            return ArstOrario(g, stazioni, linee, corse, calendari)
        }

        /** Legge un orario compresso da uno stream. */
        fun leggi(stream: InputStream): ArstOrario? = runCatching {
            GZIPInputStream(stream).bufferedReader().use { deserializza(it.readText()) }
        }.getOrNull()

        /** Scrive l'orario compresso su file. */
        fun scrivi(orario: ArstOrario, destinazione: File) {
            destinazione.outputStream().use { out ->
                GZIPOutputStream(out).bufferedWriter().use { it.write(orario.serializza()) }
            }
        }

        /**
         * L'orario da usare: quello scaricato se c'e', altrimenti l'imbarcato.
         *
         * Un aggiornamento andato storto non deve lasciare l'app senza orario,
         * quindi un file locale illeggibile fa ricadere sulla risorsa del modulo
         * invece di propagare l'errore.
         */
        fun carica(cartella: File?): ArstOrario? {
            val locale = cartella?.let { File(it, FILE_LOCALE) }
            if (locale != null && locale.isFile) {
                locale.inputStream().use { leggi(it) }?.let { return it }
            }
            val risorsa = ArstOrario::class.java.getResourceAsStream(RISORSA) ?: return null
            return risorsa.use { leggi(it) }
        }
    }
}

/**
 * Trasforma il GTFS ARST nell'orario compatto.
 *
 * Come per EAV, lo stesso codice serve due volte — genera la risorsa imbarcata e
 * ingerisce il feed scaricato quando l'orario invecchia — perche' due
 * implementazioni della stessa cosa, il giorno in cui divergessero, farebbero
 * leggere al telefono dati diversi da quelli compilati.
 *
 * Rispetto al parser EAV cambia in tre punti, tutti imposti dal feed:
 *
 * 1. **le colonne si cercano per nome.** In ARST `route_type` e' la sesta
 *    colonna, in EAV la quinta: il GTFS fissa i nomi, non l'ordine.
 * 2. **i calendari vanno espansi.** EAV elenca le date una per una in
 *    `calendar_dates.txt`; ARST usa `calendar.txt` con i giorni della settimana
 *    e un intervallo, piu' le eccezioni. Vanno srotolati in date esplicite.
 * 3. **non c'e' `trip_short_name`.** ARST non numera i treni: al suo posto si
 *    tiene il codice della corsa.
 */
internal object ArstGtfsParser {

    /** `route_type` 2: ferrovia. Le altre 315 rotte del feed sono autolinee. */
    private const val TIPO_FERROVIA = "2"

    private val GIORNI = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /**
     * Legge lo zip GTFS e ne estrae il solo servizio ferroviario.
     *
     * Vuole un [File] e non uno stream perche' l'archivio va letto fuori ordine:
     * per sapere quali passaggi tenere bisogna gia' sapere quali corse sono
     * ferroviarie, e `stop_times.txt` da solo sono 176.000 righe di cui ne
     * servono 859.
     */
    fun parse(zip: File): ArstOrario? = runCatching {
        ZipFile(zip).use { archivio ->

            // 1. le rotte ferroviarie
            val rotte = mutableMapOf<String, String>()
            GtfsCsv.scorri(archivio, "routes.txt") { t, c ->
                val tipo = t.richiesta("route_type") ?: return@scorri
                if (c.campo(tipo) != TIPO_FERROVIA) return@scorri
                val id = t.richiesta("route_id") ?: return@scorri
                rotte[c.campo(id)] = c.campo(t.col("route_long_name"))
            }
            if (rotte.isEmpty()) return@use null

            // 2. le corse su quelle rotte
            val corse = mutableMapOf<String, Grezza>()
            GtfsCsv.scorri(archivio, "trips.txt") { t, c ->
                val rid = t.richiesta("route_id") ?: return@scorri
                val linea = c.campo(rid)
                if (linea !in rotte) return@scorri
                val tid = t.richiesta("trip_id") ?: return@scorri
                val sid = t.richiesta("service_id") ?: return@scorri
                corse[c.campo(tid)] =
                    Grezza(linea, c.campo(t.col("trip_headsign")), c.campo(sid))
            }
            if (corse.isEmpty()) return@use null

            // 3. i passaggi delle sole corse tenute
            val passaggi = mutableMapOf<String, MutableList<Passaggio>>()
            val fermateUsate = mutableSetOf<Int>()
            var scartate = 0
            GtfsCsv.scorri(archivio, "stop_times.txt") { t, c ->
                val tid = t.richiesta("trip_id") ?: return@scorri
                val trip = c.campo(tid)
                if (trip !in corse) return@scorri
                val sid = t.richiesta("stop_id") ?: return@scorri
                val stop = idFermata(c.campo(sid))
                if (stop == null) {
                    scartate++
                    return@scorri
                }
                val arr = GtfsCsv.minuti(c.campo(t.col("arrival_time"))) ?: return@scorri
                val par = GtfsCsv.minuti(c.campo(t.col("departure_time"))) ?: arr
                val seq = c.campo(t.col("stop_sequence")).toIntOrNull() ?: 0
                fermateUsate += stop
                passaggi.getOrPut(trip) { mutableListOf() }.add(Passaggio(seq, stop, arr, par))
            }
            /*
             * Gli stop_id ferroviari sono nella forma `F_22561`. Se ARST li
             * rinominasse, questa lettura fallirebbe in silenzio e l'orario
             * uscirebbe con qualche fermata in meno invece che con un errore.
             * Meglio rifiutarsi di produrre un orario mutilato: chi rigenera se
             * ne accorge subito, e sul telefono resta l'orario precedente.
             */
            if (scartate > 0) return@use null

            // 4. le stazioni, solo quelle che il ferro tocca
            val stazioni = mutableListOf<ArstOrario.Stazione>()
            GtfsCsv.scorri(archivio, "stops.txt") { t, c ->
                val sid = t.richiesta("stop_id") ?: return@scorri
                val id = idFermata(c.campo(sid)) ?: return@scorri
                if (id !in fermateUsate) return@scorri
                val nome = c.campo(t.col("stop_name")).ifBlank { return@scorri }
                val lat = c.campo(t.col("stop_lat")).toDoubleOrNull() ?: return@scorri
                val lon = c.campo(t.col("stop_lon")).toDoubleOrNull() ?: return@scorri
                stazioni += ArstOrario.Stazione(id, nomePulito(nome), lat, lon)
            }
            if (stazioni.isEmpty()) return@use null

            // 5. i calendari, srotolati in date esplicite
            val serviziUsati = corse.values.mapTo(mutableSetOf()) { it.servizio }
            val giorniPerServizio = calendari(archivio, serviziUsati)
            if (giorniPerServizio.isEmpty()) return@use null

            val indice = mutableMapOf<String, Int>()
            val elenco = corse.mapNotNull { (tripId, g) ->
                if (g.servizio !in giorniPerServizio) return@mapNotNull null
                val f = passaggi[tripId]?.sortedBy { it.sequenza }
                    ?.map { ArstOrario.Fermata(it.stazione, it.arrivo, it.partenza) }
                    ?: return@mapNotNull null
                // Una corsa con una fermata sola non e' un viaggio.
                if (f.size < 2) return@mapNotNull null
                ArstOrario.Corsa(
                    id = codiceCorsa(tripId),
                    linea = g.linea,
                    destinazione = nomePulito(g.meta),
                    calendario = indice.getOrPut(g.servizio) { indice.size },
                    fermate = f,
                )
            }.sortedWith(compareBy({ it.linea }, { it.fermate.first().partenza }))
            if (elenco.isEmpty()) return@use null

            ArstOrario(
                generato = dataDelFeed(archivio),
                stazioni = stazioni.sortedBy { it.nome },
                linee = rotte.mapValues { nomePulito(it.value) },
                corse = elenco,
                calendari = giorniPerServizio
                    .filterKeys { it in indice }
                    .mapKeys { indice.getValue(it.key) },
            )
        }
    }.getOrNull()

    private data class Grezza(val linea: String, val meta: String, val servizio: String)

    private data class Passaggio(
        val sequenza: Int,
        val stazione: Int,
        val arrivo: Int,
        val partenza: Int,
    )

    /**
     * I giorni di servizio, da `calendar.txt` srotolato e corretto con
     * `calendar_dates.txt`.
     *
     * `calendar.txt` da' i giorni della settimana e un intervallo; le eccezioni
     * aggiungono (`1`) o tolgono (`2`) singole date. Nel feed ARST diversi
     * servizi hanno tutti i flag a zero e vivono di sole eccezioni, quindi
     * nessuno dei due file basta da solo.
     */
    private fun calendari(archivio: ZipFile, servizi: Set<String>): Map<String, Set<LocalDate>> {
        val giorni = mutableMapOf<String, MutableSet<LocalDate>>()

        GtfsCsv.scorri(archivio, "calendar.txt") { t, c ->
            val sid = t.richiesta("service_id") ?: return@scorri
            val servizio = c.campo(sid)
            if (servizio !in servizi) return@scorri
            val da = GtfsCsv.data(c.campo(t.col("start_date"))) ?: return@scorri
            val a = GtfsCsv.data(c.campo(t.col("end_date"))) ?: return@scorri
            val flag = GIORNI.map { c.campo(t.col(it)) == "1" }
            if (flag.none { it }) return@scorri

            val set = giorni.getOrPut(servizio) { mutableSetOf() }
            var d = da
            while (!d.isAfter(a)) {
                // DayOfWeek.value: lunedi' 1 ... domenica 7, come l'ordine di GIORNI.
                if (flag[d.dayOfWeek.value - 1]) set += d
                d = d.plusDays(1)
            }
        }

        GtfsCsv.scorri(archivio, "calendar_dates.txt") { t, c ->
            val sid = t.richiesta("service_id") ?: return@scorri
            val servizio = c.campo(sid)
            if (servizio !in servizi) return@scorri
            val d = GtfsCsv.data(c.campo(t.col("date"))) ?: return@scorri
            when (c.campo(t.col("exception_type"))) {
                "1" -> giorni.getOrPut(servizio) { mutableSetOf() }.add(d)
                "2" -> giorni[servizio]?.remove(d)
            }
        }

        return giorni.filterValues { it.isNotEmpty() }
    }

    /** `F_22561` diventa `22561`. Null quando la forma non e' quella attesa. */
    private fun idFermata(stopId: String): Int? = stopId.substringAfterLast('_').toIntOrNull()

    /** `TCA_AT1` diventa `AT1`: il codice della corsa senza la sigla di linea. */
    private fun codiceCorsa(tripId: String): String =
        tripId.substringAfterLast('_').ifBlank { tripId }

    /**
     * ARST scrive tutto in maiuscolo, destinazioni comprese.
     *
     * "SENORBI" gridato in mezzo a un tabellone di nomi normali stona e si legge
     * peggio. Si riporta a maiuscola iniziale per parola, lasciando stare le
     * parole di due lettere — le preposizioni e le sigle — e quelle con dentro
     * una cifra, che maiuscole ci vanno.
     */
    private fun nomePulito(s: String): String = s.trim()
        .split(' ')
        .joinToString(" ") { p ->
            if (p.length <= 2 || p.any { it.isDigit() }) p
            else p.lowercase().replaceFirstChar { it.uppercase() }
        }

    /**
     * La data del feed, dai timestamp interni dello zip.
     *
     * Nell'archivio ARST non c'e' `feed_info.txt`, e la data di download direbbe
     * quando lo abbiamo preso, non quanto e' vecchio: per decidere se rinfrescare
     * sono cose opposte.
     */
    private fun dataDelFeed(archivio: ZipFile): LocalDate = runCatching {
        archivio.entries().asSequence()
            .mapNotNull { it.lastModifiedTime?.toMillis() }
            .maxOrNull()
            ?.let {
                java.time.Instant.ofEpochMilli(it)
                    .atZone(java.time.ZoneId.of("Europe/Rome")).toLocalDate()
            }
    }.getOrNull() ?: LocalDate.now()
}
