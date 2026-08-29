package it.zawardo.treni.ui.train

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.ui.common.delayColor
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.delayNumber
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val GIORNO = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)

private fun LocalDateTime?.hhmm(): String = this?.format(TIME) ?: "--:--"

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
    val followedNumber by TrainFollowService.followed.collectAsState()
    val isFollowing = followedNumber == trainNumber

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
                subtitle = if (date != LocalDate.now()) {
                    "partita il " + date.format(GIORNO)
                } else {
                    null
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
                    // "Segui" ha senso solo su un treno che sta ancora circolando oggi.
                    val followable = state.status != null &&
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
                    item { Header(status) }
                    item { Box(Modifier.height(16.dp)) }
                    itemsIndexed(status.stops, key = { _, s -> "${s.index}-${s.stationName}" }) { i, stop ->
                        StopRow(
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
            Text(
                "${status.origin.orEmpty()} → ${status.destination.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stateLabel(status.state) ?: delayLabel(status.delayMinutes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = stateColor(status.state, status.delayMinutes),
            )
            if (status.stops.any { it.isEstimate } && status.delayMinutes != 0) {
                // La proiezione e' nostra, non di ViaggiaTreno: meglio dichiararlo.
                Text(
                    "Gli orari delle fermate non ancora raggiunte sono ricalcolati su questo scarto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            // "Dov'e' il treno": e' il dato che la gente cerca per primo.
            if (status.lastDetectionStation != null) {
                Text("Ultimo rilevamento", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${status.lastDetectionStation} alle ${status.lastDetectionTime.hhmm()}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    if (status.state == TrainState.NOT_DEPARTED) {
                        "Non ancora partito"
                    } else {
                        "Posizione non ancora rilevata"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status.notice?.let {
                Text(
                    it,
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun StopRow(
    stop: Stop,
    isFirst: Boolean,
    isLast: Boolean,
    isBoarding: Boolean = false,
    isAlighting: Boolean = false,
    trainCancelled: Boolean = false,
    onOpenStation: (String, String) -> Unit = { _, _ -> },
) {
    // Senza codice RFI non esiste un tabellone da aprire: la riga resta inerte
    // invece di portare a una schermata vuota.
    val code = stop.stationCode?.takeIf { it.isNotBlank() }
    val done = stop.status == StopStatus.DONE
    val current = stop.status == StopStatus.CURRENT
    val stopCancelled = stop.status == StopStatus.CANCELLED
    val cancelled = stopCancelled || trainCancelled

    val scheme = MaterialTheme.colorScheme
    val markerColor = when {
        cancelled -> scheme.error
        current -> scheme.tertiary
        done -> scheme.primary
        else -> scheme.outlineVariant
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (code != null) {
                    Modifier.clickable { onOpenStation(code, stop.stationName) }
                } else {
                    Modifier
                }
            )
            .then(
                if (isBoarding || isAlighting) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                }
            ),
    ) {

        /*
         * Il binario visivo del percorso.
         *
         * Tratto già percorso: linea piena e spessa, pallino pieno.
         * Tratto ancora da fare: linea tratteggiata e sottile, pallino vuoto.
         * Fermata corrente: anello attorno al pallino, così si distingue a colpo d'occhio.
         *
         * Tutte le misure passano per dp.toPx(): in pixel grezzi la linea sarebbe
         * quasi invisibile su uno schermo ad alta densità.
         */
        Canvas(
            Modifier
                .width(36.dp)
                .fillMaxHeight(),
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val travelled = done || current
            val thick = 3.dp.toPx()
            val thin = 2.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)

            if (!isFirst) {
                drawLine(
                    color = if (travelled) scheme.primary else scheme.outlineVariant,
                    start = Offset(cx, 0f),
                    end = Offset(cx, cy),
                    strokeWidth = if (travelled) thick else thin,
                    cap = StrokeCap.Round,
                    pathEffect = if (travelled) null else dash,
                )
            }
            if (!isLast) {
                // Il tratto DOPO la fermata corrente è ancora da percorrere.
                drawLine(
                    color = if (done) scheme.primary else scheme.outlineVariant,
                    start = Offset(cx, cy),
                    end = Offset(cx, size.height),
                    strokeWidth = if (done) thick else thin,
                    cap = StrokeCap.Round,
                    pathEffect = if (done) null else dash,
                )
            }

            val r = if (current) 7.dp.toPx() else 5.dp.toPx()
            if (current) {
                drawCircle(markerColor.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = Offset(cx, cy))
            }
            if (done || current || cancelled) {
                drawCircle(markerColor, radius = r, center = Offset(cx, cy))
            } else {
                // Fermata futura: anello vuoto, non un punto pieno.
                drawCircle(
                    color = scheme.outline,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp)) {
            Text(
                stop.stationName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current || isBoarding || isAlighting) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                },
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                color = if (cancelled) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // In neretto sono uguali: a dire quale sia quale sono due parole.
            if (isBoarding || isAlighting) {
                Text(
                    if (isBoarding) "Sali qui" else "Scendi qui",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }

            if (stopCancelled) {
                Text(
                    "Fermata soppressa",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            } else {
                TimeLine(stop, isFirst, isLast, cancelled = trainCancelled)
                PlatformLine(stop)
                if (!stop.detected) {
                    Text(
                        if (stop.effectiveArrival != null || stop.effectiveDeparture != null) {
                            "Orari ricostruiti: passaggio non rilevato"
                        } else {
                            "Passaggio non rilevato"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeLine(stop: Stop, isFirst: Boolean, isLast: Boolean, cancelled: Boolean = false) {
    val scheme = MaterialTheme.colorScheme

    // Al capolinea di partenza non esiste un arrivo, a quello finale non esiste una partenza.
    val showArrival = !isFirst && stop.scheduledArrival != null
    val showDeparture = !isLast && stop.scheduledDeparture != null

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showArrival) {
            TimeCell(
                prefix = "arr",
                scheduled = stop.scheduledArrival.hhmm(),
                effective = stop.effectiveArrival?.format(TIME),
                delay = stop.arrivalDelayMinutes,
                // In arrivo solo la cifra: la riga sarebbe troppo lunga con due testi.
                withText = false,
                cancelled = cancelled,
            )
        }
        if (showDeparture) {
            TimeCell(
                prefix = "par",
                scheduled = stop.scheduledDeparture.hhmm(),
                effective = stop.effectiveDeparture?.format(TIME),
                delay = stop.departureDelayMinutes,
                withText = true,
                cancelled = cancelled,
            )
        }
        if (!showArrival && !showDeparture) {
            Text(
                "orario non disponibile",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeCell(
    prefix: String,
    scheduled: String,
    effective: String?,
    delay: Int,
    withText: Boolean,
    /** Corsa soppressa: l'orario resta scritto, ma non lo fa nessuno. */
    cancelled: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shifted = delay != 0 && effective != null

    Row {
        Text("$prefix ", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

        // L'orario di orario ufficiale resta sempre leggibile, barrato se superato.
        Text(
            scheduled,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (shifted || cancelled) TextDecoration.LineThrough else null,
            color = if (shifted) scheme.onSurfaceVariant else scheme.onSurface,
        )

        if (shifted) {
            Text(
                " $effective",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = delayColor(delay),
            )
        }
        if (delay != 0) {
            Text(
                " " + if (withText) delayLabel(delay) else delayNumber(delay),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = delayColor(delay),
            )
        }
    }
}

@Composable
private fun PlatformLine(stop: Stop) {
    val platform = stop.actualPlatform ?: stop.scheduledPlatform ?: return
    val scheme = MaterialTheme.colorScheme
    Row {
        Text("bin. ", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text(
            platform,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (stop.platformChanged) FontWeight.Bold else FontWeight.Normal,
            color = if (stop.platformChanged) scheme.tertiary else scheme.onSurfaceVariant,
        )
        if (stop.platformChanged) {
            // Il binario cambiato e' l'informazione che fa perdere i treni.
            Text(
                "  (era ${stop.scheduledPlatform})",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
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
