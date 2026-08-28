package it.zawardo.treni

import android.content.Context
import it.zawardo.treni.data.local.SettingsStore
import it.zawardo.treni.data.local.TreniDatabase
import it.zawardo.treni.data.remote.NetworkModule
import it.zawardo.treni.data.remote.gtfs.AggiornamentoOrari
import it.zawardo.treni.data.repository.ArstRepository
import it.zawardo.treni.data.repository.SvizzeraRepository
import it.zawardo.treni.data.repository.EavRepository
import it.zawardo.treni.data.repository.FnbRepository
import it.zawardo.treni.data.repository.FonteStazioniLocale
import it.zawardo.treni.data.repository.ItaloRepository
import it.zawardo.treni.data.repository.JourneyRepository
import it.zawardo.treni.data.repository.ViaggiMistiRepository
import it.zawardo.treni.data.repository.SearchStore
import it.zawardo.treni.data.repository.StationFavoritesStore
import it.zawardo.treni.data.repository.StationRepository
import it.zawardo.treni.data.repository.TrainMemoryStore
import it.zawardo.treni.data.repository.TrenordRepository
import it.zawardo.treni.data.repository.TrainStatusRepository
import it.zawardo.treni.domain.model.DataSource
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
    lateinit var stationFavorites: StationFavoritesStore
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

    val italoRepository: ItaloRepository by lazy { ItaloRepository(NetworkModule.italoApi) }

    val eavRepository: EavRepository by lazy { EavRepository(NetworkModule.eavApi, cartellaOrari) }

    /**
     * Le due reti regionali fuori da RFI che pubblicano ritardo e binario.
     *
     * Portano dentro l'app fermate che prima non esistevano affatto: Ruvo,
     * Corato, Andria e l'aeroporto di Bari da una parte, la Val Vigezzo
     * dall'altra. Nessuna delle due ha un orario da interrogare, solo il
     * tabellone, ed e' il motivo per cui compaiono qui e non in
     * [journeyRepository].
     */
    val fnbRepository: FnbRepository by lazy { FnbRepository(NetworkModule.fnbApi) }

    val svizzeraRepository: SvizzeraRepository by lazy {
        SvizzeraRepository(NetworkModule.svizzeraApi)
    }

    /**
     * ARST: le ferrovie sarde, l'unica sorgente **senza tempo reale**.
     *
     * Non interroga niente: legge l'orario imbarcato, o quello piu' recente che
     * [aggiornamentoOrari] ha depositato. E' anche l'unica che sappia rispondere
     * per i giorni futuri, perche' e' l'unica ad avere un orario invece di un
     * tabellone.
     */
    val arstRepository: ArstRepository by lazy { ArstRepository(cartellaOrari) }

    /**
     * Il registro delle reti con stazioni proprie, per interrogarle senza sapere
     * quali siano.
     *
     * Chi propone i suggerimenti scorre questo, non un `when` scritto a mano:
     * aggiungere una rete fuori-RFI significa segnare `stazioniProprie = true`
     * sul suo [DataSource] e aggiungere qui la riga che la collega al suo
     * repository — e i ViewModel non cambiano. Le chiavi sono esattamente le
     * fonti con `stazioniProprie`; un controllo all'avvio lo verifica.
     */
    val fontiStazioniLocali: Map<DataSource, FonteStazioniLocale> by lazy {
        mapOf(
            DataSource.EAV to eavRepository,
            DataSource.FNB to fnbRepository,
            DataSource.SVIZZERA to svizzeraRepository,
            DataSource.ARST to arstRepository,
        ).also { registro ->
            // Il flag sull'enum e questo registro devono coincidere: se domani si
            // segna una rete con stazioni proprie ma si scorda di collegarla qui,
            // meglio saperlo subito che vederla sparire dai suggerimenti in silenzio.
            val attese = DataSource.entries.filterTo(HashSet()) { it.stazioniProprie }
            require(registro.keys == attese) {
                "Registro fonti locali disallineato: mancano ${attese - registro.keys}, di troppo ${registro.keys - attese}"
            }
        }
    }

    /**
     * Tiene aggiornati gli orari imbarcati di EAV e ARST.
     *
     * Vive qui e non dentro le repository perche' riguarda tutte e due, e perche'
     * quando parte deve poterlo dire a chi sta guardando lo schermo: venti
     * megabyte scaricati di nascosto sulla rete dati di qualcun altro non sono
     * un dettaglio implementativo.
     */
    val aggiornamentoOrari: AggiornamentoOrari by lazy {
        AggiornamentoOrari(NetworkModule.orariClient, cartellaOrari)
    }

    val journeyRepository: JourneyRepository by lazy {
        JourneyRepository(NetworkModule.lefrecceApi, trenordRepository)
    }

    /** I viaggi misti multi-operatore (beta): feeder fuori-RFI piu' alta velocita' Italo. */
    val viaggiMistiRepository: ViaggiMistiRepository by lazy {
        ViaggiMistiRepository(eavRepository, italoRepository)
    }
    val trainStatusRepository: TrainStatusRepository by lazy {
        TrainStatusRepository(NetworkModule.viaggiaTrenoApi, trenordRepository, italoRepository)
    }

    /**
     * Dove finiscono gli orari riscaricati.
     *
     * `filesDir` e non `cacheDir`: un orario ricostruito da venti megabyte non
     * e' roba da far buttare al sistema quando lo spazio scarseggia, e
     * riscaricarlo costerebbe piu' di quanto valga tenerlo.
     */
    private lateinit var cartellaOrari: java.io.File

    fun init(context: Context) {
        val db = TreniDatabase.get(context)
        val app = context.applicationContext
        settings = SettingsStore(app)
        searchStore = SearchStore(db.searchHistoryDao(), db.savedSearchDao(), db.stationDao())
        trainMemory = TrainMemoryStore(db.favoriteTrainDao(), db.recentTrainDao())
        stationFavorites = StationFavoritesStore(db.favoriteStationDao())
        cartellaOrari = java.io.File(app.filesDir, "orari")
    }

    /**
     * Sorveglia le reti accese e tiene aggiornati i loro orari.
     *
     * Una collezione sola copre i due momenti richiesti, perche' sono lo stesso
     * evento visto da due parti: all'avvio il flusso emette l'insieme corrente,
     * e ogni accensione dalle impostazioni ne emette un altro. Chi e' gia' stato
     * guardato viene saltato dal coordinatore, quindi spegnere una rete non
     * scarica niente.
     *
     * Si rilegge l'orario ARST dopo ogni giro: e' un file da sei KB, e non
     * farlo significherebbe tenere in memoria quello vecchio fino al riavvio,
     * cioe' scaricare un aggiornamento e poi non usarlo.
     */
    suspend fun sorvegliaOrari() {
        settings.enabledSources.collect { accese ->
            aggiornamentoOrari.controlla(accese)
            arstRepository.ricarica()
        }
    }
}
