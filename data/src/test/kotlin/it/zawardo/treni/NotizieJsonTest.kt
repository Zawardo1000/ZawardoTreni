package it.zawardo.treni

import it.zawardo.treni.data.remote.viaggiatreno.InfomobilitaParser
import it.zawardo.treni.data.remote.viaggiatreno.NotiziaInfomobilitaDto
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Le notizie di ViaggiaTreno del 19/09/2026 verso le 23, prese nello stesso
 * minuto nelle due forme: `news/infomobility` in JSON, che l'app legge per prima,
 * e la pagina `infomobilitaRSS/false`, che resta di riserva. Devono dire le
 * stesse cose alle stesse corse: il JSON ha preso il posto della pagina, e non
 * deve perdere niente per strada.
 */
class NotizieJsonTest {

    private fun risorsa(nome: String) = javaClass.classLoader!!.getResource(nome)!!.readText()

    private val json = Json { ignoreUnknownKeys = true }
    private val voci = json.decodeFromString<List<NotiziaInfomobilitaDto>>(risorsa("infomobilita-2026-09-19.json"))
    private val dalJson = InfomobilitaParser.daJson(voci)
    private val dallaPagina = InfomobilitaParser.parse(risorsa("infomobilita-2026-09-19.html"))

    @Test
    fun `il JSON dice le stesse cose della pagina, alle stesse corse`() {
        dalJson.forEach { println("  json   ${it.numero} ${it.origine} ${it.partenza} ${it.giorno} ${it.soloNumero} | ${it.testo.lines().first()}") }
        dallaPagina.forEach { println("  pagina ${it.numero} ${it.origine} ${it.partenza} ${it.giorno} | ${it.testo.lines().first()}") }
        assertTrue("la pagina di quella sera nominava dei treni", dallaPagina.isNotEmpty())
        assertEquals(dallaPagina.toSet(), dalJson.filterNot { it.soloNumero }.toSet())
    }

    @Test
    fun `il perche' di una Freccia, con origine e giorno dal collegamento`() {
        val frecciarossa = dalJson.filter { it.numero == "9588" }
        assertTrue(frecciarossa.isNotEmpty())
        frecciarossa.forEach {
            assertEquals("S11781", it.origine)
            assertEquals(LocalDate.of(2026, 9, 19), it.giorno)
        }
    }

    /**
     * L'evento della sera non scriveva un treno nel testo: gli otto coinvolti
     * erano solo in `trainTags`. La pagina HTML non li ha, e non li diceva.
     */
    @Test
    fun `l'evento vale per i treni che elenca solo nei trainTags`() {
        val evento = voci.first { it.title!!.startsWith("Linea AV Roma - Firenze") }
        val titolo = dalJson.filter { it.testo == evento.title }
        assertEquals(evento.trainTags.toSet(), titolo.map { it.numero }.toSet())
        assertTrue(titolo.all { it.soloNumero && it.giorno == LocalDate.of(2026, 9, 19) })
        assertTrue("la pagina non li aveva", dallaPagina.none { it.testo == evento.title })
    }

    @Test
    fun `solo i numeri di una sezione generica non dicono niente`() {
        val frecce = voci.first { it.title == "INFOTRENI FRECCE" }
        assertTrue(frecce.trainTags.isNotEmpty())
        assertTrue(dalJson.none { it.soloNumero && it.testo.startsWith("INFOTRENI") })
        // Il 9588 ha la sua notizia col collegamento, e non ne riceve una seconda.
        assertEquals(1, dalJson.count { it.numero == "9588" })
    }

    private fun corsa(numero: String, categoria: String?, etichetta: String) = TrainStatus(
        number = numero,
        category = categoria,
        label = etichetta,
        origin = "Roma Termini",
        destination = "Milano Centrale",
        delayMinutes = 30,
        state = TrainState.DELAYED,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = listOf(
            Stop(0, "Roma Termini", "S08409", null, null, 0, LocalDateTime.of(2026, 9, 19, 19, 50), null, 0, null, null, StopStatus.DONE),
        ),
    )

    @Test
    fun `il solo numero basta per una Freccia, non per un regionale con lo stesso numero`() {
        val notizia = dalJson.first { it.soloNumero && it.numero == "9653" }
        val giorno = LocalDate.of(2026, 9, 19)
        assertTrue(notizia.riguarda(corsa("9653", "FR", "FR 9653"), giorno, giorno))
        // La sigla vuota di ViaggiaTreno: resta l'etichetta.
        assertTrue(notizia.riguarda(corsa("9653", "", " FR 9653"), giorno, giorno))
        assertFalse(notizia.riguarda(corsa("9653", "REG", "REG 9653"), giorno, giorno))
        assertFalse(notizia.riguarda(corsa("9653", "FR", "FR 9653"), giorno.plusDays(1), giorno.plusDays(1)))
        assertFalse(notizia.riguarda(corsa("9654", "FR", "FR 9654"), giorno, giorno))
    }

    @Test
    fun `i lavori programmati restano fuori, come nella pagina`() {
        assertTrue(voci.any { it.title!!.startsWith("INFOLAVORI") })
        assertTrue(dalJson.none { "Termini" in it.testo && "lavori di manutenzione" in it.testo })
    }

