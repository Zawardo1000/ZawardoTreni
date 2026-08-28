package it.zawardo.treni.data.remote.eav

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile

/**
 * L'orario ufficiale EAV, ricavato dal loro GTFS.
 *
 * Serve perche' il tabellone non basta: quello dice cosa parte adesso, non dove
 * ferma una corsa ne' cosa passera' domani. E' l'unica fonte dell'app che copra
 * date future — ViaggiaTreno oltre oggi risponde `204`.
 *
 * **Perche' imbarcato e non scaricato a ogni avvio.** Il ferroviario del feed
 * EAV e' minuscolo: 623 corse e 7.693 passaggi, contro le 228.884 righe totali
 * che sono quasi tutte autolinee. Compresso sta in poche decine di KB, meno di
 * un'icona. Scaricarlo vorrebbe dire 3,1 MB a ogni controllo, perche' — e
 * questo e' il punto — **il loro server non permette di sapere se e' cambiato
 * senza scaricarlo**: niente `Last-Modified`, niente `ETag`, niente
 * `Content-Length`, e sia `If-Modified-Since` sia `Range` vengono ignorati con
 * un 200 pieno. Verificato il 28/08/2026.
 *
 * Resta quindi imbarcato e si rinfresca da solo quando invecchia troppo, che e'
 * il compito di [EavGtfsUpdater]. Nel frattempo l'orario invecchia ma il tempo
 * reale no: i ritardi e le soppressioni continuano ad arrivare dal tabellone,
 * che e' quello che serve davvero a chi e' in stazione.
 */
