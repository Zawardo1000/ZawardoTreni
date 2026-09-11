package it.zawardo.treni

import it.zawardo.treni.domain.model.nomeLeggibile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I nomi di ViaggiaTreno, dal maiuscolo alla forma in cui si scrivono.
 *
 * Gli esempi sono nomi veri, presi dalle risposte o dai punti di rilevamento
 * visti passare: ognuno presidia una regola.
 */
class NomiStazioneTest {

    private val esempi = mapOf(
        "VENEZIA S.LUCIA" to "Venezia S.Lucia",
        "PESCHIERA DEL GARDA" to "Peschiera del Garda",
        "CASSANO D'ADDA" to "Cassano d'Adda",
        "SANT'ILARIO D'ENZA" to "Sant'Ilario d'Enza",
        "L'AQUILA" to "L'Aquila",
        "LA SPEZIA CENTRALE" to "La Spezia Centrale",
        "CUZZAGO (MI)" to "Cuzzago (MI)",
        "MILANO P.TA GARIBALDI" to "Milano P.ta Garibaldi",
        "BOLOGNA C.LE" to "Bologna C.le",
        "FIRENZE S.M.N." to "Firenze S.M.N.",
        "REGGIO EMILIA AV MEDIOPADANA" to "Reggio Emilia AV Mediopadana",
        "BV/PC SETTEBAGNI" to "BV/PC Settebagni",
        "1° BIVIO CHIUSI SUD" to "1° Bivio Chiusi Sud",
        "BOLZANO/BOZEN" to "Bolzano/Bozen",
        "MUSIANO-PIAN DI MACINA" to "Musiano-Pian di Macina",
        "BERGAMO OSPEDALE PAPA GIOVANNI XXIII" to "Bergamo Ospedale Papa Giovanni XXIII",
        "CANTU'" to "Cantu'",
        "MILANO CENTRALE" to "Milano Centrale",
    )

    @Test
    fun `i nomi in maiuscolo prendono la forma scritta`() {
        esempi.forEach { (grezzo, atteso) -> assertEquals(grezzo, atteso, nomeLeggibile(grezzo)) }
    }

    /**
     * La garanzia che conta: nessuna lettera cambia, solo le maiuscole. Le fonti
     * si confrontano ignorando le maiuscole, e un carattere in piu' o in meno
     * farebbe smettere di combaciare due fermate che sono la stessa.
     */
    @Test
    fun `cambiano solo le maiuscole`() {
        esempi.keys.forEach { grezzo ->
            assertEquals(grezzo, grezzo.uppercase(), nomeLeggibile(grezzo).uppercase())
        }
    }

    @Test
    fun `un nome gia' scritto a mano resta com'e'`() {
        listOf("Milano Centrale", "Roma Termini", "Lugano", "Brescia Borgo San Giovanni", "Domodossola FS")
            .forEach { assertEquals(it, nomeLeggibile(it)) }
    }

    @Test
    fun `stringa vuota`() {
        assertEquals("", nomeLeggibile(""))
    }
}
