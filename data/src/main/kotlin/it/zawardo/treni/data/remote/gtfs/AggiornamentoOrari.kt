package it.zawardo.treni.data.remote.gtfs

import it.zawardo.treni.data.remote.arst.ArstGtfsUpdater
import it.zawardo.treni.data.remote.eav.EavGtfsUpdater
import it.zawardo.treni.domain.model.DataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDate

/**
 * Tiene aggiornati gli orari imbarcati, e lo fa vedere.
 *
 * Due sorgenti dell'app non hanno tempo reale ma un **orario scaricato**: EAV e
 * ARST. Sta qui la decisione di quando riprenderlo, per chi, e con quale
 * annuncio.
 *
 * ## Tre mesi, non sei
 *
 * Un orario ferroviario cambia due volte l'anno — a dicembre e a giugno — piu'
 * le varianti estive e i lavori. I feed lo dicono chiaramente: quello ARST vale
 * fino a fine anno, ma TFT copriva due settimane e Trentino dodici giorni. Sei
 * mesi erano tarati sul solo EAV, che pubblica con largo anticipo; su una
 * finestra breve significherebbero mostrare per mesi un orario che non copre
 * piu' il giorno richiesto. Tre mesi rinfrescano prima che questo accada,
 * restando lontani dall'idea di scaricare a ogni avvio.
 *
 * ## Solo le sorgenti accese
 *
 * Il feed ARST pesa 19,7 MB, quello EAV 3,1. Scaricarli a chi quelle reti non le
 * guarda sarebbe consumo di dati altrui per dati che non aprira' mai. Si guarda
 * quindi cosa e' acceso nelle impostazioni, e non si tocca il resto.
 *
 * Il controllo scatta in due momenti, che sono lo stesso momento visto da due
 * parti: all'avvio dell'app, e quando una sorgente viene accesa. Entrambi
 * arrivano qui come una nuova emissione dell'insieme delle reti accese, quindi
 * basta chiamare [controlla] a ogni emissione: chi e' gia' stato guardato in
 * questa sessione viene saltato, e spegnere una rete non fa scaricare niente.
 *
 * ## E si vede
 *
 * [stato] dice cosa sta succedendo e per quale rete. Non e' un dettaglio
 * implementativo esposto per comodita': venti megabyte scaricati di nascosto,
 * sulla rete dati di qualcun altro, sono esattamente il genere di cosa che
 * un'app non deve fare in silenzio.
 */
