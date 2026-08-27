package it.zawardo.treni.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DepartureBoard
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.ui.about.AboutScreen
import it.zawardo.treni.ui.board.BoardScreen
import it.zawardo.treni.ui.results.ResultsScreen
import it.zawardo.treni.ui.search.SearchScreen
import it.zawardo.treni.ui.theme.ZawardoTreniTheme
import it.zawardo.treni.ui.train.TrainDetailScreen
import it.zawardo.treni.ui.train.TrainNumberScreen
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
)

@Serializable
data class TrainRoute(
    val number: String,
    val dateEpochDay: Long,
)

private data class TabItem(
    val route: Any,
    val kClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ZawardoTreniTheme { TreniApp() } }
    }
}

@Composable
private fun TreniApp() {
    val nav = rememberNavController()

    val tabs = listOf(
        TabItem(SearchRoute, SearchRoute::class, "Tratta", Icons.Filled.Search),
        TabItem(TrainSearchRoute, TrainSearchRoute::class, "Treno", Icons.Outlined.Train),
        TabItem(BoardRoute, BoardRoute::class, "Stazione", Icons.Outlined.DepartureBoard),
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
        NavHost(
            navController = nav,
            startDestination = SearchRoute,
            modifier = Modifier.padding(
                bottom = if (showBar) inner.calculateBottomPadding() else 0.dp,
            ),
        ) {
            composable<SearchRoute> {
                SearchScreen(
                    onSearch = { from, to, dateTime ->
                        nav.navigate(
                            ResultsRoute(
                                fromId = from.locationId,
                                fromRfi = from.rfiCode,
                                fromName = from.name,
                                toId = to.locationId,
                                toRfi = to.rfiCode,
                                toName = to.name,
                                whenEpochSec = dateTime.toEpochSecond(ZoneOffset.UTC),
                            )
                        )
                    },
                    onOpenAbout = { nav.navigate(AboutRoute) },
                )
            }

            composable<TrainSearchRoute> {
                TrainNumberScreen(
                    onOpenTrain = { number, date -> nav.navigate(TrainRoute(number, date.toEpochDay())) },
                )
            }

            composable<BoardRoute> {
                BoardScreen(
                    onOpenTrain = { number, date -> nav.navigate(TrainRoute(number, date.toEpochDay())) },
                )
            }

            composable<AboutRoute> {
                AboutScreen(onBack = { nav.popBackStack() })
            }

            composable<ResultsRoute> { entry ->
                val r = entry.toRoute<ResultsRoute>()
                ResultsScreen(
                    from = Station(r.fromRfi, r.fromId, r.fromName),
                    to = Station(r.toRfi, r.toId, r.toName),
                    departure = LocalDateTime.ofEpochSecond(r.whenEpochSec, 0, ZoneOffset.UTC),
                    onBack = { nav.popBackStack() },
                    onOpenTrain = { number, date ->
                        nav.navigate(TrainRoute(number, date.toEpochDay()))
                    },
                )
            }

            composable<TrainRoute> { entry ->
                val r = entry.toRoute<TrainRoute>()
                TrainDetailScreen(
                    trainNumber = r.number,
                    date = LocalDate.ofEpochDay(r.dateEpochDay),
                    onBack = { nav.popBackStack() },
                )
            }
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
