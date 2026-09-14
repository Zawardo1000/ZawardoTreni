package it.zawardo.treni

import it.zawardo.treni.domain.model.binarioCambiato
import it.zawardo.treni.domain.model.binarioConfermato
import it.zawardo.treni.domain.model.binarioDaMostrare
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

    @Test
    fun `un numero diverso nello stesso piazzale resta un cambio`() {
        // Il 9618 dello stesso giorno: dal 17 AV al 16 AV.
        assertTrue(binarioCambiato("17 AV", "16 AV"))
        assertEquals("16 AV", binarioDaMostrare("17 AV", "16 AV"))
    }

    @Test
    fun `da solo, il piazzale resta quello che c'e'`() {
        assertEquals("AV", binarioDaMostrare(null, "AV"))
        assertTrue(binarioConfermato(null, "AV"))
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
}
