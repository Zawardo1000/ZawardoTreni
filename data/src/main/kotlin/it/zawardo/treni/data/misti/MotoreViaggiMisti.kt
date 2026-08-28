package it.zawardo.treni.data.misti

import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.TransportKind
import java.time.Duration

/**
 * Concatena due meta' di viaggio, di operatori diversi, in un viaggio unico.
 *
 * E' una funzione **pura**: non tocca la rete, non conosce le sorgenti. Riceve
 * i viaggi che portano a un nodo e quelli che ne ripartono — gia' etichettati
 * con la loro rete da chi li ha cercati — e restituisce le combinazioni che
 * hanno senso. Tutto quello che puo' andare storto in un viaggio misto sta qui,
 * ed e' qui isolato apposta: si prova con dati costruiti a mano, dove una
 * coincidenza impossibile o un cambio assurdo si vedono a colpo d'occhio, prima
 * ancora di collegare le API vere.
 *
 * Il punto di cambio si riconosce dai codici stazione: stesso codice = stessi
 * binari; codici diversi ma in tabella = trasferimento a piedi dichiarato (vedi
 * [Interscambi]). Fuori da questi due casi le due meta' non si toccano e non si
 * concatenano.
 */
internal object MotoreViaggiMisti {

    /**
     * I paletti che tengono i risultati sensati. I valori di default sono
     * conservativi: meglio scartare un misto buono che proporne uno inutile.
     */
    data class Vincoli(
        /** Minuti minimi per cambiare quando si resta nella stessa stazione. */
        val cambioMinimoMinuti: Long = 15,
        /** Margine da aggiungere ai minuti dichiarati di un trasferimento a piedi. */
        val margineTransferMinuti: Long = 5,
        /** Oltre questa attesa al cambio, la coincidenza e' valida ma inutile. */
        val attesaMassimaMinuti: Long = 60,
        /**
         * Quanto un misto puo' essere piu' lento della migliore soluzione a rete
         * singola prima di non valere il cambio.
         */
        val peggioramentoMassimoMinuti: Long = 30,
    )

    /**
     * Le sigle con cui i vettori marcano l'alta velocita'.
     *
     * Servono all'anti-sovrapposizione: due gambe entrambe AV significano due
     * lunghe percorrenze concorrenti sulla stessa direttrice — Trenitalia fino a
     * un nodo e Italo oltre — che quasi mai ha senso. La distinzione e' la
     * categoria, non la durata: Sorrento-Napoli e' settanta minuti ma resta
     * adduzione, Roma-Bologna e' un'ora e mezza di Frecciarossa e non lo e'.
     */
    private val ALTA_VELOCITA = setOf("FR", "FA", "FB", "ES", "AV", "EC")

    /**
     * Assembla i viaggi misti validi.
     *
     * [prime] finiscono in un nodo, [seconde] ne ripartono; le loro gambe hanno
     * gia' la [Leg.source] della rispettiva rete. [direttoMigliore] e' la durata
     * della migliore soluzione a rete singola gia' trovata, o null se non ce
     * n'e': serve al vincolo sul peggioramento. [limite] taglia il risultato.
     */
    fun assembla(
        prime: List<Journey>,
        seconde: List<Journey>,
        direttoMigliore: Duration? = null,
        vincoli: Vincoli = Vincoli(),
        limite: Int = 10,
    ): List<Journey> {
        if (prime.isEmpty() || seconde.isEmpty()) return emptyList()

        // Indicizza le seconde meta' per codice di partenza: evita il prodotto
        // cartesiano cieco e tiene solo le coppie che condividono un nodo.
        val secondePerPartenza: Map<String, List<Journey>> = seconde
            .filter { it.legs.isNotEmpty() }
            .groupBy { it.legs.first().from.rfiCode.orEmpty() }
            .filterKeys { it.isNotBlank() }

        val assemblati = mutableListOf<Journey>()

        for (prima in prime) {
            val ultimoArrivo = prima.legs.lastOrNull() ?: continue
            val codiceArrivo = ultimoArrivo.to.rfiCode ?: continue

            // I nodi da cui una seconda meta' puo' ripartire: la stessa stazione,
            // piu' le stazioni a piedi in tabella.
            val nodi = buildList {
                add(NodoCambio(codiceArrivo, minuti = vincoli.cambioMinimoMinuti, aPiedi = null))
                Interscambi.aPiediDa(codiceArrivo).forEach {
                    add(NodoCambio(it.codice, minuti = it.minuti + vincoli.margineTransferMinuti, aPiedi = it))
                }
            }

            for (nodo in nodi) {
                val candidate = secondePerPartenza[nodo.codice] ?: continue
                for (seconda in candidate) {
                    val viaggio = concatena(prima, seconda, nodo, vincoli, direttoMigliore) ?: continue
                    assemblati += viaggio
                }
            }
        }

        return dedup(assemblati)
            .sortedWith(compareBy({ it.duration }, { it.departure }))
            .take(limite)
    }

