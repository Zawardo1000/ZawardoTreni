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
 * I ritardi EAV contro il servizio vero, da tutt'e due le fonti.
 *
 * EAV non ha un servizio solo che dica come va una corsa, ne ha due che si
 * reggono a vicenda. Il **pianificatore** sa il ritardo lungo tutto il percorso
 * ed e' la fonte principale; il **monitor** parla della stazione che guardi e
 * basta, che per l'elenco dei treni ancora prendibili e' pero' esattamente la
 * stazione da cui sali.
 *
 * Cadono a turno, e l'hanno fatto a due giorni di distanza: il 20/09/2026
 * `orariotreni.eavsrl.it` dava 503 su tutto, home compresa, e reggeva il
 * pianificatore; il 22/09/2026 il pianificatore rispondeva 200 con
 * `CorsePercorso` vuoto — su ogni tratta e ogni data provata, dal 21 al 24,
 * mentre il monitor mostrava le partenze da Porta Nolana come sempre — e
 * reggeva il monitor. Se tacessero tutt'e due, dei ritardi EAV non resterebbe
 * niente e l'app tornerebbe a dire solo l'orario di tabella: giusto, ma meno, e
 * senza che nessuno se ne accorga. E' quello che questi test non lasciano
 * passare in silenzio.
 */
class EavPianificatoreLiveTest {

    private val eav = EavRepository(NetworkModule.eavApi)

    /** Napoli Porta Nolana e Sorrento: i due capi della Circumvesuviana. */
    private val portaNolana = "EAV1"
    private val sorrento = "EAV62"

