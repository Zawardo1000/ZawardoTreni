package it.zawardo.treni

import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.viaggiatreno.AndamentoTrenoDto
import it.zawardo.treni.data.remote.viaggiatreno.FermataDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.binarioCambiato
import it.zawardo.treni.domain.model.binarioConfermato
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.stessoBinario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Un binario si scrive in un modo solo, da qualunque fonte arrivi.
 *
 * Segnalazione con lo screenshot davanti: il REG 24526 del 07/09/2026, aperto
 * in corsa, dava a Melzo "bin. 2" barrato seguito da un "II" in evidenza. Non
 * era un cambio di binario, era la stessa banchina scritta due volte in due
 * modi.
 *
 * Sondando le API quel giorno si e' visto che il caso segnalato era il piu'
 * piccolo dei tre, e che non serve nemmeno che le fonti siano due:
 *
 *  - **Napoli Centrale**, tabellone ViaggiaTreno: binario programmato in cifre
 *    romane ("XV", "XIV", "IX", "II") ed effettivo in cifre arabe ("15", "13",
 *    "3"), sulle stesse corse. Li' *ogni* treno con il binario assegnato
 *    dichiarava un cambio mai avvenuto.
 *  - **Torino Porta Nuova**: un "XVII" in mezzo a tredici numeri arabi, dallo
 *    stesso campo della stessa risposta.
 *  - **Milano Centrale**, dentro `andamentoTreno`: i binari tronchi scritti in
 *    cinque modi — "1 Tronco OVEST", "2 Tronco Ovest", "2 TR Ovest", "IITR O",
 *    "IItr" — piu' un "Iw".
 *
 * I valori usati qui sotto sono quelli veri, presi da quel sondaggio.
 */
class BinarioNotazioneTest {

    private fun fermata(
        programmatoPartenza: String? = null,
        effettivoPartenza: String? = null,
        programmatoArrivo: String? = null,
        effettivoArrivo: String? = null,
    ): Stop = AndamentoTrenoDto(
        numeroTreno = 24526,
        fermate = listOf(
            FermataDto(
                stazione = "MELZO",
                id = "S01331",
                progressivo = 1,
                binarioProgrammatoPartenzaDescrizione = programmatoPartenza,
                binarioEffettivoPartenzaDescrizione = effettivoPartenza,
                binarioProgrammatoArrivoDescrizione = programmatoArrivo,
                binarioEffettivoArrivoDescrizione = effettivoArrivo,
            ),
        ),
    ).toTrainStatus().stops.first()

    private fun riga(programmato: String?, effettivo: String?) = BoardEntry(
        trainRef = TrainRef("24526", "S01700", 0),
        label = "REG 24526",
        category = "REG",
        direction = "VARESE",
        scheduledTime = "08:58",
        delayMinutes = 0,
        scheduledPlatform = programmato,
        actualPlatform = effettivo,
        state = TrainState.REGULAR,
        inStation = false,
    )

    // ------------------------------------------------- romano contro arabo

    @Test
    fun `il binario II e il binario 2 sono lo stesso binario`() {
        assertTrue(stessoBinario("2", "II"))
        assertTrue(stessoBinario("II", "2"))
        assertFalse("il 2 e il 3 restano due binari diversi", stessoBinario("2", "III"))
    }

    @Test
    fun `una cifra romana e una araba non sono un cambio di binario`() {
        val melzo = fermata(programmatoPartenza = "2", effettivoPartenza = "II")
        assertFalse("e' la stessa banchina scritta in due modi", melzo.platformChanged)
        assertEquals("2", melzo.platform)

        // E la stessa regola vale sul tabellone, che ha la sua copia del confronto.
        assertFalse(riga("2", "II").platformChanged)
    }

    @Test
    fun `un cambio vero resta un cambio anche fra notazioni diverse`() {
        assertTrue(fermata(programmatoPartenza = "1", effettivoPartenza = "II").platformChanged)
        assertTrue(fermata(programmatoPartenza = "IV", effettivoPartenza = "5").platformChanged)
    }

    @Test
    fun `la cifra romana sparisce anche dallo schermo`() {
        // In stazione i cartelli sono in cifre arabe: se il binario lo dice una
        // fonte sola, e' comunque quella grafia che deve uscire, o la stessa
        // stazione si leggerebbe in due modi da una corsa all'altra.
        assertEquals("2", fermata(effettivoPartenza = "II").platform)
        assertEquals("14", riga(null, "XIV").platform)
    }

    @Test
    fun `Napoli Centrale, come lo dava il tabellone`() {
        // Programmato in romano ed effettivo in arabo, dalla stessa risposta:
        // senza questa regola ogni corsa di quella stazione, appena assegnato il
        // binario, avrebbe annunciato un cambio mai avvenuto.
        assertFalse(riga("XV", "15").platformChanged)
        assertFalse(riga("XIV", "14").platformChanged)
        assertTrue("un cambio vero li' resta visibile", riga("XV", "13").platformChanged)
    }

    @Test
    fun `le cifre romane che una stazione puo' avere`() {
        assertEquals("4", binarioPulito("IV"))
        assertEquals("9", binarioPulito("ix"))
        assertEquals("13", binarioPulito("XIII"))
        assertEquals("24", binarioPulito("XXIV"))
    }

    // ------------------------------------------------- i binari tronchi

