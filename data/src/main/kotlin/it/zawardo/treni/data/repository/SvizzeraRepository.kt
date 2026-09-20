package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.diVettore
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.remote.svizzera.SearchChApi
import kotlinx.serialization.json.Json
import it.zawardo.treni.data.remote.svizzera.SearchChCorsaDto
import it.zawardo.treni.data.remote.svizzera.SearchChBoardDto
import it.zawardo.treni.data.remote.svizzera.SvizzeraApi
import it.zawardo.treni.data.remote.svizzera.SvizzeraStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
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
 * ## Gli arrivi: l'origine c'e', il tempo reale no
 *
 * Chiesto con `type=arrival`, il tabellone mette nel campo della direzione
 * l'**origine** della corsa, e l'orario d'arrivo nel campo della partenza. Lo si
 * era creduto il capolinea, e per questo gli arrivi mancavano; l'inventario del
 * 19/09/2026 l'ha smentito (`data/fonti/MINORI.md`): a Locarno FART, capolinea
 * della Vigezzina, l'R 70 in arrivo ha `to = "Camedo"`.
 *
 * Il tempo reale di quel tabellone invece e' finto — ritardo sempre null,
 * previsione uguale all'ora della richiesta — e le righe degli arrivi escono come
 * orario e basta (vedi `toBoardEntry`).
 */
class SvizzeraRepository(
    private val api: SvizzeraApi,
    /**
     * `search.ch`, la fonte sotto a `transport.opendata.ch`: serve al ritardo
     * degli arrivi e alle soppressioni, che l'involucro non espone. Null = si
     * resta a quel che dice l'involucro.
     */
    private val searchCh: SearchChApi? = null,
    /**
     * Serve a leggere il corpo di `search.ch` quando lo stato e' sbagliato: vedi
     * [tabelloneDi].
     */
    private val json: Json = Json { ignoreUnknownKeys = true },
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
     * Vuoto fuori dalle fermate servite, senza interrogare nessuno. Gli arrivi
     * senza tempo reale: vedi la nota di classe.
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
        val id = SvizzeraStations.idSvizzero(stationCode) ?: return@withContext emptyList()
        if (date != LocalDate.now(ROME)) return@withContext emptyList()

        val vettori = SvizzeraStations.vettori(stationCode)
        if (vettori.isEmpty()) return@withContext emptyList()

        val tabellone = runCatching {
            api.stationboard(id, type = if (arrivals) "arrival" else "departure")
        }.getOrNull() ?: return@withContext emptyList()
        val righe = tabellone.stationboard
            .filter { it.diVettore(vettori) }
            .mapNotNull { it.toBoardEntry(arrivo = arrivals) }
        conSearchCh(righe, id, arrivals)
    }

    /**
     * Il ritardo e le **soppressioni** da `search.ch`, sulle righe gia' lette.
     *
     * `transport.opendata.ch` e' un involucro di `search.ch` e per strada perde
     * due cose: le soppressioni, che la fonte segna con un ritardo «X», e il
     * **ritardo degli arrivi**, che li' non c'e' mai — ed e' il motivo per cui
     * le righe in arrivo uscivano senza tempo reale.
     *
     * E' un'aggiunta, non una fonte: le righe restano quelle di prima, si
     * accoppiano per numero di treno e ora di tabella, e se `search.ch` non
     * risponde — o risponde qualcosa che non e' il tabellone che abbiamo chiesto
     * — non cambia niente.
     */
    private suspend fun conSearchCh(
        righe: List<BoardEntry>,
        id: String,
        arrivals: Boolean,
    ): List<BoardEntry> {
        val searchCh = searchCh ?: return righe
        if (righe.isEmpty()) return righe

        val corse = tabelloneDi(searchCh, id, arrivals) ?: return righe

        return righe.map { riga ->
            val sua = corse.firstOrNull { corsa ->
                stessoNumero(corsa.numero, riga.trainRef.number) && stessaOra(corsa.time, riga.scheduledTime)
            } ?: return@map riga
            val scritto = (if (arrivals) sua.ritardoArrivo else sua.ritardoPartenza) ?: return@map riga
            when {
                // «X»: la corsa non si fa. E' l'unico modo di saperlo.
                scritto.trim().equals("X", ignoreCase = true) ->
                    riga.copy(state = TrainState.CANCELLED, realtime = true)
                else -> {
                    val minuti = scritto.trim().removePrefix("+").toIntOrNull() ?: return@map riga
                    riga.copy(
                        delayMinutes = minuti,
                        state = if (minuti > 0) TrainState.DELAYED else riga.state,
                        realtime = true,
                    )
                }
            }
        }
    }

    /**
     * Il tabellone di `search.ch`, **se e' davvero quello chiesto**.
     *
     * Lo stato non decide: gli arrivi rispondono quasi sempre 404 col tabellone
     * giusto in corpo (vedi [SearchChApi.tabellone]). Decide il contenuto, e si
     * accetta solo cio' che e' riconoscibile: la fermata dev'essere quella che
     * abbiamo chiesto e le corse devono esserci. Gli errori veri non passano:
     * un URL sbagliato da' `{"error": …}` senza fermata ne' corse, una fermata
     * inesistente da' `{"messages": …}` e nessuna corsa, e una pagina d'errore
     * non si deserializza affatto.
     */
    private suspend fun tabelloneDi(
        searchCh: SearchChApi,
        id: String,
        arrivals: Boolean,
    ): List<SearchChCorsaDto>? {
        val risposta = runCatching {
            searchCh.tabellone(
                stop = id,
                mode = if (arrivals) SearchChApi.ARRIVI else SearchChApi.PARTENZE,
            )
        }.getOrNull() ?: return null

        val letto = risposta.body() ?: risposta.errorBody()?.let { corpo ->
            runCatching { json.decodeFromString<SearchChBoardDto>(corpo.string()) }.getOrNull()
        } ?: return null

        return letto.connections.takeIf { it.isNotEmpty() && letto.stop?.id?.trim() == id }
    }

    /**
     * Lo stesso treno scritto in due modi: `search.ch` tiene gli zeri davanti
     * (`025115`), `transport.opendata.ch` no (`25115`). E' lo stesso numero, e a
     * confrontarli come stringhe non si accoppiava piu' niente.
     */
    private fun stessoNumero(searchCh: String?, tabellone: String): Boolean {
        val qui = searchCh?.trim()?.trimStart('0')?.takeIf { it.isNotEmpty() } ?: return false
        val la = tabellone.trim().trimStart('0').takeIf { it.isNotEmpty() } ?: return false
        return qui == la
    }

    /** `2026-09-20 09:51:00` e `09:51` sono la stessa corsa. */
    private fun stessaOra(searchCh: String?, tabellone: String?): Boolean {
        val qui = tabellone?.trim()?.takeIf { it.length >= 4 } ?: return false
        val la = searchCh?.substringAfter(' ')?.take(5) ?: return false
        return qui.take(5) == la
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
