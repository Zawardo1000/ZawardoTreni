package it.zawardo.treni.ui.results

import it.zawardo.treni.ui.common.GIORNO_BREVE
import it.zawardo.treni.ui.common.ORA_DEL_GIORNO
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.compositeOver
import it.zawardo.treni.domain.model.Coincidenza
import it.zawardo.treni.domain.model.JourneySource
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.adessoInItalia
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.comeDistinguerlaDa
import it.zawardo.treni.domain.model.nomeDelCambio
import it.zawardo.treni.ui.TrainRoute
import it.zawardo.treni.ui.ViaggioRoute
import it.zawardo.treni.ui.comeJson
import it.zawardo.treni.ui.tratteDelViaggio
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.primoCambio
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import it.zawardo.treni.ui.common.BinarioPillola
import it.zawardo.treni.ui.common.earlyColor
import it.zawardo.treni.ui.common.lateColor
import it.zawardo.treni.ui.common.onLateColor
import it.zawardo.treni.ui.common.scartoColor
import it.zawardo.treni.ui.theme.Cifre
import kotlinx.coroutines.delay
import java.time.Duration
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.fermoInRitardo
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.layout.widthIn

private val FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

/**
 * L'ora di adesso, che avanza da sola finche' l'elenco resta a schermo.
 *
 * Serve perche' **le righe invecchiano**: una ricerca delle 08:11 guardata alle
 * 08:23 conteneva ancora un treno partito alle 08:21. Vedi
 * `JourneyRow.giaPartita`.
 *
 * Mezzo minuto, e non uno: al minuto tondo una riga poteva restare «da
 * prendere» per cinquantanove secondi dopo esserlo diventata. Non costa nessuna
 * chiamata — e' l'orologio, non la rete — e una sola per tutto l'elenco, non una
 * per riga.
 */
@Composable
private fun adessoOgniMezzoMinuto(): LocalDateTime {
    val ora by produceState(adessoInItalia()) {
        while (true) {
            delay(30_000)
            value = adessoInItalia()
        }
    }
    return ora
}

/**
 * Le colonne di stato e prezzo in fondo alla scheda: vedi [RigaColonne].
 *
 * Larghe quanto le scritte piu' larghe che ci finiscono di solito — «non
 * partito» da una parte, «intero viaggio» dall'altra — **misurate col carattere
 * del telefono**, non scritte in dp: con il testo ingrandito nelle impostazioni
 * crescono anche loro, e le colonne restano colonne. Minime, non fisse: una
 * scritta piu' larga, come il bollo «Soppresso», allarga la sua scheda invece di
 * andare a capo a meta' parola.
 *
 * Prima erano 72 e 104 dp, e la seconda era misurata su «vendita chiusa» con
 * l'icona, che sta solo sulle schede rosse dei treni gia' passati in tabella.
 * Il 19/09/2026 sul Pixel 7 «intero viaggio» ne occupava 70: gli altri 34 li
 * pagavano i treni, che andavano a capo.
 */
private val SCRITTA_STATO = "non partito"
private val SCRITTA_PREZZO = "intero viaggio"
private val SPAZIO_COLONNE = 6.dp

