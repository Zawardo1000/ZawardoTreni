package it.zawardo.treni

import it.zawardo.treni.domain.model.Coincidenza
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.TransportKind
import it.zawardo.treni.domain.model.coincidenza
import it.zawardo.treni.domain.model.coincidenzaRegge
import it.zawardo.treni.domain.model.partenzaAncoraUtile
import it.zawardo.treni.domain.model.primoCambio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Un treno gia' passato in tabella si prende ancora, se e' in ritardo.
 *
 * Il caso da cui nasce: il 14/09/2026, cercando Taormina - Catania Centrale
 * alle 10:10, non usciva il REG 5385 delle 10:01, che viaggiava con dieci
 * minuti di ritardo e sarebbe partito da Taormina verso le 10:11. La corsa qui
 * e' quella, come la dava ViaggiaTreno poco dopo S.Teresa di Riva; la
 * coincidenza per Siracusa e' costruita.
 */
class AncoraPrendibileTest {

    private val giorno = LocalDate.of(2026, 9, 14)
    private fun ora(hhmm: String) = giorno.atTime(LocalTime.parse(hhmm))

    private val taormina = Station("S12317", 0L, "Taormina-Giardini")
    private val catania = Station("S12332", 0L, "Catania Centrale")
    private val siracusa = Station("S12530", 0L, "Siracusa")

    private fun fermata(
        n: Int,
        nome: String,
        codice: String,
        arrivo: String?,
        partenza: String?,
        stato: StopStatus = StopStatus.FUTURE,
        ritardo: Long = 10,
        arrivoReale: String? = null,
        partenzaReale: String? = null,
        rilevata: Boolean = true,
    ) = Stop(
        index = n,
        stationName = nome,
        stationCode = codice,
        scheduledArrival = arrivo?.let(::ora),
        actualArrival = arrivoReale?.let(::ora),
        arrivalDelayMinutes = 0,
        scheduledDeparture = partenza?.let(::ora),
        actualDeparture = partenzaReale?.let(::ora),
        departureDelayMinutes = 0,
        scheduledPlatform = null,
        actualPlatform = null,
        status = stato,
        // Come il mapper: il ritardo corrente portato sulle fermate da fare.
        projectedArrival = arrivo?.takeIf { stato == StopStatus.FUTURE && ritardo != 0L }
            ?.let { ora(it).plusMinutes(ritardo) },
        projectedDeparture = partenza?.takeIf { stato == StopStatus.FUTURE && ritardo != 0L }
            ?.let { ora(it).plusMinutes(ritardo) },
        detected = rilevata,
    )

    private fun corsa(
        ritardo: Int = 10,
        stato: TrainState = TrainState.DELAYED,
        taorminaQui: Stop = fermata(4, "TAORMINA-GIARDINI", taormina.rfiCode!!, "10:00", "10:01", ritardo = ritardo.toLong()),
        fiumefreddo: Stop = fermata(5, "FIUMEFREDDO DI SICILIA", "S12322", "10:08", "10:09", ritardo = ritardo.toLong()),
    ) = TrainStatus(
        number = "5385",
        category = "REG",
        label = "REG 5385",
        origin = "MESSINA CENTRALE",
        destination = "CATANIA CENTRALE",
        delayMinutes = ritardo,
        state = stato,
        lastDetectionStation = "S.TERESA DI RIVA",
        lastDetectionTime = ora("09:49"),
        notice = null,
        stops = listOf(
            fermata(1, "MESSINA CENTRALE", "S12301", null, "09:15", StopStatus.DONE, partenzaReale = "09:17"),
            fermata(2, "ALI' TERME", "S12310", "09:32", "09:33", StopStatus.DONE, arrivoReale = "09:43", partenzaReale = "09:44"),
            fermata(3, "S.TERESA DI RIVA", "S12314", "09:40", "09:44", StopStatus.DONE, arrivoReale = "09:49", partenzaReale = "09:54"),
            taorminaQui,
            fiumefreddo,
            fermata(6, "GIARRE RIPOSTO", "S12324", "10:16", "10:17", ritardo = ritardo.toLong()),
            fermata(7, "ACIREALE", "S12328", "10:26", "10:27", ritardo = ritardo.toLong()),
            fermata(8, "CATANIA CENTRALE", catania.rfiCode!!, "10:37", null, ritardo = ritardo.toLong()),
        ),
    )

    private val reg5385 = Leg("5385", "REG", taormina, catania, ora("10:01"), ora("10:37"))

    private fun viaggio(vararg tratte: Leg, ritardoDichiarato: Int? = null) = Journey(
        departure = tratte.first().departure,
        arrival = tratte.last().arrival,
        duration = Duration.between(tratte.first().departure, tratte.last().arrival),
        legs = tratte.toList(),
        delayMinutes = ritardoDichiarato,
    )

