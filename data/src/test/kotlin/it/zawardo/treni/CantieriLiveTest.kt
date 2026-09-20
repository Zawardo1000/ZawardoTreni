package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.TrenordRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate

/**
 * I cantieri di Trenord, contro il servizio vero.
 *
 * Stanno su Yext, che serve i dati di `cantieri.trenord.it`, e si chiedono con
 * la chiave pubblica di lettura scritta in quella pagina. Se quella chiave
 * smette di valere — o cambiano `experienceKey`, `verticalKey` o i nomi dei
 * campi — l'app non si rompe: smette di avvisare dei lavori, in silenzio. Questo
 * test e' il rumore che serve.
 */
class CantieriLiveTest {

    private val trenord = TrenordRepository(
        NetworkModule.trenordApi,
        NetworkModule.json,
        NetworkModule.cantieriApi,
    )

    @Test
    fun `il servizio elenca i cantieri, con periodi, linee e stazioni`() = runBlocking {
        val risposta = NetworkModule.cantieriApi.cantieri()
        val cantieri = risposta.response?.results?.mapNotNull { it.data }.orEmpty()
        println("\n=== CANTIERI TRENORD: ${risposta.response?.resultsCount} ===")
        cantieri.take(5).forEach {
            println(
                "  ${it.name} | ${it.linee.mapNotNull { l -> l.name }} | " +
                    "${it.periodi.map { p -> "${p.dataInizio}…${p.dataFine}" }} | " +
                    "${it.stazioni.size} stazioni",
            )
        }
        assertTrue("Yext non risponde piu' coi cantieri: chiave o chiavi di ricerca cambiate", cantieri.isNotEmpty())
        assertTrue("nessun cantiere ha un nome", cantieri.any { !it.name.isNullOrBlank() })
        assertTrue("nessun cantiere ha un periodo", cantieri.any { it.periodi.isNotEmpty() })
        assertTrue("nessun cantiere elenca le stazioni", cantieri.any { it.stazioni.isNotEmpty() })
        assertTrue(
            "le date non sono piu' `yyyy-MM-dd`",
            cantieri.flatMap { it.periodi }.mapNotNull { it.dataInizio }
                .all { runCatching { LocalDate.parse(it) }.isSuccess },
        )
    }

    @Test
    fun `l'avviso arriva sulla stazione che il cantiere nomina`() = runBlocking {
        val cantieri = NetworkModule.cantieriApi.cantieri().response?.results?.mapNotNull { it.data }.orEmpty()
        val oggi = LocalDate.now()
        // Un cantiere aperto oggi, e una stazione che nomina: l'avviso deve uscire.
        val aperto = cantieri.firstOrNull { cantiere ->
            cantiere.stazioni.any { !it.name.isNullOrBlank() } &&
                cantiere.periodi.any { p ->
                    val da = p.dataInizio?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val a = p.dataFine?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    (da == null || !oggi.isBefore(da)) && (a == null || !oggi.isAfter(a))
                }
        }
        assumeTrue("nessun cantiere aperto oggi", aperto != null)

        val stazione = aperto!!.stazioni.first { !it.name.isNullOrBlank() }.name!!
        val avvisi = trenord.cantieriPer(listOf(stazione), oggi)
        println("  ${aperto.name}: cercando «$stazione» escono ${avvisi.size} avvisi")
        avvisi.take(3).forEach { println("    ${it.title} — ${it.message}") }
        assertTrue("il cantiere non arriva alla sua stessa stazione", avvisi.isNotEmpty())
        assertTrue("l'avviso deve dire di che lavori si tratta", avvisi.all { !it.title.isNullOrBlank() })
        assertTrue("e non e' un guasto: non va dato per grave", avvisi.none { it.severe })
    }

    @Test
    fun `una stazione che nessun cantiere nomina non prende avvisi`() = runBlocking {
        assertEquals(emptyList<Any>(), trenord.cantieriPer(listOf("Stazione Che Non Esiste")))
        assertEquals(emptyList<Any>(), trenord.cantieriPer(emptyList()))
        // E un giorno lontanissimo non ha lavori in corso.
        assertEquals(
            emptyList<Any>(),
            trenord.cantieriPer(listOf("MILANO PORTA GARIBALDI"), LocalDate.of(2040, 1, 1)),
        )
    }

    @Test
    fun `senza il servizio dei cantieri la ricerca non cambia`() = runBlocking {
        val senza = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
        assertEquals(emptyList<Any>(), senza.cantieriPer(listOf("MILANO PORTA GARIBALDI")))
    }
}
