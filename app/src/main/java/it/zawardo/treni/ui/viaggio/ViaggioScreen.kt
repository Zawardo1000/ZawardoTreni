package it.zawardo.treni.ui.viaggio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.binarioDaMostrare
import it.zawardo.treni.domain.model.dopoLaDiscesa
import it.zawardo.treni.domain.model.indiceFermata
import it.zawardo.treni.domain.model.primaDellaSalita
import it.zawardo.treni.domain.model.soppressione
import it.zawardo.treni.service.TrainFollowService
import it.zawardo.treni.ui.TrattaViaggio
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.ui.common.BinarioPillola
import it.zawardo.treni.ui.common.onLateColor
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.lateBackground
import it.zawardo.treni.ui.common.lateColor
import it.zawardo.treni.ui.common.scartoColor
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import it.zawardo.treni.ui.theme.Cifre
import it.zawardo.treni.ui.theme.TreniBrand
import it.zawardo.treni.ui.train.ColonneCorsa
import it.zawardo.treni.ui.train.EtichettaTratto
import it.zawardo.treni.ui.train.FermataRiga
import it.zawardo.treni.ui.train.FermateNascoste
import it.zawardo.treni.ui.train.IntestazioneColonne
import it.zawardo.treni.ui.train.PosizioneTreno
import it.zawardo.treni.ui.train.nelTratto
import it.zawardo.treni.ui.train.posizioneInViaggio
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
 *
 * Dal secondo treno in poi si chiudono anche le fermate prima della salita —
 * vedi [primaDellaSalita] — la strada che il treno ha fatto prima di arrivare da
 * te. Il primo le tiene aperte: li' e' proprio il treno che stai aspettando.
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

            /*
             * I ritardi fissi in cima, uno per treno, impilati.
             *
             * Chiesti per i viaggi con cambi: lo scarto deve restare in vista come
             * nel dettaglio della corsa singola, ma i treni sono piu' d'uno. In cima
             * resta quello che ti riguarda adesso — il treno su cui sei, o il primo
             * che devi ancora prendere — e sotto, scorrendo, una riga per ogni treno
             * fino a quello della sezione che stai guardando. Senza un tetto: in
             * partenza erano al massimo due, ma provate sul telefono le righe
             * occupano poco, e il limite e' stato tolto (11/09/2026). I treni gia'
             * lasciati si staccano da soli.
             *
             * Stanno **sopra la lista, non prima di lei**. Messe prima, la seconda
             * riga comparendo spingerebbe giu' il contenuto, il secondo treno
             * uscirebbe di vista, la riga sparirebbe e il contenuto risalirebbe:
             * un'altalena. Sopra, la lista riserva in cima solo il posto della prima
             * riga, e la seconda scorre su cio' che e' gia' passato.
             */
            val corrente = trattaCorrente(state)
            val guardata by remember(righe) {
                derivedStateOf { righe.getOrNull(listState.firstVisibleItemIndex)?.trattaDi }
            }
            val impilate = if (corrente == null) {
                emptyList()
            } else {
                (corrente..maxOf(corrente, guardata ?: corrente))
                    .filter { state.tratte.getOrNull(it)?.tratta?.treno == true }
            }
            val traLeFermate by remember(righe) {
                derivedStateOf {
                    when (righe.getOrNull(listState.firstVisibleItemIndex)) {
                        is RigaViaggio.Fermata, is RigaViaggio.Nascoste, is RigaViaggio.Posizione -> true
                        else -> false
                    }
                }
            }
            var primaRiga by remember { mutableIntStateOf(0) }
            val spazioInCima = with(LocalDensity.current) { (if (corrente != null) primaRiga else 0).toDp() }

            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // Solo in verticale: le righe del percorso hanno i loro margini, e
                // la fascia gialla del tuo tratto arriva quasi al bordo. In cima, il
                // posto del primo ritardo fisso.
                contentPadding = PaddingValues(top = spazioInCima + 16.dp, bottom = 16.dp),
            ) {
                items(righe, key = { it.chiave }) { riga ->
                    when (riga) {
                        is RigaViaggio.Intestazione -> {
                            val t = state.tratte[riga.tratta]
                            Column {
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    SchedaTratta(
                                        stato = t,
                                        preferito = t.tratta.numero != null && t.tratta.numero in preferiti,
                                        onStella = { t.tratta.numero?.let(vm::toggleFavorite) },
                                    )
                                }
                                // Le colonne delle fermate si leggono solo con
                                // l'intestazione: senza, due orari affiancati non
                                // dicono quale sia quello vero.
                                if (t.status != null) IntestazioneColonne(Modifier.padding(top = 8.dp))
                            }
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
                                    tratto = nelTratto(riga.posizione, riga.salita, riga.discesa),
                                    realtime = t.status?.realtime == true,
                                    zebra = riga.posizione % 2 == 1,
                                    trenoDopo = riga.trenoDopo,
                                    trenoInStazione = riga.trenoInStazione,
                                    rilevatoAlle = riga.rilevatoAlle,
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

                        is RigaViaggio.Cambio -> Box(Modifier.padding(horizontal = 16.dp)) {
                            CambioRiga(riga)
                        }

                        is RigaViaggio.NonTreno -> Box(Modifier.padding(horizontal = 16.dp)) {
                            TrattaSenzaPercorso(state.tratte[riga.tratta].tratta)
                        }

                        is RigaViaggio.Attesa -> Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
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
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )

                        is RigaViaggio.Spazio -> Box(Modifier.height(16.dp))

                        is RigaViaggio.Posizione -> PosizioneTreno(
                            dove = riga.dove,
                            quando = riga.quando,
                            nelTuoTratto = riga.nelTuoTratto,
                        )
                    }
                }
            }

            // I ritardi fissi, sopra la lista: vedi il commento qui sopra.
            Column(Modifier.fillMaxWidth()) {
                impilate.forEachIndexed { k, indice ->
                    RitardoFisso(
                        state.tratte,
                        indice,
                        // La prima riga fissa e' anche lo spazio che la lista riserva in cima.
                        if (k == 0) Modifier.onSizeChanged { primaRiga = it.height } else Modifier,
                    )
                }
                // L'intestazione delle colonne, quando si e' in mezzo alle fermate.
                if (traLeFermate) IntestazioneColonne()
            }
        }
    }
}

