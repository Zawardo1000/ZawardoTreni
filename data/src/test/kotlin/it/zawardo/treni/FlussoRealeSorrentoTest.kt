package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.ViaggiMistiRepository
import it.zawardo.treni.domain.model.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Il flusso reale della ricerca, con le Station come le da' il picker.
 *
 * Copre le due regressioni trovate provando l'app: le coordinate che si
 * perdevano fra picker e ricerca (i misti non partivano), e i diretti tutta-EAV
 * che nessuno cercava.
 */
class FlussoRealeSorrentoTest {
    private val bff = StationRepository(NetworkModule.lefrecceApi)
    private val eav = EavRepository(NetworkModule.eavApi)
    private val arst = ArstRepository()
    private val italo = ItaloRepository(NetworkModule.italoApi)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi)
    private val misti = ViaggiMistiRepository(eav, arst, italo, bff, journeys)

    private val tutte = setOf(DataSource.TRENITALIA, DataSource.EAV, DataSource.ITALO)

    @Test
    fun `Sorrento EAV verso Roma BFF ha coordinate e italo copre l'arrivo`() = runBlocking {
        val sorrento = eav.search("Sorrento").first()
        val roma = bff.search("Roma Termini").first()
        assertTrue("Sorrento senza coordinate", sorrento.latitude != 0.0 && sorrento.longitude != 0.0)
        assertTrue("Roma senza coordinate", roma.latitude != 0.0 && roma.longitude != 0.0)
        assertTrue("Italo deve coprire Roma", italo.covers(roma.rfiCode))

        val out = misti.cerca(sorrento, roma, LocalDate.now().plusDays(1).atTime(8, 0), sources = tutte)
        println("\n=== Sorrento->Roma: ${out.size} misti ===")
        out.take(3).forEach { println("  ${it.departure.toLocalTime()} ${it.legs.joinToString(" + ") { l -> l.label }}") }
        // dipende da Italo: se risponde, i misti devono essere ben formati
        out.forEach { assertTrue("un misto attraversa piu' operatori", it.multiOperator) }
    }

    @Test
    fun `Sorrento-Napoli, tutta EAV, ha corse dirette dall'orario`() = runBlocking {
        val sorrento = eav.search("Sorrento").first()
        val napoli = eav.search("Napoli Porta Nolana").first()
        val corse = eav.itinerario(sorrento.rfiCode!!, napoli.rfiCode!!, LocalDate.now().plusDays(1))
        println("\n=== Sorrento->Napoli (EAV diretto): ${corse.size} corse ===")
        corse.take(3).forEach { println("  ${it.departure.toLocalTime()} -> ${it.arrival.toLocalTime()}  ${it.legs.first().label}") }
        assertTrue("una tratta interna EAV deve dare corse", corse.isNotEmpty())
        assertTrue("le gambe EAV dirette sono della rete EAV", corse.all { j -> j.legs.all { it.source == DataSource.EAV } })
    }
}
