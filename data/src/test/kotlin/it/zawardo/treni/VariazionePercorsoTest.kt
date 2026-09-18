package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.conAvvisiDa
import it.zawardo.treni.domain.model.terminus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Il REG 2833 Tirano - Milano Centrale del 18/09/2026, com'era alle 17:44
 * nella schermata che l'ha fatto notare: 29 minuti di ritardo, rilevato ad
 * Airuno fra Lecco e Monza, e oggi limitato a Sesto S.Giovanni.
 *
 * ViaggiaTreno la raccontava cosi': Monza da fare (`actualFermataType` 0),
 * Milano Centrale soppressa (3), Sesto capolinea aggiunto (2). Leggendo il 2
 * come "fatta" l'app disegnava il treno gia' a Sesto, Monza passata senza
 * rilevamento, e il rilevamento di Airuno dopo tutte e due. Il treno a Monza
 * e' arrivato alle 17:56 ed e' ripartito alle 17:59.
 *
 * Il perche' della variazione ViaggiaTreno non lo dice: lo dice Trenord.
 */
class VariazionePercorsoTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 9, 18)

    private fun millis(ora: String?): Long? =
        ora?.let { giorno.atTime(LocalTime.parse(it)).atZone(roma).toInstant().toEpochMilli() }

    private fun fermata(
        n: Int,
        codice: String,
        nome: String,
        tipo: Int,
        arrivo: String?,
        partenza: String?,
        arrivoReale: String? = null,
        partenzaReale: String? = null,
    ) = FermataDto(
        stazione = nome,
        id = codice,
        progressivo = n,
        actualFermataType = tipo,
        arrivoTeorico = millis(arrivo),
        arrivoReale = millis(arrivoReale),
        partenzaTeorica = millis(partenza),
        partenzaReale = millis(partenzaReale),
    )

    private val viaggiaTreno = AndamentoTrenoDto(
        numeroTreno = 2833,
        categoria = "REG",
        origine = "TIRANO",
        destinazione = "SESTO S.GIOVANNI",
        ritardo = 29,
        provvedimento = 2,
        subTitle = "Treno cancellato da MONZA a MILANO CENTRALE. Il treno oggi arriva a SESTO S.GIOVANNI.",
        stazioneUltimoRilevamento = "AIRUNO",
        oraUltimoRilevamento = millis("17:42"),
        fermate = listOf(
            fermata(1, "S01440", "TIRANO", 1, null, "15:08", partenzaReale = "15:39"),
            // Straordinaria anche questa: Trenord non la ha nel suo orario.
            fermata(7, "S01434", "PONTE IN VALTELLINA", 2, "15:29", "15:30", "15:57", "15:57"),
            fermata(9, "S01430", "SONDRIO", 1, "15:39", "15:40", "16:05", "16:14"),
            fermata(28, "S01520", "LECCO", 1, "16:58", "17:01", "17:32", "17:34"),
            fermata(38, "S01322", "MONZA", 0, "17:26", "17:27"),
            // Nell'ordine della risposta, che mette Milano prima di Sesto.
            fermata(41, "S01700", "MILANO CENTRALE", 3, "17:43", null),
            fermata(39, "S01325", "SESTO S.GIOVANNI", 2, "17:33", null),
        ),
    ).toTrainStatus()

    private fun TrainStatus.fermata(nome: String) = stops.first { it.stationName == nome }

    @Test
    fun `la fermata straordinaria non e' una fermata fatta`() {
        val sesto = viaggiaTreno.fermata("Sesto S.Giovanni")
        assertEquals("Sesto e' ancora da fare", StopStatus.FUTURE, sesto.status)
        assertTrue("ed e' il capolinea aggiunto", sesto.straordinaria)

        val monza = viaggiaTreno.fermata("Monza")
        assertEquals("Monza non e' stata passata", StopStatus.FUTURE, monza.status)
        assertTrue("e non puo' essere 'passaggio non rilevato'", monza.detected)
        assertEquals(
            "a Monza si arriva col ritardo di adesso: 17:26 + 29",
            giorno.atTime(17, 55),
            monza.projectedArrival,
        )
    }

    @Test
    fun `il treno sta dopo Lecco, dove l'ha visto Airuno`() {
        val lecco = viaggiaTreno.stops.indexOfFirst { it.stationName == "Lecco" }
        assertEquals("la posizione e' l'ultima fermata fatta", lecco, viaggiaTreno.currentStopIndex)
        assertEquals(StopStatus.CURRENT, viaggiaTreno.stops[lecco].status)
    }

    @Test
    fun `una straordinaria con orari reali e' fatta e misurata`() {
        val ponte = viaggiaTreno.fermata("Ponte in Valtellina")
        assertEquals(StopStatus.DONE, ponte.status)
        assertTrue("gli orari ci sono: sono una misura", ponte.detected)
        assertTrue(ponte.straordinaria)
        assertFalse(viaggiaTreno.fermata("Sondrio").straordinaria)
    }

    @Test
    fun `la corsa finisce a Sesto e Milano resta soppressa`() {
        assertEquals(StopStatus.CANCELLED, viaggiaTreno.fermata("Milano Centrale").status)
        assertEquals(
            "Sesto prima di Milano, come sulla linea",
            listOf("Monza", "Sesto S.Giovanni", "Milano Centrale"),
            viaggiaTreno.stops.takeLast(3).map { it.stationName },
        )
        assertEquals("Sesto S.Giovanni", viaggiaTreno.terminus())
        assertEquals(
            "Milano Centrale non si fa: prima di tutto e' una soppressione",
            TrainState.PARTIALLY_CANCELLED,
            viaggiaTreno.state,
        )
    }

    /**
     * Il REG 4972 Velletri - Roma Tiburtina del 18/09/2026, "Parte da
     * CIAMPINO": com'era nella risposta, con Ciampino che riprende a contare da 1.
     */
    private fun quattromilaNovecentoSettantadue(nonPartito: Boolean = true, arrivato: Boolean = false) =
        AndamentoTrenoDto(
            numeroTreno = 4972,
            categoria = "REG",
            tipoTreno = "SI",
            nonPartito = nonPartito,
            arrivato = arrivato,
            subTitle = "Treno cancellato da VELLETRI a CIAMPINO. Parte da CIAMPINO.",
            fermate = listOf(
                fermata(1, "S08500", "VELLETRI", 3, null, "18:29"),
                fermata(3, "S08501", "S.GENNARO", 3, "18:34", "18:35"),
                fermata(4, "S08502", "LANUVIO", 3, "18:41", "18:45"),
                fermata(5, "S08503", "CECCHINA", 3, "18:49", "18:50"),
                fermata(6, "S08504", "CANCELLIERA", 3, "18:53", "18:54"),
                fermata(7, "S08505", "PAVONA", 3, "18:59", "19:00"),
                fermata(8, "S08506", "S.MARIA DELLE MOLE", 3, "19:05", "19:06"),
                fermata(9, "S08507", "CASABIANCA", 3, "19:08", "19:09"),
                fermata(1, "S08407", "CIAMPINO", 0, null, "19:14").copy(tipoFermata = "P"),
                fermata(2, "S08408", "CAPANNELLE", 0, "19:17", "19:18"),
                fermata(4, "S08217", "ROMA TIBURTINA", 0, "19:33", null).copy(tipoFermata = "A"),
            ),
        ).toTrainStatus()

    @Test
    fun `la corsa che riparte da una stazione nuova resta in ordine`() {
        assertEquals(
            listOf(
                "Velletri", "S.Gennaro", "Lanuvio", "Cecchina", "Cancelliera", "Pavona",
                "S.Maria delle Mole", "Casabianca", "Ciampino", "Capannelle", "Roma Tiburtina",
            ),
            quattromilaNovecentoSettantadue().stops.map { it.stationName },
        )
    }

    @Test
    fun `soppressa all'inizio, ma finche' e' ferma e' un treno non partito`() {
        // E' su "non partito" che si calcola il ritardo di chi e' fermo all'origine.
        assertEquals(TrainState.NOT_DEPARTED, quattromilaNovecentoSettantadue().state)
        assertEquals(
            "in viaggio, la soppressione si legge",
            TrainState.PARTIALLY_CANCELLED,
            quattromilaNovecentoSettantadue(nonPartito = false).state,
        )
        assertEquals(
            "a destinazione e' arrivato, o gli aggiornamenti non finiscono mai",
            TrainState.ARRIVED,
            quattromilaNovecentoSettantadue(nonPartito = false, arrivato = true).state,
        )
    }

    @Test
    fun `una fermata straordinaria in un treno regolare e' un percorso variato`() {
        // L'IC 612 del 18/09/2026: "PG", provvedimento 0, e due straordinarie.
        val ic = AndamentoTrenoDto(
            numeroTreno = 612,
            categoria = "IC",
            tipoTreno = "PG",
            ritardo = 3,
            subTitle = "Percorso deviato con fermate straordinarie",
            fermate = listOf(
                fermata(1, "S11145", "LECCE", 1, null, "08:42", partenzaReale = "08:44"),
                fermata(6, "S11119", "BRINDISI", 1, "09:10", "09:21", "09:05", "09:27"),
                fermata(9, "S11117", "MESAGNE", 2, "09:34", "09:39", "09:40", "09:40"),
                fermata(12, "S11112", "FRANCAVILLA FONTANA", 1, "09:53", "09:55", "09:58", "10:03"),
                fermata(17, "S11004", "TARANTO", 0, "10:24", null),
            ),
        ).toTrainStatus()
        assertEquals(TrainState.DIVERTED, ic.state)
    }

    /** La stessa corsa letta da Trenord, ridotta ai campi che contano. */
    private fun trenord(
        fermate: String = FERMATE,
        avvisi: String = """
            {"type": "suppressed", "reason": "Richiesta Impresa Ferroviaria",
             "message": "Il treno è cancellato da SESTO S.GIOVANNI a MILANO CENTRALE."}
        """,
        motivo: String? = "Richiesta Impresa Ferroviaria",
    ): TrainStatus = NetworkModule.json.decodeFromString<List<TrenordSolutionDto>>(
        """
        [{
          "date": "20260918",
          "journey_list": [{
            "train": {
              "train_id": "2833", "train_category": "RE", "line": "RE_8", "delay": 29,
              "has_live_info": true,
              "suppression_reason": ${motivo?.let { "\"$it\"" } ?: "null"},
              "alerts": [$avvisi]
            },
            "pass_list": [$fermate]
          }]
        }]
        """,
    ).single().toTrainStatus()!!

    private companion object {
        const val FERMATE = """
            {"station": {"station_id": "S01440", "station_ori_name": "TIRANO"},
             "dep_time": "15:08:00", "cancelled": false,
             "actual_data": {"dep_actual_time": "15:39:00"}},
            {"station": {"station_id": "S01322", "station_ori_name": "MONZA"},
             "arr_time": "17:26:00", "dep_time": "17:27:00", "cancelled": false},
            {"station": {"station_id": "S01700", "station_ori_name": "MILANO CENTRALE"},
             "arr_time": "17:43:00", "cancelled": true}
        """
    }

    @Test
    fun `il perche' della variazione lo dice Trenord`() {
        val corsa = trenord()
        assertEquals("Richiesta Impresa Ferroviaria", corsa.motivo)
        assertEquals(
            "la soppressione e' la notizia della corsa, non un avviso di linea",
            "Il treno è cancellato da SESTO S.GIOVANNI a MILANO CENTRALE.",
            corsa.notice,
        )
        assertTrue(corsa.avvisi.isEmpty())
    }

    @Test
    fun `senza suppression_reason vale il motivo dell'avviso`() {
        assertEquals("Richiesta Impresa Ferroviaria", trenord(motivo = null).motivo)
    }

    @Test
    fun `l'avviso di linea arriva pulito`() {
        val corsa = trenord(
            motivo = null,
            avvisi = """
                {"type": "custom_high_severity", "reason": null,
                 "message": "+Circolazione fortemente rallentata, per accertamenti delle forze dell'ordine nella stazione di MILANO ROGOREDO. "}
            """,
        )
        assertEquals(
            listOf(
                "Circolazione fortemente rallentata, per accertamenti delle forze " +
                    "dell'ordine nella stazione di MILANO ROGOREDO.",
            ),
            corsa.avvisi,
        )
        assertNull(corsa.motivo)
        assertNull(corsa.notice)
    }

    @Test
    fun `il motivo passa sulla corsa di ViaggiaTreno`() {
        val unita = viaggiaTreno.conAvvisiDa(trenord())
        assertEquals("Richiesta Impresa Ferroviaria", unita.motivo)
        assertEquals(
            "il testo della variazione resta quello di ViaggiaTreno",
            viaggiaTreno.notice,
            unita.notice,
        )
    }

    @Test
    fun `il motivo di un altro treno con lo stesso numero non passa`() {
        // Stesso numero, altra corsa: la stessa stazione, ma nove ore prima.
        val altra = trenord(
            fermate = """
                {"station": {"station_id": "S01440", "station_ori_name": "TIRANO"},
                 "dep_time": "06:08:00", "cancelled": false},
                {"station": {"station_id": "S01322", "station_ori_name": "MONZA"},
                 "arr_time": "08:26:00", "cancelled": true}
            """,
        )
        assertNull(viaggiaTreno.conAvvisiDa(altra).motivo)
    }
}