    private data class NodoCambio(
        val codice: String,
        val minuti: Long,
        val aPiedi: Interscambi.Vicino?,
    )

    /**
     * Vero se il viaggio e' una lunga percorrenza ad alta velocita'.
     *
     * Italo lo e' sempre — non fa regionali. Per gli altri decide la categoria
     * di una gamba: un Frecciarossa si', un regionale no, un EAV men che meno.
     */
    private fun altaVelocita(j: Journey): Boolean = j.legs.any { leg ->
        leg.source == DataSource.ITALO ||
            leg.category?.uppercase()?.take(2)?.let { it in ALTA_VELOCITA } == true
    }

    private fun concatena(
        prima: Journey,
        seconda: Journey,
        nodo: NodoCambio,
        vincoli: Vincoli,
        direttoMigliore: Duration?,
    ): Journey? {
        val arrivo = prima.arrival
        val partenza = seconda.departure

        // Tempo disponibile al cambio.
        val gap = Duration.between(arrivo, partenza).toMinutes()
        if (gap < nodo.minuti) return null // non si fa in tempo
        if (gap > nodo.minuti + vincoli.attesaMassimaMinuti) return null // troppa attesa

        // Anti-sovrapposizione: almeno una meta' dev'essere adduzione, non due
        // alte velocita' concorrenti in fila.
        if (altaVelocita(prima) && altaVelocita(seconda)) return null

        val walk = nodo.aPiedi?.let {
            Leg(
                trainNumber = null,
                category = null,
                from = prima.legs.last().to,
                to = seconda.legs.first().from,
                departure = arrivo,
                arrival = arrivo.plusMinutes(it.minuti.toLong()),
                kind = TransportKind.WALK,
                kindLabel = it.nota,
                source = null,
            )
        }

        val legs = prima.legs + listOfNotNull(walk) + seconda.legs
        val durata = Duration.between(prima.departure, seconda.arrival)

        // Non deve essere troppo peggiore del miglior diretto, se esiste.
        if (direttoMigliore != null &&
            durata.toMinutes() > direttoMigliore.toMinutes() + vincoli.peggioramentoMassimoMinuti
        ) {
            return null
        }

        return Journey(
            departure = prima.departure,
            arrival = seconda.arrival,
            duration = durata,
            legs = legs,
            // Il tempo reale si legge gamba per gamba dalla source di ciascuna;
            // qui non c'e' una sorgente unica.
            cancelled = prima.cancelled || seconda.cancelled,
            partiallyCancelled = prima.partiallyCancelled || seconda.partiallyCancelled,
            /*
             * Nessun prezzo sul viaggio assemblato.
             *
             * Sommare i prezzi delle due meta' darebbe una cifra falsamente
             * precisa: un misto quasi sempre ha una gamba Italo, di cui il
             * prezzo non si conosce, e mostrare solo meta' del costo come se
             * fosse il totale sarebbe peggio che tacere. Il prezzo delle singole
             * gambe resta leggibile aprendo le corse.
             */
            price = null,
            assembled = true,
        )
    }

    /**
     * Toglie i doppioni.
     *
     * Lo stesso misto puo' nascere da coppie diverse quando due nodi portano
     * alla stessa concatenazione. Si riconoscono da orario di partenza, di
     * arrivo e numeri di treno in sequenza.
     */
    private fun dedup(viaggi: List<Journey>): List<Journey> {
        val visti = LinkedHashMap<String, Journey>()
        viaggi.forEach { j ->
            val chiave = j.departure.toString() + "|" + j.arrival.toString() + "|" +
                j.legs.joinToString(",") { it.trainNumber.orEmpty() }
            visti.putIfAbsent(chiave, j)
        }
        return visti.values.toList()
    }
}
