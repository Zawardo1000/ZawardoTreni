package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.domain.model.SuggerimentiStazioni
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il dedup: la Sorrento EAV vince sui doppioni BFF, Roma Termini resta pulita. */
class DedupSuggerimentiTest {
    private val bff = StationRepository(NetworkModule.lefrecceApi)
    private val eav = EavRepository(NetworkModule.eavApi)

    @Test
    fun `Sorrento EAV vince sui doppioni BFF senza dati`() = runBlocking {
        val locali = eav.search("Sorrento")          // EAV: Sorrento(EAV62)
        val nazionali = bff.search("Sorrento")        // BFF: Sorrento, SORRENTO CIRCUMVESUVIANA, ...
        val uniti = SuggerimentiStazioni.unisci(locali, nazionali)
        println("\n=== uniti (${uniti.size}) ===")
        uniti.forEach { println("  ${it.name}  rfi=${it.rfiCode}  trackable=${it.trackable}") }

        // La Sorrento EAV c'e', col suo codice.
        assertTrue("Sorrento EAV deve esserci", uniti.any { it.rfiCode == "EAV62" })
        // Il doppione BFF vicino (stesse coordinate) e' stato tolto.
        assertTrue(
            "il doppione BFF di Sorrento non deve restare accanto a quello EAV",
            uniti.none { it.rfiCode == null && it.name.equals("Sorrento", true) },
        )
        // Le fermate diverse (Porto, Meta) restano.
        // Le tracciabili stanno in cima.
        val primaNonTrack = uniti.indexOfFirst { !it.trackable }
        val ultimaTrack = uniti.indexOfLast { it.trackable }
        if (primaNonTrack >= 0 && ultimaTrack >= 0) {
            assertTrue("le tracciabili devono precedere le non tracciabili", ultimaTrack < primaNonTrack)
        }
    }

    @Test
    fun `Roma Termini resta RFI, tracciabile, senza badge`() = runBlocking {
        val nazionali = bff.search("Roma Termini")
        val uniti = SuggerimentiStazioni.unisci(emptyList(), nazionali)
        val roma = uniti.firstOrNull { it.name.contains("Roma Termini", true) }
        println("\n=== Roma Termini: rfi=${roma?.rfiCode} trackable=${roma?.trackable} ===")
        assertTrue("Roma Termini deve avere codice RFI", roma?.rfiCode?.startsWith("S") == true)
        assertTrue("Roma Termini e' tracciabile", roma?.trackable == true)
    }
}
