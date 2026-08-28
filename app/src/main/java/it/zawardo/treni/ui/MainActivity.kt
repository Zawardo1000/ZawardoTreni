package it.zawardo.treni.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DepartureBoard
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.remote.gtfs.AggiornamentoOrari
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.ui.about.AboutScreen
import it.zawardo.treni.ui.board.BoardScreen
import it.zawardo.treni.ui.results.ResultsScreen
import it.zawardo.treni.ui.search.SearchScreen
import it.zawardo.treni.service.TrainFollowService
import it.zawardo.treni.ui.theme.ZawardoTreniTheme
import it.zawardo.treni.ui.train.TrainDetailScreen
import it.zawardo.treni.ui.train.TrainNumberScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.reflect.KClass

@Serializable
object SearchRoute

@Serializable
object TrainSearchRoute

@Serializable
object BoardRoute

@Serializable
object AboutRoute

/**
 * Tabellone di una stazione precisa, aperto da una fermata del dettaglio corsa.
 *
 * E' una rotta a se' e non la scheda "Stazione": cosi' finisce nello stack e il
 * tasto indietro riporta alla corsa da cui si era partiti, invece di cambiare
 * scheda sotto le dita.
 */
@Serializable
data class StationBoardRoute(
    val rfiCode: String,
    val name: String,
)

/**
 * Le rotte type-safe di Navigation Compose serializzano i parametri per noi:
 * niente stringhe da codificare a mano, e lo stato sopravvive alla morte del processo.
 */
@Serializable
data class ResultsRoute(
    val fromId: Long,
    val fromRfi: String?,
    val fromName: String,
    val toId: Long,
    val toRfi: String?,
    val toName: String,
    val whenEpochSec: Long,
    /** Filtro "solo diretti": viaggia nella rotta perche' fa parte della ricerca. */
    val directOnly: Boolean = false,
    /**
     * Le coordinate delle due punte.
     *
     * Non sono un di piu': servono ai viaggi misti per scegliere gli hub di
     * cambio. Senza, arriverebbero a zero, la preselezione geografica
     * fallirebbe e i misti non comparirebbero mai — la ricerca da Sorrento
     * dava sempre lo stesso risultato, con o senza il flag acceso.
     */
    val fromLat: Double = 0.0,
    val fromLon: Double = 0.0,
    val toLat: Double = 0.0,
    val toLon: Double = 0.0,
)

/**
 * Come si e' arrivati a una corsa, che decide quanto c'e' da indovinare.
 *
 * Dal tabellone e dalla ricerca per numero la corsa e' gia' identificata:
 * [originCode] e [departureMillis] la indicano senza margine, e si va dritti al
 * dettaglio. Dalla ricerca per tratta invece si ha solo il numero, che due treni
 * possono condividere: li' servono [boardingRfi] e [boardingEpochSec], cioe'
 * dove e quando si sale, per capire di quale dei due si stia parlando.
 *
 * [boardingRfi] serve comunque anche a mostrare il ritardo **alla fermata
 * dell'utente** invece di quello globale, e a sapere quando smettere di seguire.
 *
 * [alightingRfi] e' l'altro capo del pezzo che si percorre. In una soluzione con
 * cambio e' la stazione dove si scende per prendere l'altro treno, ed e' la
 * fermata piu' importante di tutto l'elenco: senza, il percorso mostra venti
 * nomi e nessuno dice quale sia il tuo.
 */
@Serializable
data class TrainRoute(
    val number: String,
    val dateEpochDay: Long,
    val boardingRfi: String? = null,
    val boardingName: String? = null,
    val alightingRfi: String? = null,
    val originCode: String? = null,
    val departureMillis: Long? = null,
    val boardingEpochSec: Long? = null,
)

private data class TabItem(
    val route: Any,
    val kClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
)

class MainActivity : ComponentActivity() {

