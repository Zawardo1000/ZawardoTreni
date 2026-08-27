package it.zawardo.treni

import android.content.Context
import it.zawardo.treni.data.local.SettingsStore
import it.zawardo.treni.data.local.TreniDatabase
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.SearchStore
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainMemoryStore
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.Station
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DI manuale. Il grafo e' piccolo e stabile: Hilt aggiungerebbe un processore di
 * annotazioni e tempi di build senza risolvere un problema che abbiamo.
 */
object ServiceLocator {

    lateinit var settings: SettingsStore
        private set
    lateinit var searchStore: SearchStore
        private set
    lateinit var trainMemory: TrainMemoryStore
        private set

    /**
     * Stazione di partenza scelta nella scheda Tratta, condivisa con il tabellone.
     *
     * Vive in memoria e non su disco: e' un ponte fra due schede aperte nella
     * stessa sessione, non una preferenza da ricordare fra un avvio e l'altro.
     */
    val currentDeparture = MutableStateFlow<Station?>(null)

    val stationRepository: StationRepository by lazy { StationRepository(NetworkModule.lefrecceApi) }

    /**
     * Copre il servizio suburbano lombardo, che le altre due sorgenti ignorano,
     * ed e' l'unica a fornire gli avvisi di lavori e sospensione.
     */
    val trenordRepository: TrenordRepository by lazy {
        TrenordRepository(NetworkModule.trenordApi, NetworkModule.json)
    }

    val journeyRepository: JourneyRepository by lazy {
        JourneyRepository(NetworkModule.lefrecceApi, trenordRepository)
    }
    val trainStatusRepository: TrainStatusRepository by lazy {
        TrainStatusRepository(NetworkModule.viaggiaTrenoApi)
    }

    fun init(context: Context) {
        val db = TreniDatabase.get(context)
        settings = SettingsStore(context.applicationContext)
        searchStore = SearchStore(db.searchHistoryDao(), db.savedSearchDao(), db.stationDao())
        trainMemory = TrainMemoryStore(db.favoriteTrainDao(), db.recentTrainDao())
    }
}
