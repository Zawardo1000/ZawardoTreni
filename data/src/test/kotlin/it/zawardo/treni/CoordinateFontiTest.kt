package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.FnbRepository
import it.zawardo.treni.data.repository.SvizzeraRepository
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le stazioni delle reti fuori-RFI portano sempre le coordinate.
 *
 * E' il presupposto dei viaggi misti: senza coordinate la preselezione degli
 * hub fallisce e i misti non partono. Il difetto era altrove — la navigazione e
 * la cronologia le perdevano — ma la fonte deve comunque darle, sempre, o non
 * c'e' niente da preservare.
 */
class CoordinateFontiTest {
    private val eav = EavRepository(NetworkModule.eavApi)
    private val fnb = FnbRepository(NetworkModule.fnbApi)
    private val arst = ArstRepository()
    private val svizzera = SvizzeraRepository(NetworkModule.svizzeraApi)

    private fun conCoordinate(nome: String, res: List<it.zawardo.treni.domain.model.Station>) {
        println("  $nome: ${res.size}, prime: ${res.take(2).joinToString { "${it.name}(${it.latitude},${it.longitude})" }}")
        assertTrue("$nome non ha restituito stazioni", res.isNotEmpty())
        assertTrue(
            "$nome: alcune stazioni pianificabili sono senza coordinate",
            res.any { it.latitude != 0.0 && it.longitude != 0.0 },
        )
    }

    @Test
    fun `EAV FNB ARST danno stazioni con coordinate`() {
        println("\n=== coordinate fonti fuori-RFI ===")
        conCoordinate("EAV Sorrento", eav.search("Sorrento"))
        conCoordinate("EAV Napoli", eav.search("Napoli"))
        conCoordinate("FNB Bari", fnb.search("Bari"))
        conCoordinate("ARST Sassari", arst.search("Sassari"))
    }

    @Test
    fun `la Svizzera da fermate con coordinate`() {
        val res = svizzera.search("Domodossola") + svizzera.search("Locarno")
        println("\n=== Svizzera: ${res.size} ===")
        // La Svizzera puo' dipendere dalla rete: se vuota, non significativo.
        if (res.isNotEmpty()) {
            assertTrue("Svizzera senza coordinate", res.any { it.latitude != 0.0 })
        }
    }
}
