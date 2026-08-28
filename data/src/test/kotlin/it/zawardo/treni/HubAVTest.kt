package it.zawardo.treni

import it.zawardo.treni.data.misti.HubAV
import org.junit.Assert.assertTrue
import org.junit.Test

/** La preselezione geografica degli hub: tiene i sensati, scarta gli assurdi. */
class HubAVTest {
    // coordinate approssimate
    private val sorrento = doubleArrayOf(40.6258, 14.3797)
    private val roma = doubleArrayOf(41.9010, 12.5015)      // Termini
    private val torino = doubleArrayOf(45.0620, 7.6785)     // Porta Nuova
    private val milano = doubleArrayOf(45.4872, 9.2049)     // Centrale
    private val napoli = doubleArrayOf(40.8523, 14.2720)    // Centrale

    private fun hub(a: DoubleArray, c: DoubleArray) =
        HubAV.candidati(a[0], a[1], c[0], c[1]).mapNotNull { it.rfi }

    @Test
    fun `Sorrento-Roma propone Napoli`() {
        val h = hub(sorrento, roma)
        println("\n=== Sorrento->Roma hub: $h ===")
        assertTrue("Napoli (S09218/S09988) deve essere fra gli hub", h.any { it == "S09218" || it == "S09988" })
    }

    @Test
    fun `Torino-Milano non passa da Napoli`() {
        val h = hub(torino, milano)
        println("=== Torino->Milano hub: $h ===")
        assertTrue("Napoli non c'entra niente", h.none { it == "S09218" || it == "S09988" })
    }

    @Test
    fun `Sorrento-Milano propone Napoli o Roma, non Bari`() {
        val h = hub(sorrento, milano)
        println("=== Sorrento->Milano hub: $h ===")
        assertTrue("un hub sulla direttrice tirrenica", h.isNotEmpty())
        assertTrue("Bari e' fuori strada", h.none { it == "S11119" })
    }

    @Test
    fun `una tratta gia servita da Italo a entrambi i capi non si auto-propone`() {
        // Napoli->Roma: entrambe hub Italo. Non deve proporre se stesse come cambio.
        val h = hub(napoli, roma)
        println("=== Napoli->Roma hub: $h ===")
        assertTrue("gli estremi non sono punti di cambio", h.none { it == "S09218" })
    }
}