/**
 * La testata di una corsa dentro il viaggio: che treno e', dove va, e dove sali
 * e scendi tu.
 *
 * Era una scheda grigia che ripeteva tre cose gia' scritte altrove: lo scarto
 * grande, ora fisso in cima; il punto dell'ultimo rilevamento, ora sulla linea
 * col segno del treno; e un paragrafo sugli orari stimati, che il tondo e il
 * neretto delle colonne dicono gia'. Restano il codice, la direzione e la stella,
 * su due righe (scelte fra due varianti l'11/09/2026), e i tuoi due capi
 * incolonnati come le fermate. Lo stato si dice solo quando non e' quello
 * normale.
 */
@Composable
private fun SchedaTratta(
    stato: TrattaUiState,
    preferito: Boolean,
    onStella: () -> Unit,
) {
    val tratta = stato.tratta
    val status = stato.status
    val scheme = MaterialTheme.colorScheme
    val realtime = status?.realtime == true
    val salita = status?.let { it.stops.getOrNull(it.indiceFermata(tratta.salitaRfi, tratta.partenzaNota)) }
    val discesa = status?.let { it.stops.getOrNull(it.indiceFermata(tratta.discesaRfi, tratta.arrivoNoto)) }
    val sigla = status?.category?.takeIf { it.isNotBlank() }
        ?: tratta.etichetta.substringBefore(' ').takeIf { it != tratta.etichetta }
    // «Non ancora partito» non c'e': lo dice gia' la riga fissa in cima.
    val anomalia = when (status?.state) {
        TrainState.ARRIVED -> "arrivato"
        TrainState.CANCELLED -> "soppresso"
        TrainState.PARTIALLY_CANCELLED -> "soppresso in parte"
        TrainState.DIVERTED -> "percorso variato"
        else -> null
    }

    Column(
        // Un treno gia' lasciato si spegne: resta leggibile, ma non chiede attenzione.
        Modifier.fillMaxWidth().alpha(if (stato.finita) 0.6f else 1f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(color = scheme.outlineVariant)
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sigla?.let { SiglaTreno(it) }
                Text(
                    tratta.numero ?: tratta.etichetta,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                anomalia?.let { ChipStato(it, grave = status?.state?.soppressione == true) }
                if (tratta.numero != null) {
                    // Il preferito e' il numero del treno, non questo viaggio:
                    // per questo la stella sta sulla corsa e non nella barra.
                    IconButton(onClick = onStella) {
                        Icon(
                            if (preferito) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (preferito) "Togli dai preferiti"
                            else "Aggiungi ai preferiti",
                            tint = if (preferito) TreniBrand.star else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val origine = status?.origin
            val destinazione = status?.destination
            if (origine != null && destinazione != null) {
                Text(
                    "$origine → $destinazione",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
            }
        }

        status?.notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
        }

        /*
         * I tuoi due capi, scritti anche qui e non solo evidenziati fra le
         * fermate: su una corsa lunga la salita puo' stare venti righe piu' giu',
         * e la prima cosa che si cerca e' a che ora si sale e dove si scende.
         * Nelle stesse colonne delle fermate, cosi' si leggono allo stesso modo.
         */
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CapoDelTratto(
                testo = "Sali",
                piena = true,
                nome = tratta.salitaNome,
                previsto = salita?.scheduledDeparture ?: tratta.partenza,
                reale = salita?.effectiveDeparture?.takeIf { realtime },
                misurato = salita?.actualDeparture != null,
                scarto = salita?.departureDelayMinutes ?: 0,
                programmato = salita?.scheduledPlatform,
                effettivo = salita?.actualPlatform,
            )
            CapoDelTratto(
                testo = "Scendi",
                piena = false,
                nome = tratta.discesaNome,
                previsto = discesa?.scheduledArrival ?: tratta.arrivo,
                reale = discesa?.effectiveArrival?.takeIf { realtime },
                misurato = discesa?.actualArrival != null,
                scarto = discesa?.arrivalDelayMinutes ?: 0,
                programmato = discesa?.scheduledPlatform,
                effettivo = discesa?.actualPlatform,
            )
        }
    }
}

/** Uno dei tuoi due capi, nelle stesse colonne delle fermate. */
@Composable
private fun CapoDelTratto(
    testo: String,
    piena: Boolean,
    nome: String,
    previsto: LocalDateTime,
    reale: LocalDateTime?,
    misurato: Boolean,
    scarto: Int,
    programmato: String?,
    effettivo: String?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Larghezza fissa: «Sali» e «Scendi» sono lunghi diversi, i nomi no.
        Box(Modifier.width(64.dp)) { EtichettaTratto(testo, piena) }
        Text(
            nome,
            Modifier.weight(1f).padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(previsto.format(ORARIO), Modifier.width(ColonneCorsa.orario), style = Cifre.riga)
        Text(
            reale?.format(ORARIO) ?: "",
            Modifier.width(ColonneCorsa.reale),
            style = Cifre.riga,
            fontWeight = if (misurato) FontWeight.SemiBold else FontWeight.Normal,
            color = scartoColor(scarto),
        )
        Box(Modifier.width(ColonneCorsa.binario), contentAlignment = Alignment.CenterEnd) {
            BinarioPillola(programmato, effettivo, piccola = true, segnaposto = true)
        }
    }
}

/** La sigla del treno nel riquadro, come nell'elenco e nelle righe fisse. */
@Composable
private fun SiglaTreno(sigla: String) {
    val colore = MaterialTheme.colorScheme.onSurface
    Text(
        sigla,
        Modifier
            .border(1.5.dp, colore, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colore,
    )
}

/** Lo stato del treno quando non e' quello normale: pieno di rosso se soppresso. */
@Composable
private fun ChipStato(testo: String, grave: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(50)
    Text(
        testo,
        Modifier
            .then(
                if (grave) Modifier.background(lateColor(), forma)
                else Modifier.border(1.5.dp, scheme.outline, forma),
            )
            .padding(horizontal = 9.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (grave) onLateColor() else scheme.onSurfaceVariant,
    )
}

/**
 * Il cambio fra due treni: dove si scende, e quanto tempo resta **adesso**.
 *
 * Scrive i due orari che fanno quel tempo — quando arrivi, quando parte il treno
 * dopo — cosi' il numero si spiega da se'. Prima c'era scritto «meno del
 * previsto», che diceva solo che l'attesa si era accorciata rispetto
 * all'orario, non di quanto ne' perche'; e bastava un minuto per accendere il
 * rosso, anche con 38 minuti di margine. Ora il rosso vuol dire una cosa sola: il
 * margine e' stretto, o la coincidenza e' persa. Vedi [RigaViaggio.Cambio.stretto].
 */
@Composable
private fun CambioRiga(riga: RigaViaggio.Cambio) {
    val scheme = MaterialTheme.colorScheme
    val rosso = lateColor()
    val tratteggio = scheme.outlineVariant
    val raggio = 12.dp
    val forma = RoundedCornerShape(raggio)
    val allarme = riga.saltato || riga.stretto
    val bordo = if (allarme) {
        Modifier.border(1.5.dp, rosso, forma)
    } else {
        // Tratteggiato: e' un passaggio fra due treni, non un oggetto a se'.
        Modifier.drawBehind {
            val w = 1.5.dp.toPx()
            drawRoundRect(
                color = tratteggio,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(raggio.toPx()),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .background(if (riga.saltato) lateBackground() else scheme.surfaceContainerLow, forma)
            .then(bordo)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (riga.saltato) rosso else scheme.primary,
            )
            Text(
                if (riga.saltato) "Coincidenza persa a ${riga.stazione}" else "Cambio a ${riga.stazione}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (riga.saltato) rosso else scheme.onSurface,
            )
            if (!riga.saltato) {
                Text(
                    "${riga.minuti} min",
                    style = Cifre.binario.copy(fontSize = 22.sp, lineHeight = 26.sp),
                    color = if (riga.stretto) rosso else scheme.onSurface,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OrarioDelCambio("arrivi", riga.arrivo, riga.scartoArrivo, riga.reale)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OrarioDelCambio("parte", riga.partenza, riga.scartoPartenza, riga.reale)
                binarioDaMostrare(riga.binarioProgrammato, riga.binarioEffettivo)?.let {
                    Text(
                        " · bin $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        val differenza = riga.minuti - riga.previsti
        Text(
            when {
                riga.saltato && riga.minuti == 0L -> "Il treno per ${riga.verso} parte proprio mentre arrivi"
                riga.saltato -> "Il treno per ${riga.verso} parte ${-riga.minuti} min prima che tu arrivi"
                riga.stretto && riga.reale && differenza < 0 ->
                    "Margine stretto · ${-differenza} min in meno dell'orario"
                riga.stretto -> "Margine stretto"
                !riga.reale -> "secondo l'orario"
                differenza < 0 -> "${-differenza} min in meno dell'orario"
                differenza > 0 -> "$differenza min in più dell'orario"
                else -> "come da orario"
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (allarme) FontWeight.SemiBold else FontWeight.Normal,
            color = if (allarme) rosso else scheme.onSurfaceVariant,
        )
    }
}

/** Uno dei due orari del cambio: colorato solo se viene dal tempo reale. */
@Composable
private fun OrarioDelCambio(etichetta: String, quando: LocalDateTime, scarto: Int, reale: Boolean) {
    Row {
        Text(
            "$etichetta ",
            Modifier.alignByBaseline(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            quando.format(ORARIO),
            Modifier.alignByBaseline(),
            style = Cifre.riga,
            fontWeight = FontWeight.SemiBold,
            color = if (reale) scartoColor(scarto) else MaterialTheme.colorScheme.onSurface,
        )
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
        /** Il treno e' ripartito da qui, e sta sul tratto verso la prossima. */
        val trenoDopo: Boolean = false,
        /** Il treno e' fermo qui, in stazione. */
        val trenoInStazione: Boolean = false,
        val rilevatoAlle: LocalDateTime? = null,
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

    /** Il treno sul percorso, fra l'ultima fermata fatta e la prossima. */
    data class Posizione(
        val tratta: Int,
        val dove: String?,
        val quando: LocalDateTime?,
        val nelTuoTratto: Boolean,
    ) : RigaViaggio {
        override val chiave get() = "treno-$tratta"
    }

    data class Cambio(
        val dopo: Int,
        val stazione: String,
        val minuti: Long,
        val previsti: Long,
        /** Vero se i minuti vengono dagli orari veri e non da quelli di tabella. */
        val reale: Boolean,
        /** Quando arrivi e quando parte il treno dopo: i due orari che fanno i minuti. */
        val arrivo: LocalDateTime,
        val partenza: LocalDateTime,
        val scartoArrivo: Int = 0,
        val scartoPartenza: Int = 0,
        val binarioProgrammato: String? = null,
        val binarioEffettivo: String? = null,
        /** Dove va, per te, il treno dopo: «il treno per Brescia». */
        val verso: String,
    ) : RigaViaggio {
        override val chiave get() = "cambio-$dopo"

        /** Il cambio non si tiene piu': e' la cosa da vedere per prima. */
        val saltato: Boolean get() = minuti <= 0

        /**
         * Il margine e' davvero stretto: sotto i [MARGINE_STRETTO] minuti.
         *
         * Prima il rosso scattava per qualunque riduzione rispetto all'orario:
         * 38 minuti su 40 uscivano in rosso, «meno del previsto», come se il
         * cambio fosse a rischio. Conta quanto tempo resta, non di quanto sia
         * calato. Deciso con l'utente l'11/09/2026.
         */
        val stretto: Boolean get() = !saltato && minuti < MARGINE_STRETTO
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
    // Il primo treno, non la prima tratta: un viaggio puo' cominciare a piedi.
    val primoTreno = state.tratte.indexOfFirst { it.tratta.treno }
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
                 * Le fermate dopo la discesa, se dopo questo treno ne prendi un
                 * altro: sull'ultima tratta si scende a destinazione e il resto
                 * della corsa e' comunque cio' che stavi guardando. E dal secondo
                 * treno in poi quelle prima della salita: sul primo, o sull'unico,
                 * da dove arriva il treno che aspetti e' proprio cio' che guardi, e
                 * restano aperte.
                 */
                val blocchi: List<Pair<BloccoNascosto, IntRange>> = buildList {
                    if (i > primoTreno) {
                        stops.primaDellaSalita(salita)?.let { add(BloccoNascosto.PRIMA_DELLA_SALITA to it) }
                    }
                    if (i != state.tratte.lastIndex) {
                        stops.dopoLaDiscesa(discesa)?.let { add(BloccoNascosto.DOPO_LA_DISCESA to it) }
                    }
                }

                /*
                 * Il treno, se sta viaggiando. Fermo su una stazione, e allora il
                 * suo segno sta sulla fermata; ripartito, e allora ha una riga sua
                 * dopo l'ultima fermata fatta. Vedi `posizioneInViaggio`.
                 */
                val posizione = status.posizioneInViaggio()
                val fermoA = posizione?.takeIf { it.inStazione }?.fermata
                val dopoLa = posizione?.takeIf { !it.inStazione }?.fermata
                val trenoQui = posizione?.let { p ->
                    val qui = stops[p.fermata]
                    RigaViaggio.Posizione(
                        tratta = i,
                        // Serve solo quando la fermata e' chiusa dietro i puntini:
                        // fermo, il treno si dice dove sta.
                        dove = if (p.inStazione) "In stazione a ${qui.stationName}" else status.lastDetectionStation,
                        quando = status.lastDetectionTime,
                        nelTuoTratto = salita in 0 until discesa && p.fermata >= salita && p.fermata < discesa,
                    )
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
                            // Il treno dentro un tratto chiuso resta visibile,
                            // subito sotto i puntini: dov'e' il treno non si nasconde.
                            val treno = posizione?.fermata
                            if (!aperto && treno != null && treno in intervallo && trenoQui != null) {
                                righe += trenoQui
                            }
                        }
                        if (!aperto) return@forEachIndexed
                    }
                    righe += RigaViaggio.Fermata(
                        i, pos, salita, discesa, stop.stationName,
                        trenoDopo = pos == dopoLa,
                        trenoInStazione = pos == fermoA,
                        rilevatoAlle = status.lastDetectionTime.takeIf { pos == fermoA },
                    )
                    // Ripartito, la sua riga sta subito dopo; fermo, e' gia' sulla fermata.
                    if (pos == dopoLa && trenoQui != null) righe += trenoQui
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

    val discesa = qui.status?.let { s ->
        s.stops.getOrNull(s.indiceFermata(qui.tratta.discesaRfi, qui.tratta.arrivoNoto))
    }
    val salita = poi.status?.let { s ->
        s.stops.getOrNull(s.indiceFermata(poi.tratta.salitaRfi, poi.tratta.partenzaNota))
    }
    val arrivo = discesa?.arrivoUtile()
    val partenza = salita?.partenzaUtile()
    val reale = arrivo != null && partenza != null
    val minuti = if (reale) Duration.between(arrivo, partenza).toMinutes() else previsti

    return RigaViaggio.Cambio(
        dopo = i,
        stazione = qui.tratta.discesaNome,
        minuti = minuti,
        previsti = previsti,
        reale = reale,
        arrivo = arrivo ?: qui.tratta.arrivo,
        partenza = partenza ?: poi.tratta.partenza,
        scartoArrivo = discesa?.arrivalDelayMinutes ?: 0,
        scartoPartenza = salita?.departureDelayMinutes ?: 0,
        binarioProgrammato = salita?.scheduledPlatform,
        binarioEffettivo = salita?.actualPlatform,
        verso = poi.tratta.discesaNome,
    )
}

/** Sotto questi minuti il cambio e' stretto, e la scheda si accende di rosso. */
private const val MARGINE_STRETTO = 5L

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

// ------------------------------------------------------- i ritardi fissi

/**
 * Il treno che ti riguarda adesso: quello su cui sei, o il primo che devi
 * ancora prendere. `null` quando li hai lasciati tutti.
 */
private fun trattaCorrente(state: ViaggioUiState): Int? =
    state.tratte.indexOfFirst { it.tratta.treno && !it.finita }.takeIf { it >= 0 }

/**
 * Un treno smette di riguardarti quando e' arrivato dove scendi tu: la sua
 * fermata di discesa e' fatta, o la corsa e' finita. Senza tempo reale non lo si
 * puo' sapere, e il treno resta tuo.
 */
private val TrattaUiState.finita: Boolean
    get() {
        val s = status ?: return false
        if (!s.realtime) return false
        if (s.state == TrainState.ARRIVED) return true
        val discesa = s.stops.getOrNull(s.indiceFermata(tratta.discesaRfi, tratta.arrivoNoto)) ?: return false
        return discesa.status == StopStatus.DONE || discesa.status == StopStatus.CURRENT
    }

/** La tratta a cui appartiene una riga della pagina. */
private val RigaViaggio.trattaDi: Int
    get() = when (this) {
        is RigaViaggio.Intestazione -> tratta
        is RigaViaggio.Fermata -> tratta
        is RigaViaggio.Nascoste -> tratta
        is RigaViaggio.Posizione -> tratta
        is RigaViaggio.Cambio -> dopo
        is RigaViaggio.NonTreno -> tratta
        is RigaViaggio.Attesa -> tratta
        is RigaViaggio.Errore -> tratta
        is RigaViaggio.Spazio -> dopo
    }

/**
 * Il ritardo di un treno del viaggio, fisso in cima.
 *
 * Sigla e numero davanti, perche' con due righe impilate si capisca a colpo
 * d'occhio quale ritardo sia di quale treno. Poi il pezzo di corsa che fai tu,
 * con gli orari di tabella: «fino a» il cambio sul primo treno, «da» il cambio
 * sull'ultimo, «da… a…» su quelli in mezzo.
 *
 * Il testo va a capo invece di troncarsi. Con nomi come «Milano Porta Garibaldi
 * Sotterranea» e due orari una riga sola non basta sempre, e un nome tagliato a
 * meta' e' un nome sbagliato: lo si e' voluto provare cosi', sul telefono.
 */
@Composable
private fun RitardoFisso(tutte: List<TrattaUiState>, indice: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val t = tutte[indice]
    val tratta = t.tratta
    val treni = tutte.indices.filter { tutte[it].tratta.treno }
    val primo = indice == treni.firstOrNull()
    val ultimo = indice == treni.lastOrNull()
    val da = "${tratta.salitaNome} ${tratta.partenza.format(ORARIO)}"
    val a = "${tratta.discesaNome} ${tratta.arrivo.format(ORARIO)}"
    val dove = when {
        primo && !ultimo -> "fino a $a"
        ultimo && !primo -> "da $da"
        else -> "da $da a $a"
    }
    val sigla = t.status?.category
        ?: tratta.etichetta.substringBefore(' ').takeIf { it != tratta.etichetta }

    Column(modifier.fillMaxWidth().background(scheme.surfaceContainer)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sigla?.let {
                Text(
                    it,
                    Modifier
                        .border(1.5.dp, scheme.onSurface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                tratta.numero ?: tratta.etichetta,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                dove,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ScartoBreve(t.status)
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

/** Lo scarto in poche lettere: la riga fissa e' stretta. */
@Composable
private fun ScartoBreve(status: TrainStatus?) {
    val scheme = MaterialTheme.colorScheme
    val cifre = Cifre.binario.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val anomalia = status?.let { stateLabel(it.state) }
    when {
        status == null -> Text("…", style = cifre, color = scheme.onSurfaceVariant)
        // Senza tempo reale nessuna cifra: quello zero non sarebbe una misura.
        !status.realtime -> Text(
            "orario previsto",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        status.state == TrainState.NOT_DEPARTED && status.delayMinutes == 0 -> Text(
            "non partito",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        anomalia != null && status.state != TrainState.NOT_DEPARTED -> Text(
            anomalia,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = stateColor(status.state, status.delayMinutes),
        )
        else -> Text(
            if (status.delayMinutes == 0) "in orario" else delayLabel(status.delayMinutes),
            style = cifre,
            color = scartoColor(status.delayMinutes),
        )
    }
}
