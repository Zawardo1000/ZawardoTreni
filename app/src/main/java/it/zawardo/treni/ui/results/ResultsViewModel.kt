package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.repository.chiavePrezzoLeFrecce
import it.zawardo.treni.data.repository.chiaveSoluzione
import it.zawardo.treni.domain.model.Coincidenza
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.FiltroFonti
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.JourneySource
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.coincidenza
import it.zawardo.treni.domain.model.declaredState
import it.zawardo.treni.domain.model.fermataA
import it.zawardo.treni.domain.model.prezzoDaTrenord
import it.zawardo.treni.domain.model.soloTreni
import it.zawardo.treni.domain.model.partenzaAncoraUtile
import it.zawardo.treni.domain.model.prezzoTotale
import it.zawardo.treni.domain.model.primoCambio
import it.zawardo.treni.domain.model.soppressione
import it.zawardo.treni.domain.model.tratteDaBiglietto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Una soluzione più, se disponibile, il suo stato in tempo reale. */
data class JourneyRow(
    val journey: Journey,
    val loadingStatus: Boolean = false,
    val state: TrainState? = null,
    /**
     * Col [state] soppresso, la corsa si fa ma non da dove sali o fin dove
     * scendi: barrata come una soppressa, perche' per questo viaggio non serve,
     * ma detta «Variato». Vedi `Journey.variato`.
     */
    val variato: Boolean = false,
    val delayMinutes: Int? = null,
    /**
     * Il binario da cui parti: la fermata di salita del **primo** treno.
     *
     * Su un viaggio con cambio è l'unico che serva prima di uscire di casa —
     * quello della coincidenza si guarda dopo, quando si è a bordo, e cambia
     * comunque nel frattempo. Le due letture restano separate perché è dal loro
     * confronto che nasce il "cambiato": vedi `binarioCambiato`.
     */
    val scheduledPlatform: String? = null,
    val actualPlatform: String? = null,
    /**
     * Quando parte davvero, per le soluzioni gia' passate in tabella che si
     * prendono ancora perche' il treno e' in ritardo; null per tutte le altre.
     * Vedi `ResultsViewModel.cercaAncoraPrendibili`.
     */
    val partenzaStimata: LocalDateTime? = null,
    /**
     * Come sta il primo cambio coi ritardi di adesso: vedi `coincidenza`.
     *
     * Su una soluzione gia' passata in tabella non arriva mai persa, perche' in
     * quel caso la soluzione non si propone affatto. Sulle altre la riga resta e
     * lo dice, come resta barrato un treno soppresso: vedi
     * `ResultsViewModel.enrich`.
     */
    val coincidenza: Coincidenza = Coincidenza.REGGE,
    /**
     * Quante richieste stanno cercando il prezzo di questa riga: vedi
     * `ResultsViewModel.riprovaPrezzi` e `prezziDeiTreni`. Un contatore e non un
     * si'/no, perche' sulla stessa riga possono lavorare tutte e due, e la prima
     * che finisce non deve spegnere la rotella dell'altra.
     */
    val richiestePrezzo: Int = 0,
) {
    /** Il prezzo manca e lo si sta chiedendo: al suo posto, una rotella. */
    val prezzoInArrivo: Boolean get() = richiestePrezzo > 0

    /**
     * I campi del tempo reale letti in [letta], sopra la riga com'e' adesso.
     *
     * Non la riga letta intera: e' una copia presa prima delle chiamate, e nel
     * frattempo possono essere arrivati prezzi o partite le loro rotelle, che
     * rimpiazzandola si perderebbero. Vedi `ResultsViewModel.enrich`.
     */
    fun conTempoRealeDi(letta: JourneyRow): JourneyRow = copy(
        loadingStatus = letta.loadingStatus,
        state = letta.state,
        variato = letta.variato,
        delayMinutes = letta.delayMinutes,
        scheduledPlatform = letta.scheduledPlatform,
        actualPlatform = letta.actualPlatform,
    )

    /**
     * Una soluzione senza prezzo che una richiesta in corso puo' riempire: Le
     * Frecce, con una ricerca nuova, o da piu' biglietti (`tratteDaBiglietto`).
     * Non i misti, il cui prezzo e' parziale per costruzione.
     */
    val aspettaPrezzo: Boolean
        get() = (
            journey.source == JourneySource.LEFRECCE && !journey.assembled &&
                journey.price == null && journey.partialPrice == null
            ) || journey.tratteDaBiglietto() != null

    /** Stabile fra un refresh e l'altro: evita che la lista salti sotto le dita. */
    val key: String
        get() = journey.departure.toString() + "|" +
            journey.legs.joinToString(",") { it.trainNumber ?: "?" }

    /**
     * Bus sostitutivi e collegamenti urbani non esistono su ViaggiaTreno.
     * Lasciare "stato in aggiornamento" all'infinito sarebbe una bugia.
     */
    val realtimePossible: Boolean get() = journey.hasTrain

    /**
     * Il tempo reale vale per il giorno della **soluzione**, non per quello
     * cercato.
     *
     * Non sono la stessa cosa: una ricerca fatta stasera puo' tornare corse di
     * domani mattina, e chiedere per quelle lo stato di oggi risponde con la
     * corsa sbagliata, quasi sempre gia' arrivata.
     */
    val isRealtimeDay: Boolean get() = giornoDelPrimoTreno == LocalDate.now()

    /**
     * Il giorno del primo treno, che non e' sempre quello della soluzione.
     *
     * «Urbano › RE 10911» da Milano Porta Garibaldi, il 18/09/2026: il tratto
     * urbano alle 23:52, il treno da Lambrate dopo la mezzanotte. Chiesto col
     * giorno della soluzione, il 10911 era la corsa della notte prima, e la riga
     * diceva "Arrivato" con un binario cambiato che non era il suo.
     */
    val giornoDelPrimoTreno: LocalDate
        get() = (journey.legs.firstOrNull { it.isTrain }?.departure ?: journey.departure).toLocalDate()

    /** Interrogabile davvero: un treno, e nella giornata in cui il dato esiste. */
    val realtimeNow: Boolean get() = realtimePossible && isRealtimeDay
}

