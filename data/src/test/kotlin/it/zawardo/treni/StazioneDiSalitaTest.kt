package it.zawardo.treni

import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.comeDistinguerlaDa
import it.zawardo.treni.domain.model.nomeDelCambio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Accanto al binario, la stazione a cui e' quando non e' quella cercata: vedi
 * `comeDistinguerlaDa`. Nomi e codici come li danno Le Frecce e Trenord.
 */
class StazioneDiSalitaTest {

    private val garibaldi = Station("S01645", 830001645, "Milano Porta Garibaldi")
    private val passante = Station("S01647", 830001662, "Milano Porta Garibaldi Passante")

    @Test
    fun `cercando la superficie, il Passante si chiama Passante`() {
        assertEquals("Passante", passante.comeDistinguerlaDa(garibaldi))
    }

    @Test
    fun `cercando il Passante, la superficie si chiama col nome intero`() {
        assertEquals("Milano Porta Garibaldi", garibaldi.comeDistinguerlaDa(passante))
    }

    @Test
    fun `la stessa stazione non si distingue`() {
        assertNull(garibaldi.comeDistinguerlaDa(garibaldi))
        // Trenord la scrive in maiuscolo, e il codice puo' mancare: resta la stessa.
        assertNull(Station(null, 0, "MILANO PORTA GARIBALDI").comeDistinguerlaDa(garibaldi))
        // Bologna Centrale ha due codici, ed e' una stazione sola.
        assertNull(Station("S05046", 0, "Bologna C.le/AV").comeDistinguerlaDa(Station("S05043", 830005043, "Bologna Centrale")))
    }

    @Test
    fun `le altre coppie, e il cambio in metropolitana`() {
        assertEquals("Nord", Station("S01738", 830025251, "Varese Nord").comeDistinguerlaDa(Station("S01205", 830001205, "Varese")))
        assertEquals(
            "Piazza Garibaldi",
            Station("S09109", 830009109, "Napoli Piazza Garibaldi").comeDistinguerlaDa(Station("S09218", 830009218, "Napoli Centrale")),
        )
        assertEquals("Lambrate", Station("S01701", 830001701, "Milano Lambrate").comeDistinguerlaDa(Station("S01700", 830001700, "Milano Centrale")))
    }

    /** Il nome del cambio: dove si scende, e dove si risale se e' un'altra stazione. */
    @Test
    fun `il cambio fra due gemelle porta tutti e due i nomi`() {
        val eav = Station("EAV3", 9_000_000_003L, "Napoli P. Garibaldi")
        val centrale = Station("S09218", 830009218, "Napoli Centrale")
        assertEquals("Napoli P. Garibaldi › Centrale", nomeDelCambio(eav, centrale))
        assertEquals("Milano Porta Garibaldi › Passante", nomeDelCambio(garibaldi, passante))
        assertEquals("Milano Porta Garibaldi Passante › Milano Porta Garibaldi", nomeDelCambio(passante, garibaldi))
        assertEquals("Milano Porta Garibaldi", nomeDelCambio(garibaldi, garibaldi))
    }
}
