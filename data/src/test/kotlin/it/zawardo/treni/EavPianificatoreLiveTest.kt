package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.EavRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Il pianificatore EAV contro il servizio vero.
 *
 * E' l'unica fonte che dica **come va una corsa** EAV: il monitor parla della
 * stazione che si guarda e basta, e per giunta non sempre risponde — il
 * 20/09/2026 `orariotreni.eavsrl.it` dava 503 su tutto, home compresa, mentre il
 * pianificatore rispondeva come sempre. Se smette anche questo, dei ritardi EAV
 * non resta niente, e l'app torna a dire solo l'orario di tabella: giusto, ma
 * meno, e senza che nessuno se ne accorga.
 */
class EavPianificatoreLiveTest {

    private val eav = EavRepository(NetworkModule.eavApi)

    /** Napoli Porta Nolana e Sorrento: i due capi della Circumvesuviana. */
    private val portaNolana = "EAV1"
    private val sorrento = "EAV62"

    @Test
    fun `il pianificatore dice il ritardo delle corse di adesso`() = runBlocking {
        val adesso = LocalDateTime.now()
        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, adesso)
        println("\n=== EAV PIANIFICATORE (Porta Nolana → Sorrento, ${adesso.toLocalTime().withNano(0)}) ===")
        ritardi.forEach { (numero, r) -> println("  $numero: ${r.minuti} min${if (r.soppressa) ", soppressa" else ""}") }
        assumeTrue("a quest'ora la Circumvesuviana e' ferma", !notteFonda() || ritardi.isNotEmpty())
        assertTrue(
            "il pianificatore non ha risposto nessuna corsa: e' l'ultima fonte di ritardi EAV",
            ritardi.isNotEmpty(),
        )
        ritardi.forEach { (numero, r) ->
            assertTrue("numero di treno illeggibile: $numero", numero.isNotBlank() && numero.all(Char::isDigit))
            assertTrue("ritardo fuori scala per il $numero: ${r.minuti}", r.minuti in -5..240)
        }
    }

    @Test
    fun `la corsa aperta porta il ritardo, e lo dichiara`() = runBlocking {
        val adesso = LocalDateTime.now()
        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, adesso)
        assumeTrue("nessuna corsa dal pianificatore adesso", ritardi.isNotEmpty())

        // Una corsa di oggi che l'orario imbarcato conosce: le due fonti si
        // parlano per numero, ed e' l'unico aggancio che hanno.
        val numero = ritardi.keys.firstOrNull { eav.dettaglioCorsa(it) != null }
        assumeTrue("nessuna delle corse del pianificatore e' nell'orario imbarcato", numero != null)

        val senza = eav.dettaglioCorsa(numero!!)!!
        val con = eav.dettaglioCorsa(numero, LocalDate.now(), portaNolana, sorrento)!!
        val suo = ritardi.getValue(numero)
        println("  $numero: pianificatore ${suo.minuti} min, corsa ${con.delayMinutes} min, realtime ${con.realtime}")

        assertTrue("senza le due stazioni resta l'orario di tabella", !senza.realtime)
        assertEquals("il ritardo della corsa e' quello del pianificatore", suo.minuti, con.delayMinutes)
        assertTrue("un ritardo conosciuto va dichiarato", con.realtime)
        assertTrue("e va detto da dove viene", con.notice?.contains("pianificatore") == true)
        assertEquals("le fermate restano quelle dell'orario", senza.stops.size, con.stops.size)
        if (suo.minuti > 0) {
            assertTrue(
                "col ritardo gli orari delle fermate future si spostano",
                con.stops.any { it.projectedDeparture != null || it.projectedArrival != null },
            )
        }
    }

    @Test
    fun `per un altro giorno il pianificatore non promette ritardi`() = runBlocking {
        val domani = LocalDate.now().plusDays(1).atTime(9, 0)
        assertEquals(emptyMap<String, EavRepository.RitardoEav>(), eav.ritardiFraStazioni(portaNolana, sorrento, domani))
        // E fuori dalla rete non si chiede niente.
        assertEquals(
            emptyMap<String, EavRepository.RitardoEav>(),
            eav.ritardiFraStazioni("S01700", sorrento, LocalDateTime.now()),
        )
    }
}