data class ResultsUiState(
    val loading: Boolean = true,
    val loadingEarlier: Boolean = false,
    val loadingLater: Boolean = false,
    val journeys: List<JourneyRow> = emptyList(),
    val realtimeAvailable: Boolean = true,
    val noMoreEarlier: Boolean = false,
    val noMoreLater: Boolean = false,
    val error: String? = null,
    /**
     * Vero quando nessuna corsa cade nel giorno richiesto.
     *
     * Succede nei casi eccezionali: linea chiusa per lavori, servizio sostituito
     * da bus, ultimo treno gia' passato. Il BFF non manda alcun avviso, quindi
     * la condizione va dedotta e dichiarata: due corse notturne di domani,
     * mostrate senza spiegazione, sembrano un guasto dell'app.
     */
    val noSameDayResults: Boolean = false,
    /** Avvisi di servizio: lavori, sospensioni, bus sostitutivi. Solo da Trenord. */
    val alerts: List<ServiceAlert> = emptyList(),
    val directOnly: Boolean = false,
    /** Sta ancora cercando i viaggi misti (beta), che arrivano dopo i diretti. */
    val loadingMisti: Boolean = false,
    /**
     * Trenitalia non ha risposto nemmeno ai nuovi tentativi: le corse
     * nazionali mancano per un guasto. Vedi `SearchOutcome.nazionaleNonRisponde`.
     */
    val nazionaleNonRisponde: Boolean = false,
)

