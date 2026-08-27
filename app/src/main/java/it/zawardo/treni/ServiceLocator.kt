package it.zawardo.treni

import android.content.Context
import it.zawardo.treni.data.local.SettingsStore
import it.zawardo.treni.data.local.TreniDatabase
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.SearchStore
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainStatusRepository

/**
 * DI manuale. Il grafo e' piccolo e stabile: Hilt aggiungerebbe un processore di
 * annotazioni e tempi di build senza risolvere un problema che abbiamo.
 */
object ServiceLocator {

    lateinit var settings: SettingsStore
        private set
    lateinit var searchStore: SearchStore
        private set

    val stationRepository: StationRepository by lazy { StationRepository(NetworkModule.lefrecceApi) }
    val journeyRepository: JourneyRepository by lazy { JourneyRepository(NetworkModule.lefrecceApi) }
    val trainStatusRepository: TrainStatusRepository by lazy {
        TrainStatusRepository(NetworkModule.viaggiaTrenoApi)
    }

    fun init(context: Context) {
        val db = TreniDatabase.get(context)
        settings = SettingsStore(context.applicationContext)
        searchStore = SearchStore(db.searchHistoryDao(), db.savedSearchDao(), db.stationDao())
    }
}
