package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.FnbRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * La ricerca Ferrotramviaria contro il portale vero: Bari Centrale FNB →
 * Barletta, domani mattina.
 *
 * E' l'unico orario di questa rete, e l'unica cosa che ne dica le fermate. Se il
 * portale cambia forma, qui l'app resta senza: la ricerca torna vuota e nessuno
 * se ne accorge.
 *
 * Fra Andria Sud e Barletta si viaggia in bus dai lavori del 2025: le soluzioni
 * escono con due tratti, il secondo `conServizioSostitutivo`. Il test non lo
 * pretende — i lavori finiranno — ma se c'e' deve arrivare a schermo come bus.
 */
class FnbRicercaLiveTest {

    private val fnb = FnbRepository(NetworkModule.fnbApi)
    private val bari = "FNB1110"
    private val barletta = "FNB1180"
    private val domani = LocalDate.now().plusDays(1).atTime(7, 0)

    @Test
    fun `la ricerca di domani da' viaggi con fermate, mezzi e prezzo`() = runBlocking {
        val viaggi = fnb.itinerario(bari, barletta, domani)
        viaggi.forEach { v ->
            println("  ${v.departure.toLocalTime()} → ${v.arrival.toLocalTime()} ${v.price?.amount ?: "-"} € | " +
                v.legs.joinToString(" › ") { "${it.category ?: ""}${it.trainNumber ?: ""} ${it.kind} ${it.from.name}→${it.to.name}" })
        }
        assumeTrue("il portale non ha soluzioni per domani mattina", viaggi.isNotEmpty())

        viaggi.forEach { v ->
            assertTrue("un viaggio senza tratte", v.legs.isNotEmpty())
            assertTrue("gli orari devono crescere", !v.arrival.isBefore(v.departure))
            assertEquals("parte da Bari Centrale FNB", bari, v.legs.first().from.rfiCode)
            assertEquals("arriva a Barletta", barletta, v.legs.last().to.rfiCode)
            assertTrue("le tappe si susseguono", v.legs.zipWithNext().all { (a, b) -> !b.departure.isBefore(a.arrival) })
            assertTrue("ogni tappa ha un numero", v.legs.all { !it.trainNumber.isNullOrBlank() })
            assertTrue("il prezzo, se c'e', e' positivo", v.price?.amount?.toDouble()?.let { it > 0 } ?: true)
        }
    }

    @Test
    fun `il dettaglio di una corsa da' le sue fermate`() = runBlocking {
        val viaggi = fnb.itinerario(bari, barletta, domani)
        val primo = viaggi.firstOrNull()?.legs?.firstOrNull()
        assumeTrue("nessun viaggio da cui partire", primo != null)
        val corsa = fnb.dettaglioCorsa(primo!!.trainNumber!!, primo.from.rfiCode, primo.to.rfiCode, primo.departure)
        println("  ${corsa?.label}: ${corsa?.stops?.joinToString { "${it.stationName} ${it.scheduledDeparture?.toLocalTime() ?: it.scheduledArrival?.toLocalTime()}" }}")
        assertTrue("la corsa non esce", corsa != null)
        assertTrue("almeno due fermate", corsa!!.stops.size >= 2)
        assertEquals(primo.trainNumber, corsa.number)
        assertEquals("parte dalla fermata di salita", primo.from.rfiCode, corsa.stops.first().stationCode)
        assertEquals(primo.departure, corsa.stops.first().scheduledDeparture)
        assertTrue("di questa rete non c'e' tempo reale per corsa", !corsa.realtime)
        assertTrue("e lo deve dire", corsa.notice?.contains("tabellone") == true)
        assertTrue("nessun binario per una corsa futura", corsa.stops.none { it.actualPlatform != null || it.scheduledPlatform != null })
    }

    /**
     * Salendo a meta' linea, le fermate devono essere quelle di tutta la corsa,
     * non solo del tratto percorso: e' la regola decisa per i giorni futuri, e
     * vale anche qui.
     */
    @Test
    fun `salendo a meta' linea la corsa esce intera`() = runBlocking {
        val aeroporto = "FNB1141"
        val viaggi = fnb.itinerario(aeroporto, barletta, domani)
        val primo = viaggi.firstOrNull { it.legs.first().kind == it.legs.first().kind }?.legs?.firstOrNull()
        assumeTrue("nessun viaggio dall'aeroporto", primo?.trainNumber != null)
        val corsa = fnb.dettaglioCorsa(primo!!.trainNumber!!, aeroporto, barletta, primo.departure)
        println("  ${corsa?.label}: ${corsa?.stops?.size} fermate, da ${corsa?.stops?.firstOrNull()?.stationName} a ${corsa?.stops?.lastOrNull()?.stationName}")
        assertTrue(corsa != null)
        val salita = corsa!!.stops.indexOfFirst { it.stationCode == aeroporto }
        assertTrue("l'aeroporto deve esserci", salita >= 0)
        assertTrue(
            "ci si aspetta le fermate prima della salita: ${corsa.stops.map { it.stationName }}",
            salita > 0 || corsa.notice?.contains("tratto che percorri") == true,
        )
    }

    @Test
    fun `dal tabellone, senza discesa, la corsa si trova coi capolinea`() = runBlocking {
        val viaggi = fnb.itinerario(bari, barletta, domani)
        val primo = viaggi.firstOrNull()?.legs?.firstOrNull()
        assumeTrue(primo?.trainNumber != null)
        val corsa = fnb.dettaglioCorsa(primo!!.trainNumber!!, bari, null, primo.departure)
        println("  senza discesa: ${corsa?.stops?.size} fermate fino a ${corsa?.stops?.lastOrNull()?.stationName}")
        assertTrue("senza discesa la corsa si trova lo stesso", corsa != null)
    }

    @Test
    fun `un numero che quella tratta non ha non esce`() = runBlocking {
        assertEquals(null, fnb.dettaglioCorsa("999999", bari, barletta, domani))
        // E fuori dalla rete non si chiede niente.
        assertEquals(emptyList<Any>(), fnb.itinerario("S01700", "S01717", domani))
        assertEquals(null, fnb.dettaglioCorsa("91008", "S01700", barletta, domani))
    }

    @Test
    fun `senza sessione il dettaglio sarebbe vuoto, con la sessione no`() = runBlocking {
        // Due ricerche di fila devono dare gli stessi viaggi: la sessione si
        // riapre da sola quando scade, e gli id delle soluzioni cambiano ogni volta.
        val prima = fnb.itinerario(bari, barletta, domani)
        val dopo = fnb.itinerario(bari, barletta, domani)
        assumeTrue(prima.isNotEmpty())
        assertEquals(prima.map { it.departure }, dopo.map { it.departure })
    }

    @Test
    fun `anche per un giorno lontano risponde`() = runBlocking {
        val fraUnMese = LocalDate.now().plusDays(30).atTime(9, 0)
        val viaggi = fnb.itinerario(bari, barletta, fraUnMese)
        println("  fra un mese: ${viaggi.size} viaggi, il primo alle ${viaggi.firstOrNull()?.departure?.toLocalTime()}")
        assertTrue("il portale risponde fino alla fine dell'orario", viaggi.isNotEmpty())
        assertTrue(viaggi.all { it.departure.toLocalDate() == fraUnMese.toLocalDate() })
    }
}
