package it.zawardo.treni.data.repository

import it.zawardo.treni.data.misti.HubAV
import it.zawardo.treni.data.misti.Interscambi
import it.zawardo.treni.data.misti.MotoreViaggiMisti
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Costruisce i viaggi che cambiano operatore per strada.
 *
 * E' la feature beta dei "viaggi misti": mette insieme un feeder e una lunga
 * percorrenza di reti diverse, che nessuna singola sorgente sa collegare. Il
 * caso che copre e' quello che serve davvero e che nessun altro fa —
 * **una rete fuori-RFI di adduzione piu' l'alta velocita'**: Sorrento→Roma, EAV
 * fino a Napoli e poi il veloce oltre. Il resto, RFI con RFI diretto, lo fa gia'
 * il BFF.
 *
 * **Non e' inchiodato a EAV.** Il feeder e' una qualunque rete fuori-RFI che
 * sappia dire un itinerario A→B: oggi EAV e ARST (vedi [feeders]). ARST pero'
 * non ha hub ad alta velocita' vicini — e' un'isola — quindi in pratica non
 * compone nulla; e Ferrotramviaria e le svizzere restano fuori finche' non
 * espongono un orario e non solo un tabellone. La **gamba veloce** e' Italo (se
 * accesa) o la Freccia via Le Frecce (Trenitalia, sempre disponibile).
 *
 * Il lavoro sporadico e delicato — preselezione, concatenazione, vincoli — vive
 * altrove ([HubAV], [Interscambi], [MotoreViaggiMisti]) ed e' gia' provato a
 * tavolino. Qui si orchestra soltanto: si scelgono gli hub, si chiedono le due
 * meta' alle rispettive fonti, si passano al motore.
 *
 * La gamba Freccia parte da un hub, e Le Frecce vuole il suo `locationId`: lo si
 * risolve dalle **coordinate** dell'hub ([stations] «piu' vicina»), esatte e
 * robuste. Non e' una fuga di dati: e' lo stesso endpoint «stazioni piu' vicine»
 * dell'informativa, su azione esplicita dell'utente, e le coordinate sono quelle
 * fisse dell'hub, non quelle di chi cerca.
 */
