package it.zawardo.treni.data.repository

import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.data.remote.arst.ArstOrario
import it.zawardo.treni.data.remote.StationMatching
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * ARST: le quattro ferrovie a scartamento ridotto della Sardegna.
 *
 * Monserrato - Mandas - Isili, Macomer - Nuoro, Sassari - Alghero,
 * Sassari - Sorso. Prima di questa classe la Sardegna interna nell'app non
 * c'era: Mandas, Isili, Sorso, Bortigali e gli altri non hanno un codice RFI
 * perche' sulla rete nazionale non hanno stazione.
 *
 * ## E' diversa da tutte le altre sorgenti
 *
 * Le altre cinque rispondono in tempo reale e non sanno niente di domani.
 * Questa fa l'opposto: **non ha tempo reale affatto** — ARST non pubblica ne'
 * tabelloni ne' API — ma ha l'orario, quindi sa rispondere anche per i giorni
 * futuri, finche' il feed li copre.
 *
 * Da qui due conseguenze che il resto dell'app deve rispettare:
 *
 * - ogni riga esce con [BoardEntry.realtime] falso. Il ritardo a zero non vuol
 *   dire "in orario", vuol dire "non lo sa nessuno", e chi mostra la riga deve
 *   dirlo invece di far sembrare una previsione una misura.
 * - fuori dalla copertura dell'orario non si risponde vuoto per sbaglio ma per
 *   scelta, ed e' la stessa cosa: oltre l'ultimo giorno del feed non si sa
 *   niente.
 *
 * ## L'orario sta in un file, non in rete
 *
 * Viene da [ArstOrario], che lo legge dal file scaricato se c'e' e altrimenti
 * da quello imbarcato nell'APK. Nessuna chiamata di rete parte da qui: quella
 * la fa, di rado e solo se ARST e' accesa nelle impostazioni,
 * [it.zawardo.treni.data.remote.gtfs.AggiornamentoOrari].
 */
