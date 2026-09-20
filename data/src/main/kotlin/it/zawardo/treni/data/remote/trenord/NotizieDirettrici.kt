package it.zawardo.treni.data.remote.trenord

import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Una direttrice di `mia/direttrici/`, con le sue notizie. */
@Serializable
data class DirettriceDto(
    /** Il codice, `D027`: la corsa lo porta in `train.direttrice`. */
    val nome: String? = null,
    val descrizione: String? = null,
    val news: List<NotiziaDirettriceDto> = emptyList(),
)

@Serializable
data class NotiziaDirettriceDto(
    /** Quando e' uscita, ISO in UTC: `2026-09-19T16:22:41.000Z`. */
    val date: String? = null,
    @SerialName("severity_code") val severityCode: Int? = null,
    /** Testo in chiaro, coi paragrafi separati da righe vuote. */
    val description: String? = null,
)

/**
 * Una notizia di Trenord che nomina una corsa precisa: «Il treno 24564
 * (TREVIGLIO 18:10 - VARESE 20:21) sta viaggiando in ritardo a causa di un
 * guasto che ha richiesto un intervento tecnico».
 */
data class NotiziaTrenord(
    val numero: String,
    /** L'origine scritta fra parentesi, com'e'. */
    val origine: String,
    /** L'ora di partenza dall'origine scritta fra parentesi. */
    val partenza: LocalTime,
    /** I giorni nominati nel testo; vuoto se il testo non ne nomina: vale [pubblicata]. */
    val giorni: List<ClosedRange<LocalDate>>,
    /**
     * Quando e' uscita, a Roma. Conta anche l'ora: una notizia delle 00:10 su un
     * treno che parte alle 23:25 parla della corsa di **ieri**, quella in viaggio
     * in quel momento. Vedi [giornoDellaCorsa].
     */
    val pubblicata: LocalDateTime?,
    val testo: String,
    /**
     * Il testo nomina un giorno che non sappiamo leggere («domani», «sabato
     * 20/09» scritto in un modo nuovo): la notizia non vale per nessun giorno.
     * Attaccarla a quello di pubblicazione la metterebbe sulla corsa sbagliata,
     * ed e' proprio il caso in cui sbagliare si nota: «il 20 settembre non ferma
     * a Borgonato-Adro» letto il 19.
     */
    val giornoIncerto: Boolean = false,
) {
    /**
     * Se la notizia parla di [corsa] del giorno [data]: stesso numero, stessa
     * ora di partenza dall'origine — lo stesso numero puo' essere di due treni —
     * e il giorno giusto. Il 20909 «il 20 settembre non ferma a Borgonato-Adro»
     * usci' il 19: vale per la corsa del 20 e non per quella del 19. Una notizia
     * senza giorni scritti parla del giorno in cui e' uscita: un ritardo in corso.
     */
    fun riguarda(corsa: TrainStatus, data: LocalDate): Boolean {
        if (numero != corsa.number) return false
        if (giornoIncerto) return false
        val delGiorno = if (giorni.isEmpty()) giornoDellaCorsa() == data else giorni.any { data in it }
        if (!delGiorno) return false
        val prima = corsa.stops.firstOrNull()
        return corsa.stops.any { fermata ->
            val ora = fermata.scheduledDeparture?.toLocalTime() ?: return@any false
            ora.hour == partenza.hour && ora.minute == partenza.minute &&
                (fermata === prima || stessoNome(fermata.stationName, origine))
        }
    }

    /**
     * Il giorno della corsa di cui parla una notizia senza date scritte: quello
     * in cui e' uscita, tranne per un notturno letto dopo la mezzanotte. Il 10911
     * parte da Milano Centrale alle 23:25 e arriva a Lecco alle 00:40: un avviso
     * delle 00:10 riguarda la corsa partita ieri, non quella di stasera, che deve
     * ancora partire.
     */
    private fun giornoDellaCorsa(): LocalDate? {
        val uscita = pubblicata ?: return null
        val notturno = partenza.hour >= SERA && uscita.hour < NOTTE_FONDA
        return if (notturno) uscita.toLocalDate().minusDays(1) else uscita.toLocalDate()
    }

    private fun stessoNome(a: String, b: String): Boolean {
        val x = a.lettere()
        val y = b.lettere()
        return x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x))
    }

    private fun String.lettere() = uppercase().filter(Char::isLetterOrDigit)

    private companion object {
        /** Da che ora una partenza si considera serale, per [giornoDellaCorsa]. */
        const val SERA = 20

        /** E fino a che ora della notte la notizia parla ancora di ieri. */
        const val NOTTE_FONDA = 4
    }
}