    /**
     * **L'allarme e' che tacciano tutte e due**, non che ne taccia una.
     *
     * Le due fonti EAV cadono a turno: il 20/09/2026 il monitor dava 503 su
     * tutto e reggeva il pianificatore; il 22/09/2026 il pianificatore
     * rispondeva 200 con `CorsePercorso` vuoto — su ogni tratta e ogni data
     * provata — e reggeva il monitor. Finche' ne risponde una, i ritardi EAV si
     * sanno, ed e' questo che il test difende. Quale delle due abbia risposto lo
     * stampa, cosi' si vede a colpo d'occhio quando una torna o se ne va.
     */
    @Test
    fun `i ritardi EAV di adesso si sanno, da una delle due fonti`() = runBlocking {
        val adesso = LocalDateTime.now()
        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, adesso)
        println("\n=== RITARDI EAV (Porta Nolana → Sorrento, ${adesso.toLocalTime().withNano(0)}) ===")
        val fonti = ritardi.values.map { it.fonte }.distinct()
        println("  fonte che ha risposto: ${fonti.joinToString { it.comeSiChiama }.ifBlank { "nessuna" }}")
        ritardi.forEach { (numero, r) -> println("  $numero: ${r.minuti} min${if (r.soppressa) ", soppressa" else ""}") }
        assumeTrue("a quest'ora la Circumvesuviana e' ferma", !notteFonda() || ritardi.isNotEmpty())
        assertTrue(
            "ne' il pianificatore ne' il monitor hanno risposto: dei ritardi EAV non resta niente",
            ritardi.isNotEmpty(),
        )
        assertEquals("una risposta sola non puo' venire da due fonti diverse", 1, fonti.size)
        ritardi.forEach { (numero, r) ->
            assertTrue("numero di treno illeggibile: $numero", numero.isNotBlank() && numero.all(Char::isDigit))
            assertTrue("ritardo fuori scala per il $numero: ${r.minuti}", r.minuti in -5..240)
        }
    }

    @Test
    fun `la corsa aperta porta il ritardo, e lo dichiara`() = runBlocking {
        val adesso = LocalDateTime.now()
        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, adesso)
        assumeTrue("nessuna corsa da nessuna delle due fonti EAV adesso", ritardi.isNotEmpty())

        // Una corsa di oggi che l'orario imbarcato conosce: le due fonti si
        // parlano per numero, ed e' l'unico aggancio che hanno.
        val numero = ritardi.keys.firstOrNull { eav.dettaglioCorsa(it) != null }
        assumeTrue("nessuna delle corse trovate e' nell'orario imbarcato", numero != null)

        val senza = eav.dettaglioCorsa(numero!!)!!
        val con = eav.dettaglioCorsa(numero, LocalDate.now(), portaNolana, sorrento)!!
        val suo = ritardi.getValue(numero)
        println("  $numero: ${suo.fonte.comeSiChiama} ${suo.minuti} min, corsa ${con.delayMinutes} min, realtime ${con.realtime}")

        assertTrue("senza le due stazioni resta l'orario di tabella", !senza.realtime)
        assertEquals("il ritardo della corsa e' quello della fonte", suo.minuti, con.delayMinutes)
        assertTrue("un ritardo conosciuto va dichiarato", con.realtime)
        // L'avviso nomina la fonte che ha risposto davvero, non una delle due a caso.
        assertTrue("e va detto da dove viene", con.notice?.contains(suo.fonte.comeSiChiama) == true)
        assertEquals("le fermate restano quelle dell'orario", senza.stops.size, con.stops.size)
        if (suo.minuti > 0) {
            assertTrue(
                "col ritardo gli orari delle fermate future si spostano",
                con.stops.any { it.projectedDeparture != null || it.projectedArrival != null },
            )
        }
    }

    /**
     * Il ritardo deve arrivare **fino alla riga dell'elenco**, non fermarsi nel
     * repository.
     *
     * E' la catena intera: la fonte che risponde, `conRitardi` che accoppia per
     * numero di treno, e il `Journey` che esce con un ritardo invece che con
     * nulla. Se si spezzasse in mezzo, l'elenco EAV tornerebbe a essere solo
     * orario senza che nessun altro test se ne accorga — e con lui sparirebbe
     * la regola dei treni ancora prendibili, che su EAV vive di questo ritardo.
     *
     * Conta che il campo sia **valorizzato**, non che sia diverso da zero: una
     * corsa in orario e' una notizia quanto una in ritardo, ed e' la differenza
     * fra «so che va bene» e «non so niente».
     */
    @Test
    fun `il ritardo arriva fino alle corse dell'elenco`() = runBlocking {
        val adesso = LocalDateTime.now()
        val ritardi = eav.ritardiFraStazioni(portaNolana, sorrento, adesso)
        assumeTrue("nessuna corsa da nessuna delle due fonti EAV adesso", ritardi.isNotEmpty())

        val corse = eav.itinerario(portaNolana, sorrento)
        assumeTrue("l'orario imbarcato non ha corse Porta Nolana-Sorrento oggi", corse.isNotEmpty())
        val conRitardi = eav.conRitardi(corse, portaNolana, sorrento, adesso)

        val note = conRitardi.filter { it.delayMinutes != null }
        println("\n=== EAV: ${note.size} corse su ${conRitardi.size} col ritardo, da ${ritardi.values.first().fonte.comeSiChiama} ===")
        note.take(5).forEach { println("  ${it.legs.first().trainNumber}: ${it.delayMinutes} min${if (it.cancelled) ", soppressa" else ""}") }
        assertTrue(
            "nessuna corsa dell'elenco ha preso il ritardo: la catena fonte -> conRitardi -> riga e' rotta",
            note.isNotEmpty(),
        )
    }

    @Test
    fun `per un altro giorno nessuna delle due fonti promette ritardi`() = runBlocking {
        val domani = LocalDate.now().plusDays(1).atTime(9, 0)
        assertEquals(emptyMap<String, EavRepository.RitardoEav>(), eav.ritardiFraStazioni(portaNolana, sorrento, domani))
        // E fuori dalla rete non si chiede niente.
        assertEquals(
            emptyMap<String, EavRepository.RitardoEav>(),
            eav.ritardiFraStazioni("S01700", sorrento, LocalDateTime.now()),
        )
    }
}
