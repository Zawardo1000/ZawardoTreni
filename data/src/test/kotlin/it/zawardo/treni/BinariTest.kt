package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.trenord.TrenordSolutionDto
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.conBinariDa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * I binari di una corsa non stanno tutti nella stessa fonte.
 *
 * Segnalazione con lo screenshot davanti: il REG 2874 delle 17:50 per Lecco,
 * aperto alle 16:43 del 04/09/2026, mostrava "bin. 4" a Monza, "bin. 5" a
 * Carnate, "bin. 2" a Cernusco — e **niente** a Milano Centrale, che e' l'unica
 * fermata di quell'elenco da cui devi salire. Non era un dato perso per strada:
 * `andamentoTreno` per quella corsa ha tutti e quattro i campi del binario
 * vuoti al capolinea, e nelle stazioni grandi il binario non sta in orario, lo
 * assegnano un quarto d'ora prima.
 *
 * Misurando il resto, pero', si e' visto che le due fonti si completano invece
 * di ripetersi. Il materiale di questo test e' il REG 2934 Milano Centrale -
 * Gallarate delle 09:55 del 04/09/2026, letto da tutte e due:
 *
 *  - **ViaggiaTreno** aveva il binario a tre fermate su otto — Centrale,
 *    Porta Garibaldi, Gallarate — e a nessuna delle stazioni FNM, che non sono
 *    rete RFI e per lui non esistono;
 *  - **Trenord** aveva quelle cinque, piu' il binario **vero** di Milano
 *    Centrale, ma non il suo programmato: sulla rete RFI pubblica solo quello
 *    assegnato.
 *
 * Unite, le fermate col binario passano da tre a sette, e a Milano Centrale
 * compare un cambio — dal 1 al 2 — che nessuna delle due fonti poteva dire da
 * sola, perche' ciascuna aveva in mano una sola meta' del confronto.
 */
class BinariTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val giorno = LocalDate.of(2026, 9, 4)

    private fun millis(ora: String): Long =
        giorno.atTime(LocalTime.parse(ora)).atZone(roma).toInstant().toEpochMilli()

    // ------------------------------------------------------- ViaggiaTreno

    private fun fermata(
        n: Int,
        nome: String,
        codice: String,
        arrivo: String? = null,
        partenza: String? = null,
        programmato: String? = null,
        effettivo: String? = null,
    ) = FermataDto(
        stazione = nome,
        id = codice,
        progressivo = n,
        arrivoTeorico = arrivo?.let { millis(it) },
        partenzaTeorica = partenza?.let { millis(it) },
        // Al capolinea d'arrivo il binario ViaggiaTreno lo mette sui campi
        // dell'arrivo, non su quelli della partenza, che li' non esiste.
        binarioProgrammatoPartenzaDescrizione = programmato.takeIf { partenza != null },
        binarioEffettivoPartenzaDescrizione = effettivo.takeIf { partenza != null },
        binarioProgrammatoArrivoDescrizione = programmato.takeIf { partenza == null },
        binarioEffettivoArrivoDescrizione = effettivo.takeIf { partenza == null },
    )

    /** Il REG 2934 come lo dava ViaggiaTreno: tre fermate su otto col binario. */
    private val viaggiaTreno: TrainStatus = AndamentoTrenoDto(
        numeroTreno = 2934,
        compNumeroTreno = "REG 2934",
        origine = "MILANO CENTRALE",
        destinazione = "GALLARATE",
        fermate = listOf(
            fermata(1, "MILANO CENTRALE", "S01700", partenza = "09:55", programmato = "1"),
            fermata(2, "MILANO PORTA GARIBALDI", "S01645", "10:04", "10:05", programmato = "14"),
            fermata(3, "MILANO BOVISA POLITECNICO", "S01642", "10:11", "10:12"),
            fermata(4, "SARONNO", "S01933", "10:24", "10:25"),
            fermata(5, "BUSTO ARSIZIO NORD", "S01137", "10:38", "10:39"),
            fermata(6, "MALPENSA AEROPORTO TERMINAL 1", "S01139", "10:48", "10:51"),
            fermata(7, "MALPENSA AEROPORTO TERMINAL 2", "S01146", "10:56", "10:58"),
            fermata(8, "GALLARATE", "S01030", arrivo = "11:06", programmato = "1"),
        ),
    ).toTrainStatus()

    // ------------------------------------------------------------ Trenord

    private fun passo(
        nome: String,
        codice: String,
        arrivo: String? = null,
        partenza: String? = null,
        binario: String? = null,
        vero: Boolean? = null,
    ) = """
        {"station":{"station_id":"$codice","station_ori_name":"$nome"},
         ${arrivo?.let { """"arr_time":"$it",""" }.orEmpty()}
         ${partenza?.let { """"dep_time":"$it",""" }.orEmpty()}
         ${binario?.let { """"platform":"$it","is_actual_platform":$vero,""" }.orEmpty()}
         "type":"F"}
    """.trimIndent()

    private fun trenord(passi: List<String>): TrainStatus =
        NetworkModule.json.decodeFromString<List<TrenordSolutionDto>>(
            """
            [{"date":"20260904","dep_time":"09:55:00","arr_time":"11:06:00",
              "journey_list":[{
                "train":{"train_id":"2934","train_category":"RE","has_live_info":true},
                "pass_list":[${passi.joinToString(",")}]}]}]
            """.trimIndent(),
        ).first().toTrainStatus()!!

    /**
     * Il REG 2934 come lo dava Trenord: il binario vero a Centrale, quello di
     * tabella sulle FNM. I secondi sono quelli veri, e sono il motivo per cui
     * l'accoppiamento delle fermate tollera qualche minuto: Busto Arsizio Nord
     * e' "10:39" per ViaggiaTreno e "10:39:30" per Trenord.
     */
    private val trenord: TrainStatus = trenord(
        listOf(
            passo("MILANO CENTRALE", "S01700", partenza = "09:55:00", binario = "2", vero = true),
            passo("MILANO PORTA GARIBALDI", "S01645", "10:04:00", "10:05:00"),
            passo("MILANO BOVISA POLITECNICO", "S01642", "10:11:00", "10:12:00"),
            passo("SARONNO", "S01933", "10:24:00", "10:25:00", "6", vero = false),
            passo("BUSTO ARSIZIO NORD", "S01137", "10:38:30", "10:39:30", "3", vero = false),
            passo("MALPENSA AEROPORTO T1", "S01139", "10:48:30", "10:51:30", "3", vero = false),
            passo("MALPENSA AEROPORTO T2", "S01146", "10:56:00", "10:58:00", "3", vero = false),
            passo("GALLARATE", "S01030", arrivo = "11:06:00", binario = "1", vero = false),
        ),
    )

    private val unite: TrainStatus = viaggiaTreno.conBinariDa(trenord)

    // --------------------------------------------------------------- test

    @Test
    fun `is_actual_platform separa il binario vero da quello di tabella`() {
        val centrale = trenord.stops.first()
        assertEquals("2", centrale.actualPlatform)
        assertNull("Trenord non ha il binario programmato su RFI", centrale.scheduledPlatform)

        val saronno = trenord.stops[3]
        assertEquals("6", saronno.scheduledPlatform)
        assertNull(saronno.actualPlatform)
    }

    @Test
    fun `un binario senza is_actual_platform vale come tabella, non come cambio`() {
        val letto = trenord(
            listOf(passo("SARONNO", "S01933", "10:24:00", "10:25:00", "6", vero = null)),
        ).stops.first()
        assertEquals("6", letto.scheduledPlatform)
        assertNull(
            "Un binario che nessuno dichiara vero non puo' far comparire un cambio",
            letto.actualPlatform,
        )
    }

    @Test
    fun `Trenord riempie le fermate che ViaggiaTreno lascia vuote`() {
        assertEquals(3, viaggiaTreno.stops.count { it.platform != null })
        assertEquals(7, unite.stops.count { it.platform != null })

        assertEquals("6", unite.stops[3].platform)
        assertEquals("3", unite.stops[4].platform)
        assertEquals("3", unite.stops[5].platform)
        assertEquals("3", unite.stops[6].platform)

        // Bovisa non ce l'ha nessuna delle due: resta vuota, e va bene cosi'.
        assertNull(unite.stops[2].platform)
    }

    @Test
    fun `le fermate si accoppiano sul codice, non sul nome`() {
        // "MALPENSA AEROPORTO TERMINAL 1" e "MALPENSA AEROPORTO T1" sono la
        // stessa stazione solo per il codice: S01139.
        val malpensa = unite.stops[5]
        assertEquals("MALPENSA AEROPORTO TERMINAL 1", malpensa.stationName)
        assertEquals("3", malpensa.scheduledPlatform)
    }

    @Test
    fun `mezzo minuto di scarto non impedisce l'accoppiamento`() {
        // Busto Arsizio Nord: 10:39 per ViaggiaTreno, 10:39:30 per Trenord.
        assertEquals("3", unite.stops[4].scheduledPlatform)
    }

    @Test
    fun `il cambio di binario nasce dall'unione delle due fonti`() {
        /*
         * E' il caso che da solo giustifica l'unione. A Milano Centrale
         * ViaggiaTreno ha il binario di tabella (1) e Trenord quello vero (2):
         * presa una alla volta, nessuna delle due fonti puo' dire che sono
         * diversi, perche' ciascuna ne conosce uno solo.
         */
        val centrale = unite.stops.first()
        assertEquals("1", centrale.scheduledPlatform)
        assertEquals("2", centrale.actualPlatform)
        assertTrue(centrale.platformChanged)

        assertFalse("da sola, ViaggiaTreno non poteva accorgersene", viaggiaTreno.stops.first().platformChanged)
        assertFalse("e nemmeno Trenord", trenord.stops.first().platformChanged)
    }

    @Test
    fun `un binario gia' noto non viene sostituito`() {
        // A Gallarate ViaggiaTreno ha gia' il programmato: quello di Trenord
        // non lo scavalca, nemmeno quando coincide.
        assertEquals("1", unite.stops.last().scheduledPlatform)
        assertEquals("14", unite.stops[1].scheduledPlatform)
    }

    @Test
    fun `due treni con lo stesso numero non si scambiano i binari`() {
        /*
         * Il 04/09/2026 il 178 era insieme l'EuroCity delle 10:10 Milano
         * Centrale - Chiasso e il regionale Trenord delle 19:46 Como Lago -
         * Milano Cadorna: due treni diversi, stesso numero, stesso giorno.
         * Qui l'altra lettura passa dalle stesse stazioni del 2934 ma dieci ore
         * dopo, ed e' l'orario a dire che non e' la stessa corsa.
         */
        val altroTreno = trenord(
            listOf(
                passo("SARONNO", "S01933", "20:24:00", "20:25:00", "4", vero = true),
                passo("BUSTO ARSIZIO NORD", "S01137", "20:38:00", "20:39:00", "5", vero = true),
            ),
        )
        assertEquals(viaggiaTreno.stops, viaggiaTreno.conBinariDa(altroTreno).stops)
    }

    @Test
    fun `una fonte senza binari lascia la corsa com'era`() {
        val muta = trenord(listOf(passo("SARONNO", "S01933", "10:24:00", "10:25:00")))
        assertEquals(viaggiaTreno.stops, viaggiaTreno.conBinariDa(muta).stops)
    }
}
