package it.zawardo.treni.ui.train

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import it.zawardo.treni.ui.common.SectionHeader
import it.zawardo.treni.ui.common.TreniTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import it.zawardo.treni.data.repository.TrainSuggestion
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainRun
import it.zawardo.treni.ui.TrainRoute
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM")
private val ROME_ZONE: ZoneId = ZoneId.of("Europe/Rome")

private fun TrainRef.dateInRome(): LocalDate =
    Instant.ofEpochMilli(departureDateMillis).atZone(ROME_ZONE).toLocalDate()

/**
 * Qui la corsa e' gia' identificata: origine e data di partenza vengono
 * dall'elenco, quindi il dettaglio non deve indovinare quale treno sia fra
 * quelli che condividono il numero.
 */
private fun TrainRef.toRoute() = TrainRoute(
    number = number,
    dateEpochDay = dateInRome().toEpochDay(),
    originCode = originCode,
    departureMillis = departureDateMillis,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainNumberScreen(
    onOpenTrain: (TrainRoute) -> Unit,
    vm: TrainNumberViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val favorites by vm.favorite.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Un solo risultato: si entra dritti nella corsa. Si consuma subito, cosi'
    // tornando indietro non si viene rispediti dentro.
    LaunchedEffect(state.openDirectly) {
        val ref = state.openDirectly ?: return@LaunchedEffect
        vm.consumeOpen()
        keyboard?.hide()
        focus.clearFocus(force = true)
        onOpenTrain(ref.toRoute())
    }

    Scaffold(
        topBar = { TreniTopBar(title = "Cerca treno") },
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
                placeholder = { Text("es. 2874, RE 2874, REG20") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = vm::clearQuery) {
                            Icon(Icons.Filled.Clear, contentDescription = "Svuota il campo")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    // Non Number: il numero e' intero, ma la sigla davanti va
                    // scritta, sia perche' e' cosi' che l'etichetta si legge sia
                    // perche' distingue due treni con lo stesso numero.
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

            if (state.suggestionsOpen && state.suggestions.isNotEmpty()) {
                SuggestionCard(
                    suggestions = state.suggestions,
                    onPick = {
                        keyboard?.hide()
                        focus.clearFocus(force = true)
                        vm.pick(it)
                    },
                )
            }

            // In cima, non in fondo: aprendo la scheda questa e' la sola cosa
            // che si puo' fare senza digitare, quindi deve essere la prima che
            // si vede. `fill = false` la tiene alta quanto serve.
            if (favorites.isNotEmpty() && state.results.isEmpty() && !state.loading) {
                SectionHeader(
                    icon = Icons.Filled.Star,
                    title = "Preferiti",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
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
                                vm.pick(fav.number)
                            },
                            onRemove = { vm.removeFavorite(fav.number) },
                        )
                    }
                }
            }

            Text(
                "Puoi incollare l'etichetta intera, sigla compresa. " +
                    "Solo i treni in circolazione oggi: ViaggiaTreno non conosce le altre giornate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (favorites.isEmpty()) {
                Text(
                    "Aprendo una corsa, la stellina in alto la aggiunge ai preferiti " +
                        "e la ritrovi qui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                        items(
                            state.results,
                            key = { it.ref.originCode + it.ref.departureDateMillis },
                        ) { corsa ->
                            RunCard(corsa, onOpenTrain)
                        }
                    }
            }

        }
    }
}

@Composable
private fun RunCard(corsa: TrainRun, onOpenTrain: (TrainRoute) -> Unit) {
    val date = corsa.ref.dateInRome()
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenTrain(corsa.ref.toRoute()) },
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // La sigla e' l'unica cosa che distingue due corse omonime:
                // "EC 20" e "REG 20" sono due treni, "Treno 20" e' un enigma.
                Text(corsa.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(corsa.origin, corsa.destination).joinToString(" → ")
                        .ifBlank { "percorso non disponibile" } + "  ·  " + date.format(DATE),
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
 * "REG 2618 Â· Milano Centrale -> Lecco" dice all'utente qual e' il suo treno
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
                        tratta.joinToString(" â†’ "),
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

/**
 * I suggerimenti mentre si digita.
 *
 * Non vengono da ViaggiaTreno, che cerca solo per numero esatto, ma da quello
 * che l'app ha gia' visto: preferiti e corse aperte di recente. E' un insieme
 * piccolo e per questo utile, perche' sono i treni di chi sta cercando.
 */
@Composable
private fun SuggestionCard(suggestions: List<TrainSuggestion>, onPick: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 260.dp)) {
            suggestions.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(s.number) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (s.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (s.favorite) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            s.label ?: "Treno ${s.number}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        s.description?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
