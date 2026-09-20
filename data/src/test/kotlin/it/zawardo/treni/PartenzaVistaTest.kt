package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.data.remote.viaggiatreno.TabelloneVoceDto
import it.zawardo.treni.data.remote.viaggiatreno.ViaggiaTrenoApi
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.vedePartireDa
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Il «non partito» di ViaggiaTreno vuol dire fermo solo dove il treno parte
 * davvero, e la partenza si vede.
 *
 * Il caso da cui nasce: il REG 2987 Gallarate - Milano Centrale del 18/09/2026,
 * in tabella alle 22:54. Quella sera Saronno-Malpensa-Gallarate era fatta in
 * bus e il treno partiva da Saronno, ma la corsa riportava il percorso intero,
 * e ViaggiaTreno l'ha dato «non partito» fino a Saronno, alle 23:43. Contato da
 * Gallarate, alle 23:44 era a +50; da Saronno era partito con sette minuti. La
 * corsa della sera prima non aveva l'orario reale a Gallarate nemmeno lei, ed e'
 * da li' che si capisce.
 *
 * Quella prova c'e' di rado: ViaggiaTreno da' la corsa di ieri solo se ha passato
 * la mezzanotte. Senza, vale la regola di prima, ed e' il caso del RE 2824 fermo
 * a Milano Centrale che l'ha fatta nascere: vedi [RitardoDaFermoTest].
 *
 * Gli orari sono relativi ad adesso, perche' il repository misura il fermo con
 * l'ora vera: il treno doveva partire venti minuti fa.
 */
class PartenzaVistaTest {

    private val roma = ZoneId.of("Europe/Rome")
    private val partenza = LocalDateTime.now(roma).minusMinutes(20).withSecond(0).withNano(0)
    private val oggi = partenza.toLocalDate()
    private val ieri = oggi.minusDays(1)

    private fun mezzanotte(giorno: LocalDate) = giorno.atStartOfDay(roma).toInstant().toEpochMilli()
    private fun ms(t: LocalDateTime) = t.atZone(roma).toInstant().toEpochMilli()

    private val gallarate = "S01030"
    private val ref = TrainRef("2987", gallarate, mezzanotte(oggi))

    /** Una corsa del 2987; di default, come nel tratto cieco: nessun rilevamento. */
    private fun corsa(
        giorno: LocalDate,
        partitaDaGallarate: Boolean = false,
        vistaASaronno: Boolean = false,
    ): AndamentoTrenoDto {
        val p = giorno.atTime(partenza.toLocalTime())
        val nonPartito = !partitaDaGallarate && !vistaASaronno
        return AndamentoTrenoDto(
            numeroTreno = 2987,
            categoria = "REG",
            origine = "GALLARATE",
            destinazione = "MILANO CENTRALE",
            nonPartito = nonPartito,
            circolante = !nonPartito,
            fermate = listOf(
                FermataDto(
                    stazione = "GALLARATE", id = gallarate, progressivo = 1, tipoFermata = "P",
                    partenzaTeorica = ms(p),
                    partenzaReale = if (partitaDaGallarate) ms(p.plusMinutes(5)) else null,
                    actualFermataType = if (partitaDaGallarate) 1 else 0,
                ),
                FermataDto(
                    stazione = "SARONNO", id = "S01933", progressivo = 10, tipoFermata = "F",
                    arrivoTeorico = ms(p.plusMinutes(41)), partenzaTeorica = ms(p.plusMinutes(42)),
                    partenzaReale = if (vistaASaronno) ms(p.plusMinutes(49)) else null,
                    actualFermataType = if (vistaASaronno) 1 else 0,
                ),
                FermataDto(
                    stazione = "MILANO CENTRALE", id = "S01700", progressivo = 25, tipoFermata = "A",
                    arrivoTeorico = ms(p.plusMinutes(73)),
                ),
            ),
        )
    }

    private class FintoViaggiaTreno(
        private val corse: Map<Long, AndamentoTrenoDto>,
        private val tabellone: List<TabelloneVoceDto> = emptyList(),
        /** Per quale giorno la rete cade, invece di rispondere. */
        private val cadePer: Long? = null,
    ) : ViaggiaTrenoApi {
        val chieste = mutableListOf<Long>()

        override suspend fun andamentoTreno(
            originCode: String,
            trainNumber: String,
            departureDateMillis: Long,
        ): Response<AndamentoTrenoDto> {
            chieste += departureDateMillis
            if (departureDateMillis == cadePer) throw java.io.IOException("rete caduta")
            // Come ViaggiaTreno per una corsa che quel giorno non c'e': 204.
            val corsa = corse[departureDateMillis] ?: return Response.success<AndamentoTrenoDto>(204, null)
            return Response.success(corsa)
        }

        override suspend fun partenze(stationCode: String, dateTime: String) = tabellone
        override suspend fun arrivi(stationCode: String, dateTime: String): List<TabelloneVoceDto> = error("non serve")
        override suspend fun cercaNumeroTreno(trainNumber: String): ResponseBody = error("non serve")
        override suspend fun infomobilita(): ResponseBody = error("non serve")
        override suspend fun notizieInfomobilita(): List<it.zawardo.treni.data.remote.viaggiatreno.NotiziaInfomobilitaDto> = error("non serve")
        override suspend fun noteSmartCaring(numero: String, giorno: String): List<it.zawardo.treni.data.remote.viaggiatreno.NotaSmartCaringDto> = error("non serve")
    }