class ArstRepository(
    /** Dove l'aggiornamento deposita l'orario. Null = si usa solo l'imbarcato. */
    private val cartella: File? = null,
) : FonteStazioniLocale {

    override fun suggerisci(query: String): List<Station> = search(query)

    /**
     * L'orario, letto una volta e tenuto.
     *
     * Sono venti KB compressi e poche centinaia di oggetti: rileggerlo a ogni
     * tabellone sarebbe uno spreco, tenerlo non pesa. [ricarica] serve a
     * riprenderlo dopo un aggiornamento, che e' l'unico momento in cui cambia.
     */
    @Volatile
    private var orario: ArstOrario? = null
    private val lucchetto = Any()

    private fun orario(): ArstOrario? {
        orario?.let { return it }
        return synchronized(lucchetto) {
            orario ?: ArstOrario.carica(cartella)?.also { orario = it }
        }
    }

    /** Rilegge l'orario da disco: da chiamare dopo un aggiornamento riuscito. */
    fun ricarica() {
        synchronized(lucchetto) { orario = null }
    }

    /** Vero se il codice indirizza una stazione ARST. */
    fun covers(stationCode: String?): Boolean = idStazione(stationCode) != null

    /** Il nome della stazione dietro un codice, per le intestazioni. */
    fun stationName(stationCode: String?): String? =
        idStazione(stationCode)?.let { orario()?.stazione(it)?.nome }

    /** La data piu' lontana su cui l'orario abbia qualcosa da dire. */
    fun ultimoGiorno(): LocalDate? = orario()?.ultimoGiorno

    /** Quando e' stato generato l'orario che si sta usando. */
    fun generato(): LocalDate? = orario()?.generato

    /**
     * Partenze o arrivi di una stazione ARST, nel modello del tabellone.
     *
     * A differenza delle altre sorgenti accetta **qualunque data coperta
     * dall'orario**, non solo oggi: e' l'unica che un orario ce l'abbia.
     * Oltre la copertura risponde vuoto, perche' oltre non si sa nulla — non
     * perche' non ci siano treni.
     */
    suspend fun board(
        stationCode: String,
        arrivals: Boolean = false,
        date: LocalDate = LocalDate.now(ROME),
    ): List<BoardEntry> = withContext(Dispatchers.IO) {
        val id = idStazione(stationCode) ?: return@withContext emptyList()
        val o = orario() ?: return@withContext emptyList()
        if (!o.copre(date)) return@withContext emptyList()

        val millis = date.atStartOfDay(ROME).toInstant().toEpochMilli()
        o.passaggi(id, date, partenze = !arrivals).map { p ->
            val minuti = if (arrivals) p.fermata.arrivo else p.fermata.partenza
            BoardEntry(
                trainRef = TrainRef(
                    number = p.corsa.id,
                    // ARST non ha un codice d'origine interrogabile: non c'e'
                    // nessun servizio a cui chiedere l'andamento della corsa.
                    originCode = "",
                    departureDateMillis = millis,
                ),
                label = "Treno " + p.corsa.id,
                category = o.linee[p.corsa.linea] ?: p.corsa.linea,
                direction = if (arrivals) p.origine else p.corsa.destinazione,
                scheduledTime = orologio(minuti),
                /*
                 * Zero perche' il modello vuole un intero, non perche' il treno
                 * sia in orario: [BoardEntry.realtime] falso dice che quello
                 * zero non e' una misura. Chi lo mostrasse come "puntuale"
                 * starebbe inventando.
                 */
                delayMinutes = 0,
                scheduledPlatform = null,
                actualPlatform = null,
                state = TrainState.REGULAR,
                inStation = false,
                realtime = false,
            )
        }
    }

    /**
     * La stazione ARST piu' vicina a un punto, se e' abbastanza vicina da avere
     * senso proporla.
     *
     * Venticinque chilometri, piu' larghi dei dieci delle reti urbane: in
     * Sardegna interna le stazioni sono rade e i paesi distanti, e un limite
     * stretto lascerebbe senza risposta chi ha comunque quella come unica
     * ferrovia a disposizione.
     */
    fun nearest(latitude: Double, longitude: Double, maxMeters: Double = 25_000.0): Station? {
        val o = orario() ?: return null
        return o.stazioni.asSequence()
            .map { it to StationMatching.distanzaMetri(latitude, longitude, it.lat, it.lon) }
            .minByOrNull { it.second }
            ?.takeIf { it.second <= maxMeters }
            ?.first
            ?.toStation()
    }

    /** Le stazioni che corrispondono a quello che si sta digitando. */
    fun search(query: String, limit: Int = 12): List<Station> {
        val o = orario() ?: return emptyList()
        return StationMatching.cerca(o.stazioni, query, limit) { it.nome }.map { it.toStation() }
    }

    /** Tutte le stazioni ARST. */
    fun allStations(): List<Station> = orario()?.stazioni?.map { it.toStation() } ?: emptyList()

    /**
     * I viaggi diretti ARST fra due sue stazioni, in un giorno.
     *
     * E' la ricerca A→B dentro la Sardegna: senza, una tratta come Sassari→Nuoro
     * — entrambe stazioni ARST, che ora si possono scegliere nella ricerca — non
     * darebbe risultati, perche' il BFF non conosce quelle stazioni e i viaggi
     * misti richiedono l'alta velocita', che in Sardegna non c'e'. Le gambe
     * escono senza tempo reale, come tutto ARST.
     */
    fun itinerario(
        fromCode: String,
        toCode: String,
        date: LocalDate = LocalDate.now(ROME),
    ): List<Journey> {
        val da = idStazione(fromCode) ?: return emptyList()
        val a = idStazione(toCode) ?: return emptyList()
        val o = orario() ?: return emptyList()
        if (!o.copre(date)) return emptyList()

        val fromStation = o.stazione(da)?.toStation() ?: return emptyList()
        val toStation = o.stazione(a)?.toStation() ?: return emptyList()

        return o.collegamenti(da, a, date).map { c ->
            val partenza = date.atStartOfDay().plusMinutes(c.partenza.toLong())
            val arrivo = date.atStartOfDay().plusMinutes(c.arrivo.toLong())
            val leg = Leg(
                trainNumber = c.corsa.id,
                category = o.linee[c.corsa.linea] ?: c.corsa.linea,
                from = fromStation,
                to = toStation,
                departure = partenza,
                arrival = arrivo,
                source = DataSource.ARST,
            )
            Journey(
                departure = partenza,
                arrival = arrivo,
                duration = java.time.Duration.between(partenza, arrivo),
                legs = listOf(leg),
            )
        }
    }

    /**
     * Il dettaglio di una corsa ARST, dall'orario.
     *
     * Come per il tabellone, e' tutto orario previsto: ARST non pubblica un solo
     * dato in tempo reale. Le fermate escono col loro orario di tabella e il
     * [TrainStatus.notice] lo dichiara. Aprendo una corsa si vede il percorso e
     * dove si sale, che e' quanto si puo' offrire — e molto meglio di "nessun
     * dato".
     */
    fun dettaglioCorsa(numero: String, date: LocalDate = LocalDate.now(ROME)): TrainStatus? {
        val o = orario() ?: return null
        if (!o.copre(date)) return null
        val c = o.corseDel(date).firstOrNull { it.id == numero } ?: return null
        val mezzanotte = date.atStartOfDay()

        val stops = c.fermate.mapIndexed { i, f ->
            val naz = o.stazione(f.stazione)
            Stop(
                index = i + 1,
                stationName = naz?.nome ?: "Fermata ${f.stazione}",
                stationCode = naz?.let { PREFIX + it.id },
                scheduledArrival = if (i == 0) null else mezzanotte.plusMinutes(f.arrivo.toLong()),
                actualArrival = null,
                arrivalDelayMinutes = 0,
                scheduledDeparture = if (i == c.fermate.lastIndex) null else mezzanotte.plusMinutes(f.partenza.toLong()),
                actualDeparture = null,
                departureDelayMinutes = 0,
                scheduledPlatform = null,
                actualPlatform = null,
                status = StopStatus.FUTURE,
                detected = false,
            )
        }
        if (stops.size < 2) return null

        return TrainStatus(
            number = numero,
            category = o.linee[c.linea] ?: c.linea,
            label = "Treno $numero",
            origin = stops.first().stationName,
            destination = c.destinazione.ifBlank { stops.last().stationName },
            delayMinutes = 0,
            state = TrainState.REGULAR,
            lastDetectionStation = null,
            lastDetectionTime = null,
            notice = "Orario previsto. ARST non pubblica il tempo reale.",
            stops = stops,
        )
    }

    /** Minuti dalla mezzanotte in `HH:mm`, riportando oltre le 24 nel giorno dopo. */
    private fun orologio(minuti: Int): String =
        "%02d:%02d".format((minuti / 60) % 24, minuti % 60)

    /** L'id numerico dietro un codice sintetico, null se non e' ARST. */
    private fun idStazione(codice: String?): Int? {
        if (codice == null || !codice.startsWith(PREFIX)) return null
        val id = codice.removePrefix(PREFIX).toIntOrNull() ?: return null
        return if (orario()?.stazione(id) != null) id else null
    }

    private companion object {
        /** Prefisso dei codici sintetici. Nessun codice RFI comincia cosi'. */
        const val PREFIX = "ARST"

        /**
         * Base degli id sintetici.
         *
         * Quarta fascia dopo EAV (9,0 · 10⁹), Ferrotramviaria (9,1 · 10⁹) e
         * Vigezzina (9,2 · 10⁹). Gli id ARST arrivano a cinque cifre e senza una
         * fascia propria sconfinerebbero nelle altre.
         */
        const val LOCATION_ID_BASE = 9_300_000_000L
    }

    /**
     * La stazione nel modello comune.
     *
     * [Station.rfiCode] porta il codice sintetico, come per le altre reti fuori
     * da RFI: quel campo e' "il codice con cui si chiede il tabellone", e qui
     * quello e'. [Station.trackable] resta vero perche' un tabellone c'e' — e'
     * fatto di orari previsti, e la riga lo dichiara.
     */
    private fun ArstOrario.Stazione.toStation() = Station(
        rfiCode = PREFIX + id,
        locationId = LOCATION_ID_BASE + id,
        name = nome,
        latitude = lat,
        longitude = lon,
    )
}