/**
 * Le notizie delle direttrici Trenord che nominano una corsa: il perche' di un
 * ritardo o di una fermata saltata, che la corsa stessa non dice. Il 20/09/2026
 * `train/20909` dava Borgonato-Adro regolare e nessun avviso; solo la notizia
 * della direttrice D028 diceva che quel giorno li' non fermava.
 *
 * Il testo e' in chiaro, niente HTML: si legge il paragrafo che contiene «treno
 * N (ORIGINE hh:mm - DESTINAZIONE hh:mm)», la forma fissa con cui Trenord scrive
 * una corsa. Il resto delle notizie — lavori, gite, abbonamenti — riguarda linee
 * o nessuno, e resta fuori. Quel che non si riconosce si lascia fuori; che il
 * servizio smetta di rispondere lo dice `NotizieTrenordLiveTest`.
 */
object NotizieDirettrici {

    private val ROMA: ZoneId = ZoneId.of("Europe/Rome")

    /**
     * Un treno come lo scrive Trenord: numero, e fra parentesi origine e ora,
     * destinazione e ora. Il nome dell'origine puo' contenere cifre — «MALPENSA
     * T1 21:56» — quindi a delimitarla e' l'ora che la segue, non l'assenza di
     * numeri.
     */
    private val CORSA = Regex(
        """(\d{3,5})\s*\(\s*([^()]+?)\s+(\d{1,2})[:.](\d{2})\s*[-–]\s*[^()]+?\s+\d{1,2}[:.]\d{2}\s*\)""",
    )
    private val TRENO = Regex("""\btren[oi]\b""", RegexOption.IGNORE_CASE)
    private val PARAGRAFO = Regex("""\n\s*\n""")

