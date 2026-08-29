package it.zawardo.treni.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LeadingIconTab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import it.zawardo.treni.data.local.SavedSearchEntity
import it.zawardo.treni.data.local.SearchHistoryEntity
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.ui.common.ReteBadge
import it.zawardo.treni.ui.common.StationPicker
import it.zawardo.treni.ui.common.TrattaConBadge
import it.zawardo.treni.ui.common.siglaBadge
import it.zawardo.treni.ui.common.TreniTopBar
import androidx.compose.runtime.rememberCoroutineScope
import it.zawardo.treni.ui.common.DatePickerModal
import it.zawardo.treni.ui.common.currentLocation
import it.zawardo.treni.ui.common.rememberLocationRequester
import kotlinx.coroutines.launch
import it.zawardo.treni.ui.common.TimePickerModal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Dove sta il pulsante inverti dentro la colonna dei due campi.
 *
 * Meta' dell'altezza dei due campi (56 dp l'uno, 8 dp di stacco) meno mezzo
 * pulsante: 60 - 24. Serve un numero perche' con la lista dei suggerimenti
 * aperta il contenitore diventa alto quanto la lista, e "al centro" non
 * significherebbe piu' "fra i due campi".
 */
private val SWAP_TOP = 36.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onSearch: (Station, Station, LocalDateTime, Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val history by vm.history.collectAsState()
    val saved by vm.saved.collectAsState()
    val sources by vm.enabledSources.collectAsState()
    var showSources by remember { mutableStateOf(false) }

    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val requestLocation = rememberLocationRequester { granted ->
        if (!granted) {
            vm.reportLocationProblem("Permesso posizione negato.")
        } else {
            vm.setLocating(true)
            scope.launch {
                val loc = currentLocation(context)
                if (loc == null) {
                    vm.reportLocationProblem("Posizione non disponibile. Il GPS è attivo?")
                } else {
                    vm.proposeNearest(loc.latitude, loc.longitude)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TreniTopBar(
                title = "ZawardoTreni",
                actions = {
                    IconButton(onClick = { showSources = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Fonti dati")
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Info")
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchCard(
                state = state,
                onQueryChange = vm::onQueryChange,
                onFieldFocused = vm::onFieldFocused,
                onClearField = vm::clearField,
                onPickStation = { field, station ->
                    vm.select(field, station)
                    // Scelta la stazione, il campo ha finito: via cursore e tastiera,
                    // altrimenti la tastiera copre il resto della schermata.
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                },
                onSwap = {
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                    vm.swap()
                },
                onPickDate = {
                    focus.clearFocus(force = true)
                    showDate = true
                },
                onPickTime = {
                    focus.clearFocus(force = true)
                    showTime = true
                },
                onNow = vm::setNow,
                onToggleRemember = vm::setRememberLast,
                onToggleDirectOnly = vm::setDirectOnly,
                onToggleViaggiMisti = vm::setViaggiMisti,
                onSave = vm::saveCurrent,
                onUseLocation = {
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                    requestLocation()
                },
                onSearch = {
                    val f = state.from
                    val t = state.to
                    if (f != null && t != null) {
                        keyboard?.hide()
                        focus.clearFocus(force = true)
                        vm.recordSearch()
                        onSearch(f, t, state.dateTime, state.directOnly)
                    }
                },
            )

            // Mentre si sceglie una stazione la scheda occupa tutto: cronologia
            // e salvate sarebbero una seconda lista in competizione con la prima.
            if (!state.choosing) {
                HistoryAndSaved(
                    tab = tab,
                    onTabChange = { tab = it },
                    history = history,
                    saved = saved,
                    onPick = { f, t, minutes -> vm.applyPair(f, t, minutes) },
                    onDeleteHistory = vm::deleteHistory,
                    onClearHistory = vm::clearHistory,
                    onDeleteSaved = vm::deleteSaved,
                )
            }
        }
    }

    if (showDate) {
        DatePickerModal(
            initial = state.dateTime.toLocalDate(),
            onDismiss = { showDate = false },
            onConfirm = vm::setDate,
        )
    }
    if (showTime) {
        TimePickerModal(
            initial = state.dateTime.toLocalTime(),
            onDismiss = { showTime = false },
            onConfirm = vm::setTime,
        )
    }
    if (showSources) {
        SourcesDialog(
            enabled = sources,
            onToggle = vm::setSourceEnabled,
            onDismiss = { showSources = false },
        )
    }
}

/**
 * Le reti da cui pescare, da accendere e spegnere.
 *
 * Sono diventate tante, e ognuna e' traffico a ogni ricerca: spegnere quelle
 * che non servono e' l'unico modo per non pagarle. Una rete annunciata ma non
 * ancora collegata resta in elenco, spenta e non toccabile, perche' la si
 * aspetta ma non c'e' niente da accendere.
 */
@Composable
private fun SourcesDialog(
    enabled: Set<DataSource>,
    onToggle: (DataSource, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fatto") } },
        title = { Text("Fonti dati") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "La rete nazionale c'e' sempre. Qui aggiungi le reti locali " +
                        "che ti servono: ognuna in piu' e' qualche richiesta in " +
                        "piu' a ogni ricerca.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.size(8.dp))
                /*
                 * Trenitalia non compare, ed e' voluto.
                 *
                 * Le Frecce e ViaggiaTreno coprono quasi tutti i treni italiani:
                 * spegnerle non renderebbe la ricerca piu' veloce, la
                 * renderebbe vuota. Un interruttore il cui unico esito e'
                 * rompere l'app non e' una scelta da offrire.
                 */
                DataSource.entries.filter { it.opzionale }.forEach { fonte ->
                    val acceso = fonte in enabled
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = fonte.available) {
                                onToggle(fonte, !acceso)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                fonte.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (fonte.available) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                if (fonte.available) fonte.detail else fonte.detail + " · prossimamente",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = acceso,
                            onCheckedChange = { onToggle(fonte, it) },
                            enabled = fonte.available,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchCard(
    state: SearchUiState,
    onQueryChange: (SearchField, String) -> Unit,
    onFieldFocused: (SearchField) -> Unit,
    onClearField: (SearchField) -> Unit,
    onPickStation: (SearchField, Station) -> Unit,
    onSwap: () -> Unit,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onNow: () -> Unit,
    onToggleRemember: (Boolean) -> Unit,
    onToggleDirectOnly: (Boolean) -> Unit,
    onToggleViaggiMisti: (Boolean) -> Unit,
    onSave: () -> Unit,
    onUseLocation: () -> Unit,
    onSearch: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // I due campi e il pulsante inverti condividono la stessa riga: il
            // pulsante sta in mezzo, fra partenza e arrivo.
            Box(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StationField(
                        label = "Partenza",
                        value = state.fromQuery,
                        onValueChange = { onQueryChange(SearchField.FROM, it) },
                        onFocused = { onFieldFocused(SearchField.FROM) },
                        onClear = { onClearField(SearchField.FROM) },
                        // Il mirino sta qui e non altrove: "vicino a me" ha senso
                        // sulla partenza, non sulla destinazione.
                        onUseLocation = onUseLocation,
                        locating = state.locating,
                        selectedRfi = state.from?.rfiCode,
                        selectedIdNazionale = state.from?.idNazionale,
                    )
                    /*
                     * La lista sta attaccata al campo che si sta compilando, non
                     * in fondo alla schermata: li' finiva sotto la tastiera, e si
                     * sceglieva alla cieca fra le due righe che restavano fuori.
                     */
                    if (state.choosing && state.activeField == SearchField.FROM) {
                        StationPicker(
                            suggestions = state.suggestions,
                            nearby = state.nearby,
                            loading = state.loadingSuggestions,
                            onPick = { onPickStation(SearchField.FROM, it) },
                        )
                    }
                    StationField(
                        label = "Arrivo",
                        value = state.toQuery,
                        onValueChange = { onQueryChange(SearchField.TO, it) },
                        onFocused = { onFieldFocused(SearchField.TO) },
                        onClear = { onClearField(SearchField.TO) },
                        selectedRfi = state.to?.rfiCode,
                        selectedIdNazionale = state.to?.idNazionale,
                    )
                    if (state.choosing && state.activeField == SearchField.TO) {
                        StationPicker(
                            suggestions = state.suggestions,
                            // Il mirino sta solo sulla partenza: sull'arrivo non
                            // c'e' mai niente di vicino da proporre.
                            nearby = emptyList(),
                            loading = state.loadingSuggestions,
                            onPick = { onPickStation(SearchField.TO, it) },
                        )
                    }
                }
                // Ancorato in alto e non al centro: vedi [SWAP_TOP].
                FilledIconButton(
                    onClick = onSwap,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = SWAP_TOP)
                        .size(48.dp),
                    shape = CircleShape,
                ) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Inverti partenza e arrivo")
                }
            }

            // Con la lista aperta il resto della scheda sparisce: e' lo spazio
            // che serve ai suggerimenti per stare sopra la tastiera.
            if (!state.choosing) {
                SearchOptions(
                    state = state,
                    onPickDate = onPickDate,
                    onPickTime = onPickTime,
                    onNow = onNow,
                    onToggleRemember = onToggleRemember,
                    onToggleDirectOnly = onToggleDirectOnly,
                    onToggleViaggiMisti = onToggleViaggiMisti,
                    onSave = onSave,
                    onSearch = onSearch,
                )
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * La parte bassa della scheda: quando partire, e con quali preferenze.
 *
 * Sta a parte perche' mentre si sceglie una stazione non viene mostrata: un
 * `if` attorno a novanta righe dentro [SearchCard] le avrebbe solo spinte in
 * un rientro in piu'.
 */
@Composable
private fun SearchOptions(
    state: SearchUiState,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onNow: () -> Unit,
    onToggleRemember: (Boolean) -> Unit,
    onToggleDirectOnly: (Boolean) -> Unit,
    onToggleViaggiMisti: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSearch: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onPickDate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
                Text(
                    "  " + state.dateTime.format(DATE_FMT),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onPickTime) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp))
                Text("  " + state.dateTime.format(TIME_FMT))
            }
            TextButton(onClick = onNow) { Text("Adesso") }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onSearch,
                enabled = state.canSearch,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Text("  Cerca")
            }
            // Bottone con testo, non solo icona: da icona sola non si capiva
            // che la ricerca si potesse salvare.
            OutlinedButton(
                onClick = onSave,
                enabled = state.canSearch && !state.alreadySaved,
            ) {
                Icon(
                    if (state.alreadySaved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(if (state.alreadySaved) "  Salvata" else "  Salva")
            }
        }

        HorizontalDivider()

        // Preferenze che restano, non azioni della singola ricerca. Tenute
        // strette di proposito: qui sotto c'e' la cronologia, e ogni riga di
        // troppo le porta via una voce dalla vista. La spiegazione lunga della
        // beta vive nell'Info, non di fianco all'interruttore.
        ToggleRow("Solo diretti", state.directOnly, onToggleDirectOnly)
        ToggleRow(
            "Più operatori · beta",
            state.viaggiMisti,
            onToggleViaggiMisti,
            subtitle = "Combina reti diverse in un viaggio con cambi. Più lenta.",
        )
        ToggleRow("Ricorda ultima ricerca", state.rememberLast, onToggleRemember)
    }
}

/** Interruttore di preferenza: titolo, un eventuale rigo di aiuto, e lo switch. */
@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onFocused: () -> Unit,
    onClear: () -> Unit,
    onUseLocation: (() -> Unit)? = null,
    locating: Boolean = false,
    // Il codice della stazione scelta, per il badge di rete nel campo. Null
    // mentre si scrive (nessuna scelta ancora), valorizzato dopo la selezione.
    selectedRfi: String? = null,
    // L'indirizzo nazionale della scelta: se c'e', niente badge (non e' esclusiva).
    selectedIdNazionale: Long? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = {
            onFocused()
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        // Scelta una stazione esclusiva fuori-RFI, la sua sigla resta nel campo,
        // come nei suggerimenti. Se ha un gemello nazionale, niente badge.
        leadingIcon = siglaBadge(selectedRfi, selectedIdNazionale)?.let { sigla ->
            @Composable { ReteBadge(sigla) }
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Clear, contentDescription = "Cancella $label")
                    }
                }
                if (onUseLocation != null) {
                    if (locating) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onUseLocation) {
                            Icon(Icons.Filled.MyLocation, contentDescription = "Stazione più vicina")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun HistoryAndSaved(
    tab: Int,
    onTabChange: (Int) -> Unit,
    history: List<SearchHistoryEntity>,
    saved: List<SavedSearchEntity>,
    onPick: (Station, Station, Int?) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteSaved: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            // Icona di fianco al testo, non sopra: la scheda resta bassa e le due
            // voci si distinguono anche con la coda dell'occhio.
            LeadingIconTab(
                selected = tab == 0,
                onClick = { onTabChange(0) },
                text = { Text("Cronologia") },
                icon = { Icon(Icons.Outlined.History, contentDescription = null) },
            )
            LeadingIconTab(
                selected = tab == 1,
                onClick = { onTabChange(1) },
                text = { Text("Salvate") },
                icon = { Icon(Icons.Outlined.Save, contentDescription = null) },
            )
        }

        if (tab == 0) {
            if (history.isEmpty()) {
                EmptyHint("Le ultime 10 ricerche compaiono qui")
            } else {
                LazyColumn {
                    items(history, key = { it.id }) { h ->
                        PairRow(
                            // La cronologia riparte sempre da adesso.
                            onClick = { onPick(h.from.toStation(), h.to.toStation(), null) },
                            onDelete = { onDeleteHistory(h.id) },
                        ) {
                            TrattaConBadge(
                                h.from.name, h.from.rfiCode, h.from.idNazionale,
                                h.to.name, h.to.rfiCode, h.to.idNazionale,
                            )
                        }
                    }
                    item {
                        TextButton(onClick = onClearHistory, modifier = Modifier.padding(8.dp)) {
                            Text("Svuota cronologia")
                        }
                    }
                }
            }
        } else {
            if (saved.isEmpty()) {
                EmptyHint("Imposta una tratta e premi Salva per ritrovarla qui")
            } else {
                LazyColumn {
                    items(saved, key = { it.id }) { s ->
                        PairRow(
                            // Le salvate conservano solo le stazioni: l'orario
                            // riparte sempre da adesso.
                            onClick = { onPick(s.from.toStation(), s.to.toStation(), null) },
                            onDelete = { onDeleteSaved(s.id) },
                        ) {
                            // Etichetta di default = la tratta: la mostro coi badge.
                            // Se l'utente l'ha rinominata, tengo il suo nome e metto
                            // la tratta coi badge sotto, solo se c'e' una rete da segnare.
                            val tratta = "${s.from.name} → ${s.to.name}"
                            if (s.label == tratta) {
                                TrattaConBadge(
                                    s.from.name, s.from.rfiCode, s.from.idNazionale,
                                    s.to.name, s.to.rfiCode, s.to.idNazionale,
                                )
                            } else {
                                Text(
                                    s.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (siglaBadge(s.from.rfiCode, s.from.idNazionale) != null ||
                                    siglaBadge(s.to.rfiCode, s.to.idNazionale) != null
                                ) {
                                    TrattaConBadge(
                                        s.from.name, s.from.rfiCode, s.from.idNazionale,
                                        s.to.name, s.to.rfiCode, s.to.idNazionale,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
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
private fun PairRow(
    onClick: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            content = content,
        )
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = "Rimuovi",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