    @Test
    fun `il REG 5385 delle 10 e 01 si prende ancora alle 10 e 10`() {
        assertEquals(ora("10:11"), viaggio(reg5385).partenzaAncoraUtile(corsa(), ora("10:10")))
    }

    @Test
    fun `alle 10 e 12 e' gia' andato`() {
        assertNull(viaggio(reg5385).partenzaAncoraUtile(corsa(), ora("10:12")))
    }

    @Test
    fun `in orario non si prende`() {
        assertNull(viaggio(reg5385).partenzaAncoraUtile(corsa(ritardo = 0), ora("10:10")))
    }

    /**
     * ViaggiaTreno segna la fermata effettuata gia' all'arrivo: il treno fermo
     * in banchina e' proprio quello che si rincorre, e va tenuto.
     */
    @Test
    fun `fermo in banchina si prende, ripartito no`() {
        val inBanchina = fermata(
            4, "TAORMINA-GIARDINI", taormina.rfiCode!!, "10:00", "10:01",
            StopStatus.DONE, arrivoReale = "10:10",
        )
        assertEquals(
            ora("10:11"),
            viaggio(reg5385).partenzaAncoraUtile(corsa(taorminaQui = inBanchina), ora("10:10")),
        )

        val ripartito = inBanchina.copy(actualDeparture = ora("10:11"))
        assertNull(viaggio(reg5385).partenzaAncoraUtile(corsa(taorminaQui = ripartito), ora("10:10")))
    }

    @Test
    fun `visto piu' avanti vuol dire partito, anche senza l'ora di partenza`() {
        val inBanchina = fermata(
            4, "TAORMINA-GIARDINI", taormina.rfiCode!!, "10:00", "10:01",
            StopStatus.DONE, arrivoReale = "10:10",
        )
        val oltre = fermata(
            5, "FIUMEFREDDO DI SICILIA", "S12322", "10:08", "10:09",
            StopStatus.DONE, arrivoReale = "10:18",
        )
        assertNull(
            viaggio(reg5385).partenzaAncoraUtile(corsa(taorminaQui = inBanchina, fiumefreddo = oltre), ora("10:10")),
        )

        val nonRilevata = inBanchina.copy(actualArrival = null, detected = false)
        assertNull(viaggio(reg5385).partenzaAncoraUtile(corsa(taorminaQui = nonRilevata), ora("10:10")))
    }

    @Test
    fun `soppresso non si prende`() {
        assertNull(viaggio(reg5385).partenzaAncoraUtile(corsa(stato = TrainState.CANCELLED), ora("10:10")))
    }

    private val perSiracusa = { partenza: String -> Leg("3871", "REG", catania, siracusa, ora(partenza), ora("12:05")) }

    /** Il treno per Siracusa, visto a Catania: in ritardo, gia' partito o soppresso. */
    private fun siracusano(
        partenza: String,
        ritardo: Int = 0,
        partito: Boolean = false,
        stato: TrainState = if (ritardo > 0) TrainState.DELAYED else TrainState.REGULAR,
    ) = TrainStatus(
        number = "3871",
        category = "REG",
        label = "REG 3871",
        origin = "CATANIA CENTRALE",
        destination = "SIRACUSA",
        delayMinutes = ritardo,
        state = stato,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = null,
        stops = listOf(
            fermata(
                1, "CATANIA CENTRALE", catania.rfiCode!!, null, partenza,
                if (partito) StopStatus.DONE else StopStatus.FUTURE,
                ritardo = ritardo.toLong(),
                partenzaReale = if (partito) partenza else null,
            ),
            fermata(2, "SIRACUSA", siracusa.rfiCode!!, "12:05", null, ritardo = ritardo.toLong()),
        ),
    )

    /**
     * Il primo treno si prende ancora, e la coincidenza e' un'altra domanda:
     * arrivando a Catania alle 10:47 invece che alle 10:37, un cambio per le
     * 10:55 regge.
     */
    @Test
    fun `la coincidenza col margine regge`() {
        val j = viaggio(reg5385, perSiracusa("10:55"))
        assertEquals(ora("10:11"), j.partenzaAncoraUtile(corsa(), ora("10:10")))
        assertEquals(Coincidenza.REGGE, j.coincidenza(corsa(), secondo = null))
    }

    /**
     * Persa di poco si propone lo stesso: il primo treno puo' recuperare, il
     * secondo puo' aspettare o essere in ritardo anche lui. Deciso il
     * 18/09/2026 dopo il RE 2824 a Monza.
     */
    @Test
    fun `persa di due minuti la coincidenza e' a rischio, non persa`() {
        assertEquals(Coincidenza.A_RISCHIO, viaggio(reg5385, perSiracusa("10:45")).coincidenza(corsa(), null))
    }

    /** A +25 si arriva a Catania alle 11:02, diciassette minuti dopo il treno delle 10:45. */
    @Test
    fun `persa di un quarto d'ora la coincidenza e' persa`() {
        assertEquals(Coincidenza.PERSA, viaggio(reg5385, perSiracusa("10:45")).coincidenza(corsa(ritardo = 25), null))
    }

