package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * La corsa di un giorno futuro da Le Frecce (`JourneyRepository.corsaDelGiorno`),
 * sui servizi veri: le fermate di quel giorno, dall'origine al capolinea.
 */
class CorsaDelGiornoLiveTest {

    private val journeys = JourneyRepository(NetworkModule.lefrecceApi)
    private val acireale = Station("S12328", 830012328, "Acireale")
    private val cataniaAeroporto = Station("S12336", 830012336, "Catania Aeroporto Fontanarossa")
    private val milano = Station("S01700", 830001700, "Milano Centrale")
    private val roma = Station("S08409", 830008409, "Roma Termini")

    /**
     * Un regionale siciliano della domenica: il 19/09/2026 per il RE 22111 di
     * domenica 20 il dettaglio usciva vuoto, perche' sabato quel numero non
     * circola e il percorso si ricavava dalla corsa di oggi.
     */
    @Test
    fun `un regionale della domenica ha la corsa intera, anche se oggi non circola`() = runBlocking {
        val domenica = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SUNDAY))
        val viaggi = journeys.searchAll(acireale, cataniaAeroporto, domenica.atTime(12, 0), 8, setOf(DataSource.TRENITALIA)).journeys
        val leg = viaggi.flatMap { it.legs }.firstOrNull { it.isTrain && stessaStazione(it.from.rfiCode, acireale.rfiCode) }
        assumeTrue("domenica nessun treno da Acireale", leg != null)
        println("  ${leg!!.category} ${leg.trainNumber} ${leg.departure} ${leg.from.name} -> ${leg.to.name}, origine ${leg.origineCorsa}")

        val senzaCapolinea = journeys.corsaDelGiorno(leg.trainNumber!!, leg.from, leg.to, leg.departure)
        println("  dall'origine: ${senzaCapolinea?.stops?.joinToString { it.stationName }}")
        assertTrue("la corsa del giorno non esce", senzaCapolinea != null)
        val salita = senzaCapolinea!!.stops.first { stessaStazione(it.stationCode, acireale.rfiCode) }
        assertEquals(leg.departure, salita.scheduledDeparture)
        assertTrue("senza capolinea va comunque dall'origine", senzaCapolinea.stops.first().stationCode == leg.origineCorsa || senzaCapolinea.stops.indexOf(salita) > 0)
        assertTrue(senzaCapolinea.stops.none { it.scheduledPlatform != null || it.actualArrival != null })
        assertTrue("di un giorno futuro nessun tempo reale", !senzaCapolinea.realtime)
    }

    /** Una Freccia lunga fra un mese, presa a meta': col capolinea indiziato, tutta. */
    @Test
    fun `una Freccia presa a meta' esce dall'origine al capolinea`() = runBlocking {
        val giorno = LocalDate.now().plusDays(30)
        val viaggi = journeys.searchAll(milano, roma, giorno.atTime(8, 0), 8, setOf(DataSource.TRENITALIA)).journeys
        val leg = viaggi.filter { it.isDirect }.flatMap { it.legs }
            .firstOrNull { it.isTrain && it.origineCorsa != null && !stessaStazione(it.origineCorsa, milano.rfiCode) }
        assumeTrue("nessuna Freccia che passi da Milano senza partirci", leg != null)
        println("  ${leg!!.category} ${leg.trainNumber} ${leg.departure}, origine ${leg.origineCorsa}")

        val tratto = journeys.corsaDelGiorno(leg.trainNumber!!, leg.from, leg.to, leg.departure)
        val conCapolinea = journeys.corsaDelGiorno(
            leg.trainNumber!!, leg.from, leg.to, leg.departure,
            capolinea = listOf(Station(null, 0, "Napoli Centrale"), Station(null, 0, "Salerno"), Station(null, 0, "Reggio Di Calabria Centrale")),
        )
        println("  senza capolinea: ${tratto?.stops?.map { it.stationName }}")
        println("  con capolinea:   ${conCapolinea?.stops?.map { it.stationName }}")
        assertTrue(tratto != null && conCapolinea != null)
        assertTrue("deve cominciare dall'origine", stessaStazione(conCapolinea!!.stops.first().stationCode, leg.origineCorsa))
        assertTrue("con un capolinea confermato la corsa va oltre Roma", conCapolinea.stops.size >= tratto!!.stops.size)
        assertTrue(conCapolinea.stops.any { stessaStazione(it.stationCode, milano.rfiCode) && it.scheduledDeparture == leg.departure })
    }

    /** Un treno che a quell'ora non c'e' non si inventa. */
    @Test
    fun `un treno che quel giorno non c'e' non esce`() = runBlocking {
        val corsa = journeys.corsaDelGiorno("99999", milano, roma, LocalDateTime.now().plusDays(2).withHour(9).withMinute(0))
        assertEquals(null, corsa)
    }
}
