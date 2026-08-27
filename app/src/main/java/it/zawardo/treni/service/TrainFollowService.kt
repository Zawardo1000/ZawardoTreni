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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import it.zawardo.treni.R
import it.zawardo.treni.ServiceLocator
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Segui treno: aggiorna il ritardo ogni 60 secondi e avvisa quando cambia
 * di piu' di 3 minuti.
 *
 * Perche' un foreground service e non WorkManager: l'intervallo periodico minimo
 * di WorkManager e' 15 minuti, imposto dal sistema. Un polling a 60 secondi si
 * ottiene solo cosi', e Android in cambio pretende una notifica permanente.
 *
 * Il tipo dichiarato e' `dataSync`: su Android 15 ha un tetto di 6 ore al giorno,
 * sufficiente per un viaggio, e a differenza di `specialUse` non e' a rischio di
 * rigetto in review sul Play Store.
 */
class TrainFollowService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private var pollJob: Job? = null

    /** Ritardo dell'ultimo avviso emesso, non dell'ultimo rilevamento. */
    private var lastAlertedDelay: Int? = null

    /** La soppressione si annuncia una volta sola, non a ogni giro di polling. */
    private var alertedCancellation = false

    /** Corsa seguita: serve a costruire l'Intent che riapre la sua schermata. */
    private var followedNumber: String? = null
    private var followedDate: LocalDate? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFollowing()
                return START_NOT_STICKY
            }
        }

        val number = intent?.getStringExtra(EXTRA_NUMBER) ?: return START_NOT_STICKY
        val epochDay = intent.getLongExtra(EXTRA_EPOCH_DAY, LocalDate.now().toEpochDay())
        val date = LocalDate.ofEpochDay(epochDay)

        followedNumber = number
        followedDate = date
        startForegroundSafely(buildOngoing(number, "Ricerca dello stato in corso…", null))
        _followed.value = number
        lastAlertedDelay = null
        alertedCancellation = false
        startPolling(number, date)
        return START_STICKY
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompatStart.start(this, notification)
        } else {
            startForeground(NOTIF_ONGOING_ID, notification)
        }
    }

    private fun startPolling(number: String, date: LocalDate) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val trains = ServiceLocator.trainStatusRepository

            /*
             * La corsa si risolve UNA volta sola, all'avvio.
             *
             * Risolverla a ogni giro costava una chiamata di rete in piu' al minuto,
             * ma soprattutto era fragile: lo stesso numero puo' corrispondere a piu'
             * corse, e la scelta poteva cambiare fra un giro e l'altro spostando la
             * notifica su un altro treno senza preavviso.
             */
            val ref = runCatching { trains.resolveFor(number, date) }.getOrNull()

            while (isActive) {
                val status = runCatching {
                    if (ref != null) trains.status(ref) else trains.statusByNumber(number, date)
                }.getOrNull()

                if (status != null) {
                    updateOngoing(number, status)
                    maybeAlert(number, status)

                    // Treno arrivato: non c'e' piu' niente da seguire, il servizio si spegne
                    // da solo invece di restare acceso a consumare batteria.
                    if (status.state == TrainState.ARRIVED) {
                        notifyArrived(number, status)
                        stopFollowing()
                        return@launch
                    }
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun updateOngoing(number: String, status: TrainStatus) {
        val text = describe(status)
        val sub = status.lastDetectionStation?.let { st ->
            // L'ora rende evidente se l'aggiornamento si e' fermato.
            val at = status.lastDetectionTime?.format(HHMM)
            if (at != null) "Ultimo rilevamento: $st alle $at" else "Ultimo rilevamento: $st"
        }
        NotificationManagerCompat.from(this).also { nm ->
            if (hasNotificationPermission()) {
                nm.notify(NOTIF_ONGOING_ID, buildOngoing(status.label.ifBlank { number }, text, sub))
            }
        }
    }

    /**
     * Avvisa solo se lo scarto rispetto all'**ultimo avviso** supera la soglia.
     * Confrontarlo con l'ultimo rilevamento farebbe suonare il telefono a ogni
     * oscillazione di un minuto.
     */
    private fun maybeAlert(number: String, status: TrainStatus) {
        val previous = lastAlertedDelay
        val current = status.delayMinutes
        val cancelled = status.state == TrainState.CANCELLED

        // Una soppressione va annunciata sempre, anche se e' la prima lettura:
        // prima veniva silenziata dal ramo che gestisce l'assenza di storico.
        if (cancelled && !alertedCancellation) {
            alertedCancellation = true
            lastAlertedDelay = current
            emitAlert(number, status, "Il treno è stato soppresso.")
            return
        }

        if (previous == null) {
            // Prima lettura: si stabilisce il riferimento, non si suona.
            lastAlertedDelay = current
            return
        }
        if (kotlin.math.abs(current - previous) <= DELAY_THRESHOLD_MIN) return

        lastAlertedDelay = current
        if (!hasNotificationPermission()) return

        emitAlert(
            number,
            status,
            when {
                current > previous -> "Il ritardo è passato da ${describeDelay(previous)} a ${describeDelay(current)}."
                else -> "Lo scarto è cambiato da ${describeDelay(previous)} a ${describeDelay(current)}."
            },
        )
    }

    private fun describeDelay(minutes: Int): String = when {
        minutes > 0 -> "$minutes min di ritardo"
        minutes < 0 -> "${-minutes} min di anticipo"
        else -> "orario regolare"
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

    private fun notifyArrived(number: String, status: TrainStatus) {
        if (!hasNotificationPermission()) return
        NotificationManagerCompat.from(this).notify(
            NOTIF_ALERT_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(status.label.ifBlank { "Treno $number" })
                .setContentText("Arrivato a ${status.destination.orEmpty()}.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun describe(status: TrainStatus): String = when (status.state) {
        TrainState.CANCELLED -> "Soppresso"
        TrainState.PARTIALLY_CANCELLED -> "Soppresso in parte"
        TrainState.DIVERTED -> "Percorso variato"
        TrainState.NOT_DEPARTED -> "Non ancora partito"
        TrainState.ARRIVED -> "Arrivato"
        else -> when {
            status.delayMinutes > 0 -> "Ritardo ${status.delayMinutes} min"
            status.delayMinutes < 0 -> "In anticipo di ${-status.delayMinutes} min"
            else -> "In orario"
        }
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
     * Toccare la notifica deve aprire **quel** treno, non genericamente l'app.
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

    private fun hasNotificationPermission(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun stopFollowing() {
        pollJob?.cancel()
        followedNumber = null
        followedDate = null
        _followed.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _followed.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "it.zawardo.treni.FOLLOW_STOP"

        /** Letti da MainActivity per aprire direttamente la corsa seguita. */
        const val EXTRA_OPEN_TRAIN = "open_train_number"
        const val EXTRA_OPEN_DATE = "open_train_epoch_day"

        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_EPOCH_DAY = "epochDay"

        private const val CHANNEL_ONGOING = "follow_ongoing"
        private const val CHANNEL_ALERTS = "follow_alerts"
        private const val NOTIF_ONGOING_ID = 1001
        private const val NOTIF_ALERT_ID = 1002

        /** Richiesta esplicita: polling ogni minuto. */
        private const val POLL_INTERVAL_MS = 60_000L

        /** Si avvisa oltre i 3 minuti di scarto, non a ogni sussulto. */
        private const val DELAY_THRESHOLD_MIN = 3

        private val _followed = MutableStateFlow<String?>(null)
        val followed: StateFlow<String?> = _followed.asStateFlow()

        fun start(context: Context, trainNumber: String, date: LocalDate) {
            val intent = Intent(context, TrainFollowService::class.java)
                .putExtra(EXTRA_NUMBER, trainNumber)
                .putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
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
                ).apply { description = "Notifica permanente col ritardo aggiornato" },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    "Avvisi ritardo",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Avvisa quando il ritardo cambia di oltre 3 minuti" },
            )
        }
    }
}

/** Isolato perché `startForeground` con tipo esiste solo da Android 10. */
private object ServiceCompatStart {
    fun start(service: Service, notification: Notification) {
        service.startForeground(
            1001,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
