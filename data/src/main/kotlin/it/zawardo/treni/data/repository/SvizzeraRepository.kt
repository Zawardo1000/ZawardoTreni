package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.diVettore
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.remote.svizzera.SvizzeraApi
import it.zawardo.treni.data.remote.svizzera.SvizzeraStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Le due ferrovie transfrontaliere che si chiedono all'orario svizzero.
 *
 * - **Vigezzina - Centovalli**, Domodossola - Locarno, di SSIF e FART. Non e'
 *   rete RFI, ma la Svizzera la pubblica per intero, fermate italiane comprese:
 *   Santa Maria Maggiore, Malesco, Re e Druogno arrivano da qui, e da nessun
 *   altro posto.
 * - **TILO**, il regionale ticinese. Qui l'app guadagna il **lato svizzero** —
 *   Lugano, Mendrisio, Locarno, Giubiasco — che ViaggiaTreno non ha affatto.
 *
 * Stanno insieme perche' la fonte e' una sola: stessa API, stessa chiamata,
 * stesso formato. Due sorgenti separate avrebbero significato due interruttori
 * per la stessa cosa.
 *
 * ## Il filtro sui vettori e' per stazione, non globale
 *
 * E' il punto delicato. A Domodossola l'orario svizzero risponde anche con SBB e
 * BLS: sono gli EuroCity su rete RFI, che ViaggiaTreno pubblica gia', e tenerli
 * significherebbe mostrarli due volte. In Ticino invece SBB e' esattamente cio'
 * che si cerca, perche' e' sotto quel nome che circolano le linee S.
 *
 * Lo stesso vettore va quindi tenuto in un posto e scartato nell'altro, e l'unico
 * posto dove quella decisione ha senso e' la stazione: la porta
 * [SvizzeraStations.Rete].
 *
 * ## Solo partenze
 *
 * Il tabellone degli arrivi non e' implementato, e non per pigrizia: l'orario
 * svizzero, interrogato per gli arrivi, continua a restituire il **capolinea**
 * nel campo della direzione, e la lista dei passaggi di un tabellone e' vuota.
 * L'origine di una corsa in arrivo, li' dentro, non c'e'. Mostrarla lo stesso
 * vorrebbe dire dire a chi aspetta che il treno viene da dove sta andando.
 *
 * Neanche si puo' dedurre: ci sono corse limitate a Camedo, Intragna, Verdasio
 * sulla Vigezzina e a Mendrisio o Giubiasco in Ticino, quindi dal capolinea non
 * si risale all'origine.
 */
class SvizzeraRepository(
    private val api: SvizzeraApi,
) : FonteStazioniLocale {

    override fun suggerisci(query: String): List<Station> = search(query)

    /** Vero se il codice indirizza una fermata servita da questa fonte. */
    fun covers(stationCode: String?): Boolean = SvizzeraStations.isSvizzera(stationCode)

    /**
     * Vero se la fermata esiste **solo** qui.
     *
     * Chiasso e Bellinzona hanno anche un codice RFI: la a stazione e' una sola,
     * e il suo tabellone si compone di due fonti. Chi decide se interrogare
     * ViaggiaTreno deve saperlo distinguere, o su quelle due spegnerebbe meta'
     * delle corse.
     */
    fun soloSvizzera(stationCode: String?): Boolean =
        SvizzeraStations.byCodice(stationCode)?.propria == true

    /** Il nome della fermata dietro un codice, per le intestazioni. */
    fun stationName(stationCode: String?): String? =
        SvizzeraStations.byCodice(stationCode)?.nome

    /**
     * Le partenze da una fermata, gia' nel modello del tabellone.
     *
     * Vuoto fuori dalle fermate servite, senza interrogare nessuno. Vuoto anche
     * per [arrivals] vero: vedi la nota di classe.
     *
     * L'orario svizzero accetterebbe una data, ma qui non si passa: il resto
     * dell'app tratta il tabellone come "adesso", e le altre sorgenti in tempo
     * reale una data non la accettano affatto. Renderlo l'unico a rispondere per
     * domani sarebbe una differenza che nessuno si aspetta.
     */
    suspend fun board(
        stationCode: String,
        arrivals: Boolean = false,
        date: LocalDate = LocalDate.now(ROME),
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        if (arrivals) return@withContext emptyList()
        val id = SvizzeraStations.idSvizzero(stationCode) ?: return@withContext emptyList()
        if (date != LocalDate.now(ROME)) return@withContext emptyList()

        val vettori = SvizzeraStations.vettori(stationCode)
        if (vettori.isEmpty()) return@withContext emptyList()

        val tabellone = runCatching { api.stationboard(id) }.getOrNull()
            ?: return@withContext emptyList()
        tabellone.stationboard
            .filter { it.diVettore(vettori) }
            .mapNotNull { it.toBoardEntry() }
    }

    /**
     * La fermata piu' vicina a un punto, se e' abbastanza vicina da avere senso
     * proporla.
     *
     * Quindici chilometri: in Val Vigezzo le fermate sono rade e i paesi stanno
     * sparsi sui versanti, e un limite piu' stretto lascerebbe senza risposta chi
     * e' a Craveggia o a Villette pur avendo la linea a pochi minuti.
     */
    fun nearest(latitude: Double, longitude: Double, maxMeters: Double = 15_000.0): Station? =
        SvizzeraStations.piuVicina(latitude, longitude)
            ?.takeIf { it.second <= maxMeters }
            ?.first
            ?.toStation()

    /** Le fermate che corrispondono a quello che si sta digitando. */
    fun search(query: String, limit: Int = 12): List<Station> =
        SvizzeraStations.cerca(query, limit).map { it.toStation() }

    /** Le fermate che questa fonte porta in proprio. */
    fun allStations(): List<Station> = SvizzeraStations.proprie.map { it.toStation() }

    private companion object {
        /**
         * Base degli id sintetici.
         *
         * Terza fascia dopo EAV (9,0 · 10⁹) e Ferrotramviaria (9,1 · 10⁹), e
         * prima di ARST (9,3 · 10⁹). Qui l'id nativo e' grande — sono numeri a
         * sette cifre — e senza una fascia propria sconfinerebbe nelle altre.
         */
        const val LOCATION_ID_BASE = 9_200_000_000L
    }

    /**
     * La fermata nel modello comune.
     *
     * Vale solo per quelle che l'app non ha gia' altrove: le altre — Chiasso,
     * Bellinzona — vivono col loro codice RFI e le pubblica il catalogo
     * Trenitalia, quindi non passano mai di qui.
     */
    private fun SvizzeraStations.Stazione.toStation() = Station(
        rfiCode = codice,
        locationId = LOCATION_ID_BASE + (id.toLongOrNull() ?: 0L),
        name = nome,
        latitude = lat,
        longitude = lon,
    )
}
