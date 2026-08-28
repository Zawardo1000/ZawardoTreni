package it.zawardo.treni.ui.results

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Leg
import it.zawardo.treni.ui.TrainRoute
import it.zawardo.treni.domain.model.ServiceAlert
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)
private val FULL_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    from: Station,
    to: Station,
    departure: LocalDateTime,
    directOnly: Boolean = false,
    onBack: () -> Unit,
    onOpenTrain: (TrainRoute) -> Unit,
) {
    val vm: ResultsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ResultsViewModel(from, to, departure, directOnly) }
        },
    )
    val state by vm.state.collectAsState()

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
        topBar = {
            TreniTopBar(
                title = "${from.name} → ${to.name}",
                subtitle = departure.format(DATE) + ", dalle " + departure.format(TIME),
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
                    Text(
                        "Nessun collegamento trovato",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                    if (state.alerts.isEmpty() && !state.directOnly) {
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
                        JourneyCard(row, requestedDate = departure.toLocalDate()) { number, leg ->
                            onOpenTrain(
                                TrainRoute(
                                    number = number,
                                    dateEpochDay = row.journey.departure.toLocalDate().toEpochDay(),
                                    boardingRfi = leg.from.rfiCode,
                                    boardingName = leg.from.name,
                                    alightingRfi = leg.to.rfiCode,
                                    // Il BFF non da' la corsa, solo il numero:
                                    // dove e quando si sale e' cio' che
                                    // distingue due treni omonimi.
                                    boardingEpochSec = leg.departure.toEpochSecond(ZoneOffset.UTC),
                                ),
                            )
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

                    if (state.loadingMisti) {
                        item {
                            // I misti arrivano dopo i diretti: la gamba Italo costa
                            // un paio di secondi. Un rigo lo dice, senza bloccare.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    "  Cerco soluzioni con più operatori…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
    onOpenTrain: (String, Leg) -> Unit,
) {
    val j: Journey = row.journey
    val otherDay = j.departure.toLocalDate() != requestedDate
    // Barrato come su un tabellone: la corsa c'e' in orario, ma non si fa.
    val cancelled = row.state == TrainState.CANCELLED

    /*
     * Sfondo tenue quando non c'e' tempo reale.
     *
     * Due casi, entrambi da distinguere a colpo d'occhio dai treni "vivi": le
     * corse di un altro giorno, di cui il ritardo si sapra' ma non adesso, e i
     * viaggi che un tempo reale non lo avranno mai — un misto con gamba EAV, o
     * un servizio sostitutivo. In tutti l'orario e' previsto, non misurato, e la
     * card lo dice col colore prima ancora delle parole.
     */
    val soloPrevisto = !row.realtimeNow
    val fondo = if (soloPrevisto) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    } else {
        CardDefaults.cardColors()
    }

    Card(
        colors = fondo,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                // Solo i treni hanno un dettaglio: aprirlo per un bus porterebbe
                // a una schermata che dice "non trovato".
                j.legs.firstOrNull { it.isTrain }?.let { leg ->
                    leg.trainNumber?.let { onOpenTrain(it, leg) }
                }
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (j.assembled) {
                /*
                 * Il viaggio misto si annuncia per quello che e'.
                 *
                 * Cambia operatore per strada, l'abbiamo costruito noi, e la
                 * gamba Italo puo' non avere prezzo: chi lo sceglie deve saperlo
                 * prima, non scoprirlo alla biglietteria. Il badge lo distingue
                 * dai viaggi che una sorgente da' gia' pronti.
                 */
                Text(
                    "Più operatori · beta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (otherDay) {
                /*
                 * Il BFF puo' restituire corse di un altro giorno quando per quello
                 * richiesto non c'e' nulla. Senza questa riga si legge "01:01" e si
                 * capisce stanotte, mentre e' la notte dopo.
                 */
                Text(
                    j.departure.format(FULL_DATE).replaceFirstChar { c -> c.uppercase() },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    j.departure.format(TIME),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                )
                Text("  →  ", style = MaterialTheme.typography.titleMedium)
                Text(
                    j.arrival.format(TIME),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                )
                Box(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatDuration(j.duration.toMinutes()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (j.isDirect) "diretto" else "${j.changes} cambi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    /*
                     * Il prezzo compare solo quando c'e'.
                     *
                     * Lo pubblicano le due sorgenti che vendono — Trenitalia e
                     * Trenord — e nemmeno loro sempre: sulla stessa tratta una
                     * ricerca su cinque torna senza. Riempire il vuoto con un
                     * trattino o con "n.d." darebbe l'idea di un dato mancante
                     * per colpa dell'app; non scrivere niente e' piu' onesto e
                     * piu' pulito.
                     */
                    j.price?.let { p ->
                        Text(
                            if (p.saleable) p.formatted else "${p.formatted} · esaurito",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (p.saleable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        /*
                         * Su un viaggio con cambio va detto che il prezzo e' del
                         * viaggio intero, cambi compresi, non di una sola tratta.
                         * Il BFF lo da' gia' come totale, ma da utente si e' in
                         * dubbio: la riga toglie l'ambiguita'.
                         */
                        if (!j.isDirect) {
                            Text(
                                "intero viaggio",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            /*
             * Le tratte vanno a capo invece di stringersi.
             *
             * Con tre cambi i chip diventano quattro e in una `Row` non ci
             * stanno: Compose li comprimeva in orizzontale finche' l'ultimo
             * restava alto e largo un carattere, illeggibile. Un viaggio con
             * piu' cambi e' proprio quello che ha piu' bisogno di essere letto,
             * quindi si va a capo.
             */
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                j.legs.forEach { leg ->
                    AssistChip(
                        // Un bus non ha dettaglio da aprire: il chip resta inerte.
                        enabled = leg.isTrain,
                        onClick = { leg.trainNumber?.let { onOpenTrain(it, leg) } },
                        label = { Text(leg.label, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = when {
                            leg.isTrain -> null
                            leg.isWalk -> {
                                {
                                    Icon(
                                        Icons.AutoMirrored.Filled.DirectionsWalk,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            else -> {
                                {
                                    Icon(
                                        Icons.Filled.DirectionsBus,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }

            if (j.assembled) {
                // Il prezzo di un misto e' parziale: la gamba Italo non lo
                // pubblica. Dirlo qui evita che la sua assenza sembri un difetto.
                Text(
                    "Cambio fra operatori diversi. Il prezzo Italo non è " +
                        "disponibile; verifica orari e biglietti sui siti dei gestori.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when {
                // Va detto, invece di lasciare la riga vuota come se
                // l'informazione stesse ancora arrivando.
                !row.realtimePossible -> Text(
                    "Servizio sostitutivo: nessun dato in tempo reale",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                row.loadingStatus -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "  stato in aggiornamento",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                row.state != null -> {
                    // Una riga sola: o l'etichetta dello stato anomalo, o il ritardo.
                    // Prima comparivano entrambe e il ritardo veniva detto due volte.
                    val anomaly = stateLabel(row.state)
                    Text(
                        anomaly ?: delayLabel(row.delayMinutes ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = stateColor(row.state, row.delayMinutes),
                    )
                }

                /*
                 * Corsa di un altro giorno: lo stato non c'e' e non arrivera'.
                 * Succede anche cercando per oggi, quando la tratta e' ferma e
                 * le sole soluzioni sono di domani. Il vuoto, li', si legge
                 * come "in orario".
                 */
                !row.isRealtimeDay -> Text(
                    "Orario previsto: il tempo reale esiste solo per la giornata in corso",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Avviso di servizio. Arriva solo da Trenord ed e' l'unica fonte che spieghi
 * *perche'* una tratta oggi non abbia treni: lavori, sospensioni, sostitutivi.
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

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(32.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
