package it.zawardo.treni.ui.results

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.domain.model.Journey
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    from: Station,
    to: Station,
    departure: LocalDateTime,
    onBack: () -> Unit,
    onOpenTrain: (String, LocalDate) -> Unit,
) {
    val vm: ResultsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ResultsViewModel(from, to, departure) }
        },
    )
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "${from.name} → ${to.name}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            departure.format(DATE) + ", dalle " + departure.format(TIME),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
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

                state.journeys.isEmpty() ->
                    Message("Nessun collegamento trovato", Modifier.align(Alignment.Center))

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                    items(state.journeys, key = { it.key }) { row ->
                        JourneyCard(row) { number ->
                            onOpenTrain(number, row.journey.departure.toLocalDate())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneyCard(row: JourneyRow, onOpenTrain: (String) -> Unit) {
    val j: Journey = row.journey
    Card(
        Modifier
            .fillMaxWidth()
            .clickable {
                j.legs.firstOrNull()?.trainNumber?.let(onOpenTrain)
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    j.departure.format(TIME),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("  →  ", style = MaterialTheme.typography.titleMedium)
                Text(
                    j.arrival.format(TIME),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatDuration(j.duration.toMinutes()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (j.isDirect) "diretto" else "${j.changes} cambi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                j.legs.forEach { leg ->
                    AssistChip(
                        onClick = { leg.trainNumber?.let(onOpenTrain) },
                        label = { Text(leg.label, style = MaterialTheme.typography.labelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }

            when {
                row.loadingStatus -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "  stato in aggiornamento",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                row.state != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stateLabel(row.state, row.delayMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = stateColor(row.state, row.delayMinutes),
                    )
                    if (row.state != TrainState.CANCELLED && row.delayMinutes != null && row.delayMinutes != 0) {
                        Text(
                            "  " + delayLabel(row.delayMinutes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
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