class ViaggiMistiRepository(
    private val eav: EavRepository,
    private val arst: ArstRepository,
    private val italo: ItaloRepository,
    private val stations: StationRepository,
    private val journeys: JourneyRepository,
) {

    /**
     * Una rete fuori-RFI capace di fare da adduzione: la sua [fonte] (per il
     * filtro), il suo [copre] (le sue stazioni) e il suo [itinerario] (A→B).
     *
     * Aggiungere un feeder domani e' una riga qui, se quella rete espone un
     * orario. Le reti col solo tabellone (FNB, svizzere) non entrano: senza A→B
     * non si costruisce la gamba di adduzione.
     */
    private class Feeder(
        val fonte: DataSource,
        val copre: (String?) -> Boolean,
        val itinerario: suspend (String, String, LocalDate) -> List<Journey>,
    )

    private val feeders = listOf(
        Feeder(DataSource.EAV, eav::covers) { a, b, d -> eav.itinerario(a, b, d) },
        Feeder(DataSource.ARST, arst::covers) { a, b, d -> arst.itinerario(a, b, d) },
    )

    /**
     * I viaggi misti fra [from] e [to], o vuoto se non ce ne sono di sensati.
     *
     * [direttoMigliore] e' la durata della migliore soluzione a rete singola
     * gia' trovata: serve a scartare i misti che non fanno risparmiare abbastanza
     * da valere il cambio. Le chiamate alle fonti sono parallele, una manciata,
     * e partono solo per gli hub che la geografia non ha gia' scartato.
     */
    suspend fun cerca(
        from: Station,
        to: Station,
        quando: LocalDateTime,
        direttoMigliore: Duration? = null,
        /**
         * Le reti accese. Il feeder e' una rete fuori-RFI **accesa**; la gamba
         * veloce e' Italo se accesa, o la Freccia (Trenitalia, sempre
         * disponibile) — ne basta una che raggiunga l'altra punta. Chi ha spento
         * Italo non lo vede dentro un misto, ma la Freccia resta. Il flag beta
         * abilita la funzione, non scavalca le fonti.
         */
        sources: Set<DataSource> = DataSource.entries.toSet(),
    ): List<Journey> = coroutineScope {
        // La rete feeder di ciascuna punta, se fuori-RFI e accesa.
        val feederFrom = feeders.firstOrNull { it.fonte in sources && it.copre(from.rfiCode) }
        val feederTo = feeders.firstOrNull { it.fonte in sources && it.copre(to.rfiCode) }

        // Una punta e' feeder, l'altra e' raggiungibile in alta velocita'.
        val versoAV = feederFrom != null && feederTo == null && puntaAV(to)
        val daAV = feederTo != null && feederFrom == null && puntaAV(from)
        if (!versoAV && !daAV) return@coroutineScope emptyList()
        val feeder = if (versoAV) feederFrom!! else feederTo!!

        val hubs = HubAV.candidati(from.latitude, from.longitude, to.latitude, to.longitude)
        if (hubs.isEmpty()) return@coroutineScope emptyList()

        // Il nazionale degli hub candidati, risolto una volta in sequenza (N<=3):
        // serve alla gamba Freccia, e solo se Trenitalia entra davvero in gioco.
        val hubNazionale: Map<String, Station> =
            if (DataSource.TRENITALIA in sources) {
                buildMap { for (h in hubs) stazioneNazionaleHub(h)?.let { put(h.italo, it) } }
            } else {
                emptyMap()
            }

        val perHub = hubs.map { hub ->
            async {
                val hubRfi = hub.rfi ?: return@async emptyList<Journey>()
                // Le porte del feeder al nodo: le sue stazioni a piedi dall'hub.
                val porte = Interscambi.aPiediDa(hubRfi).map { it.codice }.filter { feeder.copre(it) }
                if (porte.isEmpty()) return@async emptyList<Journey>()
                val hubNaz = hubNazionale[hub.italo]

                if (versoAV) {
                    // adduzione: from -> porte del nodo; veloce: nodo -> to
                    val add = porte.flatMap { feeder.itinerario(from.rfiCode!!, it, quando.toLocalDate()) }
                    val veloce = avDaHub(hubRfi, hubNaz, to, quando, sources)
                    MotoreViaggiMisti.assembla(add, veloce, direttoMigliore)
                } else {
                    // specchio: veloce from -> nodo, adduzione nodo -> to
                    val veloce = avVersoHub(from, hubRfi, hubNaz, quando, sources)
                    val add = porte.flatMap { feeder.itinerario(it, to.rfiCode!!, quando.toLocalDate()) }
                    MotoreViaggiMisti.assembla(veloce, add, direttoMigliore)
                }
            }
        }.awaitAll().flatten()

        // Tiene solo i viaggi che partono non troppo prima dell'orario chiesto,
        // e i primi per orario di partenza.
        perHub
            .filter { !it.departure.isBefore(quando.minusHours(1)) }
            .sortedBy { it.departure }
            .take(MAX_RISULTATI)
    }

    /** La punta non-feeder e' servibile in alta velocita'? Da Italo o dal nazionale. */
    private fun puntaAV(s: Station): Boolean =
        italo.covers(s.rfiCode) || s.idNazionale != null || s.locationId < PRIMO_ID_SINTETICO

    /** Le corse veloci dall'hub alla destinazione: Italo e/o Freccia, secondo le fonti. */
    private suspend fun avDaHub(
        hubRfi: String,
        hubNaz: Station?,
        to: Station,
        quando: LocalDateTime,
        sources: Set<DataSource>,
    ): List<Journey> {
        val out = mutableListOf<Journey>()
        if (DataSource.ITALO in sources && italo.covers(to.rfiCode)) {
            out += italo.itinerario(hubRfi, to.rfiCode!!, quando.toLocalDate())
        }
        if (DataSource.TRENITALIA in sources && hubNaz != null) {
            out += journeys
                .searchAll(hubNaz, to.perNazionale(), quando, AV_LIMIT, setOf(DataSource.TRENITALIA))
                .journeys.conFonte(DataSource.TRENITALIA)
        }
        return out
    }

    /** Le corse veloci dalla partenza all'hub: specchio di [avDaHub]. */
    private suspend fun avVersoHub(
        from: Station,
        hubRfi: String,
        hubNaz: Station?,
        quando: LocalDateTime,
        sources: Set<DataSource>,
    ): List<Journey> {
        val out = mutableListOf<Journey>()
        if (DataSource.ITALO in sources && italo.covers(from.rfiCode)) {
            out += italo.itinerario(from.rfiCode!!, hubRfi, quando.toLocalDate())
        }
        if (DataSource.TRENITALIA in sources && hubNaz != null) {
            out += journeys
                .searchAll(from.perNazionale(), hubNaz, quando, AV_LIMIT, setOf(DataSource.TRENITALIA))
                .journeys.conFonte(DataSource.TRENITALIA)
        }
        return out
    }

    /**
     * Marca le gambe con la loro fonte.
     *
     * Le Frecce non la mette (storicamente c'era una sola sorgente nazionale), e
     * senza, un misto EAV+Freccia sembrerebbe a un operatore solo — non lo si
     * riconoscerebbe come composto ([Journey.multiOperator]).
     */
    private fun List<Journey>.conFonte(fonte: DataSource): List<Journey> =
        map { j -> j.copy(legs = j.legs.map { it.copy(source = it.source ?: fonte) }) }

    /**
     * La stazione nazionale dell'hub, dalle sue coordinate.
     *
     * Serve il `locationId` che Le Frecce sa instradare; la piu' vicina alle
     * coordinate fisse dell'hub e' l'hub stesso. Si preferisce quella col codice
     * RFI dell'hub, se c'e', cosi' la gamba Freccia riparte dallo stesso nodo del
     * feeder e il motore le cuce.
     */
    private suspend fun stazioneNazionaleHub(hub: HubAV.Hub): Station? {
        val vicine = runCatching { stations.nearest(hub.lat, hub.lon) }.getOrDefault(emptyList())
        return vicine.firstOrNull { it.station.rfiCode == hub.rfi }?.station
            ?: vicine.firstOrNull()?.station
    }

    private companion object {
        const val MAX_RISULTATI = 6
        /** Quante corse veloci chiedere per gamba: abbastanza da coprire l'attesa al cambio. */
        const val AV_LIMIT = 8
        /** Sotto: id nazionali del BFF; da qui in su, id sintetici fuori-RFI. */
        const val PRIMO_ID_SINTETICO = 9_000_000_000L
    }
}
