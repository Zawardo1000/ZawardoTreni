package it.zawardo.treni

import it.zawardo.treni.data.remote.viaggiatreno.InfomobilitaParser
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Le notizie di ViaggiaTreno del 18/09/2026 verso le 18:30, com'erano: una
 * sezione per le Frecce, una per Intercity ed EuroCity, e l'evento della linea
 * Salerno - Paola con la sua lista di treni.
 */
class NotizieViaggiaTrenoTest {

    private val notizie = InfomobilitaParser.parse(
        javaClass.classLoader!!.getResource("infomobilita-2026-09-18.html")!!.readText(),
    )

    private fun di(numero: String) = notizie.filter { it.numero == numero }

    @Test
    fun `il perche' di una Freccia in ritardo`() {
        val frecciarossa = di("9588").single()
        assertEquals(
            "Il treno viaggia in ritardo per la segnalazione di un incendio tra Eccellente e Vibo Valentia.",
            frecciarossa.testo,
        )
        assertEquals("S11781", frecciarossa.origine)
        assertEquals(LocalDate.of(2026, 9, 18), frecciarossa.giorno)
    }

    @Test
    fun `il titolo di una sezione generica non spiega niente e non si ripete`() {
        assertTrue(notizie.none { it.testo.startsWith("INFOTRENI") })
        assertTrue(di("556").single().testo.startsWith("Il treno oggi non ferma a Vibo Valentia - Pizzo."))
    }

    @Test
    fun `l'evento e' il perche' di ogni treno della sua lista`() {
        val evento = "Linea Salerno - Paola: circolazione regolare dalle ore 16:40 dopo il " +
            "ritrovamento di un ordigno bellico"
        // Il 9515 e' in lista senza una parola sua: resta il titolo.
        assertEquals(listOf(evento), di("9515").map { it.testo })
        // Il ")" della 9642 e' rimasto fuori dal collegamento, e non deve sporcare.
        assertEquals(listOf(evento), di("9642").map { it.testo })

        val tiburtina = di("9584").map { it.testo }
        assertEquals(evento, tiburtina.first())
        assertTrue(tiburtina[1].startsWith("Il treno oggi ferma a Roma TIburtina anziché a Roma Termini."))
        assertTrue(
            "gli altri treni nominati restano scritti per nome",
            tiburtina[1].contains("FR 9552 Napoli Centrale (15:40) - Torino Porta Nuova (22:05)"),
        )
    }

    @Test
    fun `l'a capo dentro il collegamento resta un a capo`() {
        val soppressa = di("8863").last().testo
        assertTrue(soppressa, soppressa.contains("con il treno FR 8863\n- in partenza da Paola"))
    }

    @Test
    fun `un treno nominato come alternativa non si prende la notizia di un altro`() {
        assertFalse(di("9552").any { it.testo.contains("Roma TIburtina") })
        assertFalse(di("4155").any { it.testo.contains("Roma TIburtina") })
    }

    @Test
    fun `sulla pagina vera ogni treno ha la sua partenza, letta dal nome`() {
        assertEquals(LocalTime.of(10, 2), di("9588").single().partenza)
        // Il ")" della 9642 fuori dal collegamento non toglie l'ora.
        assertEquals(LocalTime.of(8, 49), di("9642").single().partenza)
    }

    // ------------------------------------------------ la pagina che cambia

    /**
     * La stessa notizia della 9588, riscritta come potrebbe scriverla domani
     * la redazione: classi in piu', parametri in un altro ordine e con `&`
     * nudo, niente `info-text`, e la 9425 senza collegamento, col refuso vero.
     */
    private val cambiata = """
        <ul><li class="nuova editModeCollapsibleElement aperta">
          <a href="#" class="headingNewsAccordion evidenza">INFOTRENI FRECCE</a>
          <div class="altro"><h4>18.09.2026</h4>
            <p><a href="https://www.viaggiatreno.it/x/cercaTreno.jsp?datapartenza=1789682400000&origine=S11781&treno=9588">Frecciarossa 9588 Reggio Calabria Centrale (10:02) - Torino Porta Nuova (21:05)</a>: il treno viaggia in ritardo per un incendio.</p>
            <p>Frecciarosssa 9425 Venezia Santa Lucia (14:26) - Napoli Centrale (19:55): il treno viaggia in ritardo per inconvenienti tecnici.</p>
          </div>
        </li></ul>
    """

    @Test
    fun `classi, parametri e corpo cambiati, e la notizia si legge lo stesso`() {
        val lette = InfomobilitaParser.parse(cambiata).associateBy { it.numero }
        val frecciarossa = lette.getValue("9588")
        assertEquals("Il treno viaggia in ritardo per un incendio.", frecciarossa.testo)
        assertEquals("S11781", frecciarossa.origine)
        assertEquals(LocalDate.of(2026, 9, 18), frecciarossa.giorno)
    }

