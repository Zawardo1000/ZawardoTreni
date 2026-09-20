package it.zawardo.treni.ui.train

import it.zawardo.treni.data.repository.TabelloniFuturi
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.domain.model.oggiInItalia
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.soloOrarioPrevistoPer
import it.zawardo.treni.domain.model.stessaStazione
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Come si arriva a una corsa, da qualunque schermata la si guardi.
 *
 * La cascata e' una sola e non e' banale — ViaggiaTreno, poi Trenord, poi
 * Italo, poi le reti col solo orario, e per un giorno futuro le fermate di quel
 * giorno da Le Frecce coi binari dei tabelloni di quel giorno — e serve in due
 * posti: il dettaglio di un treno singolo e la
 * pagina di un viaggio con cambi, che ne carica una per tratta. Scriverla due
 * volte avrebbe voluto dire, alla prima correzione fatta da una parte sola,
 * due schermate che rispondono in modo diverso sullo stesso treno.
 *
 * Le sorgenti accese si rileggono a ogni caricamento e non una volta all'avvio:
 * spegnere una rete dalle impostazioni deve avere effetto subito.
 */
internal class CaricatoreCorsa(
    private val trainNumber: String,
    private val date: LocalDate,
    /**
     * Stazione da cui si sale, quando si arriva da una ricerca per tratta.
     *
     * Non e' un dettaglio: due treni diversi possono avere lo stesso numero
     * nello stesso giorno, e questa e' l'unica cosa che dice quale dei due sia
     * quello che si sta guardando.
     */
    private val boardingCode: String? = null,
    /** Ora di salita: distingue due corse dello stesso numero in giorni diversi. */
    private val boardingAt: LocalDateTime? = null,
    /** Nome della stazione di salita: serve a Italo, che di suo non lo dice. */
    private val boardingName: String? = null,
    /** Dove si scende: con Italo e' anche il modo piu' diretto di avere il percorso. */
    private val alightingCode: String? = null,
    /** Il nome della stazione di discesa: serve a Le Frecce per trovarla. */
    private val alightingName: String? = null,
    /**
     * Corsa gia' identificata da chi ci ha portati qui.
     *
     * Tabellone ed elenco corse sanno esattamente di quale treno si tratta:
     * passarlo evita di ricercarlo per numero e, soprattutto, di sceglierne uno
     * diverso fra quelli che quel numero lo condividono.
     */
    private val originCode: String? = null,
    private val departureMillis: Long? = null,
    /**
     * L'origine della corsa detta dalla ricerca (`bdoOrigin` di Le Frecce), senza
     * la data: con questa ViaggiaTreno si interroga direttamente, invece di
     * cercare il numero. Vedi `TrainStatusRepository.resolveFor`.
     */
    private val origineCorsa: String? = null,
) {

    private val trains = ServiceLocator.trainStatusRepository
    private val journeys = ServiceLocator.journeyRepository
    private val trenord = ServiceLocator.trenordRepository
    private val italo = ServiceLocator.italoRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    private val fnb = ServiceLocator.fnbRepository
    private val settings = ServiceLocator.settings

    /**
     * La corsa come la si puo' conoscere oggi, o null se per quel giorno non
     * esiste. Le eccezioni di rete escono di qui: e' chi mostra la schermata a
     * decidere se dirlo o tenersi il dato vecchio.
     */
    suspend fun carica(): TrainStatus? {
        val sources = runCatching { settings.enabledSources.first() }
            .getOrDefault(DataSource.defaultEnabled)

        /*
         * Una corsa che sale da una rete con stazioni proprie — EAV, ARST,
         * Ferrotramviaria — si chiede alla sua rete e basta. ViaggiaTreno e
         * Trenord quei treni non li hanno, e lo stesso numero da loro e' un altro
         * treno: il 20/09/2026 3 corse EAV su 25 avevano il numero di un treno
         * RFI, e il dettaglio dell'EAV 2093 mostrava il 2093 da Voghera.
         */
        val retePropria = eav.covers(boardingCode) || arst.covers(boardingCode) || fnb.covers(boardingCode)

        // Il tempo reale per la data cercata, dalle fonti che lo hanno.
        val status = (if (retePropria) null else realtime(date, sources))
            /*
             * Poi le reti col solo orario. EAV per le corse che il suo monitor
             * non copre — quelle di domani, e le linee senza monitor — e ARST,
             * che il tempo reale non lo ha affatto. Danno l'orario previsto del
             * giorno giusto: si riconosce di chi e' la corsa dal codice di salita.
             */
            ?: eav.takeIf { DataSource.EAV in sources && it.covers(boardingCode) }
                ?.dettaglioCorsa(trainNumber, date, boardingCode, alightingCode)
            /*
             * Ad ARST si dice anche **da dove si sale**: il suo numero non
             * identifica una corsa. Le linee sono numerate ognuna per conto suo e
             * i numeri si ripetono — il 20/09/2026, 48 corse su 117 — fra la
             * Monserrato-Isili e la Sassari-Sorso, che sono in due angoli opposti
             * dell'isola. Senza, la AT9 presa a Sassari apriva quella di Senorbi'.
             */
            ?: arst.takeIf { DataSource.ARST in sources && it.covers(boardingCode) }
                ?.dettaglioCorsa(trainNumber, date, boardingCode, boardingAt)
            /*
             * Ferrotramviaria: le fermate stanno solo nella ricerca del suo
             * portale, che risponde per qualunque giorno. Serve l'ora di salita,
             * perche' il numero da solo li' non si cerca.
             */
            ?: fnb.takeIf { DataSource.FNB in sources && it.covers(boardingCode) }
                ?.dettaglioCorsa(trainNumber, boardingCode, alightingCode, boardingAt ?: date.atTime(4, 0))
            // Per un giorno futuro: le fermate di quel giorno da Le Frecce.
            ?: delGiornoDaLeFrecce(sources)

        /*
         * Del futuro nessuno conosce il tempo reale, nemmeno le fonti che per
         * quel giorno rispondono: quel che torna e' orario, e come tale va detto.
         */
        val delGiorno = when {
            status == null -> return null
            date.isAfter(oggiInItalia()) -> status.perGiornoFuturo().conBinariDelGiorno()
            else -> status
        }
        /*
         * Il perche', per quel giorno: le note SmartCaring dei regionali
         * Trenitalia (`TrainStatusRepository.conNoteDelGiorno`) e le notizie
         * delle direttrici Trenord che nominano la corsa (`notizieDiTrenord`).
         * Dopo `perGiornoFuturo`, che gli avvisi di oggi li toglie: queste sono
         * proprio di quel giorno. Le due chiamate in parallelo, e tenute cinque
         * minuti: aggiornare la schermata non le ripete.
         */
        val nuovi = coroutineScope {
            val note = async { trains.noteDelGiorno(delGiorno, date, originCode ?: origineCorsa) }
            val trenordDice = async {
                if (DataSource.TRENORD in sources) trains.notizieDiTrenord(delGiorno, date) else emptyList()
            }
            note.await() + trenordDice.await()
        }
        if (nuovi.isEmpty()) return delGiorno
        return delGiorno.copy(avvisi = (nuovi + delGiorno.avvisi).distinct())
    }

    /**
     * La corsa di un giorno futuro da Le Frecce, con le fermate e gli orari **di
     * quel giorno**: vedi `JourneyRepository.corsaDelGiorno`.
     *
     * Prima si ricavava dalla corsa di oggi con lo stesso numero, ed era
     * sbagliato tre volte: un treno che oggi non circola restava senza niente —
     * domenica 20/09/2026 i RE 22111 e 21905 Acireale-Catania, che il sabato non
     * circolano —, una corsa oggi variata prestava il percorso sbagliato, e il
     * binario di oggi non e' quello di quel giorno. Deciso con l'utente il
     * 19/09/2026: si butta, e la corsa va dalla sua origine al capolinea.
     *
     * Il capolinea Le Frecce non lo dice: lo indicano il tabellone di quel giorno
     * alla salita e, se c'e', la corsa di oggi. Sono solo indizi: vale quello che
     * la ricerca di quel giorno conferma con lo stesso treno alla stessa ora.
     */
    private suspend fun delGiornoDaLeFrecce(sources: Set<DataSource>): TrainStatus? {
        if (!date.isAfter(oggiInItalia())) return null
        val salitaAlle = boardingAt ?: return null
        val salita = boardingCode?.takeIf { it.isNotBlank() }?.let { Station(it, 0, boardingName.orEmpty()) } ?: return null
        val capolinea = buildList {
            rigaDiSalita()?.direction?.let { add(Station(null, 0, it)) }
            runCatching { realtime(oggiInItalia(), sources) }.getOrNull()?.let { oggi ->
                val ultima = oggi.stops.lastOrNull()
                add(Station(ultima?.stationCode, 0, oggi.destination ?: ultima?.stationName.orEmpty()))
            }
        }
        /*
         * Senza una discesa — si arriva dal tabellone, o dalla notifica di una
         * corsa sola — vale il **capolinea di quel giorno**: è dove il treno va, e
         * cercare la tratta fin lì dà comunque tutte le fermate. Prima, in quel
         * caso, la corsa di un giorno futuro non usciva affatto.
         */
        val discesa = alightingCode?.takeIf { it.isNotBlank() }
            ?.let { Station(it, 0, alightingName.orEmpty()) }
            ?: capolinea.firstOrNull { it.name.isNotBlank() }
            ?: return null
        return runCatching { journeys.corsaDelGiorno(trainNumber, salita, discesa, salitaAlle, capolinea) }.getOrNull()
    }

    /** La riga del treno nel tabellone di quel giorno alla salita, letta una volta sola. */
    private var rigaDiSalitaLetta = false
    private var rigaDiSalitaValore: BoardEntry? = null

    /**
     * Il lucchetto c'e' perche' [conBinariDelGiorno] chiede le fermate in
     * parallelo: senza, la seconda coroutine trovava il segno «gia' letta»
     * messo prima della chiamata e tornava un valore ancora vuoto — il binario
     * di salita, proprio quello che serve, spariva a intermittenza.
     */
    private val letturaRigaDiSalita = Mutex()

    private suspend fun rigaDiSalita(): BoardEntry? = letturaRigaDiSalita.withLock {
        if (rigaDiSalitaLetta) return@withLock rigaDiSalitaValore
        val codice = boardingCode?.takeIf { it.startsWith("S") }
        val quando = boardingAt
        if (codice == null || quando == null || !entroITabelloni()) {
            rigaDiSalitaLetta = true
            return@withLock null
        }
        /*
         * Un tabellone che non risponde non si ricorda: questo caricatore vive
         * quanto la schermata, e l'aggiornamento automatico ripassa di qui. Se un
         * intoppo di rete valesse «letta», il binario di salita — quello che si
         * guarda — resterebbe vuoto fino a che non si esce dalla pagina, e
         * nemmeno tirando giu' per aggiornare tornerebbe.
         */
        val esito = runCatching { trains.rigaDelGiorno(codice, trainNumber, quando) }
        if (esito.isFailure) return@withLock null
        rigaDiSalitaValore = esito.getOrNull()
        rigaDiSalitaLetta = true
        rigaDiSalitaValore
    }

    /** I tabelloni di ViaggiaTreno rispondono fino a otto giorni avanti. */
    private fun entroITabelloni(): Boolean = !date.isAfter(oggiInItalia().plusDays(TabelloniFuturi.GIORNI))

    /**
     * I binari di tabella **di quel giorno**, su **tutte** le fermate, dai
     * tabelloni di ViaggiaTreno di quella data: partenze dove si sale e in
     * mezzo, arrivi all'ultima. Entro otto giorni: oltre nessuna fonte ha un
     * binario, e la corsa resta senza.
     *
     * Prima si chiedevano solo la salita e la discesa, e il dettaglio di domani
     * usciva con due binari e tutte le fermate in mezzo vuote. Il binario pero'
     * c'e' anche li': il 20/09/2026 il REG 2623 di lunedi' aveva «3» a Milano
     * Lambrate e «1» a Brescia. Costa una chiamata per fermata, quindi si
     * chiedono **insieme**, poche alla volta, e solo dove il binario manca.
     *
     * Si usano solo i programmati: per un giorno futuro un effettivo non esiste
     * ancora (a Bologna le Frecce del 22/09 portavano «AV», che e' il piazzale).
     * Restano «previsto».
     */
    private suspend fun TrainStatus.conBinariDelGiorno(): TrainStatus {
        if (!entroITabelloni()) return this
        val ultima = stops.lastIndex
        val porta = Semaphore(TabelloniFuturi.INSIEME)

        val binari = coroutineScope {
            stops.mapIndexed { i, fermata ->
                async {
                    // Solo dove manca, solo sulla rete RFI, e solo se si sa quando ci passa.
                    if (fermata.scheduledPlatform != null) return@async null
                    val codice = fermata.stationCode?.takeIf { it.startsWith("S") } ?: return@async null
                    // All'ultima fermata il treno arriva e basta: li' vale il tabellone degli arrivi.
                    val arrivo = i == ultima
                    val quando = (if (arrivo) fermata.scheduledArrival else fermata.scheduledDeparture)
                        ?: fermata.scheduledArrival ?: fermata.scheduledDeparture ?: return@async null
                    // Alla salita la riga serve anche altrove: si legge una volta sola.
                    val riga = if (!arrivo && stessaStazione(codice, boardingCode)) {
                        rigaDiSalita()
                    } else {
                        porta.withPermit {
                            runCatching { trains.rigaDelGiorno(codice, trainNumber, quando, arrivo = arrivo) }.getOrNull()
                        }
                    }
                    riga?.scheduledPlatform?.let { i to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        if (binari.isEmpty()) return this
        // Nessuna nota: sono binari di tabella come gli altri, e la corsa dice gia'
        // «orario previsto». La nota esce in rosso, il colore delle variazioni.
        return copy(
            stops = stops.mapIndexed { i, fermata ->
                binari[i]?.let { fermata.copy(scheduledPlatform = it) } ?: fermata
            },
        )
    }

    /**
     * La corsa gia' identificata, ma solo se e' del giorno che si sta guardando.
     *
     * Un riferimento porta con se' la sua data, ed e' quella a decidere quale
     * corsa apre: chiedere `andamentoTreno` con la data di ieri e intestare la
     * risposta a domani e' il modo piu' diretto per mostrare il ritardo di un
     * giorno sopra il treno di un altro. Chi ci porta qui le tiene gia'
     * d'accordo — tabellone ed elenco corse ricavano la data proprio dal
     * riferimento — quindi qui non si perde niente: si chiude una strada.
     */
    private fun exactRef(): TrainRef? {
        val origine = originCode?.takeIf { it.isNotBlank() } ?: return null
        val millis = departureMillis?.takeIf { it > 0 } ?: return null
        if (Instant.ofEpochMilli(millis).atZone(ROME).toLocalDate() != date) return null
        return TrainRef(trainNumber, origine, millis)
    }

    /**
     * Il tempo reale di una corsa in un dato giorno, dalle fonti che lo hanno.
     *
     * La stessa cascata per la data cercata e per il ripiego su oggi: prima
     * ViaggiaTreno — per la rete nazionale e, dove tace, Trenord — poi Trenord
     * diretto per il regionale lombardo, infine Italo. `exactRef` vale solo per
     * la corsa gia' identificata, quindi solo sulla data originale.
     */
    private suspend fun realtime(giorno: LocalDate, sources: Set<DataSource>): TrainStatus? {
        val at = if (giorno == date) boardingAt else null
        val nazionale = (if (giorno == date) exactRef()?.let { trains.status(it) } else null)
            ?: trains.statusByNumber(trainNumber, giorno, boardingCode, at, origine = origineCorsa)

        /*
         * I binari che ViaggiaTreno non ha spesso li ha Trenord, e viceversa:
         * vedi `completaBinari`. Non e' un ripiego ma un'aggiunta, quindi si fa
         * anche quando la risposta nazionale c'e' ed e' completa di tutto il
         * resto — ed e' l'unico modo perche' la fermata da cui sali abbia un
         * binario invece di essere l'unica riga senza.
         */
        if (nazionale != null) {
            // Il perche' di un ritardo o di una variazione ViaggiaTreno lo scrive
            // solo nelle sue notizie, non nella corsa: vedi `conNotizie`.
            val spiegata = trains.conNotizie(nazionale, giorno)
            return if (DataSource.TRENORD in sources) {
                trains.completaBinari(spiegata, giorno)
            } else {
                spiegata
            }
        }

        return trenord.takeIf { DataSource.TRENORD in sources }?.trainStatus(trainNumber, giorno)
            ?: italo.takeIf { DataSource.ITALO in sources }
                ?.trainStatus(trainNumber, giorno, boardingCode, boardingName, alightingCode)
    }

    /**
     * Cio' che si sa di una corsa di un **giorno futuro**, dichiarato per quel
     * che e'.
     *
     * Del domani nessuna fonte conosce il tempo reale, ma qualcuna risponde lo
     * stesso, e non a vuoto: il REG 2813 di domani si apriva come "Arrivato",
     * ultimo rilevamento a Lecco alle 06:48, coi ritardi e i binari di ogni
     * fermata. Erano i dati della corsa di stamattina, su un treno che deve
     * ancora partire.
     *
     * Quale fonte l'abbia detto conta meno del fatto che possa capitare: la
     * data queste API la accettano senza promettere di rispettarla, e nessuna
     * avverte quando risponde per un giorno diverso da quello chiesto. Quindi
     * per una data futura vale come orario **qualunque cosa arrivi**, da
     * chiunque. Chi si e' gia' dichiarato senza tempo reale — EAV, ARST —
     * resta com'e', notice compreso: l'ha gia' spiegato da se'.
     */
    private fun TrainStatus.perGiornoFuturo(): TrainStatus =
        if (!realtime) {
            this
        } else {
            soloOrarioPrevistoPer(
                giorno = date,
                notice = "Orario previsto per il giorno scelto. Ritardo, stato e " +
                    "binario effettivo saranno disponibili il giorno della partenza.",
            )
        }

}
