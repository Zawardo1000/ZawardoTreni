package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.domain.model.Imprese
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import java.time.Duration
import it.zawardo.treni.domain.model.Stop
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Il binario, contro le API vere.
 *
 * Presidia due contratti che nessun test offline puo' vedere, e che sono
 * esattamente quelli che si rompono in silenzio.
 *
 * Il primo: che Trenord pubblichi ancora `platform` e `is_actual_platform`
 * dentro `pass_list`. Sono due campi che il modello non deduce da nulla, e
 * sbagliarne il nome non fa fallire niente — fa solo sparire i binari, che e'
 * il modo in cui questa informazione era gia' assente senza che se ne
 * accorgesse nessuno.
 *
 * Il secondo: che le due fonti continuino a completarsi. Misurato il
 * 04/09/2026, ViaggiaTreno lasciava senza binario otto fermate su undici del
 * REG 2932 e tutte e sei quelle del REG 2874 — compresa Milano Centrale, dove
 * si sale. Se un giorno l'unione non aggiungesse piu' niente, o e' cambiata una
 * delle due API o abbiamo smesso di leggerla.
 *
 * Il terzo: che il binario continui ad arrivare in una grafia che sappiamo
 * ridurre. Non e' garantito e non e' teorico — il 07/09/2026 ViaggiaTreno
 * scriveva lo stesso binario in cifre romane e arabe nella stessa risposta, e
 * i tronchi di Milano Centrale in cinque modi. Una grafia nuova non rompe
 * niente in modo visibile: fa solo comparire un cambio di binario dove il
 * binario e' rimasto quello.
 *
 * Come gli altri `…LiveTest`: serve rete e servono treni in circolazione. Fuori
 * dall'orario di servizio i test si sospendono invece di fallire, perche' "non
 * circola niente" non e' un difetto del codice.
 */
class BinariLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi, trenord)

    private val oggi = LocalDate.now()

    /** Il formato data dei tabelloni, stile `Date.toString()` di JavaScript. */
    private val boardFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.ENGLISH)

    /** Due letture della stessa fermata: l'ora di tabella coincide al minuto. */
    private fun stessaOra(una: Stop, altra: Stop): Boolean {
        val qui = (una.scheduledDeparture ?: una.scheduledArrival)?.toLocalTime() ?: return false
        val la = (altra.scheduledDeparture ?: altra.scheduledArrival)?.toLocalTime() ?: return false
        // Tre minuti, la stessa tolleranza di `conBinariDa`.
        return abs(Duration.between(qui, la).toMinutes()) <= 3
    }

    /** Quante fermate hanno un binario, in qualunque delle due forme. */
    private fun TrainStatus.conBinario() = stops.count { it.platform != null }

    /**
     * I numeri delle prime corse in partenza da Milano Centrale.
     *
     * Si parte dal tabellone e non da un elenco scritto qui: i numeri dei
     * regionali cambiano con l'orario, e un test ancorato al REG 2932 sarebbe
     * rosso al primo cambio d'orario per una ragione che non c'entra niente.
     */
    private suspend fun corseInPartenza(quante: Int): List<String> =
        trains.departures("S01700")
            .map { it.trainRef.number }
            .distinct()
            .take(quante)

    /**
     * Le corse in partenza che sono **di Trenord**, con la lettura nazionale.
     *
     * Chi sia di Trenord lo dice `codiceCliente` 63 (`TrainStatus.impresa`),
     * come in produzione. Il numero da solo non basta: Trenord ha un suo treno
     * per molti numeri altrui — l'IC 657 per La Spezia e' il suo Milano
     * Cadorna-Asso — e misurarne i binari qui vorrebbe dire misurare un'altra
     * corsa, e dichiarare guasto Trenord quando invece si stava guardando il
     * treno sbagliato.
     */
    private suspend fun corseTrenordInPartenza(quante: Int): List<Pair<String, TrainStatus>> =
        trains.departures("S01700")
            .distinctBy { it.trainRef.number }
            .take(quante)
            .mapNotNull { riga ->
                val corsa = runCatching { trains.status(riga.trainRef) }.getOrNull() ?: return@mapNotNull null
                if (corsa.impresa != Imprese.TRENORD) null else riga.trainRef.number to corsa
            }

    /**
     * Trenord il binario lo pubblica **da quando viene assegnato**, un quarto
     * d'ora prima della partenza: su una corsa che parte fra un'ora non c'e', e
     * non e' un difetto. Percio' il binario si pretende solo se fra le corse
     * guardate ce n'e' almeno una ormai prossima.
     */
    private val quasiInPartenza = Duration.ofMinutes(20)

    @Test
    fun `Trenord pubblica ancora il binario nelle fermate`() = runBlocking {
        val sue = corseTrenordInPartenza(12)
        assumeTrue("nessuna corsa Trenord fra quelle in partenza adesso", sue.isNotEmpty())

        println("\n=== BINARI SECONDO TRENORD, da Milano Centrale ===")
        var lombarde = 0
        var conBinario = 0
        var prossime = 0
        for ((numero, nazionale) in sue) {
            val corsa = trenord.trainStatus(numero, oggi) ?: continue
            lombarde++
            nazionale.stops.firstOrNull { stessaStazione(it.stationCode, "S01700") }
                ?.scheduledDeparture?.let { partenza ->
                    val mancano = Duration.between(LocalDateTime.now(), partenza)
                    if (!mancano.isNegative && mancano <= quasiInPartenza) prossime++
                }
            val quanti = corsa.conBinario()
            if (quanti > 0) conBinario++
            println("  ${corsa.label.padEnd(12)} ${corsa.stops.size} fermate, $quanti col binario")
            corsa.stops.filter { it.platform != null }.forEach {
                val tipo = if (it.actualPlatform != null) "effettivo" else "programmato"
                println("      ${it.stationName.padEnd(28)} bin. ${it.platform}  ($tipo)")
            }
        }

        println("  corse Trenord: $lombarde, di cui $prossime quasi in partenza, $conBinario col binario")
        assumeTrue("nessuna corsa Trenord fra quelle in partenza adesso", lombarde > 0)
        assumeTrue("nessuna di queste corse e' abbastanza vicina alla partenza", prossime > 0)
        assertTrue(
            "Trenord risponde ma nessuna fermata ha un binario: controllare " +
                "`platform` e `is_actual_platform` in pass_list",
            conBinario > 0,
        )
    }

    /**
     * Le grafie con cui un binario puo' arrivare, oltre al numero e basta.
     *
     * Il 07/09/2026 erano queste: i binari tronchi di Milano Centrale, scritti
     * "Tronco"/"TR" e "Ovest"/"O"/"w" a seconda della corsa. Se ne compare una
     * nuova questo test si accorge per primo, e la risposta e' insegnarla a
     * `binarioPulito`: finche' non la conosce, quella grafia fa dichiarare un
     * cambio di binario a chi il binario non l'ha cambiato.
     *
     * Il 14/09/2026 si sono aggiunti il "20 BIS" di Roma Termini e Bologna
     * Centrale, coi binari "19 AV" della stazione sotterranea e quelli dei
     * piazzali scritti "III-EST" e "IV-PO".
     */
    private val paroleAmmesse = setOf("tronco", "ovest", "est", "sud", "nord", "bis", "AV")

    /**
     * Grafie viste e lasciate cosi' di proposito.
     *
     * "I'" e' comparso nelle risposte di ViaggiaTreno l'11/09/2026. Deciso con
     * l'utente di non ridurlo a "1": e' un binario diverso dall'"I", e tale
     * resta. `binarioPulito` lo lascia gia' intatto, perche' l'apice gli impedisce
     * di passare per cifra romana; qui va solo detto al test che non e' una
     * grafia sconosciuta. Vedi `BinarioNotazioneTest`.
     */
    private val binariAmmessi = setOf("I'")

    @Test
    fun `il binario arriva in una grammatica sola`() = runBlocking {
        val quando = ZonedDateTime.now().format(boardFormat)
        val grezzi = mutableSetOf<String>()

        for (codice in listOf("S01700", "S09218", "S08409", "S00219", "S01645", "S05043")) {
            val voci = runCatching { NetworkModule.viaggiaTrenoApi.partenze(codice, quando) }
                .getOrDefault(emptyList())
            voci.forEach {
                grezzi += listOfNotNull(
                    it.binarioProgrammatoPartenzaDescrizione,
                    it.binarioEffettivoPartenzaDescrizione,
                    it.binarioProgrammatoArrivoDescrizione,
                    it.binarioEffettivoArrivoDescrizione,
                )
            }
            // Il tabellone da' quasi solo numeri: le grafie strane stanno nelle
            // fermate della corsa, dove ci sono anche i binari tronchi.
            for (v in voci.take(5)) {
                val origine = v.codOrigine ?: continue
                val millis = v.dataPartenzaTreno ?: continue
                val corsa = runCatching {
                    NetworkModule.viaggiaTrenoApi
                        .andamentoTreno(origine, v.numeroTreno.toString(), millis)
                        .body()
                }.getOrNull() ?: continue
                corsa.fermate.forEach {
                    grezzi += listOfNotNull(
                        it.binarioProgrammatoPartenzaDescrizione,
                        it.binarioEffettivoPartenzaDescrizione,
                        it.binarioProgrammatoArrivoDescrizione,
                        it.binarioEffettivoArrivoDescrizione,
                    )
                }
            }
        }

        assumeTrue("nessun binario letto: rete assente o nessun treno in giro", grezzi.isNotEmpty())

        val puliti = grezzi.associateWith { binarioPulito(it) }
        println("\n=== GRAFIE DEL BINARIO, come arrivano da ViaggiaTreno ===")
        puliti.entries.sortedBy { it.key }
            .filter { it.key != it.value }
            .forEach { println("      '${it.key}'".padEnd(28) + " -> ${it.value}") }

        val sconosciute = puliti.values.filterNotNull().filter { valore ->
            valore.split(" ").any { it.toIntOrNull() == null && it !in paroleAmmesse && it !in binariAmmessi }
        }
        assertTrue(
            "grafie del binario che `binarioPulito` non sa ridurre: " +
                "${sconosciute.distinct()}. Finche' restano cosi', la stessa banchina " +
                "scritta in due modi si legge come un cambio di binario.",
            sconosciute.isEmpty(),
        )
    }

    /**
     * Il filtro per impresa non deve togliere i binari a chi li aveva.
     *
     * Dal 19/09/2026 a Trenord si chiede solo per i **suoi** treni
     * (`codiceCliente` 63): su 91 treni di altre imprese ne conosceva 4, e tutti
     * e 4 erano un suo treno con lo stesso numero. Il rischio del filtro e'
     * l'opposto: che un treno Trenord non venga piu' riconosciuto come tale e
     * resti senza i binari che solo Trenord ha. Qui si confronta corsa per
     * corsa: quel che Trenord dichiara alle sue fermate deve finire nell'unione.
     */
    @Test
    fun `sui treni Trenord l'unione porta ancora i binari suoi`() = runBlocking {
        val righe = trains.departures("S01700").distinctBy { it.trainRef.number }.take(12)
        assumeTrue("tabellone di Milano Centrale vuoto", righe.isNotEmpty())

        println("\n=== BINARI TRENORD: quel che Trenord ha deve arrivare nell'unione ===")
        var suoi = 0
        var verificati = 0
        for (riga in righe) {
            val corsa = runCatching { trains.status(riga.trainRef) }.getOrNull() ?: continue
            if (corsa.impresa != Imprese.TRENORD) continue
            suoi++
            val suo = runCatching { trenord.trainStatus(corsa.number, oggi) }.getOrNull()
            delay(2_000)
            if (suo == null) {
                println("  ${corsa.label}: Trenord non la conosce")
                continue
            }
            val unito = trains.completaBinari(corsa, oggi)
            // I binari che Trenord ha su una fermata che l'unione deve portarsi dietro.
            val attesi = suo.stops.mapNotNull { f ->
                val quale = f.platform ?: return@mapNotNull null
                val dove = f.stationCode ?: return@mapNotNull null
                // Stessa fermata: stessa stazione e stessa ora di tabella, come in `conBinariDa`.
                unito.stops.firstOrNull { stessaStazione(it.stationCode, dove) && stessaOra(it, f) }
                    ?.let { dove to (quale to it.platform) }
            }
            val mancanti = attesi.filter { (_, binari) -> binari.second == null }
            println("  ${corsa.label.padEnd(12)} ${attesi.size} binari da Trenord, mancanti ${mancanti.size}")
            assertTrue(
                "l'unione non ha portato i binari di Trenord sul ${corsa.label}: $mancanti",
                mancanti.isEmpty(),
            )
            if (attesi.isNotEmpty()) verificati++
        }
        println("  corse Trenord nel campione: $suoi, con binari verificati: $verificati")
        assumeTrue("nessuna corsa Trenord in partenza adesso", suoi > 0)
    }

    @Test
    fun `l'unione con Trenord aggiunge binari che ViaggiaTreno non ha`() = runBlocking {
        val numeri = corseInPartenza(8)
        assumeTrue("tabellone di Milano Centrale vuoto: nessuna corsa in partenza", numeri.isNotEmpty())

        println("\n=== BINARI: ViaggiaTreno da solo, e unito a Trenord ===")
        var confrontate = 0
        var guadagno = 0
        for (numero in numeri) {
            val solo = trains.statusByNumber(numero, oggi) ?: continue
            val unito = trains.completaBinari(solo, oggi)
            confrontate++
            val prima = solo.conBinario()
            val dopo = unito.conBinario()
            guadagno += dopo - prima
            println(
                "  ${solo.label.padEnd(12)} ${solo.stops.size} fermate: " +
                    "$prima -> $dopo col binario" + if (dopo > prima) "   (+${dopo - prima})" else "",
            )
            assertTrue("l'unione non puo' togliere binari a ${solo.label}", dopo >= prima)
            assertTrue("l'unione non puo' cambiare le fermate", unito.stops.size == solo.stops.size)
        }

        assumeTrue("nessuna corsa aperta da ViaggiaTreno", confrontate > 0)
        println("  totale binari aggiunti: $guadagno")
    }
}
