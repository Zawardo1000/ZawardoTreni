package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * La corsa di ViaggiaTreno trovata con l'origine che dice Le Frecce (`bdoOrigin`),
 * contro i servizi veri: vedi `TrainStatusRepository.resolveFor`.
 */
class OrigineCorsaLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi, trenord, ItaloRepository(NetworkModule.italoApi))

    private val tratte = listOf(
        Station("S01700", 830001700, "Milano Centrale") to Station("S06421", 830006421, "Firenze S. M. Novella"),
        Station("S08409", 830008409, "Roma Termini") to Station("S09218", 830009218, "Napoli Centrale"),
        Station("S01700", 830001700, "Milano Centrale") to Station("S01717", 830001717, "Brescia"),
    )

    @Test
    fun `con l'origine della ricerca si trova la stessa corsa che per numero`() = runBlocking {
        val adesso = LocalDateTime.now().plusMinutes(20)
        var provate = 0
        var conNome = 0
        for ((da, a) in tratte) {
            val viaggi = journeys.searchAll(da, a, adesso, 6, setOf(DataSource.TRENITALIA)).journeys
            for (leg in viaggi.flatMap { it.legs }.filter { it.isTrain }.take(3)) {
                val numero = leg.trainNumber!!
                /*
                 * L'origine non c'e' per tutti: gli **EuroCity** ne sono senza,
                 * perche' il sito non pubblica `bdoOrigin` per i treni degli
                 * operatori esteri (provato il 20/09/2026 su Milano - Brescia: EC
                 * 301 null, tutti i regionali e le Frecce con l'origine). Per loro
                 * vale il ripiego per numero, ed e' quello che si verifica qui
                 * sotto. Se pero' sparisse per **tutti**, quello si' sarebbe una
                 * rottura della ricerca dal sito, e il conto finale la dichiara.
                 */
                if (leg.origineCorsa != null) conNome++
                val conOrigine = trains.statusByNumber(numero, leg.departure.toLocalDate(), leg.from.rfiCode, leg.departure, origine = leg.origineCorsa)
                val perNumero = trains.statusByNumber(numero, leg.departure.toLocalDate(), leg.from.rfiCode, leg.departure)
                println("  ${leg.category} $numero da ${leg.origineCorsa}: con origine ${conOrigine?.stops?.size} fermate, per numero ${perNumero?.stops?.size}")
                if (perNumero == null) continue
                provate++
                assertTrue("con l'origine la corsa del $numero non si trova", conOrigine != null)
                assertEquals(perNumero.stops.map { it.stationCode }, conOrigine!!.stops.map { it.stationCode })
                assertTrue(conOrigine.stops.any { stessaStazione(it.stationCode, leg.from.rfiCode) })
            }
        }
        assumeTrue("nessun treno interrogabile adesso", provate > 0)
        assertTrue(
            "nessuna soluzione del sito porta piu' l'origine della corsa (`bdoOrigin`): " +
                "senza, ogni corsa si cerca per numero e due treni omonimi si confondono",
            conNome > 0,
        )
    }

    @Test
    fun `un'origine sbagliata non impedisce di trovare la corsa per numero`() = runBlocking {
        val viaggio = journeys.searchAll(tratte[1].first, tratte[1].second, LocalDateTime.now().plusMinutes(20), 4, setOf(DataSource.TRENITALIA))
            .journeys.firstOrNull { j -> j.legs.any { it.isTrain } }
        assumeTrue("nessuna soluzione", viaggio != null)
        val leg = viaggio!!.legs.first { it.isTrain }
        val perNumero = trains.statusByNumber(leg.trainNumber!!, leg.departure.toLocalDate(), leg.from.rfiCode, leg.departure)
        assumeTrue("ViaggiaTreno non ha la corsa", perNumero != null)
        val conSbagliata = trains.statusByNumber(leg.trainNumber!!, leg.departure.toLocalDate(), leg.from.rfiCode, leg.departure, origine = "S00001")
        assertEquals(perNumero!!.stops.size, conSbagliata?.stops?.size)
    }

    /** Stessa origine, ma l'ora di salita non torna: e' un'altra corsa, e non si prende. */
    @Test
    fun `la corsa dall'origine all'ora sbagliata non si prende`() = runBlocking {
        val viaggio = journeys.searchAll(tratte[0].first, tratte[0].second, LocalDateTime.now().plusMinutes(20), 4, setOf(DataSource.TRENITALIA))
            .journeys.firstOrNull { j -> j.legs.any { it.isTrain && it.origineCorsa != null } }
        assumeTrue("nessuna soluzione con l'origine", viaggio != null)
        val leg = viaggio!!.legs.first { it.isTrain }
        val ref = trains.resolveFor(leg.trainNumber!!, leg.departure.toLocalDate(), leg.from.rfiCode, leg.departure.plusHours(5), origine = leg.origineCorsa)
        // Senza la corsa giusta a quell'ora si ripiega sulla ricerca per numero, che non inventa.
        println("  ${leg.trainNumber} con l'ora spostata di 5 ore: $ref")
        if (ref != null) assertTrue(ref.originCode.isNotBlank())
        assertNull(trains.resolveFor(leg.trainNumber!!, leg.departure.toLocalDate().plusDays(3), leg.from.rfiCode, leg.departure.plusDays(3), origine = leg.origineCorsa))
    }
}
