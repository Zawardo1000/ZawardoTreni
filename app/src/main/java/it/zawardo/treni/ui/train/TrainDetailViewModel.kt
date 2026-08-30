package it.zawardo.treni.ui.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.soloOrarioPrevistoPer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class TrainDetailUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /**
     * Solo per l'aggiornamento chiesto col gesto.
     *
     * L'indicatore del trascinamento deve rispondere a chi trascina: farlo
     * girare anche per il rinfresco automatico di ogni minuto sembrerebbe un
     * difetto, non un servizio.
     */
    val pulling: Boolean = false,
    val status: TrainStatus? = null,
    /** Distinguere "non esiste" da "non c'e' il realtime" cambia il messaggio da mostrare. */
    val realtimeUnavailable: Boolean = false,
    /**
     * La data cercata e' futura: cambia il perche' di un dato mancante. Non e'
     * un guasto ne' un "oggi non c'e' ancora", ma "quella corsa oggi non circola"
     * — il tempo reale esiste solo per oggi, e da li' non si ricava il suo orario.
     */
    val futureDate: Boolean = false,
    val error: String? = null,
)

class TrainDetailViewModel(
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
    /**
     * Corsa gia' identificata da chi ci ha portati qui.
     *
     * Tabellone ed elenco corse sanno esattamente di quale treno si tratta:
     * passarlo evita di ricercarlo per numero e, soprattutto, di sceglierne uno
     * diverso fra quelli che quel numero lo condividono.
     */
    private val originCode: String? = null,
    private val departureMillis: Long? = null,
) : ViewModel() {

    private val trains = ServiceLocator.trainStatusRepository
    private val trenord = ServiceLocator.trenordRepository
    private val italo = ServiceLocator.italoRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    private val memory = ServiceLocator.trainMemory
    private val settings = ServiceLocator.settings

    private val _state = MutableStateFlow(TrainDetailUiState())
    val state: StateFlow<TrainDetailUiState> = _state.asStateFlow()

    /**
     * Preferito o no, letto dal database e non tenuto a parte: la stellina
     * resta d'accordo con la lista anche se il treno viene tolto da li'.
     */
    val isFavorite: StateFlow<Boolean> = memory.isFavorite(trainNumber)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var autoRefresh: Job? = null

    init {
        load(initial = true)
        startAutoRefresh()
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

    fun refresh() = load(initial = false, manual = true)

    /**
     * Si salva il numero; nome e capolinea sono solo la descrizione con cui
     * ritrovarlo nella lista, presi da com'e' adesso.
     */
    fun toggleFavorite() {
        val wanted = !isFavorite.value
        viewModelScope.launch {
            memory.toggleFavorite(trainNumber, wanted, _state.value.status, System.currentTimeMillis())
        }
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
        if (giorno == date) exactRef()?.let { trains.status(it) }?.let { return it }
        val at = if (giorno == date) boardingAt else null
        return trains.statusByNumber(trainNumber, giorno, boardingCode, at)
            ?: trenord.takeIf { DataSource.TRENORD in sources }?.trainStatus(trainNumber, giorno)
            ?: italo.takeIf { DataSource.ITALO in sources }
                ?.trainStatus(trainNumber, giorno, boardingCode, boardingName, alightingCode)
    }

    /**
     * L'orario previsto ricavato dalla corsa di oggi con lo stesso numero.
     *
     * Vale per **qualsiasi fonte in tempo reale** — Trenitalia, Trenord, Italo —
     * non solo per la rete nazionale: un treno che circola ogni giorno con lo
     * stesso numero ha lo stesso tragitto, e da chiunque lo pubblichi oggi si
     * ricava il percorso di domani. Della corsa di oggi si tiene **solo** il
     * tragitto: ritardo, stato, binari e orari reali restano a oggi, dove sono
     * veri — vedi [soloOrarioPrevistoPer].
     *
     * Vale solo per una data futura: per oggi risponde gia' [realtime], e
     * ricopiare se stessi non avrebbe senso.
     */
    private suspend fun previstoDaOggi(sources: Set<DataSource>): TrainStatus? {
        if (date == LocalDate.now()) return null
        val oggi = runCatching { realtime(LocalDate.now(), sources) }.getOrNull() ?: return null

        // Se la corsa di oggi non tocca la stazione da cui si sale, e' un altro
        // treno con lo stesso numero: non lo si spaccia per quello cercato.
        if (boardingCode != null && oggi.stops.none { it.stationCode == boardingCode }) return null

        return oggi.soloOrarioPrevistoPer(
            giorno = date,
            notice = "Percorso e orari dalla corsa di oggi con lo stesso numero. " +
                "Ritardo, binario e stato saranno disponibili il giorno della partenza.",
        )
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
                notice = "Orario previsto per il giorno scelto. Ritardo, binario " +
                    "e stato saranno disponibili il giorno della partenza.",
            )
        }

    private fun load(initial: Boolean, manual: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = initial,
                    refreshing = !initial,
                    pulling = manual && !initial,
                    error = null,
                )
            }

            /*
             * ViaggiaTreno non copre tutto: sulle linee S del Passante milanese
             * non ha alcun dato. Quando non risponde si ripiega su Trenord,
             * che quelle corse le conosce.
             *
             * I ripieghi rispettano gli interruttori delle sorgenti, che prima
             * qui venivano ignorati: chi aveva spento Italo se lo vedeva
             * comunque interrogare aprendo una corsa qualunque, e l'interruttore
             * valeva sui tabelloni e sulla ricerca ma non qui. Si leggono al
             * momento del caricamento e non una volta sola all'avvio, cosi' che
             * spegnere una rete abbia effetto subito.
             */
            val sources = runCatching { settings.enabledSources.first() }
                .getOrDefault(DataSource.defaultEnabled)

            val status = runCatching {
                // Il tempo reale per la data cercata, dalle fonti che lo hanno.
                realtime(date, sources)
                    /*
                     * Poi le reti col solo orario. EAV per le corse che il suo
                     * monitor non copre — quelle di domani, e le linee senza
                     * monitor — e ARST, che il tempo reale non lo ha affatto.
                     * Danno l'orario previsto del giorno giusto: si riconosce di
                     * chi e' la corsa dal codice di salita.
                     */
                    ?: eav.takeIf { DataSource.EAV in sources && it.covers(boardingCode) }
                        ?.dettaglioCorsa(trainNumber, date)
                    ?: arst.takeIf { DataSource.ARST in sources && it.covers(boardingCode) }
                        ?.dettaglioCorsa(trainNumber, date)
                    /*
                     * Ultimo, per una data futura: l'orario previsto dalla corsa
                     * di **oggi** con lo stesso numero, da qualunque fonte in
                     * tempo reale. Un treno che circola ogni giorno ha lo stesso
                     * tragitto; si prende quello di oggi, gli si tolgono i dati di
                     * oggi e si sposta la data. Niente se oggi quel numero non
                     * circola: meglio nessun percorso che quello di un altro treno.
                     */
                    ?: previstoDaOggi(sources)
            }
                /*
                 * Del futuro nessuno conosce il tempo reale, nemmeno le fonti
                 * che per quel giorno rispondono: quel che torna e' orario, e
                 * come tale va detto.
                 */
                .map { if (date.isAfter(LocalDate.now())) it?.perGiornoFuturo() else it }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            refreshing = false,
                            pulling = false,
                            error = "Aggiornamento non riuscito: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }

            if (status != null) {
                runCatching {
                    memory.recordOpened(trainNumber, status, System.currentTimeMillis())
                }
            }

            _state.update {
                it.copy(
                    loading = false,
                    refreshing = false,
                    pulling = false,
                    status = status ?: it.status,
                    // 204 su una data non odierna significa "dato inesistente", non "errore".
                    realtimeUnavailable = status == null && it.status == null,
                    futureDate = date.isAfter(LocalDate.now()),
                )
            }
        }
    }

    /**
     * Aggiornamento ogni 60 s finche' la schermata e' viva. Non e' il "segui treno":
     * quello sopravvive alla chiusura dell'app e usa un foreground service.
     */
    private fun startAutoRefresh() {
        autoRefresh?.cancel()
        autoRefresh = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                val s = _state.value.status
                // Su un treno gia' arrivato non c'e' piu' niente da aggiornare,
                // e nemmeno su una corsa che viene dall'orario: li' non c'e'
                // niente che possa cambiare fra un minuto e l'altro.
                if (s != null && (s.state == TrainState.ARRIVED || !s.realtime)) break
                load(initial = false)
            }
        }
    }

    override fun onCleared() {
        autoRefresh?.cancel()
        super.onCleared()
    }
}
