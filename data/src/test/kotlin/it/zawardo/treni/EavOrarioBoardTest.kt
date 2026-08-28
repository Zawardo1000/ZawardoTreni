package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.EavRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * EAV dove il tabellone non arriva.
 *
 * Il tabellone copre oggi e le sole stazioni con un monitor. Questi test
 * verificano l'altra meta': che per i giorni futuri e per le stazioni delle
 * altre reti EAV risponda l'orario imbarcato, e che lo dichiari.
 */
class EavOrarioBoardTest {

    private val eav = EavRepository(NetworkModule.eavApi)

    /** Napoli Porta Nolana: tabellone e orario, il caso pieno. */
    private val portaNolana = "EAV1"

    /** Piedimonte Matese: nell'orario, senza monitor. */
    private val piedimonte = "EAV430"

    @Test
    fun `per domani risponde l'orario, e dice che non e' tempo reale`() = runBlocking {
        val domani = LocalDate.now().plusDays(1)
        val righe = eav.board(portaNolana, date = domani)
        println("\n=== PORTA NOLANA DOMANI ($domani): ${righe.size} corse ===")
        righe.take(6).forEach {
            println("  ${it.scheduledTime}  ${it.trainRef.number}  -> ${it.direction}  realtime=${it.realtime}")
        }
        assertTrue("domani non risponde nessuno", righe.isNotEmpty())
        assertTrue(
            "una riga d'orario si e' spacciata per tempo reale",
            righe.none { it.realtime },
        )
        assertTrue(
            "ci sono orari fuori scala: le corse oltre la mezzanotte non sono state riportate",
            righe.all { (it.scheduledTime ?: "00:00") < "24:00" },
        )
    }

    @Test
    fun `una stazione senza monitor risponde comunque dall'orario`() = runBlocking {
        val righe = eav.board(piedimonte, date = LocalDate.now())
        println("\n=== PIEDIMONTE MATESE OGGI: ${righe.size} corse ===")
        righe.take(6).forEach {
            println("  ${it.scheduledTime}  ${it.trainRef.number}  -> ${it.direction}  realtime=${it.realtime}")
        }
        assertTrue("nessuna risposta per una stazione dell'orario", righe.isNotEmpty())
        assertTrue("dovrebbe essere tutto non-realtime", righe.none { it.realtime })
        assertTrue("il tabellone non esiste qui", !eav.hasBoard(piedimonte))
        assertTrue("l'orario invece si'", eav.canPlan(piedimonte))
    }

    @Test
    fun `oggi, dove c'e' il monitor, comanda il tabellone`() = runBlocking {
        val righe = eav.board(portaNolana, date = LocalDate.now())
        println("\n=== PORTA NOLANA OGGI: ${righe.size} corse ===")
        assertTrue("nessuna corsa", righe.isNotEmpty())
        /*
         * Il tabellone porta il tempo reale. Se questo fallisse significherebbe
         * che il monitor non ha risposto e si e' ripiegato sull'orario: non e'
         * un errore del codice, ma va saputo.
         */
        assertTrue(
            "oggi a Porta Nolana ha risposto l'orario invece del tabellone: " +
                "il monitor EAV probabilmente non risponde",
            righe.any { it.realtime },
        )
    }

    @Test
    fun `oltre la copertura dell'orario non si inventa niente`() = runBlocking {
        val oltre = (eav.ultimoGiorno() ?: LocalDate.now()).plusDays(1)
        val righe = eav.board(portaNolana, date = oltre)
        println("\n=== OLTRE LA COPERTURA ($oltre): ${righe.size} corse ===")
        assertTrue("oltre la copertura dovrebbe rispondere vuoto", righe.isEmpty())
    }

    @Test
    fun `l'orario copre parecchi mesi in avanti`() {
        val ultimo = eav.ultimoGiorno()
        println("\n=== EAV: generato ${eav.generato()}, copre fino al $ultimo ===")
        assertTrue("l'orario non dichiara una copertura", ultimo != null)
        assertTrue(
            "l'orario EAV non copre piu' il futuro: va rigenerato",
            ultimo!!.isAfter(LocalDate.now()),
        )
    }
}
