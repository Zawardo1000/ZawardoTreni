package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.mapper.numeroFnb
import it.zawardo.treni.data.mapper.toBoardEntry
import it.zawardo.treni.data.mapper.toJourney
import it.zawardo.treni.data.mapper.toTrainStatus
import it.zawardo.treni.data.remote.fnb.FnbApi
import it.zawardo.treni.data.remote.fnb.FnbStations
import it.zawardo.treni.data.remote.fnb.FnbTrattaDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
 * Il tempo reale sta solo nel tabellone: ritardo, binario e soppressione, e
 * solo per l'ora che viene. L'**orario** — ricerca fra due fermate, giorni
 * futuri, fermate di una corsa, prezzo — sta invece nella ricerca del portale
 * di vendita ([itinerario], [dettaglioCorsa]), che risponde fino alla fine
 * dell'orario; il GTFS aziendale, offline dal 2025, non serve piu'.
 */
class FnbRepository(
    private val api: FnbApi,
) : FonteStazioniLocale {

    override fun suggerisci(query: String): List<Station> = search(query)

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
     * I viaggi da una fermata all'altra, con le fermate di ogni tratto e il
     * prezzo del biglietto.
     *
     * E' l'unico orario di questa rete: il tabellone conosce solo l'ora che
     * viene e non dice il percorso. Risponde anche per i **giorni futuri**, fino
     * alla fine dell'orario — il 20/09/2026 fino al 31/12 — e per questo e' anche
     * l'unico modo di sapere a che ora passa un treno domani.
     *
     * La ricerca da' le soluzioni del resto della giornata da [quando] in poi;
     * le fermate di ciascuna costano una chiamata in piu', e si chiedono solo
     * per le prime [limit], poche alla volta. Una soluzione di cui non si
     * ottengono le fermate resta fuori: senza, un cambio sarebbe invisibile.
     *
     * Fuori dalla rete, o se il portale non risponde, la lista e' vuota: e' una
     * rete in piu', e non deve far fallire la ricerca delle altre.
     */
    suspend fun itinerario(
        fromCode: String,
        toCode: String,
        quando: LocalDateTime,
        limit: Int = SOLUZIONI_MAX,
    ): List<Journey> = withContext(Dispatchers.IO) {
        val da = FnbStations.codSito(fromCode) ?: return@withContext emptyList()
        val a = FnbStations.codSito(toCode) ?: return@withContext emptyList()
        if (da == a) return@withContext emptyList()

        unaAllaVolta.withLock {
            conSessione()
            val prima = unaRicerca(da, a, quando, limit)
            // Soluzioni senza fermate vuol dire sessione scaduta: si riapre e si
            // rifa' la ricerca, perche' gli id valgono solo dentro la loro sessione.
            if (prima.trovate > 0 && prima.viaggi.isEmpty()) {
                riapriSessione()
                return@withLock unaRicerca(da, a, quando, limit).viaggi
            }
            prima.viaggi
        }
    }

    /** Quante soluzioni ha dato la ricerca, e quante se ne sono lette per intero. */
    private class Ricerca(val trovate: Int, val viaggi: List<Journey>)

    private suspend fun unaRicerca(da: String, a: String, quando: LocalDateTime, limit: Int): Ricerca {
        val soluzioni = runCatching {
            api.cerca(from = da, to = a, quando = quando.format(GIORNO), ora = quando.format(ORA))
        }.getOrNull().orEmpty().take(limit)
        if (soluzioni.isEmpty()) return Ricerca(0, emptyList())

        val porta = Semaphore(DETTAGLI_INSIEME)
        val viaggi = coroutineScope {
            soluzioni.map { soluzione ->
                async {
                    val id = soluzione.idSoluzione ?: return@async null
                    val dettaglio = porta.withPermit {
                        runCatching { api.soluzione(id) }.getOrNull()
                    } ?: return@async null
                    dettaglio.toJourney(soluzione.prezzo)
                }
            }.awaitAll().filterNotNull()
        }
        return Ricerca(soluzioni.size, viaggi)
    }

    /**
     * Le fermate di una corsa, come le sa il portale, con gli orari di tabella
     * di quel giorno.
     *
     * Il portale non ha una ricerca per numero: si rifa' una ricerca e si tiene
     * la soluzione che contiene quel numero. Il numero della ricerca porta la
     * sigla (`ET 91008`), quello del tabellone no (`91008`): si confrontano le
     * sole cifre.
     *
     * **Prima si prova la corsa intera**, cercando da un capolinea all'altro nel
     * verso in cui va il treno: cosi' le fermate sono quelle vere, dalla sua
     * origine al suo termine, anche se chi guarda ne percorre solo due. Se li'
     * non si trova — il treno fa un tratto che quella ricerca non propone — resta
     * il tratto fra [fromCode] e [toCode], e la corsa lo dichiara.
     *
     * Senza [toCode] (si arriva dal tabellone, dove non c'e' una discesa) valgono
     * i due capolinea della linea.
     *
     * Niente tempo reale: per questa rete il ritardo sta solo sul tabellone della
     * fermata, e la corsa lo dice nel suo avviso.
     */
    suspend fun dettaglioCorsa(
        numero: String,
        fromCode: String?,
        toCode: String?,
        quando: LocalDateTime,
    ): TrainStatus? = withContext(Dispatchers.IO) {
        val cifre = numeroFnb(numero)?.second ?: return@withContext null
        val da = FnbStations.codSito(fromCode) ?: return@withContext null
        val arrivi = listOfNotNull(FnbStations.codSito(toCode)) +
            CAPOLINEA.mapNotNull { FnbStations.codSito(it) }
        val percorse = arrivi.distinct().filter { it != da }
        if (percorse.isEmpty()) return@withContext null

        unaAllaVolta.withLock {
            // Il tratto che si percorre: dice anche in che verso va il treno.
            val tratta = percorse.firstNotNullOfOrNull { a ->
                trattaDelNumero(cifre, da, a, quando.minusMinutes(1))
            } ?: return@withLock null

            val intera = daCapolineaACapolinea(cifre, tratta, quando)
            // Fra le due vince quella con piu' fermate: se il treno parte gia' da un
            // capolinea le due coincidono, ed e' comunque la corsa intera.
            val migliore = listOfNotNull(intera, tratta).maxByOrNull { it.fermate.size } ?: tratta
            migliore.toTrainStatus(
                notice = if (intera != null) {
                    "Orario di Ferrotramviaria per il giorno scelto. Il ritardo di questa corsa sta solo " +
                        "sul tabellone della fermata."
                } else {
                    "Le fermate sono quelle del tratto che percorri: di piu', di questa corsa, il portale " +
                        "non dice. Il ritardo sta solo sul tabellone della fermata."
                },
            )
        }
    }

    /**
     * La corsa intera, cercata fra i due capolinea nel verso del treno.
     *
     * Il verso lo dice l'ordine dei codici, che cresce da Bari verso Barletta.
     * Null se la ricerca fra i capolinea non propone quel treno: capita ai
     * servizi brevi, e allora resta il tratto percorso.
     */
    private suspend fun daCapolineaACapolinea(
        cifre: String,
        tratta: FnbTrattaDto,
        quando: LocalDateTime,
    ): FnbTrattaDto? {
        val partenza = FnbStations.daCodSito(tratta.sitoPartenza?.codSito)?.let { id(it) } ?: return null
        val arrivo = FnbStations.daCodSito(tratta.sitoArrivo?.codSito)?.let { id(it) } ?: return null
        if (partenza == arrivo) return null
        val (origine, fine) = if (arrivo > partenza) CAPOLINEA else CAPOLINEA.reversed()
        val da = FnbStations.codSito(origine) ?: return null
        val a = FnbStations.codSito(fine) ?: return null
        /*
         * Dall'origine la salita puo' essere piu' avanti: si parte da prima. Se
         * la ricerca fra i capolinea trova il treno, quella e' la corsa per
         * intero — dalla sua origine al suo termine — anche quando coincide col
         * tratto percorso, perche' oltre i capolinea non va nessuno.
         */
        return trattaDelNumero(cifre, da, a, quando.minusMinutes(ANTICIPO_CAPOLINEA))
    }

    /** La tratta di [cifre] nella ricerca da [da] a [a] dalle [dalle]; null se non c'e'. */
    private suspend fun trattaDelNumero(
        cifre: String,
        da: String,
        a: String,
        dalle: LocalDateTime,
    ): FnbTrattaDto? {
        conSessione()
        val primo = unaTratta(cifre, da, a, dalle)
        if (primo.tratta != null || !primo.sessioneSospetta) return primo.tratta
        // Come nella ricerca: il dettaglio vuoto con la ricerca piena e' il
        // sintomo della sessione scaduta, e allora si riapre. Se invece quel
        // treno su questa tratta non c'e', rifare la stessa domanda darebbe la
        // stessa risposta: si cerca sul tratto dopo, e `dettaglioCorsa` ne prova
        // fino a tre.
        riapriSessione()
        return unaTratta(cifre, da, a, dalle).tratta
    }

    /**
     * Com'e' andata una lettura: la tratta, e se il vuoto puzza di sessione
     * scaduta — cioe' la ricerca ha proposto quel treno ma il dettaglio della
     * soluzione e' tornato senza niente.
     */
    private class Tentativo(val tratta: FnbTrattaDto?, val sessioneSospetta: Boolean)

    private suspend fun unaTratta(cifre: String, da: String, a: String, dalle: LocalDateTime): Tentativo {
        val soluzioni = runCatching {
            api.cerca(from = da, to = a, quando = dalle.format(GIORNO), ora = dalle.format(ORA))
        }.getOrNull().orEmpty()
        val sua = soluzioni.firstOrNull { s -> s.elencoCorse.any { numeroFnb(it)?.second == cifre } }
            ?: return Tentativo(null, sessioneSospetta = false)
        val id = sua.idSoluzione ?: return Tentativo(null, sessioneSospetta = false)
        val dettaglio = runCatching { api.soluzione(id) }.getOrNull()
            ?: return Tentativo(null, sessioneSospetta = true)
        val tratta = dettaglio.tratte.firstOrNull { numeroFnb(it.numero)?.second == cifre }
        return Tentativo(tratta, sessioneSospetta = tratta == null && dettaglio.tratte.isEmpty())
    }

    private fun id(codice: String): Int? = codice.removePrefix(FnbStations.PREFIX).toIntOrNull()

    /**
     * Una lettura del portale alla volta.
     *
     * Il `JSESSIONID` e' uno solo per tutta l'app, e gli `idSoluzione` valgono
     * **solo dentro la sessione che li ha prodotti**: due letture in parallelo —
     * la pagina di un viaggio carica una tratta per gamba — si rubavano il
     * cookie a vicenda, e il dettaglio dell'una rispondeva `{}` perche' l'altra
     * aveva appena riaperto la sessione. Serializzare costa qualche secondo su
     * una rete di 38 fermate; sbagliare costa le fermate.
     */
    private val unaAllaVolta = Mutex()

    /** Quando si e' aperta la sessione del portale l'ultima volta. */
    @Volatile
    private var sessioneAperta = 0L

    /**
     * Apre la sessione se non e' gia' aperta da poco, e **prima della ricerca**:
     * il dettaglio di una soluzione senza `JSESSIONID` risponde `{}` con 200 —
     * un vuoto che sembra una risposta buona — e gli id delle soluzioni valgono
     * solo dentro la sessione che le ha prodotte. Il cookie lo imposta solo una
     * pagina web, non il JSON.
     */
    private suspend fun conSessione() {
        val adesso = System.currentTimeMillis()
        if (adesso - sessioneAperta < SESSIONE_VALIDA_MS) return
        riapriSessione()
    }

    private suspend fun riapriSessione() {
        // Solo se il portale ha risposto davvero: un tentativo fallito segnato come
        // sessione buona terrebbe fuori il prossimo per dieci minuti, e in quei
        // dieci minuti ogni dettaglio tornerebbe `{}` — un vuoto che sembra una
        // risposta.
        sessioneAperta = if (runCatching { api.apriSessione().close() }.isSuccess) {
            System.currentTimeMillis()
        } else {
            0L
        }
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
        /** Il formato della data nella ricerca. */
        val GIORNO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /** E quello dell'ora. */
        val ORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Quante soluzioni portare a schermo. La ricerca ne da' tutte quelle del
         * resto della giornata (13-18), e ognuna costa una chiamata per le sue
         * fermate: di piu' non servirebbe a nessuno e si pagherebbero tutte.
         */
        const val SOLUZIONI_MAX = 8

        /** Quanti dettagli chiedere insieme, per non bussare in raffica. */
        const val DETTAGLI_INSIEME = 3

        /** Per quanto si considera buona la sessione aperta. */
        const val SESSIONE_VALIDA_MS = 10 * 60_000L

        /**
         * I due capolinea della linea, in ordine di percorso: Bari Centrale FNB e
         * Barletta Centrale. Fra loro passa tutto, aeroporto compreso.
         */
        val CAPOLINEA = listOf(FnbStations.PREFIX + 1110, FnbStations.PREFIX + 1180)

        /** Di quanto si torna indietro per cercare la corsa dal suo capolinea. */
        const val ANTICIPO_CAPOLINEA = 120L

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
