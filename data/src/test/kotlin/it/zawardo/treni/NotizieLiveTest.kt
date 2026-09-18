package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.viaggiatreno.InfomobilitaParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le notizie di ViaggiaTreno contro la pagina vera, per accorgersi che il
 * parser ha smesso di capirla.
 *
 * La pagina e' HTML scritto a mano, e il parser, quando non riconosce qualcosa,
 * tace: e' la scelta giusta per l'app, ma vuol dire che nessuno se ne
 * accorgerebbe. E' gia' successo con EAV, rimasto muto per giorni finche' non
 * l'ha detto `EavOrarioBoardTest`. Questo test fa la stessa guardia: se la
 * pagina nomina dei treni e il parser non ne tira fuori nessuna notizia, e'
 * cambiata la pagina.
 *
 * Una giornata tranquilla non fa fallire niente: senza treni nominati non c'e'
 * niente da leggere, e lo si dice e basta.
 */
class NotizieLiveTest {

    private val trenoCollegato = Regex("""[?&](?:amp;)?treno=\d+""")
    private val trenoScritto = Regex(
        """(?:Frecci\p{L}*|Intercity|EuroCity|\b(?:FR|FA|FB|IC|EC|R|RV))\s*\d{2,5}\b[^()<\n]*\(\d{1,2}[:.]\d{2}\)""",
    )

    @Test
    fun `se la pagina nomina dei treni, il parser ne legge le notizie`() = runBlocking {
        val pagina = NetworkModule.viaggiaTrenoApi.infomobilita().string()
        assertTrue("ViaggiaTreno ha risposto una pagina vuota", pagina.isNotBlank())

        val notizie = InfomobilitaParser.parse(pagina)
        val collegati = trenoCollegato.findAll(pagina).count()
        val scritti = trenoScritto.findAll(pagina).count()
        println("\n=== NOTIZIE: $collegati collegamenti a treni, $scritti treni scritti, ${notizie.size} notizie ===")
        notizie.forEach { println("  ${it.numero} ${it.origine} ${it.partenza} ${it.giorno} | ${it.testo.lines().first()}") }

        if (collegati == 0 && scritti == 0) {
            println("  nessun treno nominato: giornata tranquilla, niente da verificare")
            return@runBlocking
        }
        assertTrue(
            "la pagina nomina $collegati treni con collegamento e $scritti per nome, ma il parser non " +
                "ne ha letto nessuno: ViaggiaTreno ha cambiato il markup delle notizie",
            notizie.isNotEmpty(),
        )
        notizie.forEach {
            assertTrue("numero di treno illeggibile: ${it.numero}", it.numero.all(Char::isDigit))
            assertTrue("notizia senza testo per il ${it.numero}", it.testo.isNotBlank())
            assertTrue(
                "nel testo del ${it.numero} e' rimasto dell'HTML: ${it.testo}",
                listOf("<", "&nbsp;", "&amp;").none { html -> html in it.testo },
            )
            assertTrue(
                "nel testo del ${it.numero} e' rimasto un segnaposto",
                it.testo.none { c -> c.code in 1..3 },
            )
            assertTrue(
                "la notizia del ${it.numero} non si puo' attaccare a nessuna corsa",
                it.origine != null || it.partenza != null,
            )
        }
    }
}