    private val MESI = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre",
    )
    private val MESE = MESI.joinToString("|")
    private val GIORNO = """(\d{1,2})(?:°|º)?"""

    /** L'apostrofo lo scrivono in due modi, e quello tipografico c'e' davvero. */
    private const val APOSTROFO = """['’]"""

    private val INTERVALLO = Regex(
        """\bdal(?:l$APOSTROFO)?\s*$GIORNO(?:\s+($MESE))?(?:\s+\d{4})?\s+al(?:l$APOSTROFO)?\s*$GIORNO\s+($MESE)""",
        RegexOption.IGNORE_CASE,
    )

    /** «25, 26 e 27 settembre», «il 21 e il 22 settembre», «il 20 settembre». */
    private val ELENCO = Regex(
        """((?:\d{1,2}(?:°|º)?\s*(?:,\s*|\s+e\s+(?:il\s+)?))*\d{1,2})(?:°|º)?\s+($MESE)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Una data in cifre: «20/09», «20/09/2026», «20-09». */
    private val DATA_IN_CIFRE = Regex("""\b(\d{1,2})[/.-](\d{1,2})(?:[/.-](\d{2,4}))?\b""")

    /**
     * Qualunque modo di nominare un giorno, anche quelli che non sappiamo
     * ridurre a una data: se ce n'e' uno e non si e' letto niente, la notizia
     * non vale per il giorno in cui e' uscita. Vedi [NotiziaTrenord.giornoIncerto].
     */
    private val DATA_SCRITTA = Regex(
        """\b(?:$MESE|domani|dopodomani|luned(?:i|ì)|marted(?:i|ì)|mercoled(?:i|ì)|gioved(?:i|ì)|""" +
            """venerd(?:i|ì)|sabato|domenica)\b|\d{1,2}[/.-]\d{1,2}""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Come si annuncia il treno di ripiego: quello che segue non e' il soggetto
     * della notizia ma il consiglio su cosa prendere al suo posto.
     */
    private val ALTERNATIVA = Regex(
        """\b(?:utilizz\w+|in alternativa|al posto del|sostituito dal|prendere il|si consiglia\w*)\b[^.]*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Quante lettere prima del numero si guardano per capire se e' un'alternativa. */
    private const val PRIMA_DEL_NUMERO = 120

    fun perCorsa(direttrici: List<DirettriceDto>): List<NotiziaTrenord> =
        direttrici.flatMap { it.news }.flatMap(::notizia).distinct()

    private fun notizia(dto: NotiziaDirettriceDto): List<NotiziaTrenord> {
        val pubblicata = dto.date?.let {
            runCatching { Instant.parse(it).atZone(ROMA).toLocalDateTime() }.getOrNull()
        }
        val testo = dto.description?.let(::pulito) ?: return emptyList()
        return testo.split(PARAGRAFO).map { it.trim() }.filter { TRENO.containsMatchIn(it) }.flatMap { paragrafo ->
            val giorni = giorni(paragrafo, pubblicata?.toLocalDate())
            // Un giorno nominato e non letto: vedi [NotiziaTrenord.giornoIncerto].
            val incerto = giorni.isEmpty() && DATA_SCRITTA.containsMatchIn(paragrafo)
            CORSA.findAll(paragrafo).mapNotNull { m ->
                // Il treno proposto come alternativa non e' il soggetto della
                // notizia: prendersi il guasto di un altro treno sarebbe peggio
                // che non dire niente. Stessa regola delle notizie di
                // ViaggiaTreno (vedi `InfomobilitaParser`).
                if (ALTERNATIVA.containsMatchIn(paragrafo.take(m.range.first).takeLast(PRIMA_DEL_NUMERO))) {
                    return@mapNotNull null
                }
                val (numero, origine, ore, minuti) = m.destructured
                val partenza = runCatching { LocalTime.of(ore.toInt(), minuti.toInt()) }.getOrNull()
                    ?: return@mapNotNull null
                NotiziaTrenord(numero, origine.trim(), partenza, giorni, pubblicata, paragrafo, incerto)
            }.toList()
        }
    }

    /**
     * I giorni che un paragrafo nomina: «il 20 settembre», «dal 21 al 26
     * settembre», «25, 26 e 27 settembre», «dal 28 al 3 ottobre», «20/09».
     */
    private fun giorni(testo: String, pubblicata: LocalDate?): List<ClosedRange<LocalDate>> {
        val base = pubblicata ?: return emptyList()
        val intervalli = INTERVALLO.findAll(testo).mapNotNull { m ->
            val (da, meseDa, a, meseA) = m.destructured
            val fine = giorno(a, meseA, base) ?: return@mapNotNull null
            val inizio = giorno(da, meseDa.ifBlank { meseA }, base) ?: return@mapNotNull null
            when {
                !inizio.isAfter(fine) -> inizio..fine
                // «dal 28 al 3 ottobre»: l'inizio, senza mese suo, e' del mese prima.
                meseDa.isBlank() -> inizio.minusMonths(1)..fine
                else -> null
            }
        }.toList()
        // Fuori dagli intervalli, i giorni singoli o in elenco.
        val resto = INTERVALLO.replace(testo, " ")
        val singoli = ELENCO.findAll(resto).flatMap { m ->
            val (numeri, mese) = m.destructured
            Regex("""\d{1,2}""").findAll(numeri).mapNotNull { giorno(it.value, mese, base) }.map { it..it }
        }.toList()
        val inCifre = DATA_IN_CIFRE.findAll(ELENCO.replace(resto, " ")).mapNotNull { m ->
            val (g, mese, anno) = m.destructured
            val numero = mese.toIntOrNull() ?: return@mapNotNull null
            val data = runCatching {
                LocalDate.of(anno.toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: base.year, numero, g.toInt())
            }.getOrNull() ?: return@mapNotNull null
            val giusta = if (anno.isBlank() && data.isBefore(base.minusMonths(2))) data.plusYears(1) else data
            giusta..giusta
        }.toList()
        return intervalli + singoli + inCifre
    }

    /** Un giorno scritto senza anno: quello della notizia, o il successivo se sarebbe gia' passato da mesi. */
    private fun giorno(numero: String, mese: String, base: LocalDate): LocalDate? {
        val m = MESI.indexOf(mese.lowercase()) + 1
        if (m == 0) return null
        val d = runCatching { LocalDate.of(base.year, m, numero.toInt()) }.getOrNull() ?: return null
        return if (d.isBefore(base.minusMonths(2))) d.plusYears(1) else d
    }

    /** Il testo com'e', meno le entita' e i caratteri di controllo di Windows-1252. */
    private fun pulito(testo: String): String = testo
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Char(0x92), '’')
        .replace(Char(0x91), '‘')
        .replace(Char(0x93), '“')
        .replace(Char(0x94), '”')
        .filterNot { it.code in 0x80..0x9F }
        .replace("\r", "")
        .replace(Regex("""[ \t]+"""), " ")
}