class ResultsViewModel(
    private val from: Station,
    private val to: Station,
    private val departure: LocalDateTime,
    private val directOnly: Boolean = false,
) : ViewModel() {

    private val journeys = ServiceLocator.journeyRepository
    private val misti = ServiceLocator.viaggiMistiRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    private val italo = ServiceLocator.italoRepository
    private val trains = ServiceLocator.trainStatusRepository
    private val settings = ServiceLocator.settings

    /**
     * Le reti accese, tenute aggiornate mentre la schermata vive: se l'utente
     * ne spegne una e torna qui, la prossima ricerca la salta.
     */
    private var sources: Set<DataSource> = DataSource.defaultEnabled

    private val _state = MutableStateFlow(ResultsUiState())
    val state: StateFlow<ResultsUiState> = _state.asStateFlow()

    /**
     * Solo per il cartello in cima alla lista: la data cercata non e' oggi.
     *
     * Quale riga sia interrogabile lo decide la riga stessa, dalla propria data
     * di partenza: vedi [JourneyRow.isRealtimeDay].
     */
    private val isToday: Boolean = departure.toLocalDate() == LocalDate.now()

    init {
        viewModelScope.launch {
            // La prima ricerca deve gia' sapere quali reti sono accese, o
            // partirebbe col default ignorando chi l'utente ha spento.
            sources = runCatching { settings.enabledSources.first() }.getOrDefault(sources)
            reload()
        }
        viewModelScope.launch { settings.enabledSources.collect { sources = it } }
    }

    fun reload() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = true,
                    error = null,
                    directOnly = directOnly,
                    realtimeAvailable = isToday,
                    noMoreEarlier = false,
                    noMoreLater = false,
                )
            }

            val outcome = runCatching { journeys.searchAll(from, to, departure, limit = PAGE, sources = sources) }
                .getOrElse { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "Ricerca non riuscita: ${e.message ?: "errore di rete"}",
                        )
                    }
                    return@launch
                }
            /*
             * I diretti sulle reti fuori-RFI, quando le due punte sono della
             * stessa rete: Sorrento→Napoli su EAV, Sassari→Nuoro su ARST. Il BFF
             * non conosce quelle stazioni e i viaggi misti richiedono l'alta
             * velocita', quindi senza questo passo una tratta tutta-EAV o
             * tutta-ARST resterebbe senza risultati, ora che quelle stazioni si
             * possono scegliere. Escono senza tempo reale, come la loro rete.
             */
            val fuoriRfi = direttiFuoriRfi(sources)

            /*
             * Il filtro si applica dopo, non prima: le sorgenti non sanno
             * filtrare i cambi, e chiedere meno risultati per poi scartarne una
             * parte svuoterebbe la lista. Per questo si chiede piu' del dovuto.
             */
            val list = (outcome.journeys + fuoriRfi)
                .applyDirectFilter()
                .sortedBy { it.departure }

            val rows = list.map { it.toRow() }
            val requestedDay = departure.toLocalDate()
            _state.update {
                it.copy(
                    loading = false,
                    journeys = rows,
                    alerts = outcome.alerts,
                    nazionaleNonRisponde = outcome.nazionaleNonRisponde,
                    noSameDayResults = rows.isNotEmpty() &&
                        rows.none { r -> r.journey.departure.toLocalDate() == requestedDay },
                )
            }
            enrich(rows)
            prezziDeiTreni(rows)
            prezziInPiuBiglietti(rows)
            cercaAncoraPrendibili()
            cercaAltreSoluzioni(direttoMigliore = list.minByOrNull { it.duration }?.duration)
            if (outcome.prezziAssenti) riprovaPrezzi(departure, PAGE, rows)
        }
    }

    /**
     * I viaggi diretti quando partenza e arrivo sono della stessa rete fuori-RFI.
     *
     * Solo EAV e ARST, che un orario ce l'hanno da cui ricavare gli itinerari.
     * Ferrotramviaria e Vigezzina hanno il solo tabellone di stazione, da cui una
     * ricerca A→B non si ricava: per ora quelle tratte interne restano scoperte.
     */
    private suspend fun direttiFuoriRfi(sources: Set<DataSource>): List<Journey> {
        val f = from.rfiCode ?: return emptyList()
        val t = to.rfiCode ?: return emptyList()
        val giorno = departure.toLocalDate()
        return when {
            DataSource.EAV in sources && eav.covers(f) && eav.covers(t) ->
                eav.itinerario(f, t, giorno)
            DataSource.ARST in sources && arst.covers(f) && arst.covers(t) ->
                arst.itinerario(f, t, giorno)
            else -> emptyList()
        }
    }

    /**
     * Le soluzioni che la ricerca principale non copre, cercate **dopo** e in
     * asincrono: sono lente (rete, spesso vuote) e i diretti Trenitalia/Trenord
     * sono gia' a schermo.
     *
     *  - **Italo diretti** su una tratta tutta-Italo (es. Napoli→Roma): la ricerca
     *    A→B interroga solo Le Frecce e Trenord, Italo no. Come i diretti EAV/ARST,
     *    ma via rete e solo per oggi — il suo real-time non va oltre. Non serve la beta.
     *  - **Viaggi misti** (beta): feeder fuori-RFI piu' alta velocita'.
     *
     * EAV e ARST diretti restano invece **sincroni** (orario imbarcato, istantaneo,
     * e spesso sono l'unico risultato della tratta): vedi [direttiFuoriRfi].
     */
    private fun cercaAltreSoluzioni(direttoMigliore: java.time.Duration?) {
        viewModelScope.launch {
            if (!componeAltre()) return@launch
            _state.update { it.copy(loadingMisti = true) }
            val trovate = altreSoluzioni(departure, direttoMigliore)
            _state.update { s ->
                val gia = s.journeys.map { it.key }.toHashSet()
                // Ne' i misti ne' i diretti Italo si arricchiscono col tempo reale
                // aggregato: le loro corse stanno fuori da ViaggiaTreno e il realtime
                // si legge aprendo la singola corsa. Nascono quindi gia' "fermi".
                //
                // Dall'ora cercata in avanti, come la ricerca principale — ma con
                // una **grazia sui misti**. Un EAV+Italo e' raro, e le coincidenze
                // Italo in tempo reale sono a singhiozzo: perderne una perche' il
                // feeder e' partito cinque minuti fa la farebbe sparire del tutto,
                // mentre vedere che *esiste* vale piu' del "l'hai persa per poco".
                // Cosi' un misto resta fino a [GRAZIA_MISTI] prima dell'ora cercata;
                // l'Italo diretto — corsa nazionale singola, di cui ce n'e' a bizzeffe
                // — resta stretto.
                val grazia = departure.minus(GRAZIA_MISTI)
                val nuove = trovate
                    .filter { !it.departure.isBefore(if (it.assembled) grazia else departure) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in gia }
                s.copy(
                    loadingMisti = false,
                    journeys = (s.journeys + nuove).sortedBy { it.journey.departure },
                )
            }
        }
    }

    /**
     * Una ricerca tornata tutta senza prezzi si rifa', in sottofondo, piu' volte.
     *
     * Le Frecce a volte risponde senza alcun prezzo, Frecce comprese: misurato
     * l'11/09/2026 in 12 ricerche su 36, quasi tutte su tratte regionali
     * lombarde. Richiamare la stessa sessione non cambia niente, una ricerca
     * nuova si'. La lista resta a schermo com'e': i prezzi, se arrivano, si
     * aggiungono alle righe che ne sono senza, e nient'altro si muove.
     *
     * **Un tentativo solo non bastava.** Il 18/09/2026, sedici ricerche a
     * quattro secondi l'una dall'altra: Roma-Firenze ha portato i prezzi in 6
     * su 8, coi buchi sparsi (si', no, si', si', si', no, si', si');
     * Milano-Brescia in 2 su 8. E i buchi vengono a grappoli: tre ricerche di
     * fila, una subito dopo l'altra, erano tornate tutte e tre senza. Quindi
     * fino a tre tentativi, distanziati ([ATTESE_PREZZI]), fermandosi al primo
     * che porta prezzi: e' una ricerca riuscita, e quel che li' resta senza
     * prezzo non e' in vendita.
     *
     * **Quando lo dice la ricerca** (`SearchOutcome.prezziAssenti`): con la
     * lista dalla porta dell'app, se mancano tutti; con la lista dal sito, se
     * manca a una soluzione che comincia a piedi, che il sito non prezza e l'app
     * si'. Il nuovo tentativo chiede a tutte e due; quel che resta senza sono
     * biglietti che Trenitalia li' non vende, e per quelli c'e' [prezziDeiTreni].
     *
     * Intanto le righe senza prezzo mostrano una rotella al suo posto, come la
     * pillola del binario mentre lo si chiede. Si segnano solo le [righe] di
     * questa pagina: due pagine possono riprovare insieme, e la prima che
     * finisce non deve spegnere la rotella dell'altra.
     */
    private fun riprovaPrezzi(quando: LocalDateTime, limite: Int, righe: List<JourneyRow>) {
        val attese = righe.filter { it.aspettaPrezzo }.map { it.key }.toSet()
        if (attese.isEmpty()) return
        viewModelScope.launch {
            segnaPrezzoInArrivo(attese, +1)
            try {
                for (attesa in ATTESE_PREZZI) {
                    delay(attesa)
                    val prezzi = runCatching { journeys.prezziLeFrecce(from, to, quando, limite) }
                        .getOrDefault(emptyMap())
                    if (prezzi.isEmpty()) continue
                    _state.update { s ->
                        s.copy(
                            journeys = s.journeys.map { riga ->
                                // Un prezzo gia' noto resta, tranne la somma di piu' biglietti:
                                // il prezzo intero che la fonte vende vale di piu' della nostra somma.
                                if (riga.journey.price != null && riga.journey.biglietti.isEmpty()) return@map riga
                                val prezzo = prezzi[chiavePrezzoLeFrecce(riga.journey)] ?: return@map riga
                                riga.copy(journey = riga.journey.copy(price = prezzo, biglietti = emptyList()))
                            },
                        )
                    }
                    break
                }
            } finally {
                segnaPrezzoInArrivo(attese, -1)
            }
        }
    }

    /**
     * Il prezzo dei treni dove Le Frecce non ne da' nessuno, chiesto a Trenord:
     * vedi `JourneyRepository.prezziDeiTreni`.
     *
     * In sottofondo come [riprovaPrezzi]: la lista resta ferma, al posto del
     * prezzo gira la rotella, e il prezzo, se arriva, si aggiunge. Intero su una
     * soluzione di soli treni; parziale — «solo treno» — con un tratto urbano o
     * in autobus, il cui biglietto non c'e' dentro. Solo con Trenord acceso: e' a
     * lui che si chiede.
     */
    private fun prezziDeiTreni(righe: List<JourneyRow>) {
        if (DataSource.TRENORD !in sources) return
        val daPrezzare = righe.filter { it.journey.prezzoDaTrenord }
        if (daPrezzare.isEmpty()) return
        val chiavi = daPrezzare.map { it.key }.toSet()
        viewModelScope.launch {
            segnaPrezzoInArrivo(chiavi, +1)
            try {
                val prezzi = runCatching { journeys.prezziDeiTreni(daPrezzare.map { it.journey }) }
                    .getOrDefault(emptyMap())
                if (prezzi.isEmpty()) return@launch
                _state.update { s ->
                    s.copy(
                        journeys = s.journeys.map { riga ->
                            val viaggio = riga.journey
                            if (!viaggio.prezzoDaTrenord) return@map riga
                            val prezzo = prezzi[chiaveSoluzione(viaggio)] ?: return@map riga
                            riga.copy(
                                journey = if (viaggio.soloTreni) viaggio.copy(price = prezzo)
                                else viaggio.copy(partialPrice = prezzo),
                            )
                        },
                    )
                }
            } finally {
                segnaPrezzoInArrivo(chiavi, -1)
            }
        }
    }

    /**
     * Il prezzo delle soluzioni da comprare con piu' biglietti, uno per
     * venditore: vedi `JourneyRepository.bigliettiSeparati`. In sottofondo come
     * [prezziDeiTreni], con la rotella al posto del prezzo. Il prezzo e' la somma,
     * e la scheda dice che i biglietti sono due.
     */
    private fun prezziInPiuBiglietti(righe: List<JourneyRow>) {
        val daComporre = righe.filter { it.journey.tratteDaBiglietto() != null }
        if (daComporre.isEmpty()) return
        val chiavi = daComporre.map { it.key }.toSet()
        viewModelScope.launch {
            segnaPrezzoInArrivo(chiavi, +1)
            try {
                val biglietti = runCatching {
                    journeys.bigliettiSeparati(daComporre.map { it.journey }, noti = listOf(from, to))
                }.getOrDefault(emptyMap())
                if (biglietti.isEmpty()) return@launch
                _state.update { s ->
                    s.copy(
                        journeys = s.journeys.map { riga ->
                            if (riga.journey.price != null) return@map riga
                            val questi = biglietti[chiaveSoluzione(riga.journey)] ?: return@map riga
                            val totale = questi.prezzoTotale() ?: return@map riga
                            riga.copy(journey = riga.journey.copy(price = totale, biglietti = questi))
                        },
                    )
                }
            } finally {
                segnaPrezzoInArrivo(chiavi, -1)
            }
        }
    }

    private fun segnaPrezzoInArrivo(chiavi: Set<String>, variazione: Int) {
        _state.update { s ->
            s.copy(
                journeys = s.journeys.map { riga ->
                    if (riga.key !in chiavi) return@map riga
                    riga.copy(richiestePrezzo = (riga.richiestePrezzo + variazione).coerceAtLeast(0))
                },
            )
        }
    }

    /** Vero se la tratta puo' comporre misti o Italo diretti: decide il velo. */
    private suspend fun componeAltre(): Boolean {
        val betaAttivo = runCatching { settings.viaggiMisti.first() }.getOrDefault(false)
        val vuoleMisti = FiltroFonti.componiMisti(soloDiretti = directOnly, betaAttivo = betaAttivo)
        val vuoleItalo = DataSource.ITALO in sources &&
            italo.covers(from.rfiCode) && italo.covers(to.rfiCode)
        return vuoleMisti || vuoleItalo
    }

    /**
     * I misti (beta) e gli Italo diretti in partenza da [quando]; la finestra la
     * ritaglia chi chiama (avanti o indietro).
     *
     * Self-gating: torna vuoto se la tratta non ne compone. La usano sia la prima
     * ricerca sia la paginazione: su una tratta di soli misti — Sorrento-EAV, che
     * il BFF non conosce e per cui `searchAll` e' muto — e' l'unico modo perche'
     * «corse precedenti/successive» non restino vuote.
     */
    private suspend fun altreSoluzioni(
        quando: LocalDateTime,
        direttoMigliore: java.time.Duration?,
    ): List<Journey> {
        val betaAttivo = runCatching { settings.viaggiMisti.first() }.getOrDefault(false)
        val vuoleMisti = FiltroFonti.componiMisti(soloDiretti = directOnly, betaAttivo = betaAttivo)
        val vuoleItalo = DataSource.ITALO in sources &&
            italo.covers(from.rfiCode) && italo.covers(to.rfiCode)
        val italoDiretti = if (vuoleItalo) {
            runCatching { italo.itinerario(from.rfiCode!!, to.rfiCode!!, quando.toLocalDate()) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val mistiJ = if (vuoleMisti) {
            runCatching { misti.cerca(from, to, quando, direttoMigliore, sources) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return italoDiretti + mistiJ
    }

    /**
     * Corse precedenti alla prima mostrata.
     *
     * Il BFF non sa tornare indietro: `/search` restituisce sempre soluzioni
     * *successive* all'orario chiesto. Quindi si riparte da qualche ora prima e
     * si tengono le ultime che cadono prima di quella gia' in cima. Se la tratta
     * e' scarsa la finestra si allarga una volta sola, poi si smette.
     */
    fun loadEarlier() {
        val current = _state.value
        if (current.loadingEarlier || current.noMoreEarlier) return
        /*
         * Si parte dalla prima scheda nata dalla ricerca, non da un treno in
         * ritardo messo in cima perche' si prende ancora: partendo da quello, i
         * treni fra la sua ora di tabella e l'ora cercata non comparirebbero mai.
         */
        val first = (current.journeys.firstOrNull { it.partenzaStimata == null } ?: current.journeys.firstOrNull())
            ?.journey?.departure ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingEarlier = true) }

            var found = emptyList<Journey>()
            // Da dove rifare la ricerca, se questa finestra torna tutta senza prezzi.
            var riprovaDa: LocalDateTime? = null
            // Una finestra vuota per un guasto non dice che prima non c'e' niente.
            var guasto = false
            for (hoursBack in intArrayOf(3, 8)) {
                val start = first.minusHours(hoursBack.toLong())
                // Prima dell'inizio del giorno non c'e' niente da cercare.
                val clamped = maxOf(start, first.toLocalDate().atStartOfDay())
                // searchAll e non search: anche andando indietro le corse Trenord
                // devono comparire, altrimenti la lista cambia natura scorrendo.
                val esito = runCatching { journeys.searchAll(from, to, clamped, limit = WIDE_PAGE, sources = sources) }
                    .getOrNull()
                if (esito == null || esito.nazionaleNonRisponde) guasto = true
                val batch = esito?.journeys.orEmpty()
                    .applyDirectFilter()
                    .filter { it.departure.isBefore(first) }
                if (batch.isNotEmpty()) {
                    found = batch.takeLast(PAGE)
                    if (esito?.prezziAssenti == true) riprovaDa = clamped
                    break
                }
                if (clamped == first.toLocalDate().atStartOfDay()) break
            }

            if (found.isEmpty()) {
                // Tratta di soli misti (Sorrento-EAV: il BFF non la conosce):
                // searchAll e' muto, ma feeder e Freccia girano anche prima. Si
                // pesca la finestra precedente dei misti — gia' "fermi", come nella
                // prima ricerca, e senza arricchimento in tempo reale.
                val anchor = maxOf(first.minusHours(4), first.toLocalDate().atStartOfDay())
                val existing = current.journeys.map { it.key }.toSet()
                val rows = altreSoluzioni(anchor, null)
                    .filter { it.departure.isBefore(first) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in existing }
                    .sortedBy { it.journey.departure }
                    .takeLast(PAGE)
                _state.update { s ->
                    s.copy(
                        loadingEarlier = false,
                        journeys = (rows + s.journeys).sortedBy { it.journey.departure },
                        noMoreEarlier = rows.isEmpty() && !guasto,
                    )
                }
                return@launch
            }

            val existing = current.journeys.map { it.key }.toSet()
            val rows = found.map { it.toRow() }
                .filter { it.key !in existing }

            _state.update { s ->
                s.copy(loadingEarlier = false, journeys = (rows + s.journeys).sortedBy { it.journey.departure }, noMoreEarlier = rows.isEmpty())
            }
            enrich(rows)
            prezziDeiTreni(rows)
            prezziInPiuBiglietti(rows)
            riprovaDa?.let { riprovaPrezzi(it, WIDE_PAGE, rows) }
        }
    }

    /** Corse successive all'ultima mostrata: qui il BFF lavora nella sua direzione naturale. */
    fun loadLater() {
        val current = _state.value
        if (current.loadingLater || current.noMoreLater) return
        val last = current.journeys.lastOrNull()?.journey?.departure ?: return
        /*
         * Da un po' prima dell'ultima: le fonti contano la partenza dall'inizio
         * della camminata in testa, e noi dal treno (vedi
         * `senzaCamminateAgliEstremi`). Chiedendo da un minuto dopo l'ultimo
         * treno, un'S6 delle 10:28 con la camminata dalle 10:23, dopo un'S5 delle
         * 10:25, non la restituiva nessuno. Quelle gia' in elenco si scartano per
         * chiave.
         */
        val da = last.minusMinutes(CAMMINATA_IN_TESTA_MAX)

        viewModelScope.launch {
            _state.update { it.copy(loadingLater = true) }

            val esito = runCatching {
                journeys.searchAll(from, to, da, limit = WIDE_PAGE, sources = sources)
            }.getOrNull()
            val batch = esito?.journeys.orEmpty().applyDirectFilter()
                .filter { !it.departure.isBefore(last) }
            // Una finestra vuota per un guasto non dice che dopo non c'e' niente:
            // il pulsante resta, e riprovare tocca a chi guarda.
            val guasto = esito == null || esito.nazionaleNonRisponde

            val existing = current.journeys.map { it.key }.toSet()

            if (batch.isEmpty()) {
                // Tratta di soli misti: come per «corse precedenti», la finestra
                // successiva la danno i misti — gia' "fermi", niente arricchimento.
                val rows = altreSoluzioni(last.plusMinutes(1), null)
                    .filter { it.departure.isAfter(last) }
                    .map { it.toRow().copy(loadingStatus = false) }
                    .filter { it.key !in existing }
                    .sortedBy { it.journey.departure }
                    .take(PAGE)
                _state.update { s ->
                    s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty() && !guasto)
                }
                return@launch
            }

            val rows = batch
                .map { it.toRow() }
                .filter { it.key !in existing }
                .take(PAGE)

            _state.update { s ->
                s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty())
            }
            enrich(rows)
            prezziDeiTreni(rows)
            prezziInPiuBiglietti(rows)
            if (esito?.prezziAssenti == true) riprovaPrezzi(da, WIDE_PAGE, rows)
        }
    }

    /**
     * Riga pronta da mostrare, gia' con quel che la sorgente dichiara.
     *
     * Trenord manda soppressione e ritardo insieme alla soluzione, e per le
     * linee S sono l'unico dato che esistera' mai: ViaggiaTreno quelle corse non
     * le conosce. Partire da li' vuol dire che un treno soppresso si vede subito,
     * anche quando l'interrogazione successiva non trovera' nulla.
     */
    private fun Journey.toRow(): JourneyRow {
        val row = JourneyRow(this, state = declaredState, variato = variato, delayMinutes = delayMinutes)
        return row.copy(loadingStatus = row.realtimeNow)
    }

    /**
     * I treni gia' passati in tabella che si fanno ancora in tempo a prendere.
     *
     * Cercando Taormina - Catania alle 10:10 del 14/09/2026 non usciva il REG
     * 5385 delle 10:01, che viaggiava con dieci minuti di ritardo e sarebbe
     * partito verso le 10:11. Le Frecce ragiona solo sull'orario: cercando dalle
     * 10:05 quel treno spariva. Il tabellone la regola giusta la applicava gia'
     * (vedi `stillCatchable`), la ricerca no, e sullo stesso treno le due
     * schermate si contraddicevano. Per chi fa il pendolare il ritardo e' la
     * norma, e il treno che conta e' quello che si prende davvero.
     *
     * Si cerca da [FINESTRA_RITARDI] prima dell'ora chiesta — una ricerca in
     * piu', non una per treno — e di quelle soluzioni si interroga il primo
     * treno con la stessa chiamata che ogni riga fa gia' per il suo ritardo.
     * Restano quelle la cui partenza stimata cade dall'ora cercata in poi
     * (`partenzaAncoraUtile`), e se c'e' un cambio quelle la cui coincidenza
     * regge o si perde di poco: queste ultime escono dichiarate a rischio. Vedi
     * [coincidenzaDi].
     *
     * Solo attorno ad adesso ([ritardiContano]): il ritardo di un treno di
     * stamattina non dice niente di quello delle 17, e di un giorno che non e'
     * oggi non si sa proprio.
     */
    private fun cercaAncoraPrendibili() {
        if (!ritardiContano()) return
        viewModelScope.launch {
            val da = departure.minus(FINESTRA_RITARDI)
            val esito = runCatching {
                journeys.searchAll(from, to, da, limit = WIDE_PAGE, sources = sources)
            }.getOrNull() ?: return@launch
            val candidate = esito.journeys.applyDirectFilter()
                .filter { it.departure.isBefore(departure) }
                .map { it.toRow() }
                .filter { it.realtimeNow }
            if (candidate.isEmpty()) return@launch

            val prese = coroutineScope {
                candidate.map { riga ->
                    async {
                        val (arricchita, stato) = conStato(riga)
                        // Soppresso, o variato fino a non servire piu': non si prende.
                        if (arricchita.state == TrainState.CANCELLED) return@async null
                        val partenza = riga.journey.partenzaAncoraUtile(stato, departure)
                            ?: return@async null
                        val coincidenza = coincidenzaDi(riga.journey, stato)
                        if (coincidenza == Coincidenza.PERSA) return@async null
                        arricchita.copy(partenzaStimata = partenza, coincidenza = coincidenza)
                    }
                }.awaitAll().filterNotNull()
            }
            if (prese.isEmpty()) return@launch
            _state.update { s ->
                val gia = s.journeys.map { it.key }.toHashSet()
                s.copy(
                    journeys = (prese.filter { it.key !in gia } + s.journeys)
                        .sortedBy { it.journey.departure },
                )
            }
        }
    }

    /**
     * La coincidenza di una soluzione ancora prendibile, chiedendo il secondo
     * treno solo quando serve.
     *
     * Se col secondo in orario il cambio regge, il suo ritardo non potrebbe che
     * allargarlo: la chiamata non cambierebbe niente, e non parte. Parte quando
     * la coincidenza sembra persa, perche' il secondo puo' essere in ritardo
     * anche lui — era il caso del 17/09/2026 a Monza. Se non risponde resta la
     * stima col secondo in orario.
     */
    private suspend fun coincidenzaDi(j: Journey, primo: TrainStatus?): Coincidenza {
        val inOrario = j.coincidenza(primo, secondo = null)
        if (inOrario == Coincidenza.REGGE) return inOrario
        val poi = j.primoCambio()?.second?.takeIf { it.isTrain } ?: return inOrario
        val numero = poi.trainNumber ?: return inOrario
        val secondo = runCatching {
            trains.statusByNumber(
                trainNumber = numero,
                date = poi.departure.toLocalDate(),
                boardingCode = poi.from.rfiCode,
                boardingAt = poi.departure,
            )
        }.getOrNull() ?: return inOrario
        return j.coincidenza(primo, secondo)
    }

    /**
     * Vero se il ritardo di adesso dice qualcosa sull'ora cercata: da
     * [FINESTRA_RITARDI] fa a [ORIZZONTE_RITARDI] da ora. Prima, i treni della
     * finestra sono partiti da un pezzo; dopo, sono troppo lontani da qualunque
     * ritardo misurato adesso. Il giorno lo controlla gia' ogni riga, con
     * [JourneyRow.realtimeNow].
     */
    private fun ritardiContano(): Boolean {
        val adesso = LocalDateTime.now()
        return !departure.isBefore(adesso.minus(FINESTRA_RITARDI)) &&
            !departure.isAfter(adesso.plus(ORIZZONTE_RITARDI))
    }

    /**
     * Arricchisce le righe indicate con lo stato del loro primo treno, e poi con
     * la tenuta del loro primo cambio.
     *
     * Le chiamate partono in parallelo: in serie sarebbero una dozzina di
     * round-trip verso ViaggiaTreno e la lista resterebbe grigia per secondi.
     *
     * **La coincidenza si guarda su ogni riga, non solo su quelle gia' passate
     * in tabella.** Il 18/09/2026 l'elenco dava il REG 2987 delle 23:56 da Porta
     * Garibaldi a +31, con otto minuti di cambio a Milano Centrale, e non diceva
     * altro: che il cambio saltasse lo diceva solo la pagina del viaggio. Quel
     * +31 era a sua volta sbagliato (vedi `TrainStatusRepository.nonPartitoVuolDireFermo`),
     * ma la lacuna era vera: qualunque ritardo oltre il margine del cambio,
     * l'elenco lo taceva. Viene in un secondo giro, a stato gia' a schermo:
     * costa una chiamata solo dove il ritardo fa saltare il cambio (vedi
     * [coincidenzaDi]), e non deve tenere ferme le altre righe. Un treno
     * soppresso non ha coincidenze da perdere: la sua riga e' gia' barrata.
     *
     * Ogni giro scrive solo i campi suoi, sulla riga com'e' in quel momento:
     * vedi [JourneyRow.conTempoRealeDi].
     */
    private fun enrich(rows: List<JourneyRow>) {
        // Ogni riga vale per il proprio giorno: quelle di domani non si chiedono.
        val interrogabili = rows.filter { it.realtimeNow }
        if (interrogabili.isEmpty()) return

        viewModelScope.launch {
            val letture = coroutineScope {
                interrogabili.map { row -> async { conStato(row) } }.awaitAll()
            }
            val lette = letture.associate { (riga, _) -> riga.key to riga }
            _state.update { s ->
                s.copy(journeys = s.journeys.map { r -> lette[r.key]?.let(r::conTempoRealeDi) ?: r })
            }

            val coincidenze = coroutineScope {
                letture
                    .filter { (riga, _) -> riga.state != TrainState.CANCELLED }
                    .map { (riga, stato) -> async { riga.key to coincidenzaDi(riga.journey, stato) } }
                    .awaitAll()
            }.filter { (_, c) -> c != Coincidenza.REGGE }.toMap()
            if (coincidenze.isEmpty()) return@launch
            _state.update { s ->
                s.copy(journeys = s.journeys.map { r -> coincidenze[r.key]?.let { r.copy(coincidenza = it) } ?: r })
            }
        }
    }

    /**
     * La riga con lo stato del suo primo treno, e lo stato stesso: chi cerca i
     * treni ancora prendibili ne ha bisogno per decidere, non solo per mostrarlo.
     */
    private suspend fun conStato(row: JourneyRow): Pair<JourneyRow, TrainStatus?> {
        // Solo i treni: interrogare ViaggiaTreno col "890A" di un bus
        // sostitutivo e' una chiamata sprecata che fallisce sempre.
        val leg = row.journey.legs.firstOrNull { it.isTrain }
            ?: return row.copy(loadingStatus = false) to null
        val number = leg.trainNumber
            ?: return row.copy(loadingStatus = false) to null
        val giorno = leg.departure.toLocalDate()
        val status = runCatching {
            /*
             * Data e stazione di salita sono della tratta, non della ricerca: lo
             * stesso numero torna ogni giorno e puo' appartenere a due treni
             * diversi. Senza, la soluzione delle 01:31 di domani ereditava la
             * corsa di stamattina, gia' arrivata.
             */
            trains.statusByNumber(
                trainNumber = number,
                date = giorno,
                boardingCode = leg.from.rfiCode,
                boardingAt = leg.departure,
            )
        }.getOrNull()?.let { conBinarioDiSalita(it, leg, giorno) }
        val salita = status?.fermataDiSalita(leg)
        /*
         * La corsa c'e', ma la fermata da cui sali o quella a cui scendi e'
         * soppressa: il treno limitato, che oggi nasce dopo o finisce prima. Per
         * questo viaggio non serve, come una soppressione, ma soppresso non e'.
         * Trenord lo dichiara sulla soluzione; per gli altri lo dice ViaggiaTreno
         * sulla fermata.
         */
        val discesa = status?.fermataA(leg.to.rfiCode, leg.arrival.toLocalTime())
        val salitaSoppressa = status != null && status.state != TrainState.CANCELLED &&
            (salita?.status == StopStatus.CANCELLED || discesa?.status == StopStatus.CANCELLED)
        val arricchita = row.copy(
            loadingStatus = false,
            scheduledPlatform = salita?.scheduledPlatform,
            actualPlatform = salita?.actualPlatform,
            /*
             * La soppressione dichiarata dalla sorgente resta: di un treno
             * soppresso ViaggiaTreno non ha nemmeno il record, e il suo silenzio
             * non e' una smentita.
             */
            state = row.journey.declaredState?.takeIf { it.soppressione }
                ?: TrainState.CANCELLED.takeIf { salitaSoppressa }
                ?: status?.state ?: row.state,
            variato = row.journey.variato || (salitaSoppressa && row.journey.declaredState?.soppressione != true),
            delayMinutes = when {
                status == null -> row.delayMinutes
                /*
                 * Fermo all'origine, ViaggiaTreno non misura niente: il suo
                 * ritardo e' al piu' il minimo di `conRitardoDaFermo`. Se
                 * Trenord ne annuncia di piu' sulla soluzione, vale il suo.
                 */
                status.state == TrainState.NOT_DEPARTED ->
                    maxOf(status.delayMinutes, row.journey.delayMinutes ?: 0)
                else -> status.delayMinutes
            },
        )
        return arricchita to status
    }

    /** La fermata da cui sali, dentro la corsa: vedi `fermataA`. */
    private fun TrainStatus.fermataDiSalita(leg: Leg): Stop? =
        fermataA(leg.from.rfiCode, leg.departure.toLocalTime())

    /**
     * Il binario di dove sali, chiesto all'altra fonte solo quando manca.
     *
     * Non e' il caso raro ma quello normale: nelle stazioni grandi ViaggiaTreno
     * il binario non ce l'ha finche' non lo assegnano, e su una corsa Trenord
     * quel dato lo pubblica l'altra fonte — vedi `completaBinari`. Senza questo
     * passo il binario comparirebbe su una riga si' e una no, che e' il modo
     * peggiore di darlo.
     *
     * E' pero' una chiamata di rete per riga, quindi parte **solo** se a non
     * avere il binario e' proprio la fermata di salita: su una corsa che ce
     * l'ha gia' non parte niente, e su un numero che Trenord non conosce non si
     * insiste, perche' il repository se ne ricorda per un quarto d'ora.
     */
    private suspend fun conBinarioDiSalita(
        status: TrainStatus,
        leg: Leg,
        giorno: LocalDate,
    ): TrainStatus {
        if (DataSource.TRENORD !in sources) return status
        if (status.fermataDiSalita(leg)?.platform != null) return status
        return runCatching { trains.completaBinari(status, giorno) }.getOrDefault(status)
    }

    private fun List<Journey>.applyDirectFilter(): List<Journey> =
        if (directOnly) filter { it.isDirect } else this

    private companion object {
        /**
         * Quante corse per volta, avanti o indietro.
         *
         * Cinque risultavano pochi: la lista sembrava un tabellone troncato e
         * costringeva a chiedere subito le successive.
         */
        const val PAGE = 8

        /** Si chiede piu' del necessario perche' molte cadono fuori finestra. */
        const val WIDE_PAGE = 15

        /**
         * Quanto puo' durare una camminata in testa, che le fonti contano nella
         * partenza e noi no: vedi `loadLater`. Quelle fra gemelle sono di 5-8.
         */
        const val CAMMINATA_IN_TESTA_MAX = 10L

        /**
         * Le attese prima di ogni nuova ricerca dei prezzi: vedi [riprovaPrezzi].
         * Crescenti, perche' i buchi di Le Frecce vengono a grappoli; in tutto
         * una quindicina di secondi, piu' i tre o quattro di ogni ricerca.
         */
        val ATTESE_PREZZI = listOf(1_000L, 4_000L, 10_000L)

        /**
         * Grazia sui viaggi misti: si tengono anche se il feeder e' partito da
         * poco. Le coincidenze Italo in tempo reale sono rare, e perderne una per
         * una manciata di minuti la farebbe sparire del tutto. Non si applica ai
         * diretti nazionali, di cui ce n'e' in abbondanza.
         */
        val GRAZIA_MISTI: java.time.Duration = java.time.Duration.ofMinutes(20)

        /**
         * Quanto indietro guardare per i treni in ritardo che si prendono
         * ancora. Un'ora, deciso con l'utente il 14/09/2026: oltre, il ritardo e'
         * un'eccezione che il tabellone racconta meglio di una ricerca.
         */
        val FINESTRA_RITARDI: Duration = Duration.ofMinutes(60)

        /** Fin dove, nel futuro, il ritardo misurato adesso dice ancora qualcosa. */
        val ORIZZONTE_RITARDI: Duration = Duration.ofHours(2)
    }
}
