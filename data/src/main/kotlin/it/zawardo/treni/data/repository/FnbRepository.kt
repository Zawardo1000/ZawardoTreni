package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.remote.fnb.FnbApi
import it.zawardo.treni.data.remote.fnb.FnbStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Ferrotramviaria: la Bari - Barletta e il servizio metropolitano di Bari.
 *
 * Copre cinque comuni che sulla rete nazionale non hanno stazione — Bitonto,
 * Terlizzi, Ruvo, Corato, Andria — e il collegamento con l'aeroporto di Bari.
 * Niente di tutto questo passa da RFI, quindi prima di questa classe per l'app
 * non circolava affatto.
 *
 * Fra le sorgenti non-RFI e' la piu' facile: risponde JSON e in una sola
 * chiamata da' arrivi e partenze con ritardo, binario e soppressione. Non c'e'
 * HTML da interpretare come per EAV e Trenord, ne' cifratura come per Trenord.
 *
 * Come per EAV, **le stazioni sono sue**: codici sintetici `FNB<id>` (vedi
 * [FnbStations]), fuori dal catalogo RFI. Fuori da quelle [covers] dice di no
 * senza spendere una chiamata.
 *
 * Per ora solo il tabellone, che e' l'unica cosa che il portale pubblichi in
 * tempo reale. L'orario — percorso delle corse, ricerca fra due fermate, date
 * future — non c'e': il GTFS aziendale e' offline dal 2025.
 */
class FnbRepository(
    private val api: FnbApi,
) {
    /** Vero se il codice indirizza una fermata Ferrotramviaria. */
    fun covers(stationCode: String?): Boolean = FnbStations.isFnb(stationCode)

    /** Il nome della fermata dietro un codice, per le intestazioni. */
    fun stationName(stationCode: String?): String? = FnbStations.byCodice(stationCode)?.nome

    /**
     * Arrivi o partenze di una fermata, gia' nel modello del tabellone.
     *
     * Vuoto fuori dalla rete, senza interrogare nessuno.
     *
     * La data non e' un parametro passato all'endpoint perche' l'endpoint non
     * la accetta: la risposta e' sempre "adesso". Chiedere un giorno diverso da
     * oggi restituisce vuoto invece di spacciare l'orario di adesso per quello
     * di domani.
     *
     * Una sola chiamata porta entrambe le liste, ma se ne restituisce una: e'
     * il tabellone a sapere quale sta mostrando, e tenere l'altra
     * significherebbe farla invecchiare in memoria.
     */
    suspend fun board(
        stationCode: String,
        arrivals: Boolean = false,
        date: LocalDate = LocalDate.now(ROME),
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        val codSito = FnbStations.codSito(stationCode) ?: return@withContext emptyList()
        if (date != LocalDate.now(ROME)) return@withContext emptyList()

        val tabellone = runCatching { api.tabellone(codSito) }.getOrNull() ?: return@withContext emptyList()
        val corse = if (arrivals) tabellone.arrivi else tabellone.partenze
        corse.mapNotNull { it.toBoardEntry(arrivals) }
    }

    /**
     * La fermata Ferrotramviaria piu' vicina a un punto, se e' abbastanza
     * vicina da avere senso proporla.
     *
     * Il limite esiste perche' la rete e' provinciale: da Milano la fermata
     * "piu' vicina" sarebbe comunque a ottocento chilometri. Dieci chilometri
     * coprono l'area servita — l'asse Bari - Barletta e l'hinterland — senza
     * invadere il resto d'Italia.
     */
    fun nearest(latitude: Double, longitude: Double, maxMeters: Double = 10_000.0): Station? =
        FnbStations.piuVicina(latitude, longitude)
            ?.takeIf { it.second <= maxMeters }
            ?.first
            ?.toStation()

    /** Le fermate che corrispondono a quello che si sta digitando. */
    fun search(query: String, limit: Int = 12): List<Station> =
        FnbStations.cerca(query, limit).map { it.toStation() }

    /** Tutte le fermate, per chi voglia elencarle. */
    fun allStations(): List<Station> = FnbStations.tutte.map { it.toStation() }

    private companion object {
        /**
         * Base degli id sintetici.
         *
         * Il resto dell'app identifica le stazioni con un `locationId` che
         * arriva da Trenitalia e sta intorno a 8,3 · 10⁸. EAV occupa la fascia
         * da 9 · 10⁹; questa parte da 9,1 · 10⁹ perche' le due numerazioni
         * native si sovrappongono — EAV arriva a 126, qui si parte da 1110 — e
         * senza fasce separate due fermate diverse finirebbero con lo stesso id.
         */
        const val LOCATION_ID_BASE = 9_100_000_000L
    }

    /**
     * La fermata nel modello comune.
     *
     * [Station.rfiCode] porta il codice sintetico invece di un vero codice RFI,
     * che per queste fermate non esiste. Non e' un abuso del campo: quel campo
     * e' gia' "il codice con cui si chiede il tempo reale", e qui quello e'. La
     * conseguenza voluta e' che [Station.trackable] resti vero, perche' una
     * fermata Ferrotramviaria il suo tabellone ce l'ha.
     */
    private fun FnbStations.Stazione.toStation() = Station(
        rfiCode = codice,
        locationId = LOCATION_ID_BASE + id,
        name = nome,
        latitude = lat,
        longitude = lon,
    )
}