    /** Il caso di Monza: la coincidenza reggeva, perche' era in ritardo anche il secondo. */
    @Test
    fun `se il secondo e' in ritardo anche lui la coincidenza regge`() {
        val j = viaggio(reg5385, perSiracusa("10:45"))
        assertEquals(Coincidenza.REGGE, j.coincidenza(corsa(), siracusano("10:45", ritardo = 8)))
        assertEquals(
            "un minuto di ritardo del secondo non basta a recuperare i tre di margine",
            Coincidenza.A_RISCHIO,
            j.coincidenza(corsa(), siracusano("10:45", ritardo = 1)),
        )
    }

    @Test
    fun `il secondo gia' partito o soppresso fa perdere la coincidenza`() {
        val j = viaggio(reg5385, perSiracusa("10:45"))
        assertEquals(Coincidenza.PERSA, j.coincidenza(corsa(), siracusano("10:45", partito = true)))
        assertEquals(Coincidenza.PERSA, j.coincidenza(corsa(), siracusano("10:45", stato = TrainState.CANCELLED)))
    }

    /**
     * Nell'elenco si guardano anche i viaggi di stamattina: il primo treno e'
     * arrivato in ritardo, e il secondo, gia' partito, conta per quando e'
     * partito. Dopo l'arrivo del primo l'ha aspettato, prima no.
     */
    @Test
    fun `il secondo partito dopo l'arrivo del primo l'ha aspettato`() {
        val arrivato = corsa(ritardo = 12).let { c ->
            c.copy(stops = c.stops.map { f -> if (f.stationCode == catania.rfiCode) f.copy(actualArrival = ora("10:49")) else f })
        }
        val j = viaggio(reg5385, perSiracusa("10:45"))
        assertEquals(Coincidenza.REGGE, j.coincidenza(arrivato, siracusano("10:51", partito = true)))
        assertEquals(Coincidenza.PERSA, j.coincidenza(arrivato, siracusano("10:47", partito = true)))
    }

    /** Un diretto non ha coincidenze da perdere. */
    @Test
    fun `un diretto regge sempre`() {
        assertEquals(Coincidenza.REGGE, viaggio(reg5385).coincidenza(corsa(ritardo = 40), null))
    }

    /**
     * Il cambio si giudica dal primo treno, non dalla prima tratta: e' del treno
     * il ritardo che si conosce. «Urbano › RE 10911» da Milano Porta Garibaldi
     * cominciava col tratto urbano.
     */
    @Test
    fun `un tratto urbano in testa non sposta il cambio`() {
        val urbano = Leg(null, "UB", Station("S12316", 0L, "Letojanni"), taormina, ora("09:50"), ora("09:58"))
        val j = viaggio(urbano, reg5385, perSiracusa("10:45"))
        assertEquals(reg5385 to perSiracusa("10:45"), j.primoCambio())
        assertEquals(Coincidenza.PERSA, j.coincidenza(corsa(ritardo = 25), null))
    }

    /**
     * Dopo il treno, un tratto a piedi o urbano non ha un'ora di partenza da
     * mancare: il cambio vero e' piu' in la', e quanto serva per arrivarci il
     * margine non lo sa. Prima si confrontava l'arrivo con l'inizio della
     * camminata, e ogni minuto di ritardo diventava una coincidenza a rischio.
     */
    @Test
    fun `dopo un tratto a piedi o urbano la coincidenza non si giudica`() {
        val piedi = Leg(null, null, catania, catania, ora("10:37"), ora("10:45"), kind = TransportKind.WALK)
        val urbano = Leg(null, "UB", catania, Station("S12333", 0L, "Catania Borgo"), ora("10:40"), ora("10:50"))
        for (j in listOf(viaggio(reg5385, piedi, perSiracusa("10:50")), viaggio(reg5385, urbano))) {
            assertNull(j.primoCambio())
            assertEquals(Coincidenza.REGGE, j.coincidenza(corsa(ritardo = 40), null))
        }
    }

    @Test
    fun `al cambio non si chiede piu' margine di quello che dava l'orario`() {
        val stretto = Leg("3871", "REG", catania, siracusa, ora("10:39"), ora("12:05"))
        assertTrue("in orario, due minuti pianificati bastano", coincidenzaRegge(ora("10:37"), reg5385, stretto))
        assertFalse("un minuto di ritardo li riduce a uno", coincidenzaRegge(ora("10:38"), reg5385, stretto))
    }

    /** Una corsa che ViaggiaTreno non conosce: resta il ritardo dichiarato da Trenord. */
    @Test
    fun `senza stato vale il ritardo dichiarato`() {
        assertEquals(ora("10:13"), viaggio(reg5385, ritardoDichiarato = 12).partenzaAncoraUtile(null, ora("10:10")))
        assertNull(viaggio(reg5385).partenzaAncoraUtile(null, ora("10:10")))
    }
}
