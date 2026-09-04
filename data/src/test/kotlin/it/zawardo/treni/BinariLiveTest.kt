package it.zawardo.treni

import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
 * Come gli altri `…LiveTest`: serve rete e servono treni in circolazione. Fuori
 * dall'orario di servizio i test si sospendono invece di fallire, perche' "non
 * circola niente" non e' un difetto del codice.
 */
class BinariLiveTest {

    private val trenord = TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    private val trains = TrainStatusRepository(NetworkModule.viaggiaTrenoApi, trenord)

    private val oggi = LocalDate.now()

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

    @Test
    fun `Trenord pubblica ancora il binario nelle fermate`() = runBlocking {
        val numeri = corseInPartenza(8)
        assumeTrue("tabellone di Milano Centrale vuoto: nessuna corsa in partenza", numeri.isNotEmpty())

        println("\n=== BINARI SECONDO TRENORD, da Milano Centrale ===")
        var lombarde = 0
        var conBinario = 0
        for (numero in numeri) {
            val corsa = trenord.trainStatus(numero, oggi) ?: continue
            lombarde++
            val quanti = corsa.conBinario()
            if (quanti > 0) conBinario++
            println("  ${corsa.label.padEnd(12)} ${corsa.stops.size} fermate, $quanti col binario")
            corsa.stops.filter { it.platform != null }.forEach {
                val tipo = if (it.actualPlatform != null) "effettivo" else "programmato"
                println("      ${it.stationName.padEnd(28)} bin. ${it.platform}  ($tipo)")
            }
        }

        assumeTrue("nessuna corsa Trenord fra quelle in partenza adesso", lombarde > 0)
        assertTrue(
            "Trenord risponde ma nessuna fermata ha un binario: controllare " +
                "`platform` e `is_actual_platform` in pass_list",
            conBinario > 0,
        )
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
