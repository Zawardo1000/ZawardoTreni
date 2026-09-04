package it.zawardo.treni.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import it.zawardo.treni.R
import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Segui treno: sorveglia la partenza dalla **tua** stazione e avvisa quando lo
 * scarto cambia di oltre 3 minuti.
 *
 * Lo scopo e' prendere il treno, non accompagnarlo: il monitoraggio si chiude
 * appena il treno lascia la stazione da cui sali. Da li' in poi non c'e' piu'
 * niente su cui agire, e tenerlo acceso sarebbe solo consumo.
 *
 * **Perche' un foreground service.** L'intervallo periodico minimo di
 * WorkManager e' 15 minuti, imposto dal sistema; JobScheduler e AlarmManager in
 * Doze non scendono sotto i ~9 minuti. Per stare al minuto resta solo questa
 * strada, e Android in cambio pretende una notifica permanente.
 *
 * **Perche' il wake lock.** Un foreground service tiene vivo il processo ma non
 * tiene sveglia la CPU: `delay()` non e' una sveglia. A schermo spento il giro
 * salta e riprende solo riaccendendo, che e' precisamente il modo in cui questa
 * funzione risulta inutile.
 *
 * **Come si contiene il consumo.** Non riducendo l'affidabilita', ma diradando
 * le chiamate quando non servono: il costo dominante e' la radio che si accende,
 * non la CPU inattiva. Un'ora prima della partenza il ritardo non cambia di
 * minuto in minuto, negli ultimi dieci si'.
 */
