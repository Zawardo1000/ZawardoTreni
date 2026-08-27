package it.zawardo.treni.ui.train

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.zawardo.treni.data.local.FavoriteTrainEntity
import it.zawardo.treni.domain.model.TrainRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM")
private val ROME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainNumberScreen(
    onOpenTrain: (String, LocalDate, String?, String?) -> Unit,
    vm: TrainNumberViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val favorites by vm.favorite.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cerca treno") }) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQueryChange,
                label = { Text("Numero treno") },
                placeholder = { Text("es. 9505, 888A, RE 2874") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(
                    // Non Number: con la tastiera numerica le lettere di "888A"
                    // non erano nemmeno digitabili.
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        focus.clearFocus(force = true)
                        vm.resolve()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Puoi incollare l'etichetta intera, sigla compresa. " +
                    "Solo i treni in circolazione oggi: ViaggiaTreno non conosce le altre giornate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.message != null -> Text(
                    state.message!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.results.isNotEmpty() ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.results, key = { it.originCode + it.departureDateMillis }) { ref ->
                            RunCard(ref, onOpenTrain)
                        }
                    }
            }

            // Spariscono appena c'e' un risultato: a quel punto hanno esaurito
            // il loro scopo e ruberebbero spazio. `fill = false` li tiene alti
            // quanto serve, cosi' non spingono via il resto.
            if (favorites.isNotEmpty() && state.results.isEmpty() && !state.loading) {
                Text("Preferiti", style = MaterialTheme.typography.titleSmall)
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(favorites, key = { it.number }) { fav ->
                        FavoriteCard(
                            favorite = fav,
                            onOpen = {
                                keyboard?.hide()
                                focus.clearFocus(force = true)
                                vm.searchFavorite(fav.number)
                            },
                            onRemove = { vm.removeFavorite(fav.number) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RunCard(ref: TrainRef, onOpenTrain: (String, LocalDate, String?, String?) -> Unit) {
    val date = Instant.ofEpochMilli(ref.departureDateMillis).atZone(ROME_ZONE).toLocalDate()
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenTrain(ref.number, date, null, null) },
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Treno ${ref.number}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "da ${ref.originName ?: ref.originCode} · ${date.format(DATE)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Un preferito porta con se' la descrizione del giorno in cui e' stato salvato:
 * "REG 2618 · Milano Centrale -> Lecco" dice all'utente qual e' il suo treno
 * molto meglio di un numero nudo. Non e' un dato in tempo reale e non pretende
 * di esserlo: quello arriva dopo, quando la corsa viene cercata.
 */
@Composable
private fun FavoriteCard(
    favorite: FavoriteTrainEntity,
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
            Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    favorite.label ?: "Treno ${favorite.number}",
                    style = MaterialTheme.typography.titleMedium,
                )
                val tratta = listOfNotNull(favorite.originName, favorite.destinationName)
                if (tratta.isNotEmpty()) {
                    Text(
                        tratta.joinToString(" → "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Togli dai preferiti",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}
