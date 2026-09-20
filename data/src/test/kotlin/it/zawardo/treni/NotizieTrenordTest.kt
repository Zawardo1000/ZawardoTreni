package it.zawardo.treni

import it.zawardo.treni.data.remote.trenord.DirettriceDto
import it.zawardo.treni.data.remote.trenord.NotiziaDirettriceDto
import it.zawardo.treni.data.remote.trenord.NotizieDirettrici
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
 * Le notizie delle direttrici Trenord del 19/09/2026 sera, com'erano: il 24564
 * in ritardo per un guasto, il 20909 che il 20 settembre non ferma a
 * Borgonato-Adro, e intorno lavori e gite che non nominano corse.
 */
class NotizieTrenordTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val direttrici = json.decodeFromString<List<DirettriceDto>>(
        javaClass.classLoader!!.getResource("direttrici-trenord-2026-09-19.json")!!.readText(),
    )
    private val notizie = NotizieDirettrici.perCorsa(direttrici)
    private val sabato = LocalDate.of(2026, 9, 19)
    private val domenica = LocalDate.of(2026, 9, 20)

    private fun corsa(numero: String, vararg fermate: Pair<String, String>, giorno: LocalDate = sabato) = TrainStatus(
        number = numero,
        category = "REG",
        label = "REG $numero",
        origin = fermate.first().first,
        destination = fermate.last().first,
        delayMinutes = 0,
        state = TrainState.REGULAR,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = fermate.mapIndexed { i, (nome, ora) ->
            Stop(i, nome, null, null, null, 0, giorno.atTime(LocalTime.parse(ora)), null, 0, null, null, StopStatus.FUTURE)
        },
    )

    private val corsa24564 = corsa("24564", "TREVIGLIO" to "18:10", "PIOLTELLO-LIMITO" to "18:25", "VARESE" to "20:20")

    @Test
    fun `si leggono solo le notizie che nominano una corsa`() {
        notizie.forEach { println("  ${it.numero} ${it.origine} ${it.partenza} ${it.giorni} ${it.pubblicata} | ${it.testo}") }
        assertEquals(setOf("24564", "20909"), notizie.map { it.numero }.toSet())
    }

    @Test
    fun `un ritardo in corso vale per la corsa di quel giorno, col paragrafo che la nomina`() {
        val guasto = notizie.single { it.numero == "24564" }
        assertEquals("TREVIGLIO", guasto.origine)
        assertEquals(LocalTime.of(18, 10), guasto.partenza)
        assertEquals(
            "Il treno 24564 (TREVIGLIO 18:10 - VARESE 20:21) sta viaggiando in ritardo a causa di un guasto " +
                "che ha richiesto un intervento tecnico.",
            guasto.testo,
        )
        assertTrue(guasto.riguarda(corsa24564, sabato))
        assertFalse("un altro giorno", guasto.riguarda(corsa("24564", "TREVIGLIO" to "18:10", giorno = domenica), domenica))
    }

    @Test
    fun `il giorno scritto nel testo vince su quello della notizia`() {
        val fermata = notizie.single { it.numero == "20909" }
        assertEquals(listOf(domenica..domenica), fermata.giorni)
        val iseo = { g: LocalDate -> corsa("20909", "ISEO" to "05:36", "BORGONATO-ADRO" to "05:43:30", "BRESCIA" to "06:13", giorno = g) }
        assertTrue(fermata.riguarda(iseo(domenica), domenica))
        assertFalse("uscita il 19, ma parla del 20", fermata.riguarda(iseo(sabato), sabato))
    }

    @Test
    fun `lo stesso numero a un'altra ora e' un altro treno`() {
        val guasto = notizie.single { it.numero == "24564" }
        assertFalse(guasto.riguarda(corsa("24564", "TREVIGLIO" to "06:10", "VARESE" to "08:20"), sabato))
        assertFalse(guasto.riguarda(corsa("24566", "TREVIGLIO" to "18:10", "VARESE" to "20:20"), sabato))
    }

    @Test
    fun `una corsa letta a pezzi si riconosce dall'origine fra le fermate`() {
        val guasto = notizie.single { it.numero == "24564" }
        // Da Pioltello in poi manca Treviglio: la prima fermata ha un'altra ora.
        val aPezzi = corsa("24564", "PIOLTELLO-LIMITO" to "18:25", "VARESE" to "20:20")
        assertFalse(guasto.riguarda(aPezzi, sabato))
        // Con Treviglio fra le fermate, anche non per prima, si'.
        val conTreviglio = corsa("24564", "CASSANO" to "18:02", "Treviglio" to "18:10", "VARESE" to "20:20")
        assertTrue(guasto.riguarda(conTreviglio, sabato))
    }

    private fun voce(testo: String, uscita: String = "2026-09-19T08:00:00.000Z") =
        DirettriceDto(nome = "D000", news = listOf(NotiziaDirettriceDto(date = uscita, severityCode = 1, description = testo)))

    @Test
    fun `intervalli ed elenchi di giorni`() {
        val lavori = NotizieDirettrici.perCorsa(
            listOf(
                voce("Dal 21 al 26 settembre il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) non ferma a Pioltello."),
                voce("Nei giorni 25, 26 e 27 settembre il treno 10911 (MILANO CENTRALE 23:25 - LECCO 00:40) e' sostituito da bus."),
                voce("Dal 30 settembre al 2 ottobre il treno 2987 (MILANO CENTRALE 23:56 - GALLARATE 00:44) e' limitato a Saronno."),
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 21)..LocalDate.of(2026, 9, 26)), lavori.single { it.numero == "2613" }.giorni)
        assertEquals(
            listOf(25, 26, 27).map { LocalDate.of(2026, 9, it).let { g -> g..g } },
            lavori.single { it.numero == "10911" }.giorni,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 30)..LocalDate.of(2026, 10, 2)), lavori.single { it.numero == "2987" }.giorni)
    }

    /**
     * I modi di scrivere un giorno che il feed usa davvero: l'apostrofo
     * tipografico, «il 21 e il 22», l'intervallo che cambia mese, la data in
     * cifre. Ognuno di questi, letto male, avrebbe messo la notizia sulla corsa
     * di un altro giorno.
     */
    @Test
    fun `gli altri modi di scrivere un giorno`() {
        val treno = " il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) non ferma a Pioltello."
        val casi = mapOf(
            "Dall${Char(0x2019)}8 all${Char(0x2019)}11 ottobre" to (LocalDate.of(2026, 10, 8)..LocalDate.of(2026, 10, 11)),
            "Dal 28 al 3 ottobre" to (LocalDate.of(2026, 9, 28)..LocalDate.of(2026, 10, 3)),
            "Il 21 e il 22 settembre" to (LocalDate.of(2026, 9, 21)..LocalDate.of(2026, 9, 21)),
            "Il 20/09" to (LocalDate.of(2026, 9, 20)..LocalDate.of(2026, 9, 20)),
            "Il 20/09/2026" to (LocalDate.of(2026, 9, 20)..LocalDate.of(2026, 9, 20)),
        )
        casi.forEach { (scritto, atteso) ->
            val letta = NotizieDirettrici.perCorsa(listOf(voce(scritto + treno))).single()
            assertTrue("$scritto: letti ${letta.giorni}", atteso in letta.giorni)
            assertFalse("$scritto: il giorno si e' letto", letta.giornoIncerto)
        }
        // «il 21 e il 22»: tutti e due i giorni, non solo l'ultimo.
        val due = NotizieDirettrici.perCorsa(listOf(voce("Il 21 e il 22 settembre" + treno))).single()
        assertEquals(listOf(21, 22), due.giorni.map { it.start.dayOfMonth })
    }

    /**
     * Un giorno nominato e non letto non diventa «oggi»: la notizia non vale per
     * nessuna corsa. Il caso peggiore e' proprio questo — «il 20 settembre non
     * ferma a Borgonato-Adro» uscita il 19 — e attaccarla al giorno di
     * pubblicazione direbbe una cosa falsa al treno sbagliato.
     */
    @Test
    fun `un giorno scritto in un modo che non sappiamo leggere non vale per oggi`() {
        val incerta = NotizieDirettrici.perCorsa(
            listOf(voce("Domani il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) non ferma a Pioltello.")),
        ).single()
        assertTrue(incerta.giornoIncerto)
        assertTrue(incerta.giorni.isEmpty())
        val treno2613 = corsa("2613", "MILANO CENTRALE" to "07:05", "VERONA PORTA NUOVA" to "08:55")
        assertFalse(incerta.riguarda(treno2613, LocalDate.of(2026, 9, 19)))
        assertFalse(incerta.riguarda(treno2613, LocalDate.of(2026, 9, 20)))

        // Senza nessun giorno scritto resta il ritardo di adesso, che vale oggi.
        val adesso = NotizieDirettrici.perCorsa(
            listOf(voce("Il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) viaggia in ritardo.")),
        ).single()
        assertFalse(adesso.giornoIncerto)
        assertTrue(adesso.riguarda(treno2613, LocalDate.of(2026, 9, 19)))
    }

    /** Un'origine col numero dentro: «MALPENSA T1». */
    @Test
    fun `l'origine puo' avere cifre nel nome`() {
        val n = NotizieDirettrici.perCorsa(
            listOf(voce("Il treno 383 (MALPENSA T1 21:56 - MILANO CADORNA 22:40) oggi e' soppresso.")),
        ).single()
        assertEquals("383", n.numero)
        assertEquals("MALPENSA T1", n.origine)
        assertEquals(LocalTime.of(21, 56), n.partenza)
    }

    @Test
    fun `a dicembre, gennaio e' l'anno dopo`() {
        val notizia = NotizieDirettrici.perCorsa(
            listOf(voce("Il 7 gennaio il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) non circola.", uscita = "2026-12-20T08:00:00.000Z")),
        ).single()
        assertEquals(listOf(LocalDate.of(2027, 1, 7)..LocalDate.of(2027, 1, 7)), notizia.giorni)
    }

    @Test
    fun `piu' treni nello stesso paragrafo, ciascuno col suo`() {
        val due = NotizieDirettrici.perCorsa(
            listOf(voce("I treni 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) e 2615 (MILANO CENTRALE 08:05 - VERONA PORTA NUOVA 09:55) oggi sono soppressi.")),
        )
        assertEquals(listOf("2613" to LocalTime.of(7, 5), "2615" to LocalTime.of(8, 5)), due.map { it.numero to it.partenza })
        assertTrue(due.all { it.giorni.isEmpty() && it.pubblicata?.toLocalDate() == LocalDate.of(2026, 9, 19) })
    }

    @Test
    fun `entita' e caratteri di Windows diventano testo`() {
        val n = NotizieDirettrici.perCorsa(
            listOf(voce("Il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) oggi non ferma a Pioltello per l${Char(0x92)}intervento dei VV.FF. &amp; delle forze dell${Char(0x92)}ordine.")),
        ).single()
        assertEquals(
            "Il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) oggi non ferma a Pioltello per " +
                "l’intervento dei VV.FF. & delle forze dell’ordine.",
            n.testo,
        )
    }

    /**
     * Un avviso della notte su un treno della notte parla della corsa di ieri:
     * il 10911 parte da Milano Centrale alle 23:25 e arriva a Lecco alle 00:40,
     * e alle 00:10 quello in viaggio e' partito ieri sera, non stasera.
     */
    @Test
    fun `un notturno letto dopo la mezzanotte e' la corsa di ieri`() {
        val notte = NotizieDirettrici.perCorsa(
            listOf(
                voce(
                    "Il treno 10911 (MILANO CENTRALE 23:25 - LECCO 00:40) sta viaggiando in ritardo.",
                    uscita = "2026-09-19T22:10:00.000Z",
                ),
            ),
        ).single()
        val corsa10911 = { g: LocalDate -> corsa("10911", "MILANO CENTRALE" to "23:25", "LECCO" to "00:40", giorno = g) }
        // Le 22:10 UTC del 19 sono le 00:10 di domenica a Roma: la corsa in
        // viaggio e' quella partita sabato sera, non quella di domenica.
        assertTrue(notte.riguarda(corsa10911(sabato), sabato))
        assertFalse(notte.riguarda(corsa10911(domenica), domenica))

        // Di giorno, invece, la corsa e' quella del giorno stesso.
        val giorno = NotizieDirettrici.perCorsa(
            listOf(voce("Il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) viaggia in ritardo.")),
        ).single()
        assertTrue(giorno.riguarda(corsa("2613", "MILANO CENTRALE" to "07:05"), sabato))
    }

    /** Il treno consigliato al posto di un altro non eredita la notizia di quello. */
    @Test
    fun `il treno proposto come alternativa non e' il soggetto`() {
        val lette = NotizieDirettrici.perCorsa(
            listOf(
                voce(
                    "Il treno 2613 (MILANO CENTRALE 07:05 - VERONA PORTA NUOVA 08:55) oggi e' soppresso. " +
                        "Utilizzare il treno 2615 (MILANO CENTRALE 08:05 - VERONA PORTA NUOVA 09:55).",
                ),
            ),
        )
        assertEquals(listOf("2613"), lette.map { it.numero })
    }

    @Test
    fun `una voce vuota o strana non rompe niente`() {
        val strane = listOf(
            DirettriceDto(),
            DirettriceDto(news = listOf(NotiziaDirettriceDto())),
            voce("Il treno 99999 (senza orario) ritarda."),
            voce("Treno 2613 (MILANO CENTRALE 25:99 - VERONA 08:55) ora impossibile."),
            voce("Il treno 2613 (MILANO CENTRALE 07:05 - VERONA 08:55).", uscita = "non e' una data"),
        )
        val lette = NotizieDirettrici.perCorsa(strane)
        assertEquals(1, lette.size)
        assertEquals(null, lette.single().pubblicata)
        // Senza data di uscita e senza giorni scritti non vale per nessun giorno.
        assertFalse(lette.single().riguarda(corsa("2613", "MILANO CENTRALE" to "07:05"), sabato))
    }
}
