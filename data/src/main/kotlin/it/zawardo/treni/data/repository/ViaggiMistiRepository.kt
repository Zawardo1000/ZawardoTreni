package it.zawardo.treni.data.repository

import it.zawardo.treni.data.misti.HubAV
import it.zawardo.treni.data.misti.Interscambi
import it.zawardo.treni.data.misti.MotoreViaggiMisti
import it.zawardo.treni.data.remote.eav.EavStations
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.LocalDateTime

/**
 * Costruisce i viaggi che cambiano operatore per strada.
 *
 * E' la feature beta dei "viaggi misti": mette insieme un feeder e una lunga
 * percorrenza di reti diverse, che nessuna singola sorgente sa collegare. Il
 * caso che copre e' quello che serve davvero e che nessun altro fa —
 * **una rete fuori-RFI piu' l'alta velocita' Italo**: Sorrento→Roma, EAV fino a
 * Napoli e Italo oltre. Il resto, RFI con RFI, lo fa gia' il BFF Le Frecce.
 *
 * Il lavoro sporadico e delicato — preselezione, concatenazione, vincoli — vive
 * altrove ([HubAV], [Interscambi], [MotoreViaggiMisti]) ed e' gia' provato a
 * tavolino. Qui si orchestra soltanto: si scelgono gli hub, si chiedono le due
 * meta' alle rispettive fonti, si passano al motore.
 */
class ViaggiMistiRepository(
    private val eav: EavRepository,
    private val italo: ItaloRepository,
) {

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
    ): List<Journey> = coroutineScope {
        val fromEav = EavStations.isEav(from.rfiCode)
        val toEav = EavStations.isEav(to.rfiCode)
        val fromItalo = italo.covers(from.rfiCode)
        val toItalo = italo.covers(to.rfiCode)

        // Per ora un solo schema e il suo specchio: EAV di adduzione + Italo.
        // Serve una punta EAV e una punta che Italo sappia raggiungere.
        val versoItalo = fromEav && toItalo   // A(EAV) -> ... -> C(Italo)
        val daItalo = fromItalo && toEav      // A(Italo) -> ... -> C(EAV)
        if (!versoItalo && !daItalo) return@coroutineScope emptyList()

        val hubs = HubAV.candidati(from.latitude, from.longitude, to.latitude, to.longitude)
        if (hubs.isEmpty()) return@coroutineScope emptyList()

        val perHub = hubs.map { hub ->
            async {
                val hubRfi = hub.rfi ?: return@async emptyList<Journey>()
                // Le porte EAV del nodo: le stazioni EAV a piedi da questo hub.
                val porteEav = Interscambi.aPiediDa(hubRfi).map { it.codice }
                    .filter { EavStations.isEav(it) }
                if (porteEav.isEmpty()) return@async emptyList<Journey>()

                if (versoItalo) {
                    // feeder EAV: from -> ogni porta EAV del nodo
                    val prime = porteEav.flatMap { eav.itinerario(from.rfiCode!!, it, quando.toLocalDate()) }
                    // long-haul Italo: nodo -> to
                    val seconde = italo.itinerario(hubRfi, to.rfiCode!!, quando.toLocalDate())
                    MotoreViaggiMisti.assembla(prime, seconde, direttoMigliore)
                } else {
                    // specchio: Italo from -> nodo, feeder EAV nodo -> to
                    val prime = italo.itinerario(from.rfiCode!!, hubRfi, quando.toLocalDate())
                    val seconde = porteEav.flatMap { eav.itinerario(it, to.rfiCode!!, quando.toLocalDate()) }
                    MotoreViaggiMisti.assembla(prime, seconde, direttoMigliore)
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

    private companion object {
        const val MAX_RISULTATI = 6
    }
}