    private fun repository(
        ieri: AndamentoTrenoDto?,
        tabellone: List<TabelloneVoceDto> = emptyList(),
        reteCadutaIeri: Boolean = false,
    ) = FintoViaggiaTreno(
        listOfNotNull(mezzanotte(oggi) to corsa(oggi), ieri?.let { mezzanotte(this.ieri) to it }).toMap(),
        tabellone,
        cadePer = mezzanotte(this.ieri).takeIf { reteCadutaIeri },
    ).let { it to TrainStatusRepository(it) }

    @Test
    fun `la corsa di ieri dice se la partenza dall'origine si vede`() {
        assertEquals(true, corsa(ieri, partitaDaGallarate = true, vistaASaronno = true).toTrainStatus()!!.vedePartireDa(gallarate))
        assertEquals(
            "vista a Saronno e non a Gallarate: da Gallarate e' partita, ma la partenza non si vede",
            false,
            corsa(ieri, vistaASaronno = true).toTrainStatus()!!.vedePartireDa(gallarate),
        )
        assertNull("senza nessun rilevamento non dice niente", corsa(ieri).toTrainStatus()!!.vedePartireDa(gallarate))
    }

    @Test
    fun `dove la partenza non si vede il non partito non diventa ritardo`() = runBlocking {
        val (_, trains) = repository(ieri = corsa(ieri, vistaASaronno = true))
        val stato = trains.status(ref)!!
        assertEquals(TrainState.NOT_DEPARTED, stato.state)
        assertEquals("contato da Gallarate era a +50: partiva da Saronno, con sette minuti", 0, stato.delayMinutes)
    }

    @Test
    fun `dove la partenza si vede fermo oltre l'ora e' in ritardo`() = runBlocking {
        val (_, trains) = repository(ieri = corsa(ieri, partitaDaGallarate = true, vistaASaronno = true))
        assertTrue("e' fermo da venti minuti", trains.status(ref)!!.delayMinutes >= 20)
    }

    /** Il caso di tutti i giorni: ViaggiaTreno la corsa di ieri non la da' piu'. */
    @Test
    fun `senza la corsa di ieri vale la regola di prima, e la risposta vuota si ricorda`() = runBlocking {
        val (api, trains) = repository(ieri = null)
        assertTrue("fermo da venti minuti, come il RE 2824", trains.status(ref)!!.delayMinutes >= 20)
        trains.status(ref)
        assertEquals(1, api.chieste.count { it == mezzanotte(ieri) })
    }

    @Test
    fun `una corsa di ieri senza rilevamenti non prova niente`() = runBlocking {
        val (_, trains) = repository(ieri = corsa(ieri))
        assertTrue(trains.status(ref)!!.delayMinutes >= 20)
    }

    @Test
    fun `con la rete caduta vale la regola di prima, e la volta dopo si richiede`() = runBlocking {
        val (api, trains) = repository(ieri = corsa(ieri, vistaASaronno = true), reteCadutaIeri = true)
        assertTrue(trains.status(ref)!!.delayMinutes >= 20)
        trains.status(ref)
        assertEquals(2, api.chieste.count { it == mezzanotte(ieri) })
    }

    @Test
    fun `una risposta vera si ricorda`() = runBlocking {
        val (api, trains) = repository(ieri = corsa(ieri, vistaASaronno = true))
        repeat(3) { trains.status(ref) }
        assertEquals(1, api.chieste.count { it == mezzanotte(ieri) })
    }

    /** Il tabellone di Gallarate, dove la riga si calcolerebbe da sola: vedi `conRitardoDaFermo`. */
    @Test
    fun `sul tabellone d'origine vale la stessa prova`() = runBlocking {
        val riga = TabelloneVoceDto(
            numeroTreno = 2987,
            categoria = "REG",
            codOrigine = gallarate,
            dataPartenzaTreno = mezzanotte(oggi),
            nonPartito = true,
            compOrarioPartenza = partenza.format(DateTimeFormatter.ofPattern("HH:mm")),
        )
        val (_, cieco) = repository(ieri = corsa(ieri, vistaASaronno = true), tabellone = listOf(riga))
        assertEquals(0, cieco.departures(gallarate).single().delayMinutes)

        val (_, visto) = repository(ieri = corsa(ieri, partitaDaGallarate = true), tabellone = listOf(riga))
        assertTrue(visto.departures(gallarate).single().delayMinutes >= 20)
    }
}
