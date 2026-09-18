package it.zawardo.treni

import it.zawardo.treni.domain.model.binarioCambiato
import it.zawardo.treni.domain.model.binarioConfermato
import it.zawardo.treni.domain.model.binarioDaMostrare
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.stessoBinario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Altre grafie dello stesso binario, trovate sondando i tabelloni il 14/09/2026.
 *
 * Il seguito di [BinarioNotazioneTest]: la stessa famiglia di errori — una
 * banchina scritta in due modi letta come un cambio di binario — su stazioni
 * che quel sondaggio non aveva toccato.
 */
class BinariPiazzaliTest {

    /**
     * Bologna Centrale: le Frecce della stazione AV sotterranea avevano "19 AV"
     * programmato e soltanto "AV" effettivo, e ognuna usciva in rosso come
     * "AV, nuovo: era 19 AV".
     */
    @Test
    fun `l'effettivo che dice solo il piazzale non e' un cambio`() {
        assertFalse(binarioCambiato("19 AV", "AV"))
        assertEquals("19 AV", binarioDaMostrare("19 AV", "AV"))
        assertFalse("il numero non l'ha ripetuto nessuno", binarioConfermato("19 AV", "AV"))
    }

    /**
     * Il FR 9645 per Roma Termini, la sera dello stesso giorno: il dettaglio della
     * corsa dava "19" senza "AV", il tabellone "AV" senza "19". Unite, uscivano
     * come "AV, nuovo: era 19".
     */
    @Test
    fun `il piazzale non contraddice un numero, anche scritto altrove`() {
        assertFalse(binarioCambiato("19", "AV"))
        assertEquals("19", binarioDaMostrare("19", "AV"))
        assertFalse(binarioConfermato("19", "AV"))
    }

    /**
     * Il FR 9642: "AV" programmato, "16 AV" effettivo. E' la prima assegnazione,
     * non un cambio: si legge confermato.
     */
    @Test
    fun `il piazzale programmato non fa sembrare cambiata la prima assegnazione`() {
        assertFalse(binarioCambiato("AV", "16 AV"))
        assertTrue(binarioConfermato("AV", "16 AV"))
        assertEquals("16 AV", binarioDaMostrare("AV", "16 AV"))
    }

    @Test
    fun `un numero diverso nello stesso piazzale resta un cambio`() {
        // Il 9618 dello stesso giorno: dal 17 AV al 16 AV.
        assertTrue(binarioCambiato("17 AV", "16 AV"))
        assertEquals("16 AV", binarioDaMostrare("17 AV", "16 AV"))
    }

    /**
     * Il FR 9584: " AV" in tutti e due i campi, due ore prima della partenza. Si
     * mostra — e' tutto quel che si sa — ma non e' confermato niente.
     */
    @Test
    fun `il piazzale da solo si mostra, nero`() {
        assertEquals("AV", binarioDaMostrare("AV", "AV"))
        assertFalse(binarioConfermato("AV", "AV"))
        assertEquals("AV", binarioDaMostrare(null, "AV"))
        assertFalse(binarioConfermato(null, "AV"))
        assertFalse(binarioCambiato("AV", "AV"))
    }

    /** Venezia Santa Lucia: "1 N" nelle partenze, "1N" negli arrivi. */
    @Test
    fun `lo spazio fra numero e lettera non fa un binario diverso`() {
        assertTrue(stessoBinario("1 N", "1N"))
        assertFalse(binarioCambiato("1 N", "1N"))
        assertTrue(binarioConfermato("1 N", "1N"))
    }

    /** Roma Termini: "20 BIS", segnalato da `BinariLiveTest` come grafia sconosciuta. */
    @Test
    fun `il binario bis e' un binario, in qualunque modo lo si scriva`() {
        assertEquals("20 bis", binarioDaMostrare("20 BIS", null))
        assertTrue(stessoBinario("20 BIS", "20bis"))
        assertFalse(stessoBinario("20 BIS", "20"))
    }

    /**
     * Bologna Centrale: il 17822 aveva "3 EST" programmato e "III-EST" effettivo,
     * il 17839 "2 EST" e "II-EST". Lo stesso binario, e col trattino attaccato
     * l'app ci leggeva un cambio.
     */
    @Test
    fun `il trattino fra numero e piazzale non fa un binario diverso`() {
        assertEquals("3 est", binarioPulito("III-EST"))
        assertEquals("2 est", binarioPulito("II-EST"))
        assertFalse(binarioCambiato("3 EST", "III-EST"))
        assertFalse(binarioCambiato("2 EST", "II-EST"))
        assertTrue(binarioConfermato("2 EST", "II-EST"))
    }

    /**
     * Salerno, 18/09/2026: il REG 5959 in arrivo al "7 Sud"; a Ivrea il REG 2736
     * ripartiva dal "1 NORD". La parola si legge, e il numero nudo resta un altro
     * binario: la regola di sempre.
     */
    @Test
    fun `sud e nord sono qualifiche come est e ovest`() {
        assertEquals("7 sud", binarioPulito("7 Sud"))
        assertEquals("1 nord", binarioPulito("1 NORD"))
        assertTrue(stessoBinario("7 Sud", "7 SUD"))
        assertFalse("il numero nudo e' un altro binario", stessoBinario("7 Sud", "7"))
    }

    /**
     * Il piazzale ovest di Bologna scritto "PO": il 19595 aveva "2 OVEST"
     * programmato e "VI-PO" effettivo, un cambio vero dal 2 al 6.
     */
    @Test
    fun `PO e' il piazzale ovest`() {
        assertEquals("4 ovest", binarioPulito("IV-PO"))
        assertTrue(binarioCambiato("2 OVEST", "VI-PO"))
        assertTrue(stessoBinario("6 OVEST", "VI-PO"))
        assertTrue(binarioConfermato("1 OVEST", "I-PO"))
    }

    @Test
    fun `un trattino fra due cifre resta com'e'`() {
        assertEquals("1-2", binarioPulito("1-2"))
    }
}
