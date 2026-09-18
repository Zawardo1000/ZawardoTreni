package it.zawardo.treni.data.remote.viaggiatreno

import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.stessaStazione
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Una notizia di ViaggiaTreno che riguarda una corsa precisa. */
data class NotiziaCorsa(
    val numero: String,
    /** Codice RFI dell'origine, dal collegamento; null se il treno e' solo scritto. */
    val origine: String?,
    /** L'ora di partenza scritta accanto al nome, "(10:02)": l'altro modo di riconoscerlo. */
    val partenza: LocalTime?,
    /** Il giorno della corsa; null se la pagina non lo dice da nessuna parte. */
    val giorno: LocalDate?,
    val testo: String,
) {
    /**
     * Se la notizia parla di [corsa], del giorno [data].
     *
     * Serve il numero, il giorno, e una delle due prove: l'origine del
     * collegamento fra le fermate della corsa — su una corsa che oggi parte piu'
     * avanti la notizia puo' portare l'origine di tabella o quella nuova, e
     * tutte e due stanno fra le fermate — oppure l'ora di partenza scritta nel
     * nome, uguale a quella di tabella della prima fermata. Una notizia senza
     * data vale solo per le corse di [oggi]: la pagina parla di adesso.
     */
    fun riguarda(corsa: TrainStatus, data: LocalDate, oggi: LocalDate): Boolean {
        if (numero != corsa.number) return false
        if ((giorno ?: oggi) != data) return false
        val origineFraLeFermate = origine != null && corsa.stops.any { stessaStazione(it.stationCode, origine) }
        val stessaPartenza = partenza != null &&
            corsa.stops.firstOrNull()?.scheduledDeparture?.toLocalTime() == partenza
        return origineFraLeFermate || stessaPartenza
    }
}

/**
 * Le notizie di infomobilita' di ViaggiaTreno, corsa per corsa.
 *
 * E' l'unico posto dove ViaggiaTreno dica **perche'**: la scheda della corsa ha
 * `motivoRitardoPrevalente`, `provvedimenti`, `anormalita` e `segnalazioni`, ma
 * il 18/09/2026 erano vuoti su tutte le 740 corse guardate. Le notizie invece
 * scrivevano "Frecciarossa 9588 [...]: il treno viaggia in ritardo per la
 * segnalazione di un incendio tra Eccellente e Vibo Valentia". Coprono Frecce,
 * Intercity ed EuroCity con piu' di un'ora di ritardo o variati, piu' gli
 * eventi — "Linea Salerno - Paola: [...] ritrovamento di un ordigno bellico" —
 * con l'elenco dei treni coinvolti. Dei regionali danno solo avvisi di linea,
 * senza numeri: quelli qui non si leggono.
 *
 * Non c'e' niente di meglio da leggere. La stessa API ha `news/0/it` in JSON,
 * ferma a una notizia del dicembre 2019; le RSS di RFI sono XML ma parlano di
 * linee, senza un numero di treno. Quindi si legge l'HTML, che e' **scritto a
 * mano da una redazione**, e ogni passo ha un ripiego invece di un'unica
 * strada:
 *
 * - le notizie si riconoscono dal `<li>` con la loro classe, anche fra altre
 *   classi; se non se ne trova nessuno, la pagina intera e' una notizia sola;
 * - il corpo comincia dopo `info-text`, o dopo il titolo, o e' tutto;
 * - un treno si riconosce dal collegamento (`cercaTreno.jsp?treno=9588&
 *   origine=S11781&datapartenza=...`, parametri in qualunque ordine), oppure
 *   dal nome scritto — "Frecciarossa 9588 Reggio Calabria Centrale (10:02)",
 *   con i refusi della redazione: "Frecciarosssa 9425" c'era davvero;
 * - il giorno viene dal collegamento, o dalla data della notizia (`<h4>`).
 *
 * Non ogni treno nominato e' il soggetto della notizia: quella del FR 9584
 * nominava il FR 9552 come alternativa per chi partiva da Roma Termini, e
 * attaccarle a lui avrebbe raccontato al 9552 un guasto non suo. Soggetto e' il
 * treno che **apre** un paragrafo, una voce o un punto elenco; oppure che apre
 * una riga e ha parole sue dopo il nome. Nudo a inizio riga, dentro la notizia
 * di un altro, e' un'alternativa elencata.
 *
 * Quel che non si riconosce si lascia fuori, come negli altri parser. Che la
 * pagina smetta di dire qualcosa senza che nessuno se ne accorga lo impedisce
 * `NotizieLiveTest`.
 */
internal object InfomobilitaParser {