class TrainFollowService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private var pollJob: Job? = null

    /** Scarto dell'ultimo avviso emesso, non dell'ultimo rilevamento. */
    private var lastAlertedDelay: Int? = null

    /** La soppressione si annuncia una volta sola, non a ogni giro. */
    private var alertedCancellation = false

    /**
     * Il binario dell'ultimo avviso sulla fermata di salita.
     *
     * Serve a distinguere le due cose che succedono al binario: la **prima
     * assegnazione**, che nelle stazioni grandi arriva un quarto d'ora prima
     * della partenza, e il **cambio**, che arriva quando sei gia' sul
     * marciapiede sbagliato. La prima si annuncia perche' e' quello che stavi
     * aspettando, il secondo perche' altrimenti perdi il treno.
     */
    private var lastAlertedPlatform: String? = null

    /**
     * Distingue "binario non ancora letto" da "binario che non c'e'".
     *
     * Senza, il primo giro senza binario e la prima assegnazione sarebbero
     * indistinguibili, e l'avviso che serve davvero — quello che dice da dove
     * parti — non partirebbe mai.
     */
    private var platformRead = false

    private var followedNumber: String? = null
    private var followedDate: LocalDate? = null
    private var boardingName: String? = null

    /** Ora dell'ultimo giro riuscito: e' cio' che dice se il servizio e' vivo. */
    private var lastPollAt: LocalDateTime? = null

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFollowing()
            return START_NOT_STICKY
        }

        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return START_NOT_STICKY
        val date = LocalDate.ofEpochDay(
            intent.getLongExtra(EXTRA_EPOCH_DAY, LocalDate.now().toEpochDay()),
        )
        val boardingRfi = intent.getStringExtra(EXTRA_BOARDING_RFI)

        // Riavvio sulla stessa corsa: non azzerare lo storico degli avvisi,
        // altrimenti la soglia dei 3 minuti riparte da capo.
        if (followedNumber == number && followedDate == date && pollJob?.isActive == true) {
            return START_REDELIVER_INTENT
        }

        followedNumber = number
        followedDate = date
        boardingName = intent.getStringExtra(EXTRA_BOARDING_NAME)
        lastAlertedDelay = null
        alertedCancellation = false
        lastAlertedPlatform = null
        platformRead = false
        lastPollAt = null

        startForegroundSafely(buildOngoing(number, "Ricerca dello stato in corso…", null))
        _followed.value = number
        acquireWakeLock()
        startPolling(number, date, boardingRfi)

        // REDELIVER e non STICKY: con STICKY il sistema puo' riavviare il servizio
        // con Intent nullo, e senza numero treno non saprebbe cosa seguire.
        return START_REDELIVER_INTENT
    }

    /**
     * Android 15 concede ai foreground service `dataSync` 6 ore ogni 24, poi
     * chiama questo metodo. Chi non lo implementa non viene fermato: viene
     * terminato con un ANR.
     *
     * Con la chiusura alla partenza non dovrebbe mai scattare, ma una rete che
     * non risponde per mezza giornata basterebbe ad arrivarci.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        notifyStoppedByBudget()
        stopFollowing()
    }

    @Deprecated("Sostituito da onTimeout(startId, fgsType) in Android 15")
    override fun onTimeout(startId: Int) {
        notifyStoppedByBudget()
        stopFollowing()
    }

    // ------------------------------------------------------------- polling

    private fun startPolling(number: String, date: LocalDate, boardingRfi: String?) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val trains = ServiceLocator.trainStatusRepository

            /*
             * Letto una volta sola: e' un interruttore, non un dato che cambia
             * durante la corsa, e questo giro deve costare il meno possibile.
             */
            val trenordAcceso = DataSource.TRENORD in runCatching {
                ServiceLocator.settings.enabledSources.first()
            }.getOrDefault(DataSource.defaultEnabled)

            /*
             * La corsa si risolve UNA volta sola, all'avvio. Risolverla a ogni
             * giro costava una chiamata in piu' al minuto, ma soprattutto era
             * fragile: lo stesso numero puo' avere piu' corse, e la scelta poteva
             * cambiare spostando la notifica su un altro treno senza preavviso.
             */
            val ref = runCatching { trains.resolveFor(number, date) }.getOrNull()

            while (isActive) {
                val status = runCatching {
                    val letto = if (ref != null) trains.status(ref) else trains.statusByNumber(number, date)
                    // Il binario e' la meta' del motivo per cui si segue un
                    // treno, e ViaggiaTreno da solo spesso non ce l'ha.
                    letto?.let { if (trenordAcceso) trains.completaBinari(it, date) else it }
                }.getOrNull()

                var boarding: Stop? = null

                if (status != null) {
                    lastPollAt = LocalDateTime.now()
                    boarding = boardingRfi?.let { code ->
                        status.stops.firstOrNull { it.stationCode == code }
                    }

                    updateOngoing(number, status, boarding)
                    maybeAlert(number, status, boarding)

                    if (shouldStop(status, boarding)) {
                        notifyFinished(number, status, boarding)
                        stopFollowing()
                        return@launch
                    }
                }

                delay(pollIntervalMs(boarding))
            }
        }
    }

    /**
     * Il monitoraggio finisce quando il treno lascia la stazione da cui sali:
     * da quel momento non c'e' piu' nulla su cui agire.
     *
     * Senza stazione di salita (arrivo dalla ricerca per numero) si ripiega
     * sull'arrivo a destinazione.
     */
    private fun shouldStop(status: TrainStatus, boarding: Stop?): Boolean = when {
        boarding != null -> boarding.status == StopStatus.DONE ||
            boarding.status == StopStatus.CANCELLED ||
            boarding.actualDeparture != null
        else -> status.state == TrainState.ARRIVED
    }

    /**
     * Frequenza adattiva. Il consumo dipende soprattutto da quante volte si
     * accende la radio: lontano dalla partenza il ritardo non cambia di minuto
     * in minuto, e interrogare ogni 60 secondi sarebbe spreco puro.
     */
    private fun pollIntervalMs(boarding: Stop?): Long {
        val departure = boarding?.effectiveDeparture ?: boarding?.effectiveArrival
            ?: return POLL_NEAR_MS
        val minutes = Duration.between(LocalDateTime.now(), departure).toMinutes()
        return when {
            minutes > 30 -> POLL_FAR_MS
            minutes > 10 -> POLL_MID_MS
            else -> POLL_NEAR_MS
        }
    }

    // -------------------------------------------------------- notifiche

    private fun updateOngoing(number: String, status: TrainStatus, boarding: Stop?) {
        /*
         * Due orari diversi, e servono entrambi:
         *  - dove e quando il treno e' stato rilevato da RFI;
         *  - quando NOI abbiamo controllato l'ultima volta.
         * Il secondo e' l'unico modo per accorgersi che il polling si e' fermato.
         */
        val detection = status.lastDetectionStation?.let { st ->
            val at = status.lastDetectionTime?.format(HHMM)
            if (at != null) "$st alle $at" else st
        }
        val checked = lastPollAt?.format(HHMM)?.let { "aggiornato $it" }
        val sub = listOfNotNull(detection, checked).joinToString(" · ").ifBlank { null }

        NotificationManagerCompat.from(this).let { nm ->
            if (hasNotificationPermission()) {
                nm.notify(
                    NOTIF_ONGOING_ID,
                    buildOngoing(status.label.ifBlank { number }, describe(status, boarding), sub),
                )
            }
        }
    }

    /**
     * Il testo principale parla della **tua** partenza quando la conosciamo: il
     * ritardo globale della corsa e' un'informazione peggiore, perche' quello
     * che ti riguarda e' a che ora passa da te.
     */
    private fun describe(status: TrainStatus, boarding: Stop?): String {
        stateWord(status.state)?.let { return it }

        if (boarding != null) {
            val time = boarding.effectiveDeparture?.format(HHMM)
                ?: boarding.scheduledDeparture?.format(HHMM)
            val delay = boarding.departureDelayMinutes
            val where = boardingName ?: boarding.stationName
            val suffix = when {
                delay > 0 -> " (+$delay)"
                delay < 0 -> " (${delay})"
                else -> ""
            }
            /*
             * Il binario nel testo fisso e non solo nell'avviso: chi ha
             * silenziato il telefono, o e' arrivato in stazione dopo, trova
             * comunque nella notifica permanente la cosa che deve sapere.
             */
            val binario = boarding.platform?.let {
                if (boarding.platformChanged) " · bin. $it (era ${boarding.scheduledPlatform})"
                else " · bin. $it"
            }.orEmpty()

            if (time != null) return "Parte da $where alle $time$suffix$binario"
        }

        return when {
            status.delayMinutes > 0 -> "Ritardo ${status.delayMinutes} min"
            status.delayMinutes < 0 -> "In anticipo di ${-status.delayMinutes} min"
            else -> "In orario"
        }
    }

    private fun stateWord(state: TrainState): String? = when (state) {
        TrainState.CANCELLED -> "Soppresso"
        TrainState.PARTIALLY_CANCELLED -> "Soppresso in parte"
        TrainState.DIVERTED -> "Percorso variato"
        TrainState.ARRIVED -> "Arrivato"
        else -> null
    }

    /**
     * Avvisa solo se lo scarto rispetto all'**ultimo avviso** supera la soglia.
     * Confrontarlo con l'ultimo rilevamento farebbe suonare il telefono a ogni
     * oscillazione di un minuto.
     */
    private fun maybeAlert(number: String, status: TrainStatus, boarding: Stop?) {
        // Quando si conosce la fermata di salita e' il suo scarto a contare.
        val current = boarding?.departureDelayMinutes ?: status.delayMinutes

        if (status.state == TrainState.CANCELLED && !alertedCancellation) {
            alertedCancellation = true
            lastAlertedDelay = current
            emitAlert(number, status, "Il treno è stato soppresso.")
            return
        }

        /*
         * Il binario prima del ritardo. Se cambiano insieme, quello che ti fa
         * alzare e camminare e' il binario; il ritardo resta da dire e lo dira'
         * il giro dopo, perche' uscendo di qui `lastAlertedDelay` non e' stato
         * aggiornato e lo scarto risultera' ancora nuovo.
         */
        if (alertPlatform(number, status, boarding)) return

        val previous = lastAlertedDelay
        if (previous == null) {
            // Prima lettura: si stabilisce il riferimento, non si suona.
            lastAlertedDelay = current
            return
        }
        if (kotlin.math.abs(current - previous) <= DELAY_THRESHOLD_MIN) return

        lastAlertedDelay = current
        val where = boardingName ?: boarding?.stationName
        val at = boarding?.effectiveDeparture?.format(HHMM)
        val tail = if (where != null && at != null) " Partenza da $where prevista alle $at." else ""
        emitAlert(
            number,
            status,
            "Da ${describeDelay(previous)} a ${describeDelay(current)}.$tail",
        )
    }

    /**
     * Avvisa quando il binario da cui parti compare o cambia. Restituisce vero
     * se ha suonato.
     *
     * Sono due eventi, non uno. Nelle stazioni grandi il binario **non esiste**
     * fino a un quarto d'ora dalla partenza: il primo avviso e' quello che stavi
     * aspettando, ed e' il motivo per cui sei rimasto seduto invece di piantarti
     * sotto il tabellone. Il secondo, il cambio, e' quello che ti evita di
     * correre da un capo all'altro dell'atrio.
     *
     * Solo la fermata di salita: il binario di una stazione dove non passi non
     * e' un tuo problema. E solo finche' non sei partito, perche' dopo il
     * monitoraggio si chiude comunque.
     */
    private fun alertPlatform(number: String, status: TrainStatus, boarding: Stop?): Boolean {
        // Su una corsa soppressa il binario non e' piu' una notizia.
        if (status.state == TrainState.CANCELLED) return false
        val stop = boarding ?: return false
        if (stop.status != StopStatus.FUTURE) return false

        val current = stop.platform
        if (!platformRead) {
            // Prima lettura: come per il ritardo, si prende il riferimento e
            // non si suona. Anche quando e' null, ed e' il caso interessante:
            // e' da li' che si riconoscera' la prima assegnazione.
            platformRead = true
            lastAlertedPlatform = current
            return false
        }
        if (current == null || current == lastAlertedPlatform) return false

        val previous = lastAlertedPlatform
        lastAlertedPlatform = current
        val where = boardingName ?: stop.stationName
        emitAlert(
            number,
            status,
            if (previous == null) {
                "Binario $current a $where."
            } else {
                "Cambio binario a $where: dal $previous al $current."
            },
        )
        return true
    }

    private fun describeDelay(minutes: Int): String = when {
        minutes > 0 -> "$minutes min di ritardo"
        minutes < 0 -> "${-minutes} min di anticipo"
        else -> "orario regolare"
    }

    private fun notifyFinished(number: String, status: TrainStatus, boarding: Stop?) {
        val where = boardingName ?: boarding?.stationName
        val body = when {
            boarding?.status == StopStatus.CANCELLED && where != null ->
                "La fermata di $where è stata soppressa. Monitoraggio terminato."
            where != null -> {
                val at = boarding?.actualDeparture?.format(HHMM)
                if (at != null) "Partito da $where alle $at. Monitoraggio terminato."
                else "Partito da $where. Monitoraggio terminato."
            }
            else -> "Arrivato a ${status.destination.orEmpty()}. Monitoraggio terminato."
        }
        emitAlert(number, status, body)
    }

    private fun notifyStoppedByBudget() {
        if (!hasNotificationPermission()) return
        val body = "Android limita a 6 ore al giorno il monitoraggio in background. " +
            "Riapri l'app e tocca di nuovo la campanella per riprendere."
        NotificationManagerCompat.from(this).notify(
            NOTIF_ALERT_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle("Monitoraggio interrotto")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun emitAlert(number: String, status: TrainStatus, body: String) {
        if (!hasNotificationPermission()) return
        NotificationManagerCompat.from(this).notify(
            NOTIF_ALERT_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(status.label.ifBlank { "Treno $number" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun buildOngoing(title: String, text: String, sub: String?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(
                0,
                "Smetti di seguire",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, TrainFollowService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    /**
     * Toccare la notifica apre **quel** treno, non genericamente l'app.
     * Gli extra viaggiano nell'Intent e MainActivity li trasforma in navigazione.
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TRAIN, followedNumber)
            .putExtra(EXTRA_OPEN_DATE, followedDate?.toEpochDay() ?: LocalDate.now().toEpochDay()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ------------------------------------------------------------- ciclo di vita

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ONGOING_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Tetto di guardia: se qualcosa andasse storto il lock non resta
            // appeso a consumare batteria per sempre.
            acquire(MAX_FOLLOW_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun stopFollowing() {
        pollJob?.cancel()
        releaseWakeLock()
        followedNumber = null
        followedDate = null
        boardingName = null
        _followed.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        _followed.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "it.zawardo.treni.FOLLOW_STOP"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_EPOCH_DAY = "epochDay"
        private const val EXTRA_BOARDING_RFI = "boardingRfi"
        private const val EXTRA_BOARDING_NAME = "boardingName"

        /** Letti da MainActivity per aprire direttamente la corsa seguita. */
        const val EXTRA_OPEN_TRAIN = "open_train_number"
        const val EXTRA_OPEN_DATE = "open_train_epoch_day"

        private const val CHANNEL_ONGOING = "follow_ongoing"
        private const val CHANNEL_ALERTS = "follow_alerts"
        private const val NOTIF_ONGOING_ID = 1001
        private const val NOTIF_ALERT_ID = 1002

        /** Ultimi dieci minuti: qui cambiano binario e ritardo, si guarda spesso. */
        private const val POLL_NEAR_MS = 60_000L

        /** Fra dieci e trenta minuti: cambia qualcosa, ma non ogni minuto. */
        private const val POLL_MID_MS = 120_000L

        /** Oltre la mezz'ora: il dato e' quasi fermo, la radio puo' riposare. */
        private const val POLL_FAR_MS = 300_000L

        /** Si avvisa oltre i 3 minuti di scarto, non a ogni sussulto. */
        private const val DELAY_THRESHOLD_MIN = 3

        private const val WAKE_LOCK_TAG = "ZawardoTreni:segui-treno"

        /** Limite di guardia del wake lock: nessuna attesa dura otto ore. */
        private const val MAX_FOLLOW_MS = 8 * 60 * 60 * 1000L

        private val _followed = MutableStateFlow<String?>(null)
        val followed: StateFlow<String?> = _followed.asStateFlow()

        fun start(
            context: Context,
            trainNumber: String,
            date: LocalDate,
            boardingRfi: String? = null,
            boardingName: String? = null,
        ) {
            val intent = Intent(context, TrainFollowService::class.java)
                .putExtra(EXTRA_NUMBER, trainNumber)
                .putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
                .putExtra(EXTRA_BOARDING_RFI, boardingRfi)
                .putExtra(EXTRA_BOARDING_NAME, boardingName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrainFollowService::class.java).setAction(ACTION_STOP),
            )
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING,
                    "Treno seguito",
                    // Silenziosa: e' un cruscotto sempre presente, non un avviso.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Notifica permanente con la tua partenza aggiornata" },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Avvisi ritardo",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Avvisa quando lo scarto cambia di oltre 3 minuti" },
            )
        }
    }
}