    @Test
    fun `nel testo non resta HTML, ne' sfuggito ne' in chiaro`() {
        dalJson.forEach {
            assertTrue("HTML nel ${it.numero}: ${it.testo}", listOf("<", ">", "&lt;", "&gt;", "&amp;", "&nbsp;", "&quot;").none { h -> h in it.testo })
            assertTrue(it.testo.none { c -> c.code in 1..3 })
        }
    }

    @Test
    fun `una voce vuota o senza titolo non rompe niente`() {
        val strane = listOf(
            NotiziaInfomobilitaDto(),
            NotiziaInfomobilitaDto(title = "INFOTRENI FRECCE", pubDate = 0, description = ""),
            NotiziaInfomobilitaDto(
                title = null,
                pubDate = null,
                description = "&lt;p&gt;Frecciarossa 9999 Milano Centrale (10:00): il treno viaggia in ritardo.&lt;/p&gt;",
            ),
        )
        val lette = InfomobilitaParser.daJson(strane)
        assertEquals(1, lette.size)
        assertEquals("9999", lette.single().numero)
        assertEquals(LocalTime.of(10, 0), lette.single().partenza)
        assertEquals(null, lette.single().giorno)
        assertEquals("Il treno viaggia in ritardo.", lette.single().testo)
    }

    /**
     * Le lettere accentate: la pagina RSS le scrive come lettere, il JSON come
     * `&egrave;`. Nel campione del 19/09/2026 ce n'erano 34 fra `&igrave;`,
     * `&agrave;` e `&egrave;`, e senza scioglierle a schermo si sarebbe letto
     * «il treno oggi &egrave; cancellato».
     */
    @Test
    fun `le lettere accentate arrivano come lettere`() {
        val voce = NotiziaInfomobilitaDto(
            title = "INFOTRENI FRECCE",
            pubDate = 1789768800000,
            description = "&lt;p&gt;Frecciarossa 9999 Milano Centrale (10:00): il treno oggi &egrave; cancellato " +
                "tra Salerno e Battipaglia per un&rsquo;anomalia all&#39;impianto, luned&igrave; la circolazione " +
                "sar&agrave; regolare &ndash; info sul sito.&lt;/p&gt;",
        )
        assertEquals(
            "Il treno oggi è cancellato tra Salerno e Battipaglia per un’anomalia all'impianto, " +
                "lunedì la circolazione sarà regolare – info sul sito.",
            InfomobilitaParser.daJson(listOf(voce)).single().testo,
        )
    }

    /** Quel che non si sa sciogliere resta scritto com'e': meglio di una frase tagliata. */
    @Test
    fun `un'entita' sconosciuta non sparisce`() {
        val voce = NotiziaInfomobilitaDto(
            title = "INFOTRENI FRECCE",
            pubDate = 1789768800000,
            description = "&lt;p&gt;Frecciarossa 9999 Milano Centrale (10:00): ritardo di &frac12; ora, &#8364; 10 di rimborso.&lt;/p&gt;",
        )
        assertEquals("Ritardo di &frac12; ora, € 10 di rimborso.", InfomobilitaParser.daJson(listOf(voce)).single().testo)
    }

    @Test
    fun `un minore scritto nel testo resta un minore, non diventa un tag`() {
        val voce = NotiziaInfomobilitaDto(
            title = "INFOTRENI FRECCE",
            pubDate = 1789768800000,
            description = "&lt;p&gt;Frecciarossa 9999 Milano Centrale (10:00): ritardo &amp;lt; 30 minuti.&lt;/p&gt;",
        )
        assertEquals("Ritardo < 30 minuti.", InfomobilitaParser.daJson(listOf(voce)).single().testo)
    }

    /**
     * Il punto elenco scritto come entita' non deve far perdere il treno.
     *
     * La notizia della Verona-Brennero del 23/09/2026 elencava l'FR 8505 e
     * l'8513 con `&bull;` davanti al collegamento, mentre la pagina RSS scrive
     * `•`. Chi divideva in blocchi conosceva solo il carattere, quindi dal JSON
     * quei due uscivano **senza origine e senza ora** — restava il numero nudo
     * dei `trainTags`, che vale meno: lo stesso numero puo' essere di due treni
     * diversi nello stesso giorno.
     */
    @Test
    fun `un punto elenco scritto come entita' non perde origine e ora`() {
        val voci = json.decodeFromString<List<NotiziaInfomobilitaDto>>(
            risorsa("infomobilita-punto-elenco-2026-09-23.json"),
        )
        val lette = InfomobilitaParser.daJson(voci)
        lette.forEach { println("  ${it.numero} ${it.origine} ${it.partenza} ${it.soloNumero}") }

        val ottomilacinquecentocinque = lette.firstOrNull { it.numero == "8505" }
        assertTrue("l'8505 non e' stato letto affatto", ottomilacinquecentocinque != null)
        assertEquals("S02026", ottomilacinquecentocinque!!.origine)
        assertEquals(LocalTime.of(5, 12), ottomilacinquecentocinque.partenza)
        assertFalse(
            "letto solo dai trainTags: il collegamento nel testo non e' stato visto",
            ottomilacinquecentocinque.soloNumero,
        )
        assertTrue(
            "anche l'8513 dello stesso elenco deve avere la sua origine",
            lette.any { it.numero == "8513" && it.origine == "S02026" && !it.soloNumero },
        )
    }
}
