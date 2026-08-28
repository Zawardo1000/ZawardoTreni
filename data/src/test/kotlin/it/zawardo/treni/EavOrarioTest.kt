package it.zawardo.treni
import it.zawardo.treni.data.remote.eav.EavOrario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
/**
 * L'orario EAV imbarcato: che ci sia, che si legga, che dica cose vere.
 *
 * Il grosso qui non tocca la rete: l'orario e' una risorsa del modulo, e questi
 * test verificano che il file compilato sia leggibile e coerente. Solo la
 * rigenerazione scarica il feed, e la fa il task `:data:rigeneraOrarioEav`.
 */
class EavOrarioTest {
    @Test
    fun `l'orario imbarcato si carica`() {
        val orario = EavOrario.carica(null)
        assertNotNull("la risorsa /eav-orario.gz non si carica", orario)
        orario!!
        println("\n=== ORARIO EAV IMBARCATO ===")
        println("  generato il ${orario.generato}")
        println("  corse ${orario.corse.size}, copertura fino al ${orario.ultimoGiorno}")
        assertTrue("nessuna corsa nell'orario", orario.corse.isNotEmpty())
        assertTrue(
            "le corse non hanno fermate",
            orario.corse.all { it.fermate.size >= 2 },
        )
    }
    @Test
    fun `le fermate sono stazioni che l'app conosce`() {
        val orario = EavOrario.carica(null) ?: return
        /*
         * Se il GTFS introducesse fermate nuove, qui si vedrebbe: sarebbero
         * codLoc che [EavStations] non sa tradurre, cioe' corse che passano da
         * un posto senza nome.
         */
        val ignote = orario.corse
            .flatMap { it.fermate }
            .map { it.codLoc }
            .distinct()
            .filter { EavStationsProbe.nome(it) == null }
        println("\n=== FERMATE IGNOTE: ${ignote.size} ===")
        ignote.take(10).forEach { println("  codLoc $it") }
        assertTrue("l'orario cita fermate che l'app non conosce: $ignote", ignote.isEmpty())
    }
    @Test
    fun `l'orario copre oggi e le settimane a venire`() {
        val orario = EavOrario.carica(null) ?: return
        val oggi = LocalDate.now()
        val corseOggi = orario.corseDel(oggi)
        println("\n=== CORSE DI OGGI ($oggi): ${corseOggi.size} ===")
        corseOggi.take(5).forEach {
            println("  ${it.numero} linea ${it.linea} -> ${it.destinazione}, ${it.fermate.size} fermate")
        }
        /*
         * Non si pretende che copra oggi per sempre: quando l'orario imbarcato
         * scadra' senza che nessuno lo rigeneri, questo test e' il posto dove
         * ci si accorge che e' ora. Fallisce dicendo esattamente questo.
         */
        assertTrue(
            "l'orario imbarcato non copre piu' oggi: va rigenerato " +
                "(./gradlew :data:rigeneraOrarioEav). Copre fino al ${orario.ultimoGiorno}",
            corseOggi.isNotEmpty(),
        )
    }
    @Test
    fun `le corse passano la mezzanotte senza tornare indietro`() {
        val orario = EavOrario.carica(null) ?: return
        val storte = orario.corse.filter { c ->
            c.fermate.zipWithNext().any { (a, b) -> b.arrivo < a.partenza }
        }
        println("\n=== CORSE CON ORARI NON MONOTONI: ${storte.size} ===")
        storte.take(5).forEach { println("  ${it.numero}") }
        assertTrue(
            "ci sono corse che arrivano prima di essere partite: ${storte.map { it.numero }}",
            storte.isEmpty(),
        )
    }
    @Test
    fun `il formato compatto sopravvive al giro di andata e ritorno`() {
        val orario = EavOrario.carica(null) ?: return
        val rifatto = EavOrario.deserializza(orario.serializza())
        assertNotNull("la riserializzazione non si rilegge", rifatto)
        assertEquals(orario.generato, rifatto!!.generato)
        assertEquals(orario.corse.size, rifatto.corse.size)
        assertEquals(
            orario.corse.first().fermate,
            rifatto.corse.first().fermate,
        )
    }
}
/** Ponte verso l'elenco interno delle stazioni, che il test non vede da fuori. */
private object EavStationsProbe {
    fun nome(codLoc: Int): String? =
        it.zawardo.treni.data.remote.eav.EavStations.byId(codLoc)?.nome
}
