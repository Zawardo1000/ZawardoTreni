package it.zawardo.treni.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.zawardo.treni.domain.model.Station
import it.zawardo.treni.ui.about.AboutScreen
import it.zawardo.treni.ui.results.ResultsScreen
import it.zawardo.treni.ui.search.SearchScreen
import it.zawardo.treni.ui.theme.ZawardoTreniTheme
import it.zawardo.treni.ui.train.TrainDetailScreen
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@Serializable
object SearchRoute

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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ZawardoTreniTheme { TreniNavHost() } }
    }
}

@Composable
private fun TreniNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = SearchRoute) {

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
