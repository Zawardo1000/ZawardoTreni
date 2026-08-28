package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.remote.eav.EavApi
import it.zawardo.treni.data.remote.eav.EavBoardParser
import it.zawardo.treni.data.remote.eav.EavOrario
import it.zawardo.treni.data.remote.eav.EavStations
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * EAV, la quinta sorgente: Circumvesuviana, Cumana, Circumflegrea e suburbane
 * napoletane.
 *
 * Copre l'ultimo buco davvero grande. Le linee EAV non sono su RFI, quindi non
 * le conosce ne' ViaggiaTreno ne' il BFF Le Frecce: prima di questa classe
 * Sorrento, Pompei Scavi, Ercolano e Castellammare per l'app non esistevano, e
 * chi si muove nel golfo di Napoli non aveva nulla.
 *
 * A differenza delle altre quattro, **le stazioni sono sue**: hanno codici
 * sintetici `EAV<id>` (vedi [EavStations]) e non compaiono nel catalogo RFI.
 * Fuori da quelle [covers] dice di no senza spendere una chiamata.
 *
 * **Due fonti, non una.** Il tabellone e' l'unica cosa che EAV pubblichi in
 * tempo reale, e copre solo oggi e solo le stazioni che hanno un monitor.
 * Tutto il resto — i giorni futuri, e le ventiquattro stazioni delle altre
 * reti EAV — viene dall'orario ufficiale imbarcato ([EavOrario]), le cui righe
 * escono con [BoardEntry.realtime] falso perche' un ritardo non lo conoscono.
 * Dove entrambe potrebbero rispondere comanda il tabellone: sa cose che
 * l'orario non puo' sapere.
 */
