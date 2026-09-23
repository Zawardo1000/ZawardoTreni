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
    /**
     * Il treno e' solo nell'elenco `trainTags` di un evento, senza collegamento
     * ne' ora: basta il numero, ma solo per le categorie di cui le notizie
     * parlano (vedi [riguarda]).
     */
    val soloNumero: Boolean = false,
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
     *
     * Chi e' solo in `trainTags` ([soloNumero]) non ha nessuna delle due prove, e
     * il numero da solo basta per Frecce, Intercity, EuroCity ed EuroNight: sono
     * le corse di cui le notizie parlano, e fra loro un numero in un giorno non si
     * ripete. Non per un regionale, che puo' avere lo stesso numero di un
     * EuroCity: il 178 era tutti e due.
     */
    fun riguarda(corsa: TrainStatus, data: LocalDate, oggi: LocalDate): Boolean {
        if (numero != corsa.number) return false
        if ((giorno ?: oggi) != data) return false
        val origineFraLeFermate = origine != null && corsa.stops.any { stessaStazione(it.stationCode, origine) }
        val stessaPartenza = partenza != null &&
            corsa.stops.firstOrNull()?.scheduledDeparture?.toLocalTime() == partenza
        val bastaIlNumero = soloNumero && corsa.categoriaDelleNotizie()
        return origineFraLeFermate || stessaPartenza || bastaIlNumero
    }

    private fun TrainStatus.categoriaDelleNotizie(): Boolean {
        val sigla = category?.trim()?.takeIf { it.isNotEmpty() }
            ?: label.trim().substringBefore(' ')
        return sigla.uppercase() in CATEGORIE_DELLE_NOTIZIE
    }

    private companion object {
        val CATEGORIE_DELLE_NOTIZIE = setOf("FR", "FA", "FB", "IC", "ICN", "EC", "EN")
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
 * Le stesse notizie arrivano in due forme: `news/infomobility` in JSON, la
 * strada principale ([daJson]), e la pagina `infomobilitaRSS` in HTML, di
 * riserva ([parse]). Il JSON risparmia la dipendenza dalla pagina — le classi
 * delle sezioni, il titolo, la data in `<h4>` — ma non dall'HTML del corpo: la
 * descrizione e' lo stesso testo **scritto a mano da una redazione**, e i treni
 * si leggono li' come nella pagina. Ogni passo ha un ripiego invece di un'unica
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

    /**
     * Dove finisce un blocco: paragrafi, voci, riquadri, punti elenco.
     *
     * **Il punto elenco conta comunque sia scritto**, carattere o entita'. La
     * pagina RSS scrive `•`, il JSON scrive `&bull;`, ed e' lo stesso elenco:
     * il 23/09/2026 la notizia della Verona-Brennero elencava cosi' l'FR 8505 e
     * l'8513, col collegamento che ne porta origine e giorno. Dividendo solo sul
     * carattere, dal JSON quei due treni uscivano **senza origine e senza ora**,
     * perche' il segnaposto non si trovava piu' a inizio riga e restava solo il
     * numero nudo dei `trainTags`. Le entita' si sciolgono piu' avanti
     * ([pulitoConACapo]), cioe' troppo tardi per chi divide.
     */
    private val FINE_BLOCCO =
        Regex("""</?(?:p|li|ul|ol|div|h\d|tr|td|table)\b[^>]*>|•|&bull;|&#8226;""")
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

    /**
     * I lavori programmati, una voce per regione: il JSON li ha, la pagina
     * `infomobilitaRSS/false` no. Portano la data di pubblicazione ma parlano di
     * altri giorni, e un treno che vi compare senza collegamento verrebbe preso
     * per la corsa di oggi. Restano fuori, come prima.
     */
    private const val SEZIONE_LAVORI = "INFOLAVORI"

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

    /**
     * Le notizie di `news/infomobility`, in JSON: titolo e giorno dai campi, il
     * corpo letto come quello dell'RSS. E' la strada principale: la pagina RSS
     * dipende dalle classi del sito (`editModeCollapsibleElement`, `info-text`),
     * il JSON no. Del testo resta HTML scritto dalla redazione, e se ne leggono i
     * collegamenti per corsa, che hanno una forma fissa.
     */
    fun daJson(voci: List<NotiziaInfomobilitaDto>): List<NotiziaCorsa> = voci.flatMap { voce ->
        if (voce.title?.trim()?.startsWith(SEZIONE_LAVORI, ignoreCase = true) == true) return@flatMap emptyList()
        val evento = voce.title?.let(::pulito)
            ?.takeIf { it.isNotBlank() && !it.startsWith(SEZIONE_GENERICA, ignoreCase = true) }
        val giorno = voce.pubDate?.let { Instant.ofEpochMilli(it).atZone(ROMA).toLocalDate() }
        val corpo = voce.description?.let(::sfuggito).orEmpty()
        val dalCorpo = notizie(corpo, evento, giorno)
        // Un evento a volte i treni non li scrive nel testo, e li elenca solo nei
        // `trainTags`: il 19/09/2026 "Linea AV Roma - Firenze" ne aveva otto e un
        // corpo senza un numero. Il perche' e' il titolo.
        val soloElencati = if (evento == null) {
            emptyList()
        } else {
            val nelCorpo = dalCorpo.map { it.numero }.toSet()
            voce.trainTags.map { it.trim() }
                .filter { it.isNotEmpty() && it.all(Char::isDigit) && it !in nelCorpo }
                .distinct()
                .map { NotiziaCorsa(it, null, null, giorno, evento, soloNumero = true) }
        }
        dalCorpo + soloElencati
    }.distinct()

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
        return notizie(corpo, evento, giornoSezione)
    }

    /** Il corpo di una notizia, gia' HTML: i treni che ne sono il soggetto, col loro testo. */
    private fun notizie(corpo: String, evento: String?, giorno: LocalDate?): List<NotiziaCorsa> {
        val conSegnaposto = COLLEGAMENTO.replace(corpo) { segnaposto(it) ?: it.value }
        return FINE_BLOCCO.split(conSegnaposto).flatMap { blocco(it, evento, giorno) }
    }

    /**
     * Toglie un livello di sfuggitura: il JSON scrive `&lt;p&gt;` per `<p>`. Le
     * entita' del testo (`&egrave;`, `&amp;amp;` negli indirizzi) restano, e le
     * sistema la lettura come nell'RSS. `&amp;` per ultimo, o `&amp;lt;` — un
     * `&lt;` scritto nel testo — diventerebbe un tag.
     */
    private fun sfuggito(testo: String): String = testo
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

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

    /**
     * Le entita' col nome che la redazione usa davvero.
     *
     * Le lettere accentate contano: la pagina RSS le scrive gia' come lettere,
     * il JSON no. Nel campione del 19/09/2026 la stessa frase era «il treno oggi
     * e' cancellato» nella pagina e `&igrave;`, `&agrave;`, `&egrave;` nel JSON
     * — 34 occorrenze — e senza scioglierle a schermo si leggeva
     * «il treno oggi &egrave; cancellato».
     */
    private val ENTITA = mapOf(
        "nbsp" to " ", "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">",
        "agrave" to "à", "egrave" to "è", "eacute" to "é", "igrave" to "ì", "ograve" to "ò",
        "ugrave" to "ù", "aacute" to "á", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
        "Agrave" to "À", "Egrave" to "È", "Eacute" to "É", "Igrave" to "Ì", "Ograve" to "Ò",
        "Ugrave" to "Ù", "ccedil" to "ç", "ntilde" to "ñ", "ecirc" to "ê", "ocirc" to "ô",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
        "laquo" to "«", "raquo" to "»", "deg" to "°", "euro" to "€", "times" to "×",
    )

    private val ENTITA_SCRITTA = Regex("""&(#\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);""")

    /**
     * Scioglie le entita': quelle col nome che conosciamo ([ENTITA]) e quelle
     * numeriche. Quel che non si riconosce resta com'e', com'e' scritto: meglio
     * un `&frac12;` a schermo che una frase tagliata.
     */
    private fun entita(testo: String): String = ENTITA_SCRITTA.replace(testo) { m ->
        val nome = m.groupValues[1]
        when {
            nome.startsWith("#x") || nome.startsWith("#X") ->
                nome.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            nome.startsWith("#") -> nome.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> ENTITA[nome] ?: m.value
        }
    }
}
