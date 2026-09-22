package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.conRitardoDalTempoPassato
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Fra un rilevamento e il successivo il ritardo cresce da solo.
 *
 * Il ritardo di ViaggiaTreno e' quello dell'ultimo rilevamento: se il treno si
 * pianta subito dopo, quel numero resta fermo mentre l'orologio no, e la corsa
 * finisce per dire insieme «+2» e «arriva alle 08:37» quando sono le 08:40.
 *
 * Le fermate sono quelle vere del REG 24604 del 22/09/2026, la corsa da cui e'
 * nata la segnalazione: partito da Treviglio alle 07:58, rilevato a Pioltello
 * alle 08:23, Segrate prevista alle 08:26.
 */
class RitardoFraRilevamentiTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 9, 22)
    private fun ora(hhmm: String) = giorno.atTime(LocalTime.parse(hhmm))
    private fun millis(hhmm: String) = ora(hhmm).atZone(roma).toInstant().toEpochMilli()

    private fun fermata(
        n: Int,
        nome: String,
        codice: String,
        arrivo: String?,
        partenza: String?,
        arrivoVero: String? = null,
        partenzaVero: String? = null,
    ) = FermataDto(
        stazione = nome,
        id = codice,
        progressivo = n,
        arrivoTeorico = arrivo?.let(::millis),
        partenzaTeorica = partenza?.let(::millis),
        arrivoReale = arrivoVero?.let(::millis),
        partenzaReale = partenzaVero?.let(::millis),
        actualFermataType = if (arrivoVero != null || partenzaVero != null) 1 else 0,
        tipoFermata = when (n) {
            1 -> "P"
            5 -> "A"
            else -> "F"
        },
    )

    /**
     * Il 24604 come lo dava ViaggiaTreno alle 08:23: visto a Pioltello, +1,
     * Segrate e il resto ancora da fare.
     */
    private fun reg24604(ritardo: Int = 1, rilevato: String? = "08:23") = AndamentoTrenoDto(
        numeroTreno = 24604,
        categoria = "REG",
        origine = "TREVIGLIO",
        destinazione = "NOVARA",
        ritardo = ritardo,
        circolante = true,
        stazioneUltimoRilevamento = rilevato?.let { "PIOLTELLO LIMITO" },
        oraUltimoRilevamento = rilevato?.let(::millis),
        fermate = listOf(
            fermata(1, "TREVIGLIO", "S01708", null, "07:55", partenzaVero = "07:58"),
            fermata(2, "VIGNATE", "S01712", "08:17", "08:18", partenzaVero = "08:21"),
            fermata(3, "PIOLTELLO LIMITO", "S01715", "08:22", "08:23", arrivoVero = "08:23"),
            fermata(4, "SEGRATE", "S01716", "08:26", "08:27"),
            fermata(5, "MILANO DATEO", "S01031", "08:39", null),
        ),
    ).toTrainStatus()

    @Test
    fun `finche' la fermata successiva non e' scaduta il ritardo resta quello della fonte`() {
        // Segrate e' prevista alle 08:26, col ritardo alle 08:27: alle 08:25
        // non c'e' niente da dedurre.
        val corsa = reg24604().conRitardoDalTempoPassato(ora("08:25"))
        assertEquals(1, corsa.delayMinutes)
    }

    @Test
    fun `passata l'ora della fermata successiva il ritardo e' il tempo passato`() {
        // L'esempio dell'utente, sui numeri di questa corsa: Segrate alle 08:26,
        // adesso 08:40, nessun rilevamento dopo Pioltello. Non e' piu' +1.
        val corsa = reg24604().conRitardoDalTempoPassato(ora("08:40"))
        assertEquals(14, corsa.delayMinutes)
    }

    @Test
    fun `e cresce di minuto in minuto`() {
        val corsa = reg24604()
        val prima = corsa.conRitardoDalTempoPassato(ora("08:40")).delayMinutes
        val dopo = corsa.conRitardoDalTempoPassato(ora("08:45")).delayMinutes
        assertEquals(5, dopo - prima)
    }

    @Test
    fun `nessun orario previsto resta indietro rispetto ad adesso`() {
        // E' la ragione della regola: la scheda non puo' dire insieme «+1» e
        // «passa alle 08:27» mentre sono le 08:40.
        val adesso = ora("08:40")
        val corsa = reg24604().conRitardoDalTempoPassato(adesso)
        val future = corsa.stops.filter { it.status == StopStatus.FUTURE }
        assertTrue("nessuna fermata futura: il caso non e' quello previsto", future.isNotEmpty())
        future.forEach { f ->
            f.projectedArrival?.let {
                assertTrue("${f.stationName}: arrivo previsto $it, gia' passato", !it.isBefore(adesso))
            }
        }
    }

    @Test
    fun `una corsa che nessuno ha ancora rilevato resta com'e'`() {
        // Senza rilevamenti non si deduce niente: tutte le fermate sono future
        // per sempre, e da qui uscirebbe un ritardo inventato che cresce
        // all'infinito. Il treno non ancora partito ha la sua regola, che
        // guarda l'origine (`conRitardoDaFermo`).
        val corsa = reg24604(rilevato = null).conRitardoDalTempoPassato(ora("09:30"))
        assertEquals(1, corsa.delayMinutes)
    }

    @Test
    fun `una corsa arrivata non accumula piu' niente`() {
        val arrivata = reg24604().copy(state = it.zawardo.treni.domain.model.TrainState.ARRIVED)
        assertEquals(1, arrivata.conRitardoDalTempoPassato(ora("10:00")).delayMinutes)
    }

    @Test
    fun `una corsa soppressa non accumula piu' niente`() {
        val soppressa = reg24604().copy(state = it.zawardo.treni.domain.model.TrainState.CANCELLED)
        assertEquals(1, soppressa.conRitardoDalTempoPassato(ora("10:00")).delayMinutes)
    }

    @Test
    fun `senza tempo reale non si tocca niente`() {
        // Un orario non e' una corsa: ARST, EAV fuori dal monitor, i giorni
        // futuri. La' il ritardo non esiste, e dedurlo sarebbe inventarlo.
        val orario = reg24604().copy(realtime = false)
        assertEquals(1, orario.conRitardoDalTempoPassato(ora("10:00")).delayMinutes)
    }

    @Test
    fun `il ritardo dichiarato piu' grande vince`() {
        // E' un minimo, non una sostituzione: se la fonte ne dichiara di piu',
        // quello e' misurato e questo e' dedotto.
        val corsa = reg24604(ritardo = 40).conRitardoDalTempoPassato(ora("08:40"))
        assertEquals(40, corsa.delayMinutes)
    }
}