class AggiornamentoOrari(
    private val client: OkHttpClient,
    /** Dove finiscono gli orari aggiornati. La fornisce l'app: qui non c'e' Android. */
    private val cartella: File,
    private val soglia: Long = MESI_DI_VALIDITA,
) {

    private val _stato = MutableStateFlow<Stato>(Stato.Fermo)
    val stato: StateFlow<Stato> = _stato.asStateFlow()

    /**
     * Le reti gia' guardate in questa sessione.
     *
     * Senza, ogni accensione o spegnimento di una qualunque rete rimetterebbe in
     * fila anche le altre, e riaprire le impostazioni tre volte varrebbe tre
     * controlli. Si azzera solo riavviando l'app, che e' la cadenza giusta per
     * una cosa che comunque si misura in mesi.
     */
    private val gia = mutableSetOf<DataSource>()

    /** Un aggiornamento per volta: due download insieme sarebbero 23 MB in parallelo. */
    private val turno = Mutex()

    /**
     * Guarda le reti accese e aggiorna quelle che ne hanno bisogno.
     *
     * Non fa nulla per le reti senza orario scaricabile — che sono quasi tutte —
     * ne' per quelle gia' guardate. Un fallimento non si propaga: resta l'orario
     * di prima, e lo si dice in [stato].
     */
    suspend fun controlla(accese: Set<DataSource>) {
        val daFare = SORGENTI.keys.filter { it in accese && it !in gia }
        if (daFare.isEmpty()) return

        turno.withLock {
            for (sorgente in daFare) {
                // Ricontrollato dentro il lock: due chiamate ravvicinate
                // potrebbero aver superato insieme il filtro di sopra.
                if (sorgente in gia) continue
                gia += sorgente
                val updater = SORGENTI[sorgente] ?: continue

                _stato.value = Stato.InCorso(sorgente)
                val esito = runCatching { updater(client, cartella, soglia) }
                    .getOrElse { Esito.Fallito(it.message ?: it::class.java.simpleName) }
                _stato.value = Stato.Concluso(sorgente, esito)
            }
            _stato.value = Stato.Fermo
        }
    }

    /** Cosa sta facendo l'aggiornamento, per chi lo mostra. */
    sealed interface Stato {
        /** Niente in corso. */
        data object Fermo : Stato

        /**
         * Sta scaricando il feed e ricostruendo l'orario di [sorgente].
         *
         * Le due fasi non sono distinte perche' da fuori sono una attesa sola, e
         * perche' la seconda dura una frazione della prima: il grosso del tempo
         * se ne va nei megabyte, non nel farne venti KB.
         */
        data class InCorso(val sorgente: DataSource) : Stato

        /** Finito, con l'esito dell'ultima rete guardata. */
        data class Concluso(val sorgente: DataSource, val esito: Esito) : Stato
    }

    /** L'esito di un aggiornamento, uguale per tutte le sorgenti. */
    sealed interface Esito {
        /** Non era il momento: l'orario e' ancora abbastanza fresco. */
        data class AncoraBuono(val generato: LocalDate, val mesi: Long) : Esito

        data class Aggiornato(val generato: LocalDate, val corse: Int) : Esito

        /** Scaricato, ma non era piu' recente di quello che c'era. */
        data class GiaAggiornato(val generato: LocalDate) : Esito

        /** Resta valido l'orario precedente. */
        data class Fallito(val motivo: String) : Esito
    }

    companion object {
        /** Vedi il commento in testa alla classe per il perche' di tre. */
        const val MESI_DI_VALIDITA = 3L

        /**
         * Chi ha un orario da tenere aggiornato, e come lo si aggiorna.
         *
         * Gli adattatori sono qui e non dentro i due updater perche' quelli
         * restano indipendenti l'uno dall'altro: aggiungere una terza sorgente
         * e' una riga in questa mappa, e nessuna modifica alle altre due.
         */
        private val SORGENTI: Map<DataSource, suspend (OkHttpClient, File, Long) -> Esito> = mapOf(
            DataSource.EAV to { c, f, s ->
                when (val e = EavGtfsUpdater(c, f).aggiornaSeVecchio(s)) {
                    is EavGtfsUpdater.Esito.AncoraBuono -> Esito.AncoraBuono(e.generato, e.mesi)
                    is EavGtfsUpdater.Esito.Aggiornato -> Esito.Aggiornato(e.generato, e.corse)
                    is EavGtfsUpdater.Esito.GiaAggiornato -> Esito.GiaAggiornato(e.generato)
                    is EavGtfsUpdater.Esito.Fallito -> Esito.Fallito(e.motivo)
                }
            },
            DataSource.ARST to { c, f, s ->
                when (val e = ArstGtfsUpdater(c, f).aggiornaSeVecchio(s)) {
                    is ArstGtfsUpdater.Esito.AncoraBuono -> Esito.AncoraBuono(e.generato, e.mesi)
                    is ArstGtfsUpdater.Esito.Aggiornato -> Esito.Aggiornato(e.generato, e.corse)
                    is ArstGtfsUpdater.Esito.GiaAggiornato -> Esito.GiaAggiornato(e.generato)
                    is ArstGtfsUpdater.Esito.Fallito -> Esito.Fallito(e.motivo)
                }
            },
        )

        /** Vero se quella rete ha un orario che si scarica. */
        fun scaricabile(sorgente: DataSource): Boolean = sorgente in SORGENTI
    }
}
