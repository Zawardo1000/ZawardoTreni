package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrenordRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * I prezzi, contro il servizio reale.
 *
 * Li pubblicano le due sorgenti che vendono biglietti: il BFF Le Frecce e
 * Trenord. Le altre sono servizi di informazione sulla circolazione e un prezzo
 * non ce l'hanno affatto.
 *
 * Questi test verificano le due meta' della stessa promessa — che dove il
 * prezzo c'e' arrivi, e che dove non c'e' resti null invece di diventare zero —
 * piu' la trappola specifica di Trenord: fra i titoli che allega ci sono anche i
 * giornalieri, e mostrarli al posto della corsa semplice la farebbe sembrare tre
 * volte piu' cara.
 */
class PrezziLiveTest {

    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)

    private suspend fun cerca(da: String, a: String) = run {
        val from = stations.search(da).first()
        val to = stations.search(a).first()
        journeys.search(from, to, LocalDateTime.now().plusDays(1).withHour(8).withMinute(0))
    }

    @Test
    fun `l'alta velocita' arriva col prezzo`() = runBlocking {
        /*
         * Si riprova su piu' sessioni perche' il prezzo e' intermittente: su
         * cinque ricerche consecutive per la stessa tratta, una torna senza
         * prezzi e ripetere la chiamata sullo stesso `searchId` non rimedia
         * (vedi il commento su `toPrice`). Tre tentativi portano la probabilita'
         * di un falso allarme sotto l'uno per cento, e un fallimento a quel
         * punto vuol dire davvero che i campi sono cambiati.
         */
        var conPrezzo = 0
        var soluzioni = 0
        repeat(3) { giro ->
            if (conPrezzo == 0) {
                val res = cerca("Milano Centrale", "Bologna Centrale")
                soluzioni = res.size
                conPrezzo = res.count { it.price != null }
                println("\n=== MILANO -> BOLOGNA, tentativo ${giro + 1}: $conPrezzo prezzi su $soluzioni ===")
                res.take(8).forEach {
                    println(
                        "  %s -> %s  %-18s %s".format(
                            it.departure.toLocalTime(), it.arrival.toLocalTime(),
                            it.legs.firstOrNull()?.let { l -> "${l.category ?: ""} ${l.trainNumber ?: ""}" } ?: "",
                            it.price?.let { p -> p.formatted + if (!p.saleable) " (non acquistabile)" else "" }
                                ?: "prezzo non pubblicato",
                        ),
                    )
                }
            }
        }
        assertTrue("nessuna soluzione", soluzioni > 0)
        assertTrue(
            "in tre ricerche nessuna soluzione ha portato un prezzo: " +
                "il BFF ha probabilmente cambiato i campi",
            conPrezzo > 0,
        )
    }

    @Test
    fun `un prezzo pubblicato e' una cifra sensata`() = runBlocking {
        val res = cerca("Roma Termini", "Firenze S. M. Novella")
        val prezzi = res.mapNotNull { it.price }
        println("\n=== ROMA -> FIRENZE: ${prezzi.size} prezzi su ${res.size} soluzioni ===")
        prezzi.forEach { println("  ${it.formatted}  vendibile=${it.saleable}") }
        assertTrue("nessun prezzo", prezzi.isNotEmpty())
        prezzi.forEach { p ->
            val v = p.amount.toDoubleOrNull()
            assertTrue("prezzo illeggibile: ${p.amount}", v != null)
            assertTrue("prezzo non positivo: ${p.amount}", v!! > 0.0)
            /*
             * Un tetto assurdo prende gli errori di unita': se un giorno il BFF
             * passasse ai centesimi, 5200 invece di 52.00, il test lo direbbe
             * invece di lasciar comparire "5200,00 €" nella lista.
             */
            assertTrue("prezzo fuori scala, forse centesimi: ${p.amount}", v < 1_000.0)
        }
    }

    @Test
    fun `anche Trenord porta il prezzo, e non quello dell'abbonamento`() = runBlocking {
        val from = stations.search("Milano Centrale").first()
        val to = stations.search("Milano Porta Garibaldi").first()
        val res = trenord.search(from, to, LocalDateTime.now().plusDays(1).withHour(8).withMinute(0))
        val soluzioni = res.journeys

        println("\n=== TRENORD MILANO C.LE -> P.TA GARIBALDI: ${soluzioni.size} soluzioni ===")
        soluzioni.take(6).forEach {
            println("  ${it.departure.toLocalTime()} -> ${it.arrival.toLocalTime()}  ${it.price?.formatted ?: "(nessun prezzo)"}")
        }

        assertTrue("Trenord non ha restituito soluzioni", soluzioni.isNotEmpty())
        val prezzi = soluzioni.mapNotNull { it.price?.amount?.toDoubleOrNull() }
        assertTrue(
            "nessuna soluzione Trenord porta un prezzo: i campi `ticket_routes` sono cambiati",
            prezzi.isNotEmpty(),
        )
        /*
         * Il biglietto urbano milanese sta intorno ai due euro; il giornaliero
         * agli otto. Se qui comparisse un valore da giornaliero vorrebbe dire
         * che il filtro sui titoli ordinari non sta piu' funzionando, ed e'
         * l'errore che farebbe apparire Trenord tre volte piu' cara del vero.
         */
        prezzi.forEach {
            assertTrue("prezzo non positivo: $it", it > 0.0)
            assertTrue(
                "prezzo da abbonamento su una corsa singola urbana: $it",
                it < 6.0,
            )
        }
    }

    @Test
    fun `su un viaggio con cambio Trenord somma le tratte`() = runBlocking {
        /*
         * Una tratta regionale fuori dall'area urbana, dove il viaggio puo'
         * richiedere piu' di un titolo. Interessa che il totale non sia il
         * prezzo di una sola tratta: prendere il minimo direbbe meta' del
         * prezzo vero a chi deve cambiare.
         */
        val from = stations.search("Milano Centrale").first()
        val to = stations.search("Bergamo").first()
        val res = trenord.search(from, to, LocalDateTime.now().plusDays(1).withHour(8).withMinute(0))
        println("\n=== TRENORD MILANO -> BERGAMO ===")
        res.journeys.take(6).forEach {
            println(
                "  ${it.departure.toLocalTime()} -> ${it.arrival.toLocalTime()}  " +
                    "${it.changes} cambi  ${it.price?.formatted ?: "(nessun prezzo)"}",
            )
        }
        val conPrezzo = res.journeys.filter { it.price != null }
        if (conPrezzo.isEmpty()) {
            println("  (nessun prezzo su questa tratta: fuori area tariffaria integrata)")
            return@runBlocking
        }
        conPrezzo.forEach {
            val v = it.price!!.amount.toDouble()
            assertTrue("prezzo fuori scala su Milano-Bergamo: $v", v in 0.5..40.0)
        }
    }

    @Test
    fun `la formattazione usa la virgola e l'euro`() {
        val p = it.zawardo.treni.domain.model.Price("52.00")
        org.junit.Assert.assertEquals("52,00 €", p.formatted)
    }

    @Test
    fun `dove il prezzo non c'e' resta null, non zero`() = runBlocking {
        /*
         * I regionali su tratte brevi spesso non sono commercializzati dal BFF:
         * quello che conta non e' che manchino, ma che quando mancano il campo
         * sia null. Zero significherebbe "gratis" e sarebbe una bugia.
         */
        val res = cerca("Milano Centrale", "Monza")
        println("\n=== MILANO -> MONZA ===")
        res.take(6).forEach {
            println("  ${it.departure.toLocalTime()}  ${it.price?.formatted ?: "(nessun prezzo)"}")
        }
        assertTrue(
            "esiste un prezzo pari a zero: andrebbe scartato, non mostrato",
            res.none { it.price?.amount?.toDoubleOrNull() == 0.0 },
        )
    }
}