    private val DOT = RegexOption.DOT_MATCHES_ALL

    private val SEZIONE = Regex("""<li\b[^>]*class="[^"]*editModeCollapsibleElement[^"]*"[^>]*>""")
    private val TITOLO = Regex("""<a\b[^>]*class="[^"]*headingNewsAccordion[^"]*"[^>]*>(.*?)</a>""", DOT)
    private val CORPO = Regex("""class="[^"]*info-text[^"]*"[^>]*>""")
    private val DATA_SEZIONE = Regex("""<h4[^>]*>\s*(\d{1,2})[./-](\d{1,2})[./-](\d{4})\s*</h4>""")

    private val COLLEGAMENTO = Regex("""<a\b([^>]*)>(.*?)</a>""", DOT)
    private val HREF = Regex("""href\s*=\s*["']([^"']*)["']""")
    private val PARAMETRO = Regex("""[?&](treno|origine|datapartenza)=([^&#]*)""")
    private val CODICE_RFI = Regex("""[A-Z]\d{4,6}""")

    /** Dove finisce un blocco: paragrafi, voci, riquadri, punti elenco. */
    private val FINE_BLOCCO = Regex("""</?(?:p|li|ul|ol|div|h\d|tr|td|table)\b[^>]*>|•""")
    private val A_CAPO = Regex("""<br\s*/?>""")
    private val A_CAPO_IN_FONDO = Regex("""<br\s*/?>(\s|</[^>]+>)*$""")
    private val TAG = Regex("""<[^>]*>""")
    private val SPAZI = Regex("[ \t" + Char(0xA0) + "]+")
    private val ORA = Regex("""\((\d{1,2})[:.](\d{2})\s*\)""")

