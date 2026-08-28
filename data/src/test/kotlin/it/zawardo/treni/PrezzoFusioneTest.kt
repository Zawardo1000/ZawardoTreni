package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.JourneySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Il prezzo deve sopravvivere alla fusione fra le sorgenti.
 *
 * Nasce da una segnalazione precisa: Milano Dateo - Vignate compariva senza
 * prezzo. La causa non era il prezzo in se' — Trenord quella tratta la prezza,
 * 3,00 euro — ma il fatto che il viaggio mostrato arrivasse da Le Frecce, che
 * i regionali lombardi non li commercializza. Due sorgenti descrivono la stessa
 * corsa e solo una sa quanto costa: se la fusione sceglie l'altra, il prezzo
 * sparisce pur essendo stato ottenuto.
 *
 * E' un difetto che nessun test sulla singola sorgente puo' vedere, perche'
 * entrambe funzionano: si manifesta solo nel punto in cui si incontrano.
 */
class PrezzoFusioneTest {

    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)

    /** Domani alle 8, per non dipendere da una data fissa che scade. */
    private fun quando(): LocalDateTime =
        LocalDate.now().plusDays(1).atTime(8, 0)

    @Test
    fun `una tratta urbana lombarda arriva col prezzo dalla ricerca completa`() = runBlocking {
        val da = stations.search("Milano Dateo").firstOrNull()
        val a = stations.search("Vignate").firstOrNull()
        if (da == null || a == null) {
            println("\n(stazioni non trovate: il BFF non risponde, test non significativo)")
            return@runBlocking
        }

        val esito = journeys.searchAll(
            da,
            a,
            quando(),
            sources = setOf(DataSource.TRENITALIA, DataSource.TRENORD),
        )
        println("\n=== ${da.name} -> ${a.name} ===")
        esito.journeys.take(8).forEach {
            println(
                "  ${it.departure.toLocalTime()} -> ${it.arrival.toLocalTime()}  " +
                    "[${it.source}]  ${it.price?.formatted ?: "(nessun prezzo)"}",
            )
        }

        assertTrue("nessuna soluzione", esito.journeys.isNotEmpty())

        /*
         * Non si pretende che TUTTE abbiano il prezzo: Trenord ne restituisce
         * cinque per chiamata, e le soluzioni oltre quella finestra arrivano dal
         * BFF, che i regionali lombardi non li prezza. Si pretende che le
         * soluzioni Trenord — quelle che il prezzo ce l'hanno — lo portino fino
         * in fondo invece di perderlo nella fusione.
         */
        val daTrenord = esito.journeys.filter { it.source == JourneySource.TRENORD }
        if (daTrenord.isEmpty()) {
            println("  (nessuna soluzione Trenord: il loro servizio non ha risposto)")
            return@runBlocking
        }
        assertTrue(
            "le soluzioni Trenord hanno perso il prezzo passando dalla fusione",
            daTrenord.all { it.price != null },
        )

        val cifre = daTrenord.mapNotNull { it.price?.amount?.toDoubleOrNull() }
        cifre.forEach {
            assertTrue("prezzo non positivo: $it", it > 0.0)
            // Una tratta suburbana milanese sta sotto i dieci euro; sopra
            // significherebbe che si e' preso un titolo giornaliero.
            assertTrue("prezzo da abbonamento su una corsa singola: $it", it < 10.0)
        }
    }

    @Test
    fun `dove vincono le soluzioni Trenord non si perdono quelle del BFF`() = runBlocking {
        /*
         * Il rovescio: la fusione fa vincere Trenord a parita' di corsa, e va
         * bene perche' porta ritardo, soppressione e prezzo. Ma non deve
         * *sostituire* la lista: le corse che solo il BFF conosce devono restare,
         * altrimenti si guadagna il prezzo e si perdono le soluzioni.
         */
        val da = stations.search("Milano Dateo").firstOrNull() ?: return@runBlocking
        val a = stations.search("Vignate").firstOrNull() ?: return@runBlocking

        val soloBff = journeys.searchAll(da, a, quando(), sources = setOf(DataSource.TRENITALIA))
        val entrambe = journeys.searchAll(
            da,
            a,
            quando(),
            sources = setOf(DataSource.TRENITALIA, DataSource.TRENORD),
        )
        println("\n=== solo BFF: ${soloBff.journeys.size} · con Trenord: ${entrambe.journeys.size} ===")
        assertTrue(
            "accendere Trenord ha ridotto le soluzioni invece di arricchirle",
            entrambe.journeys.size >= soloBff.journeys.size,
        )
    }
}
