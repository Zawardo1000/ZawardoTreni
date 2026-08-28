package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.domain.model.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * I viaggi **diretti su reti fuori-RFI**, quelli che il BFF non sa comporre e che
 * l'app costruisce dall'orario imbarcato: EAV e ARST, una gamba sola, nessun
 * cambio. E' la strada che [it.zawardo.treni.ui.results] percorre quando
 * partenza e arrivo stanno sulla stessa rete non nazionale.
 *
 * Offline e deterministico: legge gli orari imbarcati, non la rete. Se un domani
 * il refactor dei suggerimenti (registro delle fonti) toccasse per sbaglio anche
 * gli itinerari, qui si vedrebbe.
 */
class DirettiFuoriRfiTest {

    private val eav = EavRepository(NetworkModule.eavApi)
    private val arst = ArstRepository()

    @Test
    fun `EAV compone il diretto Sorrento - Porta Nolana`() {
        val corse = eav.itinerario("EAV62", "EAV1", LocalDate.now())
        assertTrue("la Circumvesuviana Sorrento-Napoli deve avere corse", corse.isNotEmpty())
        val prima = corse.first()
        assertEquals("un diretto ha una gamba sola", 1, prima.legs.size)
        assertEquals("la gamba e' EAV", DataSource.EAV, prima.legs.first().source)
        assertTrue("parte prima di arrivare", prima.arrival.isAfter(prima.departure))
    }

    @Test
    fun `ARST compone il diretto Sassari - Alghero, oggi e domani`() {
        val oggi = arst.itinerario("ARST22602", "ARST22561", LocalDate.now())
        val domani = arst.itinerario("ARST22602", "ARST22561", LocalDate.now().plusDays(1))
        assertTrue("Sassari-Alghero oggi", oggi.isNotEmpty())
        // ARST e' l'unica rete che risponda anche per i giorni futuri.
        assertTrue("Sassari-Alghero domani", domani.isNotEmpty())
        val prima = oggi.first()
        assertEquals("un diretto ha una gamba sola", 1, prima.legs.size)
        assertEquals("la gamba e' ARST", DataSource.ARST, prima.legs.first().source)
    }

    @Test
    fun `fuori dalla propria rete un itinerario non si compone`() {
        // Codici RFI veri: EAV e ARST non devono rispondere per stazioni altrui.
        assertTrue(eav.itinerario("S08409", "S01700", LocalDate.now()).isEmpty())
        assertTrue(arst.itinerario("S08409", "S01700", LocalDate.now()).isEmpty())
    }
}
