package it.zawardo.treni.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.ui.TrainRoute
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.ui.common.SectionHeader
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.theme.TreniBrand
import it.zawardo.treni.domain.model.soppressione
import it.zawardo.treni.ui.common.currentLocation
import it.zawardo.treni.ui.common.delayColor
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.rememberLocationRequester
import it.zawardo.treni.ui.common.stateColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

private fun BoardEntry.dateInRome(): LocalDate =
    Instant.ofEpochMilli(trainRef.departureDateMillis).atZone(ROME_ZONE).toLocalDate()

private val ROME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    onOpenTrain: (TrainRoute) -> Unit,
    initialRfi: String? = null,
    initialName: String? = null,
    onBack: (() -> Unit)? = null,
    vm: BoardViewModel = viewModel(),
) {
    // Arrivando da una fermata del dettaglio corsa il tabellone si apre gia'
    // su quella stazione, senza farla ridigitare.
    if (initialRfi != null) {
        LaunchedEffect(initialRfi) {
            vm.preselect(Station(initialRfi, 0L, initialName ?: initialRfi))
        }
    }
    val state by vm.state.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val isFavorite by vm.isFavorite.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Stesso scorrimento automatico dei risultati di ricerca: arrivando in
    // fondo si chiede la finestra oraria successiva.
    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(atEnd, state.entries.size) {
        if (atEnd) vm.loadMore()
    }

    val requestLocation = rememberLocationRequester { granted ->
        if (!granted) {
            vm.onLocationUnavailable("Permesso posizione negato.")
        } else {
            vm.setLocating(true)
            scope.launch {
                val loc = currentLocation(context)
                if (loc == null) {
                    vm.onLocationUnavailable("Posizione non disponibile. Il GPS è attivo?")
                } else {
                    vm.useNearest(loc.latitude, loc.longitude)
                }
            }
        }
    }

    // Svuotare il campo e' il gesto di chi vuole un'altra stazione: da li' le
    // preferite tornano a portata, senza doverne scrivere il nome.
    val scegliendo = state.station == null ||
        (state.suggestionsOpen && state.suggestions.isEmpty())

    Scaffold(
        topBar = {
            TreniTopBar(
                title = state.station?.name ?: initialName ?: "Tabellone stazione",
                onBack = onBack,
                actions = {
                    // Solo dove un tabellone esiste: una fermata senza codice RFI
                    // non e' consultabile, e preferirla non vorrebbe dire nulla.
                    if (state.station?.rfiCode != null) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (isFavorite) "Togli dalle preferite"
                                else "Aggiungi alle preferite",
                                // Ambra quando e' salvata; altrimenti eredita il
                                // bianco della barra.
                                tint = if (isFavorite) TreniBrand.star else LocalContentColor.current,
                            )
                        }
                    }
                    if (state.station != null) {
                        IconButton(onClick = vm::load) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna")
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Il campo resta sempre in cima: si cambia stazione senza tornare indietro.
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                label = { Text("Stazione") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = vm::clearQuery) {
                                Icon(Icons.Filled.Clear, contentDescription = "Cancella")
                            }
                        }
                        if (state.locatingNearest) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = requestLocation) {
                                Icon(Icons.Filled.MyLocation, contentDescription = "Stazione più vicina")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.suggestionsOpen && state.suggestions.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(state.suggestions, key = { it.locationId }) { s ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    keyboard?.hide()
                                    focus.clearFocus(force = true)
                                    vm.select(s)
                                }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(s.name, style = MaterialTheme.typography.bodyLarge)
                            if (!s.trackable) {
                                Text(
                                    "Senza tabellone in tempo reale",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            } else if (scegliendo) {
                /*
                 * Il momento in cui servono e' questo: nessun tabellone aperto,
                 * oppure il campo appena svuotato per cambiare stazione. Sono
                 * l'unica cosa che si puo' fare senza digitare.
                 */
                if (favorites.isNotEmpty()) {
                    SectionHeader(
                        icon = Icons.Filled.Star,
                        title = "Preferite",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(favorites, key = { it.rfiCode.orEmpty() }) { preferita ->
                            FavoriteStationRow(
                                station = preferita,
                                onOpen = {
                                    keyboard?.hide()
                                    focus.clearFocus(force = true)
                                    vm.select(preferita)
                                },
                                onRemove = { preferita.rfiCode?.let(vm::removeFavorite) },
                            )
                        }
                    }
                }
                Text(
                    if (favorites.isEmpty()) {
                        "Scegli una stazione o usa il mirino per quella più vicina." +
                            System.lineSeparator() + System.lineSeparator() +
                            "Aprendo un tabellone, la stellina in alto lo aggiunge alle " +
                            "preferite e lo ritrovi qui."
                    } else {
                        "Oppure cerca una stazione, o usa il mirino per quella più vicina."
                    },
                    Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = state.mode == BoardMode.DEPARTURES,
                        onClick = { vm.setMode(BoardMode.DEPARTURES) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Partenze") }
                    SegmentedButton(
                        selected = state.mode == BoardMode.ARRIVALS,
                        onClick = { vm.setMode(BoardMode.ARRIVALS) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Arrivi") }
                }

                when {
                    state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    state.message != null -> Text(
                        state.message!!,
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn(state = listState) {
                        items(
                            state.entries,
                            key = { it.trainRef.number + "|" + it.trainRef.departureDateMillis + "|" + it.scheduledTime },
                        ) { e ->
                            // Solo per le righe che compaiono davvero: il
                            // controllo della destinazione costa una chiamata.
                            LaunchedEffect(e.trainRef.number, e.trainRef.departureDateMillis) {
                                vm.verifyDirection(e)
                            }
                            BoardRow(e) { entry ->
                                onOpenTrain(
                                    TrainRoute(
                                        number = entry.trainRef.number,
                                        dateEpochDay = entry.dateInRome().toEpochDay(),
                                        boardingRfi = state.station?.rfiCode,
                                        boardingName = state.station?.name,
                                        // Dal tabellone la corsa e' quella e non
                                        // un'altra con lo stesso numero.
                                        originCode = entry.trainRef.originCode,
                                        departureMillis = entry.trainRef.departureDateMillis,
                                    ),
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    state.loadingMore ->
                                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    state.noMore -> Text(
                                        "Fine dell'elenco",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    else -> TextButton(onClick = vm::loadMore) { Text("Mostra altri treni") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(entry: BoardEntry, onOpenTrain: (BoardEntry) -> Unit) {
    // Barrato in entrambi i casi: la corsa e' soppressa, oppure circola ma qui
    // non ferma. Da questa banchina, la differenza non cambia cosa puoi prendere.
    val cancelled = entry.state.soppressione

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenTrain(entry) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monospace sull'orario: incolonna le cifre come un tabellone vero.
        Text(
            entry.scheduledTime ?: "--:--",
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                entry.direction.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
            Row {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!cancelled && entry.delayMinutes != 0) {
                    Text(
                        "  " + delayLabel(entry.delayMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = stateColor(entry.state, entry.delayMinutes),
                    )
                }
                if (cancelled) {
                    Text(
                        if (entry.state == TrainState.CANCELLED) "  Soppresso"
                        else "  Non ferma qui",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Column(
            Modifier.width(52.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val platform = entry.actualPlatform ?: entry.scheduledPlatform
            Text(
                platform ?: "–",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (entry.actualPlatform != null && entry.scheduledPlatform != null &&
                    entry.actualPlatform != entry.scheduledPlatform
                ) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (entry.inStation) {
                Text(
                    "in arrivo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Una stazione preferita: si tocca e si apre il suo tabellone.
 *
 * La stellina piena a destra la toglie, cosi' il gesto per aggiungerla e quello
 * per rimuoverla sono lo stesso simbolo, in due posti diversi.
 */
@Composable
private fun FavoriteStationRow(
    station: Station,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                station.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Togli dalle preferite",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
