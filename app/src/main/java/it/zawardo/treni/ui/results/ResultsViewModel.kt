package it.zawardo.treni.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.repository.chiaveSoluzione
import it.zawardo.treni.domain.model.Coincidenza
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.FiltroFonti
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.coincidenza
import it.zawardo.treni.domain.model.declaredState
import it.zawardo.treni.domain.model.fermataA
import it.zawardo.treni.domain.model.partenzaAncoraUtile
import it.zawardo.treni.domain.model.soppressione
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
     * Su una di quelle soluzioni, la coincidenza che coi ritardi di adesso si
     * perde di poco: si propone lo stesso, ma va detto. Vedi `coincidenza`.
     */
    val coincidenzaARischio: Boolean = false,
) {
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
    val isRealtimeDay: Boolean get() = journey.departure.toLocalDate() == LocalDate.now()

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
                    noSameDayResults = rows.isNotEmpty() &&
                        rows.none { r -> r.journey.departure.toLocalDate() == requestedDay },
                )
            }
            enrich(rows)
            cercaAncoraPrendibili()
            cercaAltreSoluzioni(direttoMigliore = list.minByOrNull { it.duration }?.duration)
            if (outcome.prezziAssenti) riprovaPrezzi(departure, PAGE)
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
     * Una ricerca tornata tutta senza prezzi si rifa' una volta, in sottofondo.
     *
     * Le Frecce a volte risponde senza alcun prezzo, Frecce comprese: misurato
     * l'11/09/2026 in 12 ricerche su 36, quasi tutte su tratte regionali
     * lombarde. Richiamare la stessa sessione non cambia niente, una ricerca
     * nuova si'. La lista resta a schermo com'e': i prezzi, se arrivano, si
     * aggiungono alle righe che ne sono senza, e nient'altro si muove.
     */
    private fun riprovaPrezzi(quando: LocalDateTime, limite: Int) {
        viewModelScope.launch {
            val prezzi = runCatching { journeys.prezziLeFrecce(from, to, quando, limite) }
                .getOrDefault(emptyMap())
            if (prezzi.isEmpty()) return@launch
            _state.update { s ->
                s.copy(
                    journeys = s.journeys.map { riga ->
                        if (riga.journey.price != null) return@map riga
                        val prezzo = prezzi[chiaveSoluzione(riga.journey)] ?: return@map riga
                        riga.copy(journey = riga.journey.copy(price = prezzo))
                    },
                )
            }
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
            for (hoursBack in intArrayOf(3, 8)) {
                val start = first.minusHours(hoursBack.toLong())
                // Prima dell'inizio del giorno non c'e' niente da cercare.
                val clamped = maxOf(start, first.toLocalDate().atStartOfDay())
                // searchAll e non search: anche andando indietro le corse Trenord
                // devono comparire, altrimenti la lista cambia natura scorrendo.
                val esito = runCatching { journeys.searchAll(from, to, clamped, limit = WIDE_PAGE, sources = sources) }
                    .getOrNull()
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
                        noMoreEarlier = rows.isEmpty(),
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
            riprovaDa?.let { riprovaPrezzi(it, WIDE_PAGE) }
        }
    }

    /** Corse successive all'ultima mostrata: qui il BFF lavora nella sua direzione naturale. */
    fun loadLater() {
        val current = _state.value
        if (current.loadingLater || current.noMoreLater) return
        val last = current.journeys.lastOrNull()?.journey?.departure ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingLater = true) }

            val esito = runCatching {
                journeys.searchAll(from, to, last.plusMinutes(1), limit = WIDE_PAGE, sources = sources)
            }.getOrNull()
            val batch = esito?.journeys.orEmpty().applyDirectFilter()
                .filter { it.departure.isAfter(last) }

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
                    s.copy(loadingLater = false, journeys = s.journeys + rows, noMoreLater = rows.isEmpty())
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
            if (esito?.prezziAssenti == true) riprovaPrezzi(last.plusMinutes(1), WIDE_PAGE)
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
        val row = JourneyRow(this, state = declaredState, delayMinutes = delayMinutes)
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
                        val partenza = riga.journey.partenzaAncoraUtile(stato, departure)
                            ?: return@async null
                        val coincidenza = coincidenzaDi(riga.journey, stato)
                        if (coincidenza == Coincidenza.PERSA) return@async null
                        arricchita.copy(
                            partenzaStimata = partenza,
                            coincidenzaARischio = coincidenza == Coincidenza.A_RISCHIO,
                        )
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
        val poi = j.legs.getOrNull(1)?.takeIf { it.isTrain } ?: return inOrario
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
     * Arricchisce le righe indicate con lo stato del loro primo treno.
     *
     * Le chiamate partono in parallelo: in serie sarebbero una dozzina di
     * round-trip verso ViaggiaTreno e la lista resterebbe grigia per secondi.
     */
    private fun enrich(rows: List<JourneyRow>) {
        // Ogni riga vale per il proprio giorno: quelle di domani non si chiedono.
        val interrogabili = rows.filter { it.realtimeNow }
        if (interrogabili.isEmpty()) return

        viewModelScope.launch {
            val enriched = coroutineScope {
                interrogabili.map { row -> async { conStato(row).first } }.awaitAll()
            }
            val byKey = enriched.associateBy { it.key }
            _state.update { s ->
                s.copy(journeys = s.journeys.map { byKey[it.key] ?: it })
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
        val giorno = row.journey.departure.toLocalDate()
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
                ?: status?.state ?: row.state,
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