    /**
     * Un treno scritto a parole in testa a una riga: sigla o nome, numero,
     * origine e ora fra parentesi, e se c'e' la destinazione con la sua ora.
     * L'ora e' obbligatoria: senza, "R 12" in mezzo a una frase basterebbe.
     */
    private val TRENO_A_PAROLE = Regex(
        """^(?:Frecci\p{L}*|Intercity(?:\s+Notte)?|Euro\s*City|Euro\s*Night|Regionale(?:\s+Veloce)?|""" +
            """FR|FA|FB|ICN|IC|EC|EN|RV|REG|R)\s*(\d{2,5})\b[^()\n]*\((\d{1,2})[:.](\d{2})\s*\)""" +
            """(?:\s*[-–]\s*[^()\n]*\(\d{1,2}[:.]\d{2}\s*\))?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Segnaposto di un treno collegato: numero, origine, data, ora, nome. I
     * caratteri di controllo non compaiono in una pagina web, e cosi' il
     * collegamento sopravvive intatto alla ripulitura del testo.
     */
    private val APRE = Char(1)
    private val SEPARA = Char(2)
    private val CHIUDE = Char(3)
    private val SEGNAPOSTO = Regex("""$APRE(\d+)\|([^|]*)\|([^|]*)\|([^$SEPARA]*)$SEPARA(.*?)$CHIUDE""")

    private val ROMA: ZoneId = ZoneId.of("Europe/Rome")

    /**
     * Le sezioni col titolo generico: il titolo non spiega niente. Le altre
     * sono eventi, e il titolo e' proprio il perche'.
     */
    private const val SEZIONE_GENERICA = "INFOTRENI"

    fun parse(html: String?): List<NotiziaCorsa> {
        if (html.isNullOrBlank()) return emptyList()
        val aperture = SEZIONE.findAll(html).map { it.range.first }.toList()
        val sezioni = if (aperture.isEmpty()) {
            listOf(html)
        } else {
            aperture.mapIndexed { i, da -> html.substring(da, aperture.getOrElse(i + 1) { html.length }) }
        }
        return sezioni.flatMap(::sezione).distinct()
    }

    private fun sezione(html: String): List<NotiziaCorsa> {
        val titolo = TITOLO.find(html)
        val evento = titolo?.groupValues?.get(1)?.let(::pulito)
            ?.takeIf { it.isNotBlank() && !it.startsWith(SEZIONE_GENERICA, ignoreCase = true) }
        val giornoSezione = DATA_SEZIONE.find(html)?.destructured?.let { (g, m, a) ->
            runCatching { LocalDate.of(a.toInt(), m.toInt(), g.toInt()) }.getOrNull()
        }
        val corpo = CORPO.find(html)?.let { html.substring(it.range.last + 1) }
            ?: titolo?.let { html.substring(it.range.last + 1) }
            ?: html

        val conSegnaposto = COLLEGAMENTO.replace(corpo) { segnaposto(it) ?: it.value }
        return FINE_BLOCCO.split(conSegnaposto).flatMap { blocco(it, evento, giornoSezione) }
    }

    /** Il segnaposto di un collegamento a un treno; null se il collegamento e' altro. */
    private fun segnaposto(link: MatchResult): String? {
        val href = HREF.find(link.groupValues[1])?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
        val parametri = PARAMETRO.findAll(href).associate { it.groupValues[1] to it.groupValues[2] }
        val numero = parametri["treno"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val origine = parametri["origine"]?.takeIf { CODICE_RFI.matches(it) }.orEmpty()
        val data = parametri["datapartenza"]?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty()
        val nome = link.groupValues[2]
        val etichetta = pulito(nome)
        val ora = ORA.find(etichetta)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }.orEmpty()
        // L'a capo a volte sta dentro il collegamento: "FR 8863<br></a>- in partenza...".
        val aCapo = if (A_CAPO_IN_FONDO.containsMatchIn(nome)) "<br>" else ""
        return "$APRE$numero|$origine|$data|$ora$SEPARA$etichetta$CHIUDE$aCapo"
    }

    /** Un treno in testa a una riga, e le parole che lo seguono. */
    private class Soggetto(
        val numero: String,
        val origine: String?,
        val partenza: LocalTime?,
        val giorno: LocalDate?,
        val resto: String,
    )

    private fun blocco(html: String, evento: String?, giornoSezione: LocalDate?): List<NotiziaCorsa> {
        val notizie = mutableListOf<NotiziaCorsa>()
        var soggetto: Soggetto? = null
        val suo = mutableListOf<String>()

        fun chiudi() {
            val s = soggetto ?: return
            val testo = rifinito(suo.joinToString("\n"))
            listOfNotNull(evento, testo.takeIf { it.isNotBlank() }).forEach {
                notizie += NotiziaCorsa(s.numero, s.origine, s.partenza, s.giorno, it)
            }
            soggetto = null
            suo.clear()
        }

        pulitoConACapo(html).lines().forEachIndexed { i, riga ->
            val nuovo = soggettoIn(riga, giornoSezione)
            // In testa al blocco un treno e' il soggetto anche nudo: e' una voce
            // di elenco. Dopo, solo se ha parole sue.
            if (nuovo != null && (i == 0 || nuovo.resto.any(Char::isLetter))) {
                chiudi()
                soggetto = nuovo
                suo += nuovo.resto
            } else if (soggetto != null) {
                suo += riga
            }
        }
        chiudi()
        return notizie
    }

    private fun soggettoIn(riga: String, giornoSezione: LocalDate?): Soggetto? {
        SEGNAPOSTO.find(riga)?.takeIf { it.range.first == 0 }?.let { m ->
            val (numero, origine, data, ora) = m.destructured
            return Soggetto(
                numero = numero,
                origine = origine.ifBlank { null },
                partenza = ora(ora),
                giorno = data.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ROMA).toLocalDate() }
                    ?: giornoSezione,
                resto = riga.substring(m.range.last + 1),
            )
        }
        val scritto = TRENO_A_PAROLE.find(riga) ?: return null
        val (numero, ore, minuti) = scritto.destructured
        return Soggetto(
            numero = numero,
            origine = null,
            partenza = ora("$ore:$minuti"),
            giorno = giornoSezione,
            resto = riga.substring(scritto.range.last + 1),
        )
    }

    private fun ora(testo: String): LocalTime? {
        val (h, m) = testo.split(':').takeIf { it.size == 2 } ?: return null
        return runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull()
    }

    /** Il testo di un soggetto: gli altri treni col loro nome, la punteggiatura d'attacco tolta. */
    private fun rifinito(testo: String): String = testo
        .replace(SEGNAPOSTO) { it.groupValues[5] }
        // Il ")" dell'orario a volte resta fuori dal collegamento.
        .trimStart(')', ':', ' ', '\n', ',', ';')
        .trim()
        .replaceFirstChar { it.uppercase() }

    /** Testo su una riga: tag, entita' e spazi in piu' tolti. */
    private fun pulito(html: String): String =
        entita(html.replace(A_CAPO, " ").replace(TAG, " ")).replace(SPAZI, " ").replace(Regex("""\s+"""), " ").trim()

    /** Come [pulito], ma gli a capo restano: separano le istruzioni ai passeggeri. */
    private fun pulitoConACapo(html: String): String =
        entita(html.replace(A_CAPO, "\n").replace(TAG, ""))
            .lines()
            .map { it.replace(SPAZI, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun entita(testo: String): String = testo
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
}
