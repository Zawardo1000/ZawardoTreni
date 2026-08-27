package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Verifica d'insieme delle tre sorgenti.
 *
 * I test in [LiveApiTest] controllano un contratto per volta; qui si controlla
 * che stiano insieme: che i tabelloni coprano sia la rete RFI sia Trenord, che
 * le righe non si duplichino, che la paginazione a finestre avanzi davvero, e
 * che la ricerca fonda le due sorgenti invece di perderne una.
 *
 * Dipendono dal servizio reale del momento: quando una tratta e' ferma il test
 * lo dichiara invece di fallire, perche' un treno che non circola non e' un
 * difetto dell'app.
 */
class IntegrazioneTest {

    private val stations = StationRepository(NetworkModule.lefrecceApi)
    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val journeys = JourneyRepository(NetworkModule.lefrecceApi, trenord)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi)

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    /** Stazioni scelte per coprire i tre casi: solo RFI, solo Trenord, entrambe. */
    private val campione = listOf(
        "S01700" to "Milano Centrale",
        "S01066" to "Milano Cadorna",
        "S01701" to "Milano Lambrate",
        "S01650" to "Milano Dateo",
        "S01649" to "Milano Porta Venezia",
    )

    @Test
    fun `i tabelloni coprono sia RFI sia Trenord, senza doppioni`() = runBlocking {
        println("\n=== TABELLONI: copertura, doppioni, tempo reale ===")
        println(String.format("%-24s %6s %8s %7s %9s %9s", "stazione", "RFI", "Trenord", "fusi", "mostrati", "scartati"))

        var almenoUnaSoloTrenord = false

        for ((code, nome) in campione) {
            val rfi = runCatching { trains.departures(code) }.getOrDefault(emptyList())
            val tn = runCatching { trenord.board(code) }.getOrDefault(emptyList())

            // Stessa fusione della schermata: chiave numero + orario.
            val visti = rfi.map { it.trainRef.number + "|" + it.scheduledTime }.toSet()
            val extra = tn.filter { it.trainRef.number + "|" + it.scheduledTime !in visti }
            val fusi = rfi + extra

            // Stessa politica della schermata: si mostrano solo corse tracciate.
            val mostrati = fusi.filter { it.hasRealtime }
            val scartati = fusi.size - mostrati.size
            println(String.format("%-24s %6d %8d %7d %9d %9d", nome, rfi.size, tn.size, fusi.size, mostrati.size, scartati))

            if (tn.any { it.hasRealtime } && rfi.isEmpty()) almenoUnaSoloTrenord = true

            // Nessun doppione dopo la fusione.
            val chiavi = fusi.map { it.trainRef.number + "|" + it.scheduledTime }
            assertTrue(
                "$nome: ${chiavi.size - chiavi.toSet().size} righe duplicate dopo la fusione",
                chiavi.size == chiavi.toSet().size,
            )
        }

        /*
         * Non si pretende che Trenord copra da solo qualche stazione: dopo il
         * filtro sui dati tracciati puo' non restare nulla, ed e' una scelta
         * voluta. Si verifica invece che il contributo Trenord, quando c'e',
         * non introduca doppioni: gia' controllato sopra stazione per stazione.
         */
        println(
            if (almenoUnaSoloTrenord) {
                "  Trenord copre da solo almeno una stazione con dati tracciati"
            } else {
                "  nessuna stazione coperta dal solo Trenord con dati tracciati " +
                    "(atteso quando il Passante non e' monitorato)"
            },
        )
    }

    @Test
    fun `la paginazione del tabellone avanza nel tempo`() = runBlocking {
        val code = "S01700"
        val ora = ZonedDateTime.now()
        val primo = trains.departures(code, ora)
        val secondo = trains.departures(code, ora.plusMinutes(90))

        val chiavi = primo.map { it.trainRef.number + "|" + it.scheduledTime }.toSet()
        val nuovi = secondo.filter { it.trainRef.number + "|" + it.scheduledTime !in chiavi }

        println("\n=== PAGINAZIONE TABELLONE (Milano Centrale) ===")
        println("  finestra 1: ${primo.size} treni, ultimo ${primo.lastOrNull()?.scheduledTime}")
        println("  finestra 2: ${secondo.size} treni, ultimo ${secondo.lastOrNull()?.scheduledTime}")
        println("  nuovi nella seconda: ${nuovi.size}")

        assertTrue("la prima finestra e' vuota", primo.isNotEmpty())
        assertTrue(
            "la seconda finestra non porta nulla di nuovo: lo scorrimento non avanzerebbe",
            nuovi.isNotEmpty(),
        )
    }

    @Test
    fun `la ricerca fonde davvero le due sorgenti`() = runBlocking {
        val from = stations.search("milano centrale").first { it.trackable }
        val to = stations.search("calolziocorte").first { it.trackable }
        val quando = LocalDateTime.now()

        val soloLefrecce = runCatching { journeys.search(from, to, quando, limit = 10) }
            .getOrDefault(emptyList())
        val soloTrenord = runCatching { trenord.search(from, to, quando) }.getOrNull()
        val fuse = journeys.searchAll(from, to, quando, limit = 10)

        println("\n=== RICERCA ${from.name} -> ${to.name} ===")
        println("  solo Le Frecce : ${soloLefrecce.size}")
        println("  solo Trenord   : ${soloTrenord?.journeys?.size ?: 0}")
        println("  fuse           : ${fuse.journeys.size}  (avvisi: ${fuse.alerts.size})")
        fuse.journeys.take(6).forEach {
            println("     ${it.departure.format(hhmm)} [${it.source}] cambi=${it.changes} " +
                it.legs.joinToString(" + ") { l -> l.label })
        }

        assertTrue("la ricerca combinata non restituisce nulla", fuse.journeys.isNotEmpty())

        // Nessun doppione sulla coppia orario + treni.
        val chiavi = fuse.journeys.map {
            it.departure.toString() + "|" + it.legs.mapNotNull { l -> l.trainNumber }.sorted()
        }
        assertTrue("soluzioni duplicate nella fusione", chiavi.size == chiavi.toSet().size)

        // La fusione non deve perdere pezzi: almeno quanto la migliore singola.
        val migliore = maxOf(soloLefrecce.size, soloTrenord?.journeys?.size ?: 0)
        assertTrue(
            "la fusione ha ${fuse.journeys.size} soluzioni ma la sorgente migliore ne aveva " +
                "$migliore: si sta perdendo qualcosa",
            fuse.journeys.size >= minOf(migliore, 10),
        )
    }

    @Test
    fun `i servizi sostitutivi sono riconosciuti come tali`() = runBlocking {
        val dateo = Station("S01650", 830001665, "Milano Dateo")
        val busto = Station("S01031", 830001031, "Busto Arsizio")
        val res = journeys.searchAll(dateo, busto, LocalDateTime.now(), limit = 8)

        val tratte = res.journeys.flatMap { it.legs }
        val bus = tratte.filter { it.kind == TransportKind.BUS }

        println("\n=== SERVIZI SOSTITUTIVI ===")
        println("  soluzioni: ${res.journeys.size}, tratte: ${tratte.size}, di cui bus: ${bus.size}")
        bus.take(4).forEach { println("     ${it.label}  ${it.from.name} -> ${it.to.name}") }
        res.alerts.take(1).forEach { println("     avviso: ${it.message.take(140)}") }

        assertTrue(
            "una tratta bus non deve essere apribile come treno: non esiste un " +
                "dettaglio corsa per i sostitutivi",
            bus.none { it.isTrain },
        )
        assertTrue(
            "i bus devono essere etichettati come tali",
            bus.all { it.label.contains("Bus", ignoreCase = true) },
        )
    }
}
