package it.zawardo.treni.ui.viaggio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.dopoLaDiscesa
import it.zawardo.treni.domain.model.indiceFermata
import it.zawardo.treni.service.TrainFollowService
import it.zawardo.treni.ui.TrattaViaggio
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.common.delayColor
import it.zawardo.treni.ui.common.delayNumber
import it.zawardo.treni.ui.common.lateColor
import it.zawardo.treni.ui.theme.TreniBrand
import it.zawardo.treni.ui.train.DettagliCorsa
import it.zawardo.treni.ui.train.FermataRiga
import it.zawardo.treni.ui.train.FermateNascoste
import it.zawardo.treni.ui.train.ORARIO
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val GIORNO = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN)

/**
 * Il viaggio con i cambi, tutto in una schermata sola.
 *
 * Prima ogni treno di una soluzione aveva la sua pagina, e per sapere se la
 * coincidenza reggeva bisognava uscire, tornare alla lista e riaprire l'altra —
 * cioe' proprio nel momento in cui si e' in stazione con due minuti e una mano
 * sola. Qui le corse stanno in fila nell'ordine in cui si percorrono, col
 * cambio scritto in mezzo.
 *
 * Il taglio delle fermate viene da li'. Ogni treno tranne l'ultimo prosegue
 * senza di te: le sue fermate dopo la discesa restano chiuse dietro tre punti,
 * e sotto resta il capolinea — vedi [dopoLaDiscesa]. Sull'ultimo non si taglia
 * niente, perche' li' si scende a destinazione e il resto della corsa e'
 * comunque quello che ti riguarda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViaggioScreen(
    tratte: List<TrattaViaggio>,
    /** La tratta su cui aprire: il chip toccato, o la prima. */
    focus: Int = 0,
    onBack: () -> Unit,
    onOpenStation: (String, String) -> Unit = { _, _ -> },
) {
    if (tratte.isEmpty()) return

    val vm: ViaggioViewModel = viewModel(
        factory = viewModelFactory { initializer { ViaggioViewModel(tratte) } },
    )
    val state by vm.state.collectAsState()
    val preferiti by vm.preferiti.collectAsState()

    val context = LocalContext.current
    val seguiti by TrainFollowService.followed.collectAsState()
    val numeri = remember(tratte) { tratte.filter { it.treno }.mapNotNull { it.numero } }
    val isFollowing = numeri.any { it in seguiti }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    /*
     * Seguire un viaggio vuol dire seguire tutti i suoi treni insieme: le
     * tratte partono in blocco e il servizio ne dimette una per volta, appena
     * hai superato la stazione dove scendi. Vedi [TrainFollowService].
     *
     * Solo le corse che un tempo reale ce l'hanno: una gamba EAV o ARST non
     * avrebbe mai niente da notificare, e il servizio resterebbe acceso ad
     * aspettare un ritardo che nessuno pubblichera' mai.
     */
    val tratteDaSeguire = state.tratte
        .filter { it.tratta.numero != null && it.status?.realtime == true }
        .map { it.tratta }
    val avvia: () -> Unit = { TrainFollowService.start(context, tratteDaSeguire) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) avvia() }
    val chiediNotifiche: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            avvia()
        }
    }

    val primo = tratte.first()
    val ultimo = tratte.last()

    Scaffold(
        topBar = {
            TreniTopBar(
                title = "${primo.salitaNome} → ${ultimo.discesaNome}",
                subtitle = buildString {
                    if (primo.giorno != LocalDate.now()) {
                        append(primo.giorno.format(GIORNO)).append(", ")
                    }
                    append(primo.partenza.format(ORARIO))
                    append(" → ")
                    append(ultimo.arrivo.format(ORARIO))
                    val cambi = tratte.size - 1
                    append(" · ")
                    append(if (cambi == 1) "1 cambio" else "$cambi cambi")
                },
                onBack = onBack,
                actions = {
                    /*
                     * Si segue il viaggio, non il singolo treno: e' una notifica
                     * sola con dentro tutte le corse ancora davanti a te. La
                     * stella invece resta una per treno, piu' in basso: un
                     * preferito e' un numero, e non ha niente a che fare con
                     * questo viaggio.
                     */
                    val seguibile = state.tratte.any { t ->
                        t.tratta.giorno == LocalDate.now() &&
                            t.status?.realtime == true &&
                            t.status?.state != TrainState.ARRIVED
                    }
                    if (seguibile) {
                        IconButton(
                            onClick = { if (isFollowing) TrainFollowService.stop(context) else chiediNotifiche() },
                        ) {
                            Icon(
                                if (isFollowing) Icons.Filled.NotificationsActive
                                else Icons.Filled.NotificationsNone,
                                contentDescription = if (isFollowing) "Smetti di seguire"
                                else "Segui questo viaggio",
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
        PullToRefreshBox(
            isRefreshing = state.pulling,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            val righe = remember(state) { righeDelViaggio(state) }
            val listState = rememberLazyListState()

            /*
             * Il focus: si apre sul treno che si e' toccato.
             *
             * Le righe sopra crescono man mano che le corse arrivano, quindi lo
             * scorrimento si rifa' a ogni cambio finche' tutto cio' che sta
             * sopra e' caricato. Da li' in poi non si tocca piu' niente: la
             * pagina non deve saltare sotto le dita di chi sta leggendo.
             */
            var agganciato by rememberSaveable { mutableStateOf(false) }
            val prontoSopra = state.tratte.take(focus + 1).none { it.loading }
            LaunchedEffect(righe.size, prontoSopra) {
                if (agganciato) return@LaunchedEffect
                val indice = righe.indexOfFirst { it is RigaViaggio.Intestazione && it.tratta == focus }
                if (indice >= 0) {
                    listState.scrollToItem(indice)
                    if (prontoSopra) agganciato = true
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(righe, key = { it.chiave }) { riga ->
                    when (riga) {
                        is RigaViaggio.Intestazione -> {
                            val t = state.tratte[riga.tratta]
                            SchedaTratta(
                                stato = t,
                                preferito = t.tratta.numero != null && t.tratta.numero in preferiti,
                                onStella = { t.tratta.numero?.let(vm::toggleFavorite) },
                            )
                        }

                        is RigaViaggio.Fermata -> {
                            val t = state.tratte[riga.tratta]
                            val stops = t.status?.stops.orEmpty()
                            val stop = stops.getOrNull(riga.posizione)
                            if (stop != null) {
                                FermataRiga(
                                    stop = stop,
                                    isFirst = riga.posizione == 0,
                                    isLast = riga.posizione == stops.lastIndex,
                                    trainCancelled = t.status?.state == TrainState.CANCELLED,
                                    isBoarding = riga.posizione == riga.salita,
                                    isAlighting = riga.posizione == riga.discesa,
                                    binarioAtteso = riga.posizione == riga.salita &&
                                        stop.status == StopStatus.FUTURE &&
                                        t.status?.realtime == true,
                                    onOpenStation = onOpenStation,
                                )
                            }
                        }

                        is RigaViaggio.Nascoste -> FermateNascoste(
                            etichetta = riga.blocco.etichetta,
                            quante = riga.quante,
                            espanse = riga.espanse,
                            onToggle = { vm.espandi(riga.tratta, riga.blocco) },
                        )

                        is RigaViaggio.Cambio -> CambioRiga(riga)

                        is RigaViaggio.NonTreno -> TrattaSenzaPercorso(state.tratte[riga.tratta].tratta)

                        is RigaViaggio.Attesa -> Row(
                            Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "  Carico il percorso…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        is RigaViaggio.Errore -> Text(
                            riga.testo,
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        is RigaViaggio.Spazio -> Box(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/** La scheda di una corsa dentro il viaggio: il treno, la stella, i tuoi due capi. */
@Composable
private fun SchedaTratta(
    stato: TrattaUiState,
    preferito: Boolean,
    onStella: () -> Unit,
) {
    val tratta = stato.tratta
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stato.status?.label?.takeIf { it.isNotBlank() } ?: tratta.etichetta,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (tratta.numero != null) {
                    // Il preferito e' il numero del treno, non questo viaggio:
                    // per questo la stella sta sulla corsa e non nella barra.
                    IconButton(onClick = onStella) {
                        Icon(
                            if (preferito) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (preferito) "Togli dai preferiti"
                            else "Aggiungi ai preferiti",
                            tint = if (preferito) TreniBrand.star else LocalContentColor.current,
                        )
                    }
                }
            }

            stato.status?.let { DettagliCorsa(it) }

            HorizontalDivider(Modifier.padding(vertical = 2.dp))

            /*
             * I tuoi due capi, scritti anche qui e non solo evidenziati in mezzo
             * alle fermate: su una corsa lunga la salita puo' stare venti righe
             * piu' giu', e la prima cosa che si cerca aprendo la pagina e' a che
             * ora si sale e dove si scende.
             *
             * Gli orari sono quelli **veri** dove il tempo reale li ha spostati:
             * qui non si sta leggendo un orario, si sta decidendo a che ora
             * uscire di casa e se il cambio si fa.
             */
            val salita = stato.status?.let {
                it.stops.getOrNull(it.indiceFermata(tratta.salitaRfi, tratta.partenzaNota))
            }
            val discesa = stato.status?.let {
                it.stops.getOrNull(it.indiceFermata(tratta.discesaRfi, tratta.arrivoNoto))
            }
            CapoRiga(
                testo = "Sali a ${tratta.salitaNome} alle",
                quando = salita?.effectiveDeparture ?: salita?.scheduledDeparture ?: tratta.partenza,
                scarto = salita?.departureDelayMinutes ?: 0,
            )
            CapoRiga(
                testo = "Scendi a ${tratta.discesaNome} alle",
                quando = discesa?.effectiveArrival ?: discesa?.scheduledArrival ?: tratta.arrivo,
                scarto = discesa?.arrivalDelayMinutes ?: 0,
            )
        }
    }
}

/** Uno dei due capi del tuo pezzo di corsa: l'ora, e di quanto si e' spostata. */
@Composable
private fun CapoRiga(testo: String, quando: LocalDateTime, scarto: Int) {
    Row {
        Text(
            "$testo ${quando.format(ORARIO)}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (scarto != 0) {
            Text(
                " ${delayNumber(scarto)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = delayColor(scarto),
            )
        }
    }
}

/**
 * Il cambio fra due treni: dove si scende, e quanto tempo resta **adesso**.
 *
 * Si accende di rosso quando il margine si e' ristretto rispetto all'orario, non
 * soltanto quando e' finito: e' li' che serve guardarlo, perche' quindici minuti
 * che erano venti dicono che qualcosa sta scivolando, e la lettura dopo potrebbe
 * dire che non ci sei piu' dentro.
 */
@Composable
private fun CambioRiga(riga: RigaViaggio.Cambio) {
    val scheme = MaterialTheme.colorScheme
    val allarme = riga.saltato || riga.ristretto
    Card(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allarme) scheme.errorContainer else scheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    "  Cambia a ${riga.stazione}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                when {
                    riga.saltato -> "Con gli orari aggiornati la coincidenza non si tiene"
                    riga.ristretto -> "${riga.minuti} min per il cambio (meno del previsto)"
                    riga.reale && riga.minuti > riga.previsti ->
                        "${riga.minuti} min per il cambio (più del previsto)"
                    else -> "${riga.minuti} min per il cambio"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (allarme) lateColor() else LocalContentColor.current,
            )
        }
    }
}

/** Bus sostitutivo o trasferimento a piedi: non hanno percorso da interrogare. */
@Composable
private fun TrattaSenzaPercorso(tratta: TrattaViaggio) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (tratta.piedi) Icons.AutoMirrored.Filled.DirectionsWalk
                    else Icons.Filled.DirectionsBus,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "  ${tratta.etichetta}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "${tratta.salitaNome} ${tratta.partenza.format(ORARIO)} → " +
                    "${tratta.discesaNome} ${tratta.arrivo.format(ORARIO)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Nessun tempo reale: di questa tratta esiste solo l'orario.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------- le righe

/**
 * Le righe della pagina, calcolate prima di disegnarle.
 *
 * Servono in forma di lista e non di codice sparso dentro la `LazyColumn` per
 * una ragione precisa: il focus. Aprire la pagina sul treno toccato vuol dire
 * conoscere l'**indice** della sua intestazione, e quell'indice si sa solo se le
 * righe esistono come dati.
 */
private sealed interface RigaViaggio {
    val chiave: String

    data class Intestazione(val tratta: Int) : RigaViaggio {
        override val chiave get() = "corsa-$tratta"
    }

    data class Fermata(
        val tratta: Int,
        val posizione: Int,
        val salita: Int,
        val discesa: Int,
        val nome: String,
    ) : RigaViaggio {
        override val chiave get() = "f-$tratta-$posizione-$nome"
    }

    data class Nascoste(
        val tratta: Int,
        val blocco: BloccoNascosto,
        val quante: Int,
        val espanse: Boolean,
    ) : RigaViaggio {
        override val chiave get() = "nascoste-$tratta-$blocco"
    }

    data class Cambio(
        val dopo: Int,
        val stazione: String,
        val minuti: Long,
        val previsti: Long,
        /** Vero se i minuti vengono dagli orari veri e non da quelli di tabella. */
        val reale: Boolean,
    ) : RigaViaggio {
        override val chiave get() = "cambio-$dopo"

        /** Il cambio non si tiene piu': e' la cosa da vedere per prima. */
        val saltato: Boolean get() = minuti <= 0

        /** Meno tempo di quello che dice l'orario: qualcuno dei due e' in ritardo. */
        val ristretto: Boolean get() = reale && minuti < previsti
    }

    data class NonTreno(val tratta: Int) : RigaViaggio {
        override val chiave get() = "altro-$tratta"
    }

    data class Attesa(val tratta: Int) : RigaViaggio {
        override val chiave get() = "attesa-$tratta"
    }

    data class Errore(val tratta: Int, val testo: String) : RigaViaggio {
        override val chiave get() = "errore-$tratta"
    }

    data class Spazio(val dopo: Int) : RigaViaggio {
        override val chiave get() = "spazio-$dopo"
    }
}

private fun righeDelViaggio(state: ViaggioUiState): List<RigaViaggio> {
    val righe = mutableListOf<RigaViaggio>()
    state.tratte.forEachIndexed { i, t ->
        if (!t.tratta.treno) {
            righe += RigaViaggio.NonTreno(i)
            return@forEachIndexed
        }

        righe += RigaViaggio.Intestazione(i)
        righe += RigaViaggio.Spazio(i)

        val status = t.status
        when {
            status != null -> {
                val stops = status.stops
                val salita = status.indiceFermata(t.tratta.salitaRfi, t.tratta.partenzaNota)
                val discesa = status.indiceFermata(t.tratta.discesaRfi, t.tratta.arrivoNoto)

                /*
                 * I tratti chiusi di questa corsa.
                 *
                 * Oggi ce n'e' uno: le fermate dopo la discesa, e solo se dopo
                 * questo treno ne prendi un altro — sull'ultima tratta si scende
                 * a destinazione e il resto della corsa e' comunque cio' che
                 * stavi guardando. Il secondo, «prima della salita», si accende
                 * aggiungendo una riga a questo elenco: vedi [BloccoNascosto].
                 */
                val blocchi: List<Pair<BloccoNascosto, IntRange>> = buildList {
                    if (i != state.tratte.lastIndex) {
                        stops.dopoLaDiscesa(discesa)?.let { add(BloccoNascosto.DOPO_LA_DISCESA to it) }
                    }
                }

                stops.forEachIndexed { pos, stop ->
                    val blocco = blocchi.firstOrNull { pos in it.second }
                    if (blocco != null) {
                        val (quale, intervallo) = blocco
                        val aperto = quale in t.espansi
                        // Il comando resta ancorato all'inizio del tratto, aperto
                        // o chiuso che sia: non deve spostarsi sotto il dito di
                        // chi l'ha appena toccato.
                        if (pos == intervallo.first) {
                            righe += RigaViaggio.Nascoste(i, quale, intervallo.count(), aperto)
                        }
                        if (!aperto) return@forEachIndexed
                    }
                    righe += RigaViaggio.Fermata(i, pos, salita, discesa, stop.stationName)
                }
            }

            t.loading -> righe += RigaViaggio.Attesa(i)

            t.error != null -> righe += RigaViaggio.Errore(i, t.error)
        }

        val prossima = state.tratte.getOrNull(i + 1)
        if (prossima != null && prossima.tratta.treno) {
            righe += cambio(i, t, prossima)
        }
    }
    return righe
}

/**
 * Il cambio fra due tratte, con gli orari veri quando ci sono.
 *
 * I minuti che contano sono quelli che restano **adesso**, non quelli
 * dell'orario: e' esattamente la domanda che ci si fa in treno quando il tuo
 * accumula ritardo. Si prendono dall'arrivo effettivo alla stazione di discesa
 * e dalla partenza effettiva della coincidenza; dove il tempo reale non c'e' si
 * ripiega su quel che dice la soluzione, e non si dichiara reale.
 */
private fun cambio(i: Int, qui: TrattaUiState, poi: TrattaUiState): RigaViaggio.Cambio {
    val previsti = Duration.between(qui.tratta.arrivo, poi.tratta.partenza).toMinutes()

    val arrivo = qui.status?.let { s ->
        s.stops.getOrNull(s.indiceFermata(qui.tratta.discesaRfi, qui.tratta.arrivoNoto))?.arrivoUtile()
    }
    val partenza = poi.status?.let { s ->
        s.stops.getOrNull(s.indiceFermata(poi.tratta.salitaRfi, poi.tratta.partenzaNota))?.partenzaUtile()
    }
    val reale = arrivo != null && partenza != null
    val minuti = if (reale) Duration.between(arrivo, partenza).toMinutes() else previsti

    return RigaViaggio.Cambio(
        dopo = i,
        stazione = qui.tratta.discesaNome,
        minuti = minuti,
        previsti = previsti,
        reale = reale,
    )
}

private fun Stop.arrivoUtile(): LocalDateTime? = effectiveArrival ?: scheduledArrival
private fun Stop.partenzaUtile(): LocalDateTime? = effectiveDeparture ?: scheduledDeparture

/**
 * Come si chiama, a schermo, un tratto chiuso.
 *
 * Le parole del riferimento e non le nostre: «vedi fermate successive» e «vedi
 * fermate precedenti» sono quelle che la gente ha gia' letto altrove, e su un
 * comando che nasconde delle righe non c'e' niente da reinventare.
 */
private val BloccoNascosto.etichetta: String
    get() = when (this) {
        BloccoNascosto.DOPO_LA_DISCESA -> "fermate successive"
        BloccoNascosto.PRIMA_DELLA_SALITA -> "fermate precedenti"
    }
