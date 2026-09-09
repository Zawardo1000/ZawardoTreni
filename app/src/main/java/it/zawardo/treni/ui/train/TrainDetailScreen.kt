package it.zawardo.treni.ui.train

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.service.TrainFollowService
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.theme.TreniBrand
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GIORNO = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainDetailScreen(
    trainNumber: String,
    date: LocalDate,
    boardingRfi: String? = null,
    boardingName: String? = null,
    /** Dove si scende: su una soluzione con cambio e' la stazione del cambio. */
    alightingRfi: String? = null,
    /** Corsa gia' identificata: presente quando si arriva da un elenco di corse. */
    originCode: String? = null,
    departureMillis: Long? = null,
    /** Quando si sale: distingue due corse dello stesso numero in giorni diversi. */
    boardingAt: LocalDateTime? = null,
    onBack: () -> Unit,
    onOpenStation: (String, String) -> Unit = { _, _ -> },
) {
    val vm: TrainDetailViewModel = viewModel(
        factory = viewModelFactory { initializer {
            TrainDetailViewModel(
                trainNumber, date, boardingRfi, boardingAt, boardingName, alightingRfi,
                originCode, departureMillis,
            )
        } },
    )
    val state by vm.state.collectAsState()
    val isFavorite by vm.isFavorite.collectAsState()

    val context = LocalContext.current
    val followedNumbers by TrainFollowService.followed.collectAsState()
    val isFollowing = trainNumber in followedNumbers

    // Tornando in primo piano, i dati in memoria possono avere ore: arrivando
    // dalla notifica ci si aspetta il percorso aggiornato, non l'ultimo noto.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    // Su Android 13+ senza questo permesso il servizio partirebbe muto:
    // notifica permanente invisibile e nessun avviso. Meglio chiederlo prima.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) TrainFollowService.start(context, trainNumber, date, boardingRfi, boardingName)
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TrainFollowService.start(context, trainNumber, date, boardingRfi, boardingName)
        }
    }

    Scaffold(
        topBar = {
            TreniTopBar(
                title = state.status?.label ?: "Treno $trainNumber",
                /*
                 * Un treno a lunga percorrenza parte la sera e arriva il giorno
                 * dopo: a meta' giornata ne circolano due con lo stesso numero, e
                 * senza la data non si sa quale si stia guardando.
                 */
                subtitle = when {
                    // Di domani non si dice "partita": deve ancora partire.
                    date.isAfter(LocalDate.now()) -> "in programma " + date.format(GIORNO)
                    date != LocalDate.now() -> "partita il " + date.format(GIORNO)
                    else -> null
                },
                onBack = onBack,
                actions = {
                    // Il preferito e' il numero, non la corsa di oggi: si puo'
                    // aggiungere anche a treno arrivato o in un'altra data.
                    IconButton(onClick = vm::toggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Togli dai preferiti"
                            else "Aggiungi ai preferiti",
                            // Ambra quando e' acceso, bianco della barra quando no.
                            tint = if (isFavorite) TreniBrand.star else LocalContentColor.current,
                        )
                    }
                    // "Segui" ha senso solo su un treno che sta ancora circolando
                    // oggi, e di cui qualcuno pubblichi il ritardo: su una corsa
                    // presa dall'orario non ci sarebbe mai niente da notificare.
                    val followable = state.status != null &&
                        state.status!!.realtime &&
                        state.status!!.state != TrainState.ARRIVED &&
                        date == LocalDate.now()
                    if (followable) {
                        IconButton(
                            onClick = {
                                if (isFollowing) {
                                    TrainFollowService.stop(context)
                                } else {
                                    requestNotifications()
                                }
                            },
                        ) {
                            Icon(
                                if (isFollowing) Icons.Filled.NotificationsActive
                                else Icons.Filled.NotificationsNone,
                                contentDescription = if (isFollowing) "Smetti di seguire" else "Segui questo treno",
                                tint = if (isFollowing) TreniBrand.star else LocalContentColor.current,
                            )
                        }
                    }
                    if (state.refreshing) {
                        CircularProgressIndicator(
                            Modifier.size(20.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = TreniBrand.onTopBar,
                        )
                    } else {
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna")
                        }
                    }
                },
            )
        },
    ) { inner ->
        /*
         * Trascinare verso il basso aggiorna. E' il gesto che la gente prova per
         * istinto su una lista che invecchia da sola, e il bottone in alto resta
         * comunque per chi lo cerca.
         */
        PullToRefreshBox(
            isRefreshing = state.pulling,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            val status = state.status
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.realtimeUnavailable -> Message(
                    if (state.futureDate) {
                        "Di questa corsa non c'è l'orario per il giorno scelto.\n\n" +
                            "L'orario previsto si ricava dalla stessa corsa in circolazione " +
                            "oggi, ma oggi questo treno non circola. Torna il giorno della " +
                            "partenza, quando il servizio è attivo."
                    } else {
                        "Nessun dato in tempo reale per questo treno.\n\n" +
                            "ViaggiaTreno espone i ritardi solo per la giornata in corso: " +
                            "per le altre date esiste soltanto l'orario previsto."
                    },
                    Modifier.align(Alignment.Center),
                )

                status == null -> Message(
                    state.error ?: "Treno non trovato",
                    Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                ) {
                    // Dove parti tu: la fermata di salita, o il capolinea di
                    // partenza quando nessuno ci ha detto dove sali.
                    val partenzaTua = status.stops
                        .indexOfFirst { it.stationCode?.equals(boardingRfi, true) == true }
                        .takeIf { it >= 0 } ?: 0

                    item { Header(status) }
                    item { Box(Modifier.height(16.dp)) }
                    itemsIndexed(status.stops, key = { _, s -> "${s.index}-${s.stationName}" }) { i, stop ->
                        FermataRiga(
                            stop = stop,
                            isFirst = i == 0,
                            isLast = i == status.stops.lastIndex,
                            // Corsa soppressa per intero: barrata tutta, non solo
                            // le fermate che ViaggiaTreno elenca come soppresse.
                            trainCancelled = status.state == TrainState.CANCELLED,
                            onOpenStation = onOpenStation,
                            /*
                             * I due capi del tuo viaggio dentro questa corsa.
                             *
                             * Sono le uniche due righe che stai cercando in un
                             * elenco che puo' averne venti, e con un cambio la
                             * discesa conta piu' della salita: e' li' che devi
                             * scendere per prendere l'altro treno.
                             */
                            // Senza guardare le maiuscole: i codici arrivano da
                            // quattro sorgenti diverse e basta una minuscola
                            // perche' l'evidenziazione sparisca in silenzio.
                            isBoarding = stop.stationCode?.equals(boardingRfi, true) == true,
                            isAlighting = stop.stationCode?.equals(alightingRfi, true) == true,
                            /*
                             * Il binario di dove sali e' l'unico di cui l'assenza
                             * si nota: nelle stazioni grandi non sta in orario,
                             * lo assegnano un quarto d'ora prima, e vedere
                             * "bin. 4" su Monza e niente su Milano Centrale
                             * sembra un dato perso invece di un dato che ancora
                             * non esiste. Sulle altre fermate resta muto: venti
                             * righe di "non assegnato" direbbero solo rumore.
                             */
                            binarioAtteso = i == partenzaTua && stop.status == StopStatus.FUTURE &&
                                status.realtime,
                        )
                    }
                    state.error?.let {
                        item {
                            Text(
                                it,
                                Modifier.padding(top = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(status: TrainStatus) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DettagliCorsa(status)
        }
    }
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
