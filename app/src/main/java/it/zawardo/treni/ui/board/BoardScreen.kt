package it.zawardo.treni.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.ui.common.currentLocation
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.rememberLocationRequester
import it.zawardo.treni.ui.common.stateColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

private val ROME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    onOpenTrain: (String, LocalDate) -> Unit,
    vm: BoardViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.station?.name ?: "Tabellone") },
                actions = {
                    if (state.station != null) {
                        IconButton(onClick = vm::changeStation) {
                            Icon(Icons.Filled.Search, contentDescription = "Cambia stazione")
                        }
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
            if (state.picking || state.station == null) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChange,
                    label = { Text("Stazione") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (state.locatingNearest) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = requestLocation) {
                                Icon(Icons.Filled.MyLocation, contentDescription = "Stazione più vicina")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn {
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

                    state.message != null -> Column {
                        Text(
                            state.message!!,
                            Modifier.padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = vm::changeStation) { Text("Scegli un'altra stazione") }
                    }

                    else -> LazyColumn {
                        items(state.entries, key = { it.trainRef.number + it.trainRef.departureDateMillis }) { e ->
                            BoardRow(e, onOpenTrain)
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardRow(entry: BoardEntry, onOpenTrain: (String, LocalDate) -> Unit) {
    val date = Instant.ofEpochMilli(entry.trainRef.departureDateMillis)
        .atZone(ROME_ZONE).toLocalDate()
    val cancelled = entry.state == TrainState.CANCELLED

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenTrain(entry.trainRef.number, date) }
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
                        "  Soppresso",
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