    @Test
    fun `un treno senza collegamento si riconosce dal nome, refusi compresi`() {
        val scritta = InfomobilitaParser.parse(cambiata).single { it.numero == "9425" }
        assertEquals("Il treno viaggia in ritardo per inconvenienti tecnici.", scritta.testo)
        assertEquals(null, scritta.origine)
        assertEquals(LocalTime.of(14, 26), scritta.partenza)
        assertEquals("il giorno viene dalla data della notizia", LocalDate.of(2026, 9, 18), scritta.giorno)
    }

    @Test
    fun `senza le sezioni che conosciamo, la pagina intera e' una notizia sola`() {
        val nuda = """<div><p><a href="cercaTreno.jsp?treno=553&amp;origine=S09823&amp;datapartenza=1789682400000">IC 553 Roma Termini (12:26) - Reggio Calabria Centrale (20:19)</a>: il treno viaggia in ritardo per un controllo tecnico.</p></div>"""
        val letta = InfomobilitaParser.parse(nuda).single()
        assertEquals("553", letta.numero)
        assertEquals("Il treno viaggia in ritardo per un controllo tecnico.", letta.testo)
    }

    @Test
    fun `due treni nello stesso paragrafo, uno per riga, restano due notizie`() {
        val paragrafo = """<li class="editModeCollapsibleElement"><a class="headingNewsAccordion">INFOTRENI FRECCE</a><div class="info-text">
            <p><a href="cercaTreno.jsp?treno=9588&amp;origine=S11781&amp;datapartenza=1789682400000">FR 9588 Reggio Calabria Centrale (10:02)</a>: ritardo per un incendio.<br>
            <a href="cercaTreno.jsp?treno=9425&amp;origine=S02589&amp;datapartenza=1789682400000">FR 9425 Venezia Santa Lucia (14:26)</a>: ritardo per un guasto alla linea.<br>
            I passeggeri possono utilizzare il treno<br>
            <a href="cercaTreno.jsp?treno=9552&amp;origine=S09218&amp;datapartenza=1789682400000">FR 9552 Napoli Centrale (15:40)</a>.</p>
            </div></li>"""
        val lette = InfomobilitaParser.parse(paragrafo).groupBy { it.numero }
        assertEquals("Ritardo per un incendio.", lette.getValue("9588").single().testo)
        assertTrue(lette.getValue("9425").single().testo.startsWith("Ritardo per un guasto alla linea."))
        assertFalse(
            "nudo a inizio riga, dentro la notizia di un altro, e' un'alternativa",
            lette.containsKey("9552"),
        )
    }

    // ------------------------------------------------ a quale corsa

    private fun corsa(numero: String, origine: String, partenza: LocalTime) = TrainStatus(
        number = numero, category = null, label = numero, origin = null, destination = null,
        delayMinutes = 0, state = TrainState.DELAYED, lastDetectionStation = null,
        lastDetectionTime = null, notice = null,
        stops = listOf(
            Stop(
                index = 1, stationName = "Origine", stationCode = origine,
                scheduledArrival = null, actualArrival = null, arrivalDelayMinutes = 0,
                scheduledDeparture = LocalDate.of(2026, 9, 18).atTime(partenza), actualDeparture = null,
                departureDelayMinutes = 0, scheduledPlatform = null, actualPlatform = null,
                status = StopStatus.DONE,
            ),
        ),
    )

    @Test
    fun `una notizia vale per la sua corsa, e solo per quella`() {
        val oggi = LocalDate.of(2026, 9, 18)
        val frecciarossa = di("9588").single()
        assertTrue(frecciarossa.riguarda(corsa("9588", "S11781", LocalTime.of(10, 2)), oggi, oggi))
        assertFalse("altro giorno", frecciarossa.riguarda(corsa("9588", "S11781", LocalTime.of(10, 2)), oggi.plusDays(1), oggi))
        assertFalse("altra origine e altra ora", frecciarossa.riguarda(corsa("9588", "S01700", LocalTime.of(6, 0)), oggi, oggi))

        val scritta = InfomobilitaParser.parse(cambiata).single { it.numero == "9425" }
        assertTrue("senza origine basta l'ora", scritta.riguarda(corsa("9425", "S02589", LocalTime.of(14, 26)), oggi, oggi))
        assertFalse(scritta.riguarda(corsa("9425", "S02589", LocalTime.of(6, 26)), oggi, oggi))
    }
}