internal class EavOrario(
    /** Data di generazione del feed, cosi' come la dichiara il GTFS. */
    val generato: LocalDate,
    val corse: List<Corsa>,
    /** Per ogni calendario, i giorni in cui e' attivo. */
    private val calendari: Map<Int, Set<LocalDate>>,
) {

    /**
     * Una corsa dell'orario.
     *
     * [numero] e' lo stesso numero che il tabellone mostra: e' la chiave che
     * lega le due fonti, verificata sulle corse viste circolare.
     */
    data class Corsa(
        val numero: String,
        /** Sigla di linea EAV, es. `1` per Napoli-Sorrento. */
        val linea: String,
        val destinazione: String,
        val calendario: Int,
        val fermate: List<Fermata>,
    )

    /**
     * Un passaggio, in minuti dalla mezzanotte.
     *
     * Possono superare 1440: il GTFS esprime cosi' le corse che scavalcano la
     * mezzanotte, e troncarle a 24 ore significherebbe far arrivare un treno
     * prima di essere partito.
     */
    data class Fermata(
        /** L'id del tabellone, gia' tradotto: nel GTFS e' questo piu' 6000. */
        val codLoc: Int,
        val arrivo: Int,
        val partenza: Int,
    )

    /** Le corse che circolano in una certa data. */
    fun corseDel(giorno: LocalDate): List<Corsa> =
        corse.filter { calendari[it.calendario]?.contains(giorno) == true }

    /** Una corsa per numero, se quel giorno circola. */
    fun corsa(numero: String, giorno: LocalDate): Corsa? =
        corse.firstOrNull {
            it.numero == numero && calendari[it.calendario]?.contains(giorno) == true
        }

    /**
     * I passaggi da una stazione in un giorno, ordinati per orario.
     *
     * E' quello che serve al tabellone quando il tabellone vero non c'e': per
     * una data futura, che EAV non sa dire, o per le ventiquattro stazioni che
     * stanno nell'orario ma non sui monitor.
     *
     * Con [partenze] falso da' gli arrivi, e la differenza non e' solo quale
     * orario si legge: si scarta il capolinea di partenza invece di quello
     * d'arrivo, perche' una corsa che da li' parte non e' un arrivo.
     */
    fun passaggi(stazione: Int, giorno: LocalDate, partenze: Boolean = true): List<Passaggio> =
        corse.asSequence()
            .filter { calendari[it.calendario]?.contains(giorno) == true }
            .mapNotNull { corsa ->
                val i = corsa.fermate.indexOfFirst { it.codLoc == stazione }
                if (i < 0) return@mapNotNull null
                if (partenze && i == corsa.fermate.lastIndex) return@mapNotNull null
                if (!partenze && i == 0) return@mapNotNull null
                Passaggio(
                    corsa = corsa,
                    fermata = corsa.fermate[i],
                    // Fra gli arrivi la direzione utile e' da dove la corsa viene.
                    origineCodLoc = corsa.fermate.first().codLoc,
                )
            }
            .sortedBy { if (partenze) it.fermata.partenza else it.fermata.arrivo }
            .toList()

    data class Passaggio(val corsa: Corsa, val fermata: Fermata, val origineCodLoc: Int)

    /** Il giorno piu' lontano che l'orario copre: oltre non si sa nulla. */
    val ultimoGiorno: LocalDate? = calendari.values.flatten().maxOrNull()

    /** Vero se l'orario ha qualcosa da dire su quel giorno. */
    fun copre(giorno: LocalDate): Boolean =
        calendari.values.any { it.contains(giorno) }

    // ------------------------------------------------------------- formato

    /**
     * Il formato compatto in cui l'orario viene imbarcato.
     *
     * Non e' GTFS e non e' JSON: e' un testo a righe, che sta in un quinto
     * dello spazio e si legge senza deserializzatori. Le righe sono
     *
     * ```
     * V|1|20260729                      versione e data del feed
     * C|0|20260729,20260730,…           i giorni di un calendario
     * T|10530|1|Sorrento|0|6:330:330,…  corsa: numero, linea, meta, calendario, fermate
     * ```
     *
     * Gli orari sono minuti dalla mezzanotte e le fermate sono gia' `codLoc`,
     * non `stop_id`: la traduzione si fa una volta qui invece che a ogni
     * lettura sul telefono.
     */
    fun serializza(): String = buildString {
        append("V|1|").append(giornoCompatto(generato)).append('\n')
        for ((idx, giorni) in calendari) {
            append("C|").append(idx).append('|')
            append(giorni.sorted().joinToString(",") { giornoCompatto(it) })
            append('\n')
        }
        for (c in corse) {
            append("T|").append(c.numero).append('|').append(c.linea).append('|')
            append(c.destinazione.replace('|', ' ')).append('|').append(c.calendario).append('|')
            append(c.fermate.joinToString(",") { "${it.codLoc}:${it.arrivo}:${it.partenza}" })
            append('\n')
        }
    }

    companion object {
        /** Nome della risorsa imbarcata nel modulo. */
        const val RISORSA = "/eav-orario.gz"

        /** Nome del file locale che, quando esiste, ha la precedenza. */
        const val FILE_LOCALE = "eav-orario.gz"

        private fun giornoCompatto(d: LocalDate): String =
            "%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth)

        private fun giorno(s: String): LocalDate = LocalDate.of(
            s.substring(0, 4).toInt(),
            s.substring(4, 6).toInt(),
            s.substring(6, 8).toInt(),
        )

        /** Rilegge il formato compatto. */
        fun deserializza(testo: String): EavOrario? {
            var generato: LocalDate? = null
            val calendari = mutableMapOf<Int, Set<LocalDate>>()
            val corse = mutableListOf<Corsa>()

            testo.lineSequence().forEach { riga ->
                if (riga.isBlank()) return@forEach
                val p = riga.split('|')
                when (p.firstOrNull()) {
                    "V" -> if (p.size >= 3) generato = runCatching { giorno(p[2]) }.getOrNull()
                    "C" -> if (p.size >= 3) {
                        val idx = p[1].toIntOrNull() ?: return@forEach
                        calendari[idx] = p[2].split(',')
                            .mapNotNullTo(mutableSetOf()) { runCatching { giorno(it) }.getOrNull() }
                    }
                    "T" -> if (p.size >= 6) {
                        val fermate = p[5].split(',').mapNotNull { f ->
                            val q = f.split(':')
                            if (q.size < 3) return@mapNotNull null
                            Fermata(
                                codLoc = q[0].toIntOrNull() ?: return@mapNotNull null,
                                arrivo = q[1].toIntOrNull() ?: return@mapNotNull null,
                                partenza = q[2].toIntOrNull() ?: return@mapNotNull null,
                            )
                        }
                        if (fermate.isNotEmpty()) {
                            corse += Corsa(
                                numero = p[1],
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
            if (corse.isEmpty()) return null
            return EavOrario(g, corse, calendari)
        }

        /** Legge un orario compresso da uno stream. */
        fun leggi(stream: InputStream): EavOrario? = runCatching {
            GZIPInputStream(stream).bufferedReader().use { deserializza(it.readText()) }
        }.getOrNull()

        /** Scrive l'orario compresso su file. */
        fun scrivi(orario: EavOrario, destinazione: File) {
            destinazione.outputStream().use { out ->
                GZIPOutputStream(out).bufferedWriter().use { it.write(orario.serializza()) }
            }
        }

        /**
         * L'orario da usare: quello scaricato se c'e', altrimenti l'imbarcato.
         *
         * [cartella] e' dove [EavGtfsUpdater] deposita gli aggiornamenti. Quando
         * manca, o quando il file e' illeggibile, si ricade sulla risorsa del
         * modulo: un aggiornamento andato storto non deve lasciare l'app senza
         * orario.
         */
        fun carica(cartella: File?): EavOrario? {
            val locale = cartella?.let { File(it, FILE_LOCALE) }
            if (locale != null && locale.isFile) {
                locale.inputStream().use { leggi(it) }?.let { return it }
            }
            val risorsa = EavOrario::class.java.getResourceAsStream(RISORSA) ?: return null
            return risorsa.use { leggi(it) }
        }
    }
}

/**
 * Trasforma il GTFS ufficiale EAV nell'orario compatto.
 *
 * Lo stesso codice serve due volte: genera la risorsa da imbarcare nel modulo e
 * ingerisce il file scaricato quando l'orario invecchia. Averne una sola copia
 * evita che le due strade divergano — che e' esattamente il modo in cui un
 * aggiornamento comincia a produrre dati diversi da quelli compilati.
 */
internal object EavGtfsParser {

    /** `route_type` 2: ferrovia. Le altre 87 rotte del feed sono autolinee. */
    private const val TIPO_FERROVIA = "2"

    /** Lo scarto fra i due registri, verificato su tutte le fermate. */
    private const val SCARTO_STOP_ID = 6000

    /**
     * Legge lo zip GTFS e ne estrae il solo servizio ferroviario.
     *
     * Vuole un [File] e non uno stream perche' l'archivio va letto **fuori
     * ordine**: `stop_times.txt` compare prima di `trips.txt`, e per sapere
     * quali passaggi tenere bisogna gia' sapere quali corse sono ferroviarie.
     * Con uno `ZipInputStream` sequenziale servirebbe tenere in memoria tutte e
     * 228.884 le righe, comprese quelle dei bus che poi si buttano.
     */
    fun parse(zip: File): EavOrario? = runCatching {
        ZipFile(zip).use { archivio ->
            fun leggi(nome: String, riga: (List<String>) -> Unit) {
                val entry = archivio.getEntry(nome) ?: return
                BufferedReader(InputStreamReader(archivio.getInputStream(entry), Charsets.UTF_8))
                    .use { r ->
                        var intestazione = true
                        while (true) {
                            val l = r.readLine() ?: break
                            if (intestazione) { intestazione = false; continue }
                            if (l.isNotBlank()) riga(campi(l))
                        }
                    }
            }

            // 1. le rotte ferroviarie
            val rotte = mutableMapOf<String, String>()
            leggi("routes.txt") { c ->
                if (c.size > 4 && c[4] == TIPO_FERROVIA) rotte[c[0]] = c[2].trim()
            }
            if (rotte.isEmpty()) return@use null

            // 2. le corse su quelle rotte
            val calendarioIdx = mutableMapOf<String, Int>()
            data class Grezza(val numero: String, val linea: String, val meta: String, val cal: Int)
            val corse = mutableMapOf<String, Grezza>()
            leggi("trips.txt") { c ->
                if (c.size < 5) return@leggi
                val linea = rotte[c[0]] ?: return@leggi
                val numero = c[4].trim().ifBlank { return@leggi }
                val cal = calendarioIdx.getOrPut(c[1]) { calendarioIdx.size }
                corse[c[2]] = Grezza(numero, linea, c[3].trim(), cal)
            }
            if (corse.isEmpty()) return@use null

            // 3. i passaggi delle sole corse tenute
            val passaggi = mutableMapOf<String, MutableList<Triple<Int, Int, Int>>>()
            leggi("stop_times.txt") { c ->
                if (c.size < 5) return@leggi
                if (!corse.containsKey(c[0])) return@leggi
                val stop = c[3].toIntOrNull() ?: return@leggi
                val arr = minuti(c[1]) ?: return@leggi
                val par = minuti(c[2]) ?: arr
                val seq = c[4].toIntOrNull() ?: 0
                passaggi.getOrPut(c[0]) { mutableListOf() }
                    .add(Triple(seq, stop - SCARTO_STOP_ID, arr * 10_000 + par))
            }

            // 4. i calendari, che nel feed EAV sono solo date esplicite
            val calendari = mutableMapOf<Int, MutableSet<LocalDate>>()
            leggi("calendar_dates.txt") { c ->
                if (c.size < 3) return@leggi
                // exception_type 1 = il servizio c'e', 2 = e' soppresso
                if (c[2].trim() != "1") return@leggi
                val idx = calendarioIdx[c[0]] ?: return@leggi
                val g = runCatching {
                    LocalDate.of(
                        c[1].substring(0, 4).toInt(),
                        c[1].substring(4, 6).toInt(),
                        c[1].substring(6, 8).toInt(),
                    )
                }.getOrNull() ?: return@leggi
                calendari.getOrPut(idx) { mutableSetOf() }.add(g)
            }

            val elenco = corse.mapNotNull { (tripId, g) ->
                val f = passaggi[tripId]?.sortedBy { it.first }?.map {
                    EavOrario.Fermata(it.second, it.third / 10_000, it.third % 10_000)
                } ?: return@mapNotNull null
                if (f.isEmpty()) null
                else EavOrario.Corsa(g.numero, g.linea, g.meta, g.cal, f)
            }.sortedBy { it.numero }

            if (elenco.isEmpty()) return@use null
            EavOrario(
                generato = dataDelFeed(zip),
                corse = elenco,
                calendari = calendari.mapValues { it.value.toSet() },
            )
        }
    }.getOrNull()

    /**
     * La data del feed, presa dai timestamp interni dello zip.
     *
     * Non c'e' un `feed_info.txt` in questo GTFS, e la data di download direbbe
     * solo quando lo abbiamo preso, non quanto e' vecchio: due cose che, per
     * decidere se rinfrescare, sono opposte.
     */
    private fun dataDelFeed(zip: File): LocalDate = runCatching {
        ZipFile(zip).use { a ->
            a.entries().asSequence()
                .mapNotNull { it.lastModifiedTime?.toMillis() }
                .maxOrNull()
                ?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.of("Europe/Rome")).toLocalDate()
                }
        }
    }.getOrNull() ?: LocalDate.now()

    /** `HH:MM:SS` in minuti dalla mezzanotte. Le ore oltre 24 restano tali. */
    private fun minuti(s: String): Int? {
        val p = s.trim().split(':')
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    /**
     * Divide una riga CSV rispettando le virgolette.
     *
     * Il feed EAV cita ogni campo e dentro i nomi di fermata ci sono virgole:
     * uno `split(",")` spezzerebbe "Torre Annunziata, Oplonti" in due colonne e
     * sfaserebbe tutta la riga.
     */
    private fun campi(riga: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var dentro = false
        for (ch in riga) {
            when {
                ch == '"' -> dentro = !dentro
                ch == ',' && !dentro -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }
}
