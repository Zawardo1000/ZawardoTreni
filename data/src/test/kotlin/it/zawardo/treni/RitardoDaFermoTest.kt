package it.zawardo.treni

import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.domain.model.Coincidenza
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.coincidenza
import it.zawardo.treni.domain.model.conRitardoDa
import it.zawardo.treni.domain.model.conRitardoDaFermo
import it.zawardo.treni.domain.model.partenzaAncoraUtile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Un treno fermo all'origine oltre la sua ora e' in ritardo, anche se
 * ViaggiaTreno scrive zero.
 *
 * Il caso da cui nasce: il 17/09/2026 alle 12:29, cercando Milano Centrale -
 * Calolziocorte dalle 12:28, il RE 2824 delle 12:20 era «non partito» con
 * `ritardo = 0`. Nell'elenco nessun ritardo, e la soluzione mancava fra quelle
 * ancora prendibili: a zero risultava partita da nove minuti. Le fermate sono
 * quelle vere della corsa, lette da ViaggiaTreno il 18/09/2026.
 */
class RitardoDaFermoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 9, 17)
    private fun ora(hhmm: String) = giorno.atTime(LocalTime.parse(hhmm))
    private fun millis(hhmm: String) = ora(hhmm).atZone(roma).toInstant().toEpochMilli()

    private val milano = Station("S01700", 0L, "Milano Centrale")
    private val monza = Station("S01322", 0L, "Monza")
    private val calolzio = Station("S01524", 0L, "Calolziocorte Olginate")

    private fun fermata(n: Int, nome: String, codice: String, arrivo: String?, partenza: String?) = FermataDto(
        stazione = nome,
        id = codice,
        progressivo = n,
        arrivoTeorico = arrivo?.let(::millis),
        partenzaTeorica = partenza?.let(::millis),
    )

    /** Il RE 2824 come lo dava ViaggiaTreno alle 12:29: fermo a Milano, ritardo zero. */
    private fun re2824(ritardo: Int = 0) = AndamentoTrenoDto(
        numeroTreno = 2824,
        categoria = "REG",
        origine = "MILANO CENTRALE",
        destinazione = "TIRANO",
        ritardo = ritardo,
        nonPartito = true,
        stazioneUltimoRilevamento = "--",
        fermate = listOf(
            fermata(1, "MILANO CENTRALE", milano.rfiCode!!, null, "12:20"),
            fermata(2, "MONZA", monza.rfiCode!!, "12:31", "12:32"),
            fermata(3, "LECCO", "S01520", "12:59", "13:02"),
        ),
    ).toTrainStatus()

    /** L'S8 della coincidenza, da Milano Porta Garibaldi. */
    private fun s8(ritardo: Int = 0) = AndamentoTrenoDto(
        numeroTreno = 24842,
        categoria = "REG",
        origine = "MILANO PORTA GARIBALDI",
        destinazione = "LECCO",
        ritardo = ritardo,
        circolante = true,
        fermate = listOf(
            fermata(1, "MILANO PORTA GARIBALDI", "S01645", null, "12:22").copy(
                actualFermataType = 1,
                partenzaReale = millis("12:22") + ritardo * 60_000L,
            ),
            fermata(2, "MONZA", monza.rfiCode!!, "12:37", "12:38"),
            fermata(3, "CALOLZIOCORTE OLGINATE", calolzio.rfiCode!!, "13:12", "13:13"),
        ),
    ).toTrainStatus()

    private val tratte = listOf(
        Leg("2824", "RE", milano, monza, ora("12:20"), ora("12:31")),
        Leg("24842", "S8", monza, calolzio, ora("12:38"), ora("13:12")),
    )
    private val soluzione = Journey(
        departure = ora("12:20"),
        arrival = ora("13:12"),
        duration = Duration.ofMinutes(52),
        legs = tratte,
    )

    @Test
    fun `fermo nove minuti oltre la sua ora e' a +9`() {
        val stato = re2824().conRitardoDaFermo(ora("12:29").plusSeconds(40))
        assertEquals(TrainState.NOT_DEPARTED, stato.state)
        assertEquals(9, stato.delayMinutes)
        assertEquals(
            "il ritardo va sulle fermate come ogni altro: Monza alle 12:40",
            ora("12:40"),
            stato.stops.first { it.stationCode == monza.rfiCode }.projectedArrival,
        )
    }

    @Test
    fun `prima della sua ora non e' in ritardo`() {
        assertEquals(0, re2824().conRitardoDaFermo(ora("12:19")).delayMinutes)
        assertEquals(0, re2824().conRitardoDaFermo(ora("12:20").plusSeconds(50)).delayMinutes)
    }

    /** E' un minimo: un ritardo annunciato piu' grande resta. */
    @Test
    fun `se ViaggiaTreno annuncia di piu' vale il suo`() {
        assertEquals(43, re2824(ritardo = 43).conRitardoDaFermo(ora("12:29")).delayMinutes)
    }

    @Test
    fun `un treno partito tiene la sua misura`() {
        val partito = s8(ritardo = 2)
        assertEquals(partito, partito.conRitardoDaFermo(ora("12:40")))
    }

    @Test
    fun `senza tempo reale non si calcola niente`() {
        val previsto = re2824().copy(realtime = false)
        assertEquals(previsto, previsto.conRitardoDaFermo(ora("12:29")))
    }

    /** Il cuore del caso: la soluzione delle 12:20 si prende ancora alle 12:29. */
    @Test
    fun `il RE 2824 delle 12 e 20 si prende ancora cercando dalle 12 e 28`() {
        val adesso = ora("12:29").plusSeconds(40)
        assertNull(
            "con lo zero di ViaggiaTreno la soluzione spariva",
            soluzione.partenzaAncoraUtile(re2824(), ora("12:28")),
        )
        assertEquals(ora("12:29"), soluzione.partenzaAncoraUtile(re2824().conRitardoDaFermo(adesso), ora("12:28")))
    }

    /**
     * A Monza alle 12:40 invece che alle 12:31: con l'S8 delle 12:38 in orario
     * la coincidenza si perde di poco e si propone a rischio; con l'S8 in
     * ritardo di cinque minuti regge.
     */
    @Test
    fun `al cambio di Monza decide il ritardo dell'S8`() {
        val primo = re2824().conRitardoDaFermo(ora("12:29").plusSeconds(40))
        assertEquals(Coincidenza.A_RISCHIO, soluzione.coincidenza(primo, null))
        assertEquals(Coincidenza.A_RISCHIO, soluzione.coincidenza(primo, s8(ritardo = 2)))
        assertEquals(Coincidenza.REGGE, soluzione.coincidenza(primo, s8(ritardo = 5)))
    }

    private fun riga(origine: String, orario: String) = TabelloneVoceDto(
        numeroTreno = 2824,
        categoria = "REG",
        codOrigine = origine,
        dataPartenzaTreno = giorno.atStartOfDay(roma).toInstant().toEpochMilli(),
        nonPartito = true,
        compOrarioPartenza = orario,
    ).toBoardEntry()!!

    @Test
    fun `sul tabellone della stazione d'origine il ritardo si calcola`() {
        assertEquals(9, riga("S01700", "12:20").conRitardoDaFermo("S01700", ora("12:29")).delayMinutes)
    }

    /** Altrove l'ora della riga non e' quella di partenza dall'origine. */
    @Test
    fun `sul tabellone di un'altra stazione no`() {
        assertEquals(0, riga("S01700", "12:31").conRitardoDaFermo("S01322", ora("12:33")).delayMinutes)
    }

    @Test
    fun `mezzanotte in mezzo non fa mezza giornata di ritardo`() {
        val tardi = riga("S01700", "23:55")
        assertEquals(10, tardi.conRitardoDaFermo("S01700", giorno.plusDays(1).atTime(0, 5)).delayMinutes)
        assertEquals(0, riga("S01700", "00:10").conRitardoDaFermo("S01700", ora("23:58")).delayMinutes)
    }

    /** Sulle altre stazioni il ritardo lo porta la corsa, quando la si interroga. */
    @Test
    fun `la riga di un'altra stazione prende il ritardo dalla corsa`() {
        val monzaRiga = riga("S01700", "12:31")
        val corsa = re2824().conRitardoDaFermo(ora("12:33"))
        assertEquals(13, monzaRiga.conRitardoDa(corsa).delayMinutes)
        assertEquals("a treno partito resta la misura del tabellone", 0, monzaRiga.conRitardoDa(s8(ritardo = 4)).delayMinutes)
    }
}
