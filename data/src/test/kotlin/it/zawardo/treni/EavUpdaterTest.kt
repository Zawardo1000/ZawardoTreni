package it.zawardo.treni

import it.zawardo.treni.data.remote.eav.EavGtfsUpdater
import it.zawardo.treni.data.remote.eav.EavOrario
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * La scadenza dell'orario, senza toccare la rete.
 *
 * Il download vero costa 3,1 MB e dipende da EAV: qui interessa la decisione,
 * cioe' che l'aggiornamento parta quando deve e non prima. Il giorno corrente
 * e' iniettabile proprio per poter verificare una soglia di tre mesi senza
 * aspettarne tre.
 */
class EavUpdaterTest {

    private val cartella = Files.createTempDirectory("eav-test").toFile()
    private val updater = EavGtfsUpdater(OkHttpClient(), cartella)

    @Test
    fun `un orario fresco non fa scaricare niente`() = runBlocking {
        val generato = EavOrario.carica(null)?.generato ?: return@runBlocking
        // due mesi dopo: dentro la soglia, che ora e' di tre
        val esito = updater.aggiornaSeVecchio(oggi = generato.plusMonths(2))
        println("\n=== A 2 MESI: $esito ===")
        assertTrue(
            "a due mesi non doveva scaricare nulla",
            esito is EavGtfsUpdater.Esito.AncoraBuono,
        )
    }

    @Test
    fun `il giorno stesso non fa scaricare niente`() = runBlocking {
        val generato = EavOrario.carica(null)?.generato ?: return@runBlocking
        val esito = updater.aggiornaSeVecchio(oggi = generato)
        assertTrue(
            "il giorno della generazione non si aggiorna",
            esito is EavGtfsUpdater.Esito.AncoraBuono,
        )
    }

    @Test
    fun `oggi l'orario imbarcato e' ancora dentro la soglia`() = runBlocking {
        /*
         * Se questo fallisce, l'orario compilato ha superato i tre mesi: e'
         * l'avviso che va rigenerato prima della prossima release, invece di
         * far scaricare 3 MB al primo che apre l'app.
         */
        val esito = updater.aggiornaSeVecchio()
        println("\n=== OGGI: $esito ===")
        assertTrue(
            "l'orario imbarcato ha superato i tre mesi: va rigenerato " +
                "(./gradlew :data:rigeneraOrarioEav)",
            esito is EavGtfsUpdater.Esito.AncoraBuono,
        )
    }
}