class EavRepository(
    private val api: EavApi,
    /** Dove l'aggiornamento deposita l'orario. Null = si usa solo l'imbarcato. */
    private val cartella: File? = null,
) {

    /**
     * L'orario, letto una volta e tenuto.
     *
     * Trentaquattro KB compressi e poche centinaia di oggetti: rileggerlo a
     * ogni tabellone sarebbe uno spreco, tenerlo non pesa. [ricarica] serve a
     * riprenderlo dopo un aggiornamento, che e' l'unico momento in cui cambia.
     */
    @Volatile
    private var orario: EavOrario? = null
    private val lucchetto = Any()

    private fun orario(): EavOrario? {
        orario?.let { return it }
        return synchronized(lucchetto) {
            orario ?: EavOrario.carica(cartella)?.also { orario = it }
        }
    }

    /** Rilegge l'orario da disco: da chiamare dopo un aggiornamento riuscito. */
    fun ricarica() {
        synchronized(lucchetto) { orario = null }
    }

    /** Vero se il codice indirizza una stazione EAV. */
    fun covers(stationCode: String?): Boolean = EavStations.isEav(stationCode)

    /**
     * Vero se di quella stazione esiste un tabellone in tempo reale.
     *
     * Non coincide con [covers]: EAV ha stazioni che stanno nell'orario ma non
     * sui monitor, e viceversa. Chi mostra un tabellone deve chiedere questo,
     * non quello, per non mandare una richiesta che tornera' vuota.
     */
    fun hasBoard(stationCode: String?): Boolean =
        EavStations.byCodice(stationCode)?.tabellone == true

    /** Vero se quella stazione compare nell'orario, quindi e' pianificabile. */
    fun canPlan(stationCode: String?): Boolean =
        EavStations.byCodice(stationCode)?.orario == true

    /** Il nome della stazione dietro un codice EAV, per le intestazioni. */
    fun stationName(stationCode: String?): String? = EavStations.byCodice(stationCode)?.nome

    /**
     * Partenze o arrivi di una stazione EAV, gia' nel modello del tabellone.
     *
     * Vuoto fuori dalla rete EAV, senza interrogare nessuno.
     *
     * [date] non viene passata all'endpoint perche' l'endpoint non la accetta:
     * decide invece **quale delle due fonti** risponde. Per oggi il tabellone,
     * per gli altri giorni l'orario — mai il tabellone spacciato per un altro
     * giorno, che e' l'errore che ViaggiaTreno faceva fare col REG 11813.
     * Oltre la copertura dell'orario si risponde vuoto, perche' oltre non si sa
     * nulla: non perche' non ci siano treni.
     */
    suspend fun board(
        stationCode: String,
        arrivals: Boolean = false,
        date: LocalDate = LocalDate.now(ROME),
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        val stazione = EavStations.byCodice(stationCode) ?: return@withContext emptyList()
        val oggi = date == LocalDate.now(ROME)

        /*
         * Il tabellone vero solo dove e quando esiste.
         *
         * Non copre due casi, ed entrambi sono normali: le ventiquattro
         * stazioni delle altre reti EAV — Piscinola-Aversa, Piedimonte Matese,
         * Benevento — che i monitor non hanno affatto, e qualunque giorno che
         * non sia oggi, perche' l'endpoint una data non la accetta. In tutti e
         * due si ripiega sull'orario ufficiale, che quelle risposte le ha.
         */
        if (stazione.tabellone && oggi) {
            val corpo = runCatching {
                api.tabellone(
                    codLoc = stazione.id,
                    tipoLista = if (arrivals) EavApi.ARRIVI else EavApi.PARTENZE,
                ).string()
            }.getOrNull()

            val righe = EavBoardParser.parse(corpo, millis(date))
            /*
             * Se il tabellone risponde, e' lui a comandare: ha i ritardi e le
             * soppressioni, che l'orario non puo' sapere. Si ripiega sull'orario
             * solo quando non risponde affatto — rete assente, servizio giu' —
             * invece di lasciare la schermata vuota.
             */
            if (righe.isNotEmpty()) return@withContext righe
        }

        dallOrario(stazione.id, arrivals, date)
    }

    /**
     * Il tabellone ricostruito dall'orario ufficiale.
     *
     * Ogni riga esce con [BoardEntry.realtime] falso, ed e' la differenza che
     * conta: qui il ritardo non e' zero, e' **sconosciuto**. Il modello vuole un
     * intero e zero e' l'unico che si possa scrivere, ma spacciarlo per
     * puntualita' misurata sarebbe inventare — e su una rete dove le
     * soppressioni sono all'ordine del giorno, come si e' visto, sarebbe anche
     * la bugia piu' dannosa possibile.
     */
    private fun dallOrario(codLoc: Int, arrivals: Boolean, date: LocalDate): List<BoardEntry> {
        val o = orario() ?: return emptyList()
        if (!o.copre(date)) return emptyList()

        return o.passaggi(codLoc, date, partenze = !arrivals).map { p ->
            val minuti = if (arrivals) p.fermata.arrivo else p.fermata.partenza
            val altroCapo = if (arrivals) {
                EavStations.byId(p.origineCodLoc)?.nome
            } else {
                p.corsa.destinazione.ifBlank { EavStations.byId(p.corsa.fermate.last().codLoc)?.nome }
            }
            BoardEntry(
                trainRef = TrainRef(
                    number = p.corsa.numero,
                    // Nessun codice d'origine: EAV non ha un servizio a cui
                    // chiedere l'andamento di una corsa per riferimento.
                    originCode = "",
                    departureDateMillis = millis(date),
                ),
                label = p.corsa.numero,
                category = EavStations.LINEE["L" + p.corsa.linea] ?: ("Linea " + p.corsa.linea),
                direction = altroCapo,
                scheduledTime = orologio(minuti),
                delayMinutes = 0,
                scheduledPlatform = null,
                actualPlatform = null,
                state = TrainState.REGULAR,
                inStation = false,
                realtime = false,
            )
        }
    }

    /** Il giorno piu' lontano su cui l'orario sappia rispondere. */
    fun ultimoGiorno(): LocalDate? = orario()?.ultimoGiorno

    /** Quando e' stato generato l'orario in uso. */
    fun generato(): LocalDate? = orario()?.generato

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.of("Europe/Rome")).toInstant().toEpochMilli()

    /**
     * Minuti dalla mezzanotte in `HH:MM`.
     *
     * Il modulo 1440 non e' pignoleria: il GTFS esprime le corse che scavalcano
     * la mezzanotte con ore oltre le 24, e senza questo un treno delle 00:20
     * comparirebbe come "24:20".
     */
    private fun orologio(minuti: Int): String =
        "%02d:%02d".format((minuti / 60) % 24, minuti % 60)

    /**
     * La fermata EAV piu' vicina a un punto, se e' abbastanza vicina da avere
     * senso proporla.
     *
     * Il limite esiste perche' la rete e' regionale: a Milano la stazione EAV
     * "piu' vicina" sarebbe comunque a duecento chilometri, e proporla sarebbe
     * peggio che non rispondere. Dieci chilometri coprono l'area servita senza
     * invadere il resto d'Italia.
     */
    fun nearest(latitude: Double, longitude: Double, maxMeters: Double = 10_000.0): Station? =
        EavStations.piuVicina(latitude, longitude)
            ?.takeIf { it.second <= maxMeters }
            ?.first
            ?.toStation()

    /** Le fermate EAV che corrispondono a quello che si sta digitando. */
    fun search(query: String, limit: Int = 12): List<Station> =
        EavStations.cerca(query, limit).map { it.toStation() }

    /**
     * I viaggi diretti EAV fra due sue stazioni, in un giorno.
     *
     * E' il feeder di un viaggio misto: Sorrento→Napoli per poi cambiare
     * sull'alta velocita'. Nasce dall'orario imbarcato, quindi le gambe escono
     * senza tempo reale (`realtime = false`) e la data puo' essere anche futura,
     * finche' l'orario la copre. Vuoto se una delle due stazioni non e' EAV, o
     * se non c'e' corsa diretta: un misto con due cambi sulla sola rete di
     * adduzione non lo costruiamo.
     */
    fun itinerario(
        fromCode: String,
        toCode: String,
        date: LocalDate = LocalDate.now(ROME),
    ): List<Journey> {
        val da = EavStations.byCodice(fromCode) ?: return emptyList()
        val a = EavStations.byCodice(toCode) ?: return emptyList()
        val o = orario() ?: return emptyList()
        if (!o.copre(date)) return emptyList()

        val giornoMillis = millis(date)
        return o.collegamenti(da.id, a.id, date).map { c ->
            val partenza = date.atStartOfDay().plusMinutes(c.partenza.toLong())
            val arrivo = date.atStartOfDay().plusMinutes(c.arrivo.toLong())
            val leg = Leg(
                trainNumber = c.corsa.numero,
                category = EavStations.LINEE["L" + c.corsa.linea] ?: ("Linea " + c.corsa.linea),
                from = da.toStation(),
                to = a.toStation(),
                departure = partenza,
                arrival = arrivo,
                source = DataSource.EAV,
            )
            Journey(
                departure = partenza,
                arrival = arrivo,
                duration = java.time.Duration.between(partenza, arrivo),
                legs = listOf(leg),
            )
        }
    }

    /** Tutte le fermate EAV, per chi voglia elencarle per linea. */
    fun allStations(): List<Station> = EavStations.tutte.map { it.toStation() }

    private companion object {
        /**
         * Base degli id sintetici, ben sopra a quelli del BFF Le Frecce.
         *
         * Il resto dell'app identifica le stazioni con un `locationId` che
         * arriva da Trenitalia e sta intorno a 8,3 · 10⁸. Partendo da 9 · 10⁹ le
         * stazioni EAV non possono collidere con nessuna di quelle, oggi ne'
         * quando il BFF ne aggiungera' altre.
         */
        const val LOCATION_ID_BASE = 9_000_000_000L
    }

    /**
     * La fermata EAV nel modello comune.
     *
     * [Station.rfiCode] porta il codice sintetico invece di un vero codice RFI,
     * che per queste stazioni non esiste. Non e' un abuso del campo: quel campo
     * e' gia' "il codice con cui si chiede il tempo reale", e per EAV quello e'.
     * La conseguenza voluta e' che [Station.trackable] resti vero, perche' una
     * stazione EAV il suo tabellone ce l'ha.
     */
    private fun EavStations.Stazione.toStation() = Station(
        rfiCode = codice,
        locationId = LOCATION_ID_BASE + id,
        name = nome,
        latitude = lat,
        longitude = lon,
    )
}