    @Test
    fun `i cinque modi di scrivere lo stesso binario tronco`() {
        // Tutti visti nella stessa risposta, per la stessa stazione, lo stesso
        // giorno: "2 Tronco Ovest", "2 TR Ovest", "IITR O".
        val scritture = listOf("2 Tronco Ovest", "2 TR Ovest", "IITR O", "II tronco ovest")
        scritture.forEach { assertEquals(it, "2 tronco ovest", binarioPulito(it)) }

        val fermata = fermata(programmatoPartenza = "2 TR Ovest", effettivoPartenza = "IITR O")
        assertFalse("e' lo stesso binario tronco", fermata.platformChanged)
        assertEquals("2 tronco ovest", fermata.platform)
    }

    @Test
    fun `il tronco resta un binario diverso da quello di corsa`() {
        // Qui il rischio sarebbe l'opposto: fondere due binari veri.
        assertFalse(stessoBinario("1", "1 tronco"))
        assertFalse(stessoBinario("1 tronco ovest", "1 tronco est"))
        assertTrue(fermata(programmatoPartenza = "1", effettivoPartenza = "1 Tronco OVEST").platformChanged)
    }

    @Test
    fun `si stacca la qualifica conosciuta, non ogni lettera attaccata a un numero`() {
        assertEquals("2 tronco", binarioPulito("IItr"))
        assertEquals("1 ovest", binarioPulito("Iw"))

        // "1B" e' un binario che si chiama cosi': la B non e' una qualifica.
        assertEquals("1B", binarioPulito("1B"))
        assertEquals("1 Bis", binarioPulito("  1   Bis "))
    }

    // --------------------------------------------------- il binario assente

    @Test
    fun `un binario vuoto e' un binario assente`() {
        assertNull(binarioPulito(""))
        assertNull(binarioPulito("   "))

        val muta = fermata(programmatoPartenza = "", effettivoPartenza = "")
        assertNull(muta.platform)
        assertFalse("due vuoti non sono un cambio di binario", muta.platformChanged)
    }

    @Test
    fun `al capolinea vale il binario di arrivo, che e' l'unico che esista`() {
        // Al capolinea d'arrivo il binario sta nei campi dell'arrivo: il ripiego
        // deve scattare, e deve scattare anche se il campo della partenza c'e'
        // ma non dice niente.
        assertEquals("5", fermata(programmatoArrivo = "5").platform)
        assertEquals("5", fermata(programmatoPartenza = "  ", programmatoArrivo = "5").platform)

        val cambio = fermata(programmatoArrivo = "V", effettivoArrivo = "6")
        assertTrue(cambio.platformChanged)
        assertEquals("6", cambio.platform)
    }

    @Test
    fun `la fermata resta quella che era`() {
        // Il resto del mapping non lo tocca nessuno: qui si guarda solo il binario.
        val melzo = fermata(programmatoPartenza = "2")
        // Il nome passa per `nomeLeggibile`: ViaggiaTreno lo manda in maiuscolo.
        assertEquals("Melzo", melzo.stationName)
        assertEquals(StopStatus.FUTURE, melzo.status)
    }

    // ------------------------------------------------- il binario confermato

    /**
     * L'altra meta' del cambio di binario, e finora non si vedeva: "4" previsto
     * e "4" assegnato si leggeva identico a "4" e basta.
     */
    @Test
    fun `il binario confermato e' quello annunciato che si ripete`() {
        assertTrue(binarioConfermato("4", "4"))
        // "II" e "2" sono la stessa banchina, quindi una conferma e non un cambio.
        assertTrue(binarioConfermato("II", "2"))
        assertFalse(binarioCambiato("II", "2"))

        assertTrue(fermata(programmatoPartenza = "2", effettivoPartenza = "II").platformConfirmed)
    }

    @Test
    fun `un binario cambiato non e' confermato, e viceversa`() {
        assertFalse(binarioConfermato("4", "7"))
        assertTrue(binarioCambiato("4", "7"))

        val cambio = fermata(programmatoPartenza = "4", effettivoPartenza = "7")
        assertTrue(cambio.platformChanged)
        assertFalse(cambio.platformConfirmed)
    }

    /**
     * L'effettivo basta anche da solo: Italo, EAV, Ferrotramviaria e Trenord
     * sulle FNM ne pubblicano uno senza programmato. Deciso l'11/09/2026: prima
     * servivano due letture, e quel binario restava nero come uno solo previsto.
     *
     * Il programmato da solo invece resta una previsione, e non si conferma.
     */
    @Test
    fun `l'effettivo da solo e' confermato, il programmato da solo no`() {
        assertTrue(binarioConfermato(null, "4"))
        assertTrue(fermata(effettivoPartenza = "4").platformConfirmed)

        assertFalse(binarioConfermato("4", null))
        assertFalse(binarioConfermato("4", "  "))
        assertFalse(binarioConfermato(null, null))
    }

    // ------------------------------------------------------- l'I con l'apice

    /**
     * "I'" non e' "I", e quindi nemmeno "1".
     *
     * Comparso nelle risposte di ViaggiaTreno l'11/09/2026; deciso con l'utente
     * che resta un binario a se'. L'apice gli impedisce di passare per cifra
     * romana, e questo test tiene ferma la scelta: se un giorno `binarioPulito`
     * lo riducesse a "1", l'I' e l'I diventerebbero la stessa banchina senza che
     * nessuno l'abbia deciso.
     */
    @Test
    fun `l'I con l'apice resta un binario a se'`() {
        assertEquals("I'", binarioPulito("I'"))
        assertEquals("1", binarioPulito("I"))
        assertFalse(stessoBinario("I", "I'"))
        assertFalse(stessoBinario("1", "I'"))
    }
}