/** Ritardo e prezzo: cifre tabulari come gli orari, perche' stiano in colonna. */
private val CIFRE_CODA = Cifre.riga.copy(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    from: Station,
    to: Station,
    departure: LocalDateTime,
    directOnly: Boolean = false,
    onBack: () -> Unit,
    onOpenTrain: (TrainRoute) -> Unit,
    /**
     * Un viaggio con cambio si apre tutto insieme: i treni in fila in una
     * pagina sola, col cambio scritto in mezzo. Il dettaglio della singola
     * corsa resta ai diretti, dove non c'e' niente da mettere in fila.
     */
    onOpenViaggio: (ViaggioRoute) -> Unit = {},
) {
    val vm: ResultsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ResultsViewModel(from, to, departure, directOnly) }
        },
    )
    val state by vm.state.collectAsState()
    val adesso = adessoOgniMezzoMinuto()

    val listState = rememberLazyListState()

    /*
     * Caricamento automatico arrivando in fondo.
     *
     * Il pulsante "Corse successive" resta, ma non deve essere l'unico modo:
     * era stato segnalato come mancante e non sono riuscito a riprodurre il
     * caso. Scorrere fino in fondo e vedere comparire altre corse funziona a
     * prescindere da dove finisca il pulsante nel layout.
     */
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(shouldLoadMore, state.journeys.size) {
        if (shouldLoadMore && !state.loading) vm.loadLater()
    }

    Scaffold(
        // Le schede bianche poggiano su un fondo appena azzurrato.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TreniTopBar(
                title = "${from.name} → ${to.name}",
                subtitle = departure.format(GIORNO_BREVE) + ", dalle " + departure.format(ORA_DEL_GIORNO),
                onBack = onBack,
                actions = {
                    IconButton(onClick = vm::reload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna")
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading && state.journeys.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null && state.journeys.isEmpty() ->
                    Message(state.error!!, Modifier.align(Alignment.Center))

                /*
                 * Lista vuota con avviso disponibile: prima l'avviso mostrava
                 * solo dentro la lista, quindi proprio nel caso in cui serve di
                 * piu' — nessuna corsa trovata — l'utente leggeva "nessun
                 * collegamento" senza sapere che la linea e' chiusa per lavori.
                 */
                state.journeys.isEmpty() -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    /*
                     * Vuota per un guasto non e' vuota: dirlo cambia cosa fare.
                     * Prima una ricerca a cui Trenitalia aveva risposto 500
                     * diceva "Nessun collegamento trovato", e cambiare orario
                     * era il consiglio sbagliato: bastava riprovare.
                     */
                    if (state.nazionaleNonRisponde) {
                        Text(
                            "Trenitalia non risponde",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Il servizio ha dato errore anche ai nuovi tentativi: le corse " +
                                "ci sono, ma adesso non arrivano. Riprova fra poco con ↻.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        return@Column
                    }
                    Text(
                        if (state.ultimaCorsaDelGiorno != null) "Nessuna corsa dopo quest'ora" else "Nessun collegamento trovato",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.ultimaCorsaDelGiorno?.let {
                        // Su una rete col solo orario una corsa gia' partita sparisce
                        // dall'elenco: senza questa riga lo schermo vuoto si legge
                        // come «questa tratta non esiste».
                        Text(
                            "L'ultima parte alle " + it.format(ORA_DEL_GIORNO) +
                                ": sposta indietro l'orario per vederla, o cambia giorno.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.directOnly) {
                        // Un filtro attivo che svuota la lista va detto, altrimenti
                        // sembra che la tratta non esista.
                        Text(
                            "Il filtro «Solo diretti» è attivo: su questa tratta " +
                                "potrebbero esserci soluzioni con cambi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    state.alerts.forEach { AlertCard(it) }
                    // Il consiglio generico solo se non si e' gia' detto qualcosa di
                    // piu' preciso: con l'ora dell'ultima corsa in vista, ripetere
                    // «prova a cambiare orario» e' rumore.
                    if (state.alerts.isEmpty() && !state.directOnly && state.ultimaCorsaDelGiorno == null) {
                        Text(
                            "Per questa tratta e questo orario non risultano corse. " +
                                "Prova a cambiare data o orario.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(state.alerts) { _, alert -> AlertCard(alert) }

                    if (state.error != null) {
                        item {
                            /*
                             * L'errore con la lista gia' piena: prima si mostrava
                             * solo a lista vuota, cioe' proprio quando non serviva
                             * dirlo. Toccando ↻ senza rete non succedeva niente —
                             * nessuna rotella, nessun messaggio, le stesse righe di
                             * prima — e sembrava che l'aggiornamento fosse andato.
                             */
                            AlertCard(
                                ServiceAlert(
                                    title = "Aggiornamento non riuscito",
                                    message = state.error!! + " Quello che vedi e' l'ultimo " +
                                        "risultato buono: riprova con ↻.",
                                    severe = true,
                                ),
                            )
                        }
                    }

                    if (state.nazionaleNonRisponde) {
                        item {
                            // Qualcosa c'e' — Trenord, le altre reti — ma non tutto.
                            AlertCard(
                                ServiceAlert(
                                    title = "Trenitalia non risponde",
                                    message = "Qui sotto mancano le corse nazionali: il servizio ha " +
                                        "dato errore anche ai nuovi tentativi. Riprova fra poco con ↻.",
                                    severe = true,
                                ),
                            )
                        }
                    }

                    if (state.noSameDayResults) {
                        item {
                            /*
                             * Il caso eccezionale: linea chiusa per lavori, servizio
                             * sostituito, ultimo treno gia' passato. Senza dirlo,
                             * due corse notturne di domani sembrano un guasto.
                             */
                            Card(
                                Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(
                                        "Nessun collegamento per " +
                                            departure.format(FULL_DATE),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    Text(
                                        "Le corse qui sotto sono di un altro giorno. " +
                                            "Può succedere con lavori in linea, servizi " +
                                            "sostitutivi o quando l'ultima corsa è già passata.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    if (!state.realtimeAvailable) {
                        item {
                            // Meglio dirlo che lasciar credere che tutti i treni siano in orario.
                            Text(
                                "Data futura: solo orario previsto, nessun dato in tempo reale.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    item {
                        MoreButton(
                            text = if (state.noMoreEarlier) "Nessuna corsa precedente" else "Corse precedenti",
                            icon = Icons.Filled.KeyboardArrowUp,
                            loading = state.loadingEarlier,
                            enabled = !state.noMoreEarlier,
                            onClick = vm::loadEarlier,
                        )
                    }

                    items(state.journeys, key = { it.key }) { row ->
                        JourneyCard(
                            row,
                            requestedDate = departure.toLocalDate(),
                            cercata = from,
                            adesso = adesso,
                        ) { tratta ->
                            apri(row.journey, tratta, onOpenTrain, onOpenViaggio)
                        }
                    }

                    item {
                        MoreButton(
                            text = if (state.noMoreLater) "Nessuna corsa successiva" else "Corse successive",
                            icon = Icons.Filled.KeyboardArrowDown,
                            loading = state.loadingLater,
                            enabled = !state.noMoreLater,
                            onClick = vm::loadLater,
                        )
                    }

                }
            }

            // Mentre arrivano le soluzioni con piu' operatori (una manciata di
            // secondi: feeder + alta velocita' + risoluzione dell'hub), un velo
            // semitrasparente copre la lista e ne blocca il tocco. La ragione non
            // e' estetica: i diretti sono gia' a schermo e un misto puo' essere
            // migliore, quindi non si lascia scegliere su una lista incompleta.
            if (state.loadingMisti) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            "Cerco soluzioni con più operatori…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneyCard(
    row: JourneyRow,
    requestedDate: LocalDate,
    /** La stazione di partenza cercata: il binario, se e' di un'altra, lo dice. */
    cercata: Station,
    /** L'ora di adesso, che avanza mentre la scheda resta a schermo: vedi [adessoOgniMezzoMinuto]. */
    adesso: LocalDateTime,
    /** L'indice della tratta toccata; `0` quando si tocca la scheda altrove. */
    onApri: (Int) -> Unit,
) {
    val j: Journey = row.journey
    val otherDay = j.departure.toLocalDate() != requestedDate
    // Barrato come su un tabellone: la corsa c'e' in orario, ma non si fa.
    val cancelled = row.state == TrainState.CANCELLED
    /*
     * Il treno se n'e' andato mentre l'elenco era a schermo: vedi
     * `JourneyRow.giaPartita`. Non toglie la riga — resta, spenta, e lo dice —
     * ma le leva ogni promessa: niente «fai ancora in tempo», niente coincidenza
     * da giudicare, perche' quel treno non lo prende piu' nessuno.
     *
     * **Mai su un «non partito»**, che e' il contrario: quel treno e' ancora
     * all'origine oltre la sua ora, e l'orologio da solo lo direbbe andato
     * proprio mentre lo si sta rincorrendo. Lo stato viene prima dell'orologio.
     */
    val partito = !cancelled && row.state != TrainState.NOT_DEPARTED && row.giaPartita(adesso)
    /** La fermata da cui saliresti: e' di li' che il treno e' partito, non dal suo capolinea. */
    val partenzaDa = j.legs.firstOrNull { it.isTrain }?.from?.name
    val scheme = MaterialTheme.colorScheme

    /*
     * Fondo piu' tenue quando non c'e' tempo reale.
     *
     * Due casi, entrambi da distinguere a colpo d'occhio dai treni "vivi": le
     * corse di un altro giorno, di cui il ritardo si sapra' ma non adesso, e i
     * viaggi che un tempo reale non lo avranno mai — un misto con gamba EAV, o
     * un servizio sostitutivo. In tutti l'orario e' previsto, non misurato, e la
     * scheda lo dice col colore prima ancora delle parole.
     */
    val soloPrevisto = !row.realtimeNow
    /*
     * Un fondo suo anche per il treno gia' passato in tabella che si prende
     * ancora: e' la sola scheda con un orario prima di quello cercato, e il
     * biglietto per quell'orario non si compra piu' (vedi [VenditaChiusa]). Il
     * rosso appena accennato e' quello del ritardo, che e' il motivo per cui la
     * scheda sta li'.
     */
    val ancoraInTempo = row.partenzaStimata != null && !partito
    /*
     * Lo stesso rosso per il cambio che coi ritardi di adesso non regge: anche
     * li' e' il ritardo a cambiare la soluzione, e la scheda lo dice prima
     * delle parole. Come la scheda della coincidenza nella pagina del viaggio.
     */
    val cambioSaltato = row.coincidenza != Coincidenza.REGGE && !partito
    val fondo = when {
        ancoraInTempo || cambioSaltato -> lateColor().copy(alpha = 0.07f).compositeOver(scheme.surfaceContainerLowest)
        soloPrevisto || partito -> scheme.surfaceContainerLow
        else -> scheme.surfaceContainerLowest
    }

    /*
     * L'orario reale sotto quello di tabella, solo quando se ne discosta.
     *
     * Lo scarto e' quello del primo treno, l'unico che si interroga. Sulla
     * partenza vale di sicuro; sull'arrivo lo si proietta solo per i diretti,
     * perche' su un viaggio con cambio l'arrivo dipende da un altro treno.
     */
    val scarto = row.delayMinutes?.takeIf { it != 0 && !cancelled }
    val partenzaReale = scarto?.let { j.departure.plusMinutes(it.toLong()) }
    val arrivoReale = scarto?.takeIf { j.isDirect }?.let { j.arrival.plusMinutes(it.toLong()) }
    val barrato = if (cancelled) TextDecoration.LineThrough else null
    val inchiostro = if (cancelled || partito) scheme.onSurfaceVariant else scheme.onSurface

    Card(
        /*
         * Toccando la scheda fuori dalle sigle dei treni si apre dal principio:
         * con un cambio e' la pagina del viaggio col primo treno a fuoco, su un
         * diretto il dettaglio della corsa. Un bus sostitutivo da solo non ha
         * dettaglio da aprire, e il tocco non porta da nessuna parte.
         */
        onClick = { onApri(0) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = fondo),
        border = BorderStroke(
            1.dp,
            if (ancoraInTempo || cambioSaltato) lateColor().copy(alpha = 0.45f) else scheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {

            // Dove si scende e, fra due gemelle, dove si risale: vedi `nomeDelCambio`.
            val stazioneDelCambio = j.primoCambio()?.let { (primo, poi) -> nomeDelCambio(primo.to, poi.from) }.orEmpty()
            if (otherDay || j.assembled || ancoraInTempo || cambioSaltato || partito) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    /*
                     * Il BFF puo' restituire corse di un altro giorno quando per
                     * quello richiesto non c'e' nulla. Senza questa riga si legge
                     * "01:01" e si capisce stanotte, mentre e' la notte dopo.
                     */
                    if (otherDay) {
                        Text(
                            j.departure.format(FULL_DATE).replaceFirstChar { c -> c.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.tertiary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    /*
                     * Il viaggio misto si annuncia per quello che e': cambia
                     * operatore per strada, l'abbiamo costruito noi, e la gamba
                     * Italo puo' non avere prezzo. Chi lo sceglie deve saperlo
                     * prima, non scoprirlo alla biglietteria.
                     */
                    if (j.assembled) {
                        Text(
                            "Più operatori · beta",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.tertiary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    /*
                     * Il treno e' gia' passato in tabella, prima dell'ora
                     * cercata, e compare perche' in ritardo lo si prende ancora.
                     * Va detto: senza, un 10:01 in cima a una ricerca delle 10:10
                     * sembrerebbe un errore dell'elenco. L'ora vera sta sotto
                     * quella di tabella, come su ogni altra scheda.
                     */
                    if (row.partenzaStimata != null) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Schedule,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = lateColor(),
                                )
                                Text(
                                    "In ritardo · fai ancora in tempo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = lateColor(),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            // Il primo treno si prende, ma al cambio arriva dopo
                            // la coincidenza: si propone contando su un recupero,
                            // e chi la sceglie deve saperlo.
                            if (row.coincidenza == Coincidenza.A_RISCHIO) {
                                Text(
                                    "Coincidenza a $stazioneDelCambio a rischio",
                                    Modifier.padding(top = 2.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = lateColor(),
                                )
                            }
                        }
                    }
                    /*
                     * Partito mentre guardavi. In grigio e non in rosso: non e'
                     * un guasto ne' un ritardo, e' solo il treno di prima. Il
                     * rosso resta a cio' su cui si puo' ancora fare qualcosa.
                     *
                     * **Anche su chi e' gia' «Arrivato»**, che pure lo dice nella
                     * colonna dello stato: sono due fatti diversi — partito e'
                     * da dove sali, arrivato e' al suo capolinea — e soprattutto
                     * il bollo assente su una scheda spenta in mezzo ad altre che
                     * ce l'hanno si legge come una svista dell'app. Provato a
                     * schermo il 22/09/2026: l'S5 24518 senza bollo e' saltato
                     * all'occhio prima di qualunque ragionamento.
                     */
                    if (partito) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = scheme.onSurfaceVariant,
                            )
                            /*
                             * **Col nome della stazione, non «Partito» e basta.**
                             * Secco si legge «partito dal suo capolinea», che e'
                             * un'altra cosa e lascia sperare di prenderlo: un
                             * treno partito da Treviglio a Vignate ci deve ancora
                             * arrivare. Qui si parla della fermata dove sali —
                             * quella del primo treno, che fra due gemelle non e'
                             * sempre quella cercata.
                             */
                            Text(
                                partenzaDa?.let { "Partito da $it" } ?: "Partito",
                                style = MaterialTheme.typography.labelLarge,
                                color = scheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    /*
                     * Il treno parte all'ora cercata o dopo, ma in ritardo, e al
                     * cambio arriva dopo la coincidenza. La scheda resta, come
                     * resta barrato un treno soppresso: l'orario la prevede, e
                     * sapere perche' non si fa dice di piu' che vederla sparire.
                     */
                    if (!ancoraInTempo && cambioSaltato) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = lateColor(),
                            )
                            Text(
                                if (row.coincidenza == Coincidenza.PERSA) {
                                    "Coincidenza persa a $stazioneDelCambio"
                                } else {
                                    "Coincidenza a $stazioneDelCambio a rischio"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = lateColor(),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    Column {
                        Text(j.departure.format(ORA_DEL_GIORNO), style = Cifre.grandi, color = inchiostro, textDecoration = barrato)
                        OrarioReale(partenzaReale, scarto)
                    }
                    Column(
                        Modifier.weight(1f).padding(horizontal = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LineaViaggio(j, fondo, Modifier.fillMaxWidth().height(30.dp))
                        Text(
                            riassunto(j),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(j.arrival.format(ORA_DEL_GIORNO), style = Cifre.grandi, color = inchiostro, textDecoration = barrato)
                        OrarioReale(arrivoReale, scarto)
                    }
                }

                /*
                 * Il binario di partenza, in una colonna sua separata come la
                 * matrice di un biglietto. E' l'ultima cosa che si guarda prima
                 * di muoversi, e su un viaggio con cambio e' quello del **primo**
                 * treno, l'unico che serva prima di partire.
                 *
                 * Quando non c'e' ancora, un segnaposto tratteggiato tiene il
                 * posto: nelle stazioni grandi il binario lo assegnano un quarto
                 * d'ora prima, e una colonna che compare e scompare da una scheda
                 * all'altra si legge peggio di una colonna vuota.
                 */
                if (j.hasTrain) {
                    MatriceBiglietto(Modifier.padding(start = 12.dp).height(56.dp))
                    /*
                     * Il binario e' del primo treno, alla sua stazione, che non e'
                     * sempre quella cercata: cercando Milano Porta Garibaldi le S5
                     * partono dal Passante, e «binario 1» senz'altro manderebbe al
                     * binario 1 di superficie. Sotto, allora, di quale stazione e'
                     * (vedi `comeDistinguerlaDa`).
                     */
                    val altrove = j.legs.firstOrNull { it.isTrain }?.from?.comeDistinguerlaDa(cercata)
                    // Minima e non fissa: un binario dal nome lungo allarga la
                    // colonna invece di andare a capo a meta' parola.
                    Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BinarioPillola(
                            row.scheduledPlatform,
                            row.actualPlatform,
                            modifier = if (cancelled) Modifier.alpha(0.45f) else Modifier,
                            segnaposto = true,
                            conSigla = true,
                        )
                        if (altrove != null) {
                            Text(
                                altrove,
                                Modifier.padding(top = 3.dp).widthIn(max = 96.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                Modifier.padding(top = 12.dp, bottom = 10.dp),
                color = scheme.outlineVariant.copy(alpha = 0.6f),
            )

            /*
             * Treni, stato e prezzo in tre colonne: le ultime due con una
             * larghezza propria, allineate a destra, tutte sulla stessa linea di
             * base.
             *
             * Prima stavano l'una accanto all'altra e basta. Il ritardo si
             * spostava con la larghezza del prezzo, e un «intero viaggio» su due
             * righe lo tirava piu' in basso e piu' a sinistra di quello della
             * scheda sopra: scorrendo, i numeri non stavano in colonna e l'elenco
             * sembrava disordinato (segnalato il 14/09/2026). Le colonne tengono
             * il posto anche vuote, perche' e' il posto fisso a farle leggere
             * come colonne.
             */
            RigaColonne(
                colonne = larghezzeColonne(),
                treni = { Tratte(j, Modifier, onApri) },
                stato = { StatoSoluzione(row) },
                prezzo = {
                    when {
                        /*
                         * Su un treno gia' passato in tabella il prezzo non c'e' piu',
                         * e tacerlo farebbe sembrare una ricerca venuta senza prezzi.
                         *
                         * **Non vale per i misti**: li' il prezzo e' quello della
                         * tratta lunga, venduto da Trenitalia, e resta acquistabile
                         * anche se il feeder locale e' partito da poco. Sono anche le
                         * uniche righe non nate da Le Frecce che portano il suo
                         * `source` di default.
                         */
                        ancoraInTempo && !j.assembled &&
                            (j.source == JourneySource.LEFRECCE || j.venditaChiusa) -> VenditaChiusa()
                        // Lo si sta richiedendo: vedi `ResultsViewModel.riprovaPrezzi`.
                        row.prezzoInArrivo && row.aspettaPrezzo ->
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else -> Prezzo(j)
                    }
                },
            )

            /*
             * Quel che Le Frecce dice della soluzione e la scheda non direbbe: un
             * treno che su quella tratta non porta viaggiatori, quale treno e'
             * esaurito, i convogli non comunicanti. Vedi `avvisiDelSito`.
             */
            j.avvisi.forEach { avviso ->
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 1.dp).size(15.dp),
                        tint = scheme.onSurfaceVariant,
                    )
                    Text(avviso, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }

            if (j.assembled) {
                // Italo si nomina solo se una gamba e' davvero Italo: su EAV piu'
                // Freccia il prezzo c'e' (parziale, gia' etichettato), e tirare
                // in ballo Italo dove non c'entra confonderebbe.
                val conItalo = j.legs.any { it.source == DataSource.ITALO }
                Text(
                    buildString {
                        append("Cambio fra operatori diversi. ")
                        if (conItalo) append("Il prezzo Italo non è disponibile. ")
                        append("Verifica orari e biglietti sui siti dei gestori.")
                    },
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** L'orario vero sotto quello di tabella; vuoto, tiene comunque l'altezza. */
@Composable
private fun OrarioReale(quando: LocalDateTime?, scarto: Int?) {
    Text(
        quando?.format(ORA_DEL_GIORNO) ?: "",
        style = Cifre.riga.copy(fontSize = 15.sp, lineHeight = 18.sp),
        fontWeight = FontWeight.SemiBold,
        color = scartoColor(scarto ?: 0),
    )
}

/**
 * «36 min · diretto», «1h 13 · 1 cambio, Treviglio». Con una camminata fra due
 * gemelle i nomi sono due, «1 cambio, Napoli P. Garibaldi › Centrale»: vedi
 * `nomeDelCambio`.
 */
private fun riassunto(j: Journey): String {
    val cambi = when {
        j.isDirect -> "diretto"
        j.changes == 1 -> "1 cambio, " + nomeDelCambio(j.mezzi[0].to, j.mezzi[1].from)
        else -> "${j.changes} cambi"
    }
    return formatDuration(j.duration.toMinutes()) + " · " + cambi
}

/**
 * La linea del viaggio fra partenza e arrivo, con un pallino per ogni cambio
 * messo dove cade nel tempo: un cambio a meta' strada si vede a meta' linea.
 */
@Composable
private fun LineaViaggio(j: Journey, fondo: Color, modifier: Modifier) {
    val linea = MaterialTheme.colorScheme.outlineVariant
    val punto = MaterialTheme.colorScheme.outline
    val totale = Duration.between(j.departure, j.arrival).toMinutes().coerceAtLeast(1)
    // Un pallino per cambio: la camminata di un cambio non ne fa un altro.
    val cambi = j.mezzi.dropLast(1).map { leg ->
        (Duration.between(j.departure, leg.arrival).toMinutes().toFloat() / totale).coerceIn(0.1f, 0.9f)
    }
    Canvas(modifier) {
        val y = size.height / 2
        val estremo = 3.dp.toPx()
        drawLine(linea, Offset(estremo, y), Offset(size.width - estremo, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(punto, estremo, Offset(estremo, y))
        drawCircle(punto, estremo, Offset(size.width - estremo, y))
        cambi.forEach { f ->
            val c = Offset(size.width * f, y)
            drawCircle(fondo, 5.dp.toPx(), c)
            drawCircle(punto, 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}

/** Il taglio tratteggiato fra il viaggio e il binario, come sulla matrice di un biglietto. */
@Composable
private fun MatriceBiglietto(modifier: Modifier) {
    val colore = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.width(1.dp)) {
        drawLine(
            colore,
            Offset(size.width / 2, 0f),
            Offset(size.width / 2, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
        )
    }
}

/**
 * I treni del viaggio, in fila: sigla nel riquadro e numero.
 *
 * Erano chip Material cliccabili, che facevano sembrare la scheda un modulo da
 * compilare. Restano toccabili — dentro un viaggio con cambio ognuno porta alla
 * sua tratta, bus e trasferimenti a piedi compresi — ma si leggono come
 * etichette. Monocromi apposta: il rosso resta al ritardo, e non si confonde con
 * una Freccia.
 *
 * Vanno a capo invece di stringersi: con tre cambi sono quattro, e in una riga
 * sola Compose li comprimeva finche' l'ultimo restava largo un carattere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tratte(j: Journey, modifier: Modifier, onApri: (Int) -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        j.legs.forEachIndexed { indice, leg ->
            // Su un diretto un bus non ha niente da aprire: resta inerte.
            val attiva = leg.isTrain || !j.isDirect
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = attiva) { onApri(indice) }
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // La freccia viaggia col treno che segue: andando a capo non resta
                // sola in fondo a una riga, ne' in testa alla successiva da sola.
                if (indice > 0) {
                    Text(
                        "›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                when {
                    leg.isTrain -> {
                        leg.category?.let { SiglaTreno(it) }
                        Text(
                            leg.trainNumber ?: if (leg.category == null) leg.label else "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    else -> {
                        Icon(
                            if (leg.isWalk) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Filled.DirectionsBus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(leg.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Quanto sono larghe, al minimo, le colonne di stato e prezzo: vedi [SCRITTA_STATO]. */
private data class Colonne(val stato: Int, val prezzo: Int)

/**
 * Le larghezze delle colonne, misurate con gli stili veri delle scritte.
 * Si ricalcolano solo se cambiano densita' o dimensione del testo.
 */
@Composable
private fun larghezzeColonne(): Colonne {
    val misura = rememberTextMeasurer()
    val tipi = MaterialTheme.typography
    val densita = LocalDensity.current
    return remember(densita, tipi) {
        fun largo(testo: String, stile: TextStyle) = misura.measure(testo, stile).size.width
        Colonne(
            stato = maxOf(
                largo(SCRITTA_STATO, tipi.labelLarge.copy(fontWeight = FontWeight.Medium)),
                largo("+888 min", CIFRE_CODA),
            ),
            prezzo = maxOf(
                largo(SCRITTA_PREZZO, tipi.labelSmall),
                largo("888,88 €", CIFRE_CODA),
                // La scheda rossa del treno gia' passato in tabella: vedi [VenditaChiusa].
                largo(VENDITA_CHIUSA, tipi.labelSmall.copy(fontWeight = FontWeight.SemiBold)),
                largo(ORARIO_PASSATO, tipi.labelSmall),
            ),
        )
    }
}

/**
 * La riga in fondo alla scheda: treni, stato, prezzo.
 *
 * Stato e prezzo sono colonne allineate a destra, e tengono il loro posto anche
 * vuote: cosi' ritardi e prezzi stanno in colonna da una scheda all'altra, e
 * scorrendo l'elenco si leggono come su un tabellone (segnalato il 14/09/2026).
 *
 * I treni devono stare su una riga, se possono (chiesto l'11/09/2026). Per
 * farceli stare possono prendersi il vuoto della colonna dello stato, che sta a
 * **sinistra** della scritta: la scritta, allineata a destra, non si muove. Il
 * vuoto del prezzo invece no. Sta a destra dello stato, e cederlo spostava lo
 * stato: il 19/09/2026 in Varese-Brescia «non partito» non stava piu' in
 * colonna. E si cede solo se basta: se i treni andrebbero a capo comunque, le
 * colonne restano come sono. Tranne quando lo stato e' vuoto, come sui viaggi
 * misti: li' non si sposta niente, e i treni si prendono anche il vuoto del
 * prezzo, pur di andare a capo una volta di meno.
 *
 * Tutte e tre sulla stessa linea di base.
 */
@Composable
private fun RigaColonne(
    colonne: Colonne,
    treni: @Composable () -> Unit,
    stato: @Composable () -> Unit,
    prezzo: @Composable () -> Unit,
) {
    SubcomposeLayout { vincoli ->
        val spazio = SPAZIO_COLONNE.roundToPx()
        val larghezza = vincoli.maxWidth
        val libero = Constraints(maxWidth = larghezza)

        // Quanto chiedono davvero. I treni si misurano su una riga senza limite
        // di larghezza: l'intrinseca di FlowRow non conta lo spazio fra le voci,
        // e il 18/09/2026 dava 419 px a una riga che ne voleva 431.
        val inRiga = subcompose(Parte.TRENI, treni).single().measure(Constraints())
        val testoStato = subcompose(Parte.STATO) { Box { stato() } }.single().measure(libero)
        val testoPrezzo = subcompose(Parte.PREZZO) { Box { prezzo() } }.single().measure(libero)

        var colonnaStato = maxOf(testoStato.width, colonne.stato)
        var colonnaPrezzo = maxOf(testoPrezzo.width, colonne.prezzo)
        val manca = inRiga.width + colonnaStato + colonnaPrezzo + 2 * spazio - larghezza
        val dalloStato = colonnaStato - testoStato.width
        if (testoStato.width == 0) {
            // Stato vuoto, come sui viaggi misti: niente si sposta, e i treni si
            // prendono il vuoto che serve anche solo per andare a capo una volta di meno.
            val cede = minOf(manca.coerceAtLeast(0), dalloStato + colonnaPrezzo - testoPrezzo.width)
            val primaDalloStato = minOf(cede, dalloStato)
            colonnaStato -= primaDalloStato
            colonnaPrezzo -= cede - primaDalloStato
        } else if (manca in 1..dalloStato) {
            colonnaStato -= manca
        }
        val colonnaTreni = (larghezza - colonnaStato - colonnaPrezzo - 2 * spazio).coerceAtLeast(0)

        // Se non ci stanno nemmeno cosi', i treni vanno a capo.
        val treniMisurati = if (inRiga.width <= colonnaTreni) {
            inRiga
        } else {
            subcompose(Parte.TRENI_A_CAPO, treni).single().measure(Constraints(maxWidth = colonnaTreni))
        }

        // Tutte e tre sulla stessa linea di base; chi non ne ha una si allinea dall'alto.
        fun Placeable.base() = this[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val parti = listOf(treniMisurati, testoStato, testoPrezzo)
        val base = parti.maxOf { it.base() }
        val altezza = parti.maxOf { it.height + base - it.base() }
        val inizioStato = colonnaTreni + spazio
        val inizioPrezzo = inizioStato + colonnaStato + spazio

        layout(larghezza, altezza) {
            treniMisurati.place(0, base - treniMisurati.base())
            // Le colonne sono allineate a destra, come i numeri.
            testoStato.place(inizioStato + colonnaStato - testoStato.width, base - testoStato.base())
            testoPrezzo.place(inizioPrezzo + colonnaPrezzo - testoPrezzo.width, base - testoPrezzo.base())
        }
    }
}

/** Le parti della riga in fondo alla scheda: vedi [RigaColonne]. */
private enum class Parte { TRENI, TRENI_A_CAPO, STATO, PREZZO }

@Composable
private fun SiglaTreno(sigla: String) {
    val colore = MaterialTheme.colorScheme.onSurface
    Text(
        sigla,
        Modifier
            .border(1.5.dp, colore, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colore,
    )
}

/**
 * Lo stato della soluzione, in basso a destra e sempre li'.
 *
 * Una riga sola: o l'etichetta dello stato anomalo, o il ritardo. Quando
 * comparivano entrambe il ritardo veniva detto due volte.
 */
@Composable
private fun StatoSoluzione(row: JourneyRow) {
    val scheme = MaterialTheme.colorScheme
    val stato = row.state
    when {
        /*
         * Va detto, invece di lasciare il posto vuoto come se l'informazione
         * stesse ancora arrivando.
         *
         * Ma solo se di questa corsa non si sa davvero niente: vedi
         * [JourneyRow.statoNoto]. Una riga EAV di cui il pianificatore o il
         * tabellone hanno detto il ritardo — o la soppressione — ha un dato
         * vero da mostrare, e scriverci sopra «senza tempo reale» lo cancellava.
         */
        !row.realtimePossible && !row.statoNoto -> Text(
            "senza tempo reale",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )

        row.loadingStatus -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)

        // Barrata in tutti e due i casi; la parola dice quale dei due.
        stato == TrainState.CANCELLED -> Text(
            if (row.variato) "Variato" else "Soppresso",
            Modifier
                .background(lateColor(), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = onLateColor(),
        )

        /*
         * Salta delle fermate, o cambia strada, ma sali e scendi dove previsto:
         * il treno ti porta, e la riga non si barra. «Variato» in verde, come
         * «in orario», perche' e' un'informazione e non un allarme; quali
         * fermate, lo dice il dettaglio (deciso con l'utente il 19/09/2026). Il
         * ritardo, se c'e', resta sopra.
         */
        stato == TrainState.PARTIALLY_CANCELLED || stato == TrainState.DIVERTED -> {
            val minuti = row.delayMinutes ?: 0
            if (minuti != 0) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(delayLabel(minuti), style = CIFRE_CODA, color = scartoColor(minuti))
                    Text(
                        "variato",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = earlyColor(),
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    "Variato",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = earlyColor(),
                )
            }
        }

        stato != null -> {
            val anomalia = stateLabel(stato)
            val minuti = row.delayMinutes ?: 0
            when {
                // Fermo all'origine oltre la sua ora: il ritardo prima di tutto,
                // e sotto perche' non si muove. Vedi `fermoInRitardo`.
                fermoInRitardo(stato, minuti) -> Column(horizontalAlignment = Alignment.End) {
                    Text(delayLabel(minuti), style = CIFRE_CODA, color = lateColor())
                    Text(
                        "non partito",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                anomalia != null -> Text(
                    // Nell'elenco la versione corta: «Non ancora partito» da solo
                    // mandava a capo le sigle di un viaggio con un cambio.
                    if (stato == TrainState.NOT_DEPARTED) "non partito" else anomalia,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = stateColor(stato, row.delayMinutes),
                    maxLines = 1,
                )
                minuti == 0 -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(Modifier.size(6.dp).background(earlyColor(), CircleShape))
                    Text(
                        "in orario",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = earlyColor(),
                    )
                }
                else -> Text(
                    delayLabel(minuti),
                    style = CIFRE_CODA,
                    color = scartoColor(minuti),
                )
            }
        }

        /*
         * Corsa di un altro giorno: lo stato non c'e' e non arrivera'. Succede
         * anche cercando per oggi, quando la tratta e' ferma e le sole soluzioni
         * sono di domani. Il vuoto, li', si leggerebbe come "in orario".
         */
        !row.isRealtimeDay -> Text(
            "orario previsto",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )

        /*
         * Corsa di oggi che nessuna fonte ha saputo dire: capita quando
         * ViaggiaTreno non conosce quel treno e non c'e' altro a cui chiederlo.
         * Anche qui il vuoto si leggerebbe come «in orario», che e' la cosa
         * sbagliata da far credere: meglio dire che il dato non c'e'.
         */
        else -> Text(
            "stato non disponibile",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Al posto del prezzo, su un treno gia' passato in tabella che si prende ancora.
 *
 * Non e' una supposizione: per le soluzioni partite in tabella Le Frecce
 * risponde `saleable = false` e lo dice a parole — "Impossible d'acheter un
 * voyage précédent à la date actuelle", sondato il 14/09/2026 su Milano -
 * Roma e Taormina - Catania. Chi un abbonamento non ce l'ha, e cerca il
 * biglietto proprio per quell'orario, non lo trova: deve saperlo prima di
 * correre in banchina, non davanti alla macchinetta.
 *
 * Solo dove lo dice la fonte. Le Frecce per ogni soluzione partita in tabella:
 * la porta del sito lo scrive come regola, "It's not possible to buy a travel
 * solution if the departure date is before current date" (18/09/2026). Trenord
 * soluzione per soluzione, con `PAST_DEPARTURE_DATE`: vedi `Journey.venditaChiusa`.
 * Prima valeva per le sole soluzioni di Le Frecce, e il RE 2844 del 18/09/2026,
 * che nella fusione arrivava da Trenord, restava senza prezzo e senza un perche'.
 */
@Composable
private fun VenditaChiusa() {
    val colore = MaterialTheme.colorScheme.onSurfaceVariant
    /*
     * Nella colonna del prezzo e non piu' larga: con l'icona e «per questo
     * orario» ne chiedeva 99 dp, allargava la colonna solo su questa scheda e lo
     * stato non stava piu' in colonna con le altre (19/09/2026). E in carattere
     * piccolo come «intero viaggio», perche' la colonna e' la stessa per tutte le
     * schede: piu' grande, la pagavano i treni di ogni riga. Vedi
     * [larghezzeColonne], che la misura.
     */
    Column(horizontalAlignment = Alignment.End) {
        Text(
            VENDITA_CHIUSA,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = colore,
        )
        Text(ORARIO_PASSATO, style = MaterialTheme.typography.labelSmall, color = colore)
    }
}

private const val VENDITA_CHIUSA = "vendita chiusa"
private const val ORARIO_PASSATO = "orario passato"


/**
 * Il prezzo compare solo quando c'e'.
 *
 * Lo pubblicano le due sorgenti che vendono — Trenitalia e Trenord — e nemmeno
 * loro sempre: sulla stessa tratta una ricerca su cinque torna senza. Riempire
 * il vuoto con un trattino o con "n.d." darebbe l'idea di un dato mancante per
 * colpa dell'app; non scrivere niente e' piu' onesto e piu' pulito.
 *
 * Sotto il prezzo, cosa copre. Su un viaggio con cambio interno alla rete e'
 * del viaggio intero; su un misto e' il solo parziale di chi lo pubblica, e va
 * detto forte, o quella cifra sembrerebbe il costo di tutto.
 */
@Composable
private fun Prezzo(j: Journey) {
    val p = j.price ?: j.partialPrice ?: return
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End) {
        Text(
            p.formatted,
            style = CIFRE_CODA,
            color = if (p.saleable) scheme.primary else scheme.onSurfaceVariant,
        )
        // Sotto la cifra e non accanto: accanto allargava la colonna, e il
        // ritardo di quella scheda usciva dalla colonna delle altre.
        // Esaurito solo quando la fonte lo dice: non vendibile e' un'altra cosa.
        if (!p.saleable) {
            Text(
                if (p.esaurito) "esaurito" else "non in vendita",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        val etichetta = when {
            // La somma di piu' biglietti, uno per venditore: va detto, o sembrerebbe
            // un biglietto solo. Vedi `tratteDaBiglietto`.
            j.biglietti.size == 2 -> "due biglietti"
            j.biglietti.size > 2 -> "${j.biglietti.size} biglietti"
            // Su un misto, il pezzo di un operatore.
            j.price == null && j.assembled -> "solo " + operatoreParziale(j)
            // Il tratto urbano nel prezzo non c'e': prima questa scheda diceva
            // "intero viaggio" su «Urbano › FR 9715», ed era falso.
            j.price == null || j.legs.any { it.urbano } -> "solo treno"
            !j.isDirect -> "intero viaggio"
            else -> null
        }
        etichetta?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * Dove porta il tocco su una soluzione.
 *
 * Con un cambio si va alla pagina del viaggio, che le corse le tiene tutte in
 * fila, aperta sulla tratta toccata. Su un diretto resta il dettaglio della
 * singola corsa: non c'e' nessuna sequenza da mostrare, e la pagina del viaggio
 * sarebbe la stessa cosa con un giro in piu'.
 */
private fun apri(
    journey: Journey,
    tratta: Int,
    onOpenTrain: (TrainRoute) -> Unit,
    onOpenViaggio: (ViaggioRoute) -> Unit,
) {
    if (!journey.isDirect) {
        onOpenViaggio(
            ViaggioRoute(
                tratteJson = journey.tratteDelViaggio().comeJson(),
                focus = tratta.coerceIn(0, journey.legs.lastIndex),
            ),
        )
        return
    }

    // Solo i treni hanno un dettaglio: aprirlo per un bus porterebbe a una
    // schermata che dice "non trovato".
    val leg = journey.legs.firstOrNull { it.isTrain } ?: return
    val number = leg.trainNumber ?: return
    onOpenTrain(
        TrainRoute(
            number = number,
            dateEpochDay = journey.departure.toLocalDate().toEpochDay(),
            boardingRfi = leg.from.rfiCode,
            boardingName = leg.from.name,
            alightingRfi = leg.to.rfiCode,
            // Dove e quando si sale distinguono due treni omonimi; l'origine,
            // quando la ricerca la dice, trova la corsa senza cercarla.
            boardingEpochSec = leg.departure.toEpochSecond(ZoneOffset.UTC),
            origineCorsa = leg.origineCorsa,
            alightingName = leg.to.name,
        ),
    )
}

/**
 * Avviso di servizio. Arriva da Trenord, l'unica fonte che spieghi *perche'*
 * una tratta oggi non abbia treni — lavori, sospensioni, sostitutivi — oppure
 * dall'app stessa, quando Trenitalia non risponde.
 */
@Composable
private fun AlertCard(alert: ServiceAlert) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alert.severe) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "  " + (alert.title ?: "Avviso di servizio"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                alert.message,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MoreButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Text("  $text")
    }
}

private fun formatDuration(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}" else "${m} min"
}

/**
 * L'operatore che pubblica il prezzo parziale di un misto.
 *
 * E' la gamba che vende biglietti — Trenitalia (la Freccia) o Trenord — l'unica
 * che possa avere un prezzo dentro un viaggio assemblato.
 */
private fun operatoreParziale(j: Journey): String =
    j.legs.firstOrNull { it.source == DataSource.TRENITALIA || it.source == DataSource.TRENORD }
        ?.source?.label ?: "un operatore"

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(32.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