    /**
     * Treno da aprire su richiesta esterna, oggi solo dalla notifica di
     * "segui treno". Toccare quella notifica deve portare alla corsa seguita,
     * non genericamente all'app.
     */
    private val pendingTrain = MutableStateFlow<TrainRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        /*
         * Icone di sistema chiare, sempre.
         *
         * La barra in cima e' blu notte in tutti e due i temi, e di serie
         * Android sceglie il colore delle icone dal tema: in chiaro le metteva
         * nere, cioe' invisibili sul blu. Qui il fondo lo conosciamo, e lo
         * dichiariamo scuro una volta per tutte.
         */
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        consume(intent)
        setContent { ZawardoTreniTheme { TreniApp(pendingTrain) } }
    }

    /** L'activity e' singleTop: ad app gia' aperta l'Intent arriva di qui. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(intent: Intent?) {
        val number = intent?.getStringExtra(TrainFollowService.EXTRA_OPEN_TRAIN) ?: return
        val day = intent.getLongExtra(
            TrainFollowService.EXTRA_OPEN_DATE,
            LocalDate.now().toEpochDay(),
        )
        // Consumato una volta sola: senza rimozione, ogni rotazione ripeterebbe
        // la navigazione riportando l'utente sul treno mentre naviga altrove.
        intent.removeExtra(TrainFollowService.EXTRA_OPEN_TRAIN)
        pendingTrain.value = TrainRoute(number, day)
    }
}

@Composable
private fun TreniApp(pendingTrain: MutableStateFlow<TrainRoute?> = MutableStateFlow(null)) {
    val nav = rememberNavController()

    val requested by pendingTrain.collectAsState()
    LaunchedEffect(requested) {
        requested?.let {
            nav.navigate(it) { launchSingleTop = true }
            pendingTrain.value = null
        }
    }

    /*
     * Il controllo degli orari parte qui, una volta per sessione.
     *
     * Sorveglia le reti accese, quindi copre da solo i due momenti che contano:
     * l'avvio dell'app, quando il flusso emette l'insieme corrente, e
     * l'accensione di una rete dalle impostazioni, che ne emette un altro.
     */
    LaunchedEffect(Unit) { ServiceLocator.sorvegliaOrari() }

    val tabs = listOf(
        TabItem(SearchRoute, SearchRoute::class, "Tratta", Icons.Filled.Search),
        TabItem(TrainSearchRoute, TrainSearchRoute::class, "Treno", Icons.Outlined.Train),
        TabItem(BoardRoute, BoardRoute::class, "Tabellone", Icons.Outlined.DepartureBoard),
    )

    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    // La barra sparisce nelle schermate di dettaglio: lì la navigazione è "indietro".
    val showBar = tabs.any { tab -> destination?.hasRoute(tab.kClass) == true }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = destination?.hasRoute(tab.kClass) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(
                bottom = if (showBar) inner.calculateBottomPadding() else 0.dp,
            ),
        ) {
        AvvisoAggiornamentoOrari()
        NavHost(
            navController = nav,
            startDestination = SearchRoute,
        ) {
            composable<SearchRoute> {
                SearchScreen(
                    onSearch = { from, to, dateTime, directOnly ->
                        nav.navigate(
                            ResultsRoute(
                                fromId = from.locationId,
                                fromRfi = from.rfiCode,
                                fromName = from.name,
                                toId = to.locationId,
                                toRfi = to.rfiCode,
                                toName = to.name,
                                whenEpochSec = dateTime.toEpochSecond(ZoneOffset.UTC),
                                directOnly = directOnly,
                                fromLat = from.latitude,
                                fromLon = from.longitude,
                                toLat = to.latitude,
                                toLon = to.longitude,
                            )
                        )
                    },
                    onOpenAbout = { nav.navigate(AboutRoute) },
                )
            }

            composable<TrainSearchRoute> {
                TrainNumberScreen(
                    // Cercando per numero non esiste una stazione di salita:
                    // qui il monitoraggio ripiega sull'arrivo a destinazione.
                    onOpenTrain = { nav.navigate(it) },
                )
            }

            composable<BoardRoute> {
                BoardScreen(onOpenTrain = { nav.navigate(it) })
            }

            composable<StationBoardRoute> { entry ->
                val r = entry.toRoute<StationBoardRoute>()
                BoardScreen(
                    initialRfi = r.rfiCode,
                    initialName = r.name,
                    onBack = { nav.popBackStack() },
                    onOpenTrain = { nav.navigate(it) },
                )
            }

            composable<AboutRoute> {
                AboutScreen(onBack = { nav.popBackStack() })
            }

            composable<ResultsRoute> { entry ->
                val r = entry.toRoute<ResultsRoute>()
                ResultsScreen(
                    from = Station(r.fromRfi, r.fromId, r.fromName, r.fromLat, r.fromLon),
                    to = Station(r.toRfi, r.toId, r.toName, r.toLat, r.toLon),
                    departure = LocalDateTime.ofEpochSecond(r.whenEpochSec, 0, ZoneOffset.UTC),
                    directOnly = r.directOnly,
                    onBack = { nav.popBackStack() },
                    onOpenTrain = { nav.navigate(it) },
                )
            }

            composable<TrainRoute> { entry ->
                val r = entry.toRoute<TrainRoute>()
                TrainDetailScreen(
                    trainNumber = r.number,
                    date = LocalDate.ofEpochDay(r.dateEpochDay),
                    boardingRfi = r.boardingRfi,
                    boardingName = r.boardingName,
                    alightingRfi = r.alightingRfi,
                    originCode = r.originCode,
                    departureMillis = r.departureMillis,
                    boardingAt = r.boardingEpochSec?.let {
                        LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC)
                    },
                    onBack = { nav.popBackStack() },
                    onOpenStation = { rfi, name -> nav.navigate(StationBoardRoute(rfi, name)) },
                )
            }
        }
        }
    }
}

/**
 * L'avviso che un orario si sta scaricando, con dentro il nome della rete.
 *
 * Non e' un vezzo. Aggiornare l'orario ARST vuol dire tirare giu' 19,7 MB di
 * archivio e ricostruirlo: farlo di nascosto, sulla connessione dati di
 * qualcun altro, e' esattamente il genere di cosa che un'app non deve
 * permettersi. Chi sta guardando lo schermo deve poter vedere che sta
 * succedendo e per quale rete — anche solo per sapere a cosa attribuire il
 * traffico.
 *
 * Compare solo mentre il lavoro e' in corso e sparisce da solo: non serve
 * chiuderlo, e non c'e' niente da fare oltre ad aspettare. Un aggiornamento
 * fallito non produce allarmi, perche' resta valido l'orario di prima.
 */
@Composable
private fun AvvisoAggiornamentoOrari() {
    val stato by ServiceLocator.aggiornamentoOrari.stato.collectAsState()
    val inCorso = stato as? AggiornamentoOrari.Stato.InCorso ?: return

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "Aggiorno l'orario di ${inCorso.sorgente.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Scarico il file degli orari e lo preparo. Puoi continuare a usare l'app.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Cambio scheda con stato conservato: tornare su "Tratta" non deve azzerare
 * quello che era stato digitato, e non deve impilare copie della stessa schermata.
 */
private fun NavHostController.switchTab(route: Any) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
