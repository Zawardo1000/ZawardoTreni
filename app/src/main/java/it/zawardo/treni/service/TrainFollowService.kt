package it.zawardo.treni.service

import it.zawardo.treni.ui.common.ORA_DEL_GIORNO
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
import it.zawardo.treni.domain.model.reteConStazioniProprie
import it.zawardo.treni.domain.model.oggiInItalia
import it.zawardo.treni.domain.model.adessoInItalia
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.variazione
import it.zawardo.treni.domain.model.indiceFermata
import it.zawardo.treni.domain.model.stessoBinario
import it.zawardo.treni.ui.common.stateLabel
import it.zawardo.treni.ui.train.CaricatoreCorsa
import it.zawardo.treni.ui.MainActivity
import it.zawardo.treni.ui.TrattaViaggio
import it.zawardo.treni.ui.comeJson
import it.zawardo.treni.ui.tratteDaJson
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
import kotlin.math.absoluteValue
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Segui treno: sorveglia la partenza dalla **tua** stazione e avvisa quando lo
 * scarto cambia di oltre 3 minuti.
 *
 * Lo scopo e' prendere il treno, non accompagnarlo: il monitoraggio di una
 * corsa si chiude appena quella corsa ha smesso di riguardarti. Da li' in poi
 * non c'e' piu' niente su cui agire, e tenerlo acceso sarebbe solo consumo.
 *
 * **Un viaggio, non un treno.** Su una soluzione con cambio le corse si seguono
 * *tutte insieme*, in una notifica sola: mentre sei sul primo treno la cosa che
 * ti interessa e' se la coincidenza regge, e quella la sa solo chi guarda tutti
 * e due. Ogni tratta esce dalla notifica quando hai superato la stazione dove
 * scendi — vedi [Sorvegliata.finita] — e quando esce l'ultima la notifica se ne
 * va da sola. Sulla corsa singola, dove non esiste una discesa dichiarata, la
 * regola resta quella di sempre: si chiude alla partenza da dove sali.
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
 * minuto in minuto, negli ultimi dieci si'. Con piu' tratte ognuna ha la sua
 * scadenza: la coincidenza di fra due ore non si interroga al minuto solo
 * perche' il treno su cui sei sta per partire.
 */
class TrainFollowService : Service() {

    /**
     * Una corsa sotto osservazione, con la memoria di cio' che e' gia' stato
     * annunciato.
     *
     * Lo stato degli avvisi e' per corsa e non per servizio: due treni dello
     * stesso viaggio hanno ritardi e binari propri, e una soglia condivisa
     * avrebbe fatto tacere il secondo ogni volta che si era appena parlato del
     * primo.
     */
    private class Sorvegliata(val tratta: TrattaViaggio) {

        /** Scarto dell'ultimo avviso emesso, non dell'ultimo rilevamento. */
        var lastAlertedDelay: Int? = null

        /** La soppressione si annuncia una volta sola, non a ogni giro. */
        var alertedCancellation = false

        /**
         * Il binario dell'ultimo avviso sulla fermata di salita.
         *
         * Serve a distinguere le due cose che succedono al binario: la **prima
         * assegnazione**, che nelle stazioni grandi arriva un quarto d'ora prima
         * della partenza, e il **cambio**, che arriva quando sei gia' sul
         * marciapiede sbagliato. La prima si annuncia perche' e' quello che
         * stavi aspettando, il secondo perche' altrimenti perdi il treno.
         */
        var lastAlertedPlatform: String? = null

        /**
         * Distingue "binario non ancora letto" da "binario che non c'e'".
         *
         * Senza, il primo giro senza binario e la prima assegnazione sarebbero
         * indistinguibili, e l'avviso che serve davvero — quello che dice da
         * dove parti — non partirebbe mai.
         */
        var platformRead = false

        /** L'ultima lettura riuscita, che e' cio' che si scrive in notifica. */
        var status: TrainStatus? = null
        var boarding: Stop? = null
        var alighting: Stop? = null

        /** Corsa gia' risolta: si fa una volta sola, all'avvio. */
        var ref: TrainRef? = null
        var risolta = false

        /** Quando tocca di nuovo a questa corsa: vedi `prossimoGiro`. */
        var prossimoGiro: Long = 0L

        /**
         * Letture andate a vuoto **prima** di aver mai visto questa corsa.
         *
         * Serve a non restare accesi per sempre dietro a un numero che nessuna
         * fonte conosce: capita su un viaggio misto, dove una gamba puo' stare
         * fuori da ogni servizio in tempo reale. Dopo qualche tentativo quella
         * corsa esce di scena in silenzio — non c'e' niente da annunciare, non
         * si e' mai saputo niente — e le altre proseguono.
         */
        var vuoti = 0

        /** Finita: e' uscita dalla notifica e non si interroga piu'. */
        var finita = false

        val numero: String get() = tratta.numero.orEmpty()

        /**
         * Vero se si sale da una rete con stazioni proprie — EAV, ARST,
         * Ferrotramviaria — che ViaggiaTreno non ha: vedi come si legge in
         * [comeNelDettaglio]. Il codice di salita basta a riconoscerla, perche'
         * quelle stazioni hanno un codice sintetico tutto loro.
         */
        val reteSua: Boolean get() = reteConStazioniProprie(tratta.salitaRfi)

        /**
         * Vero se di questa corsa **nessuno rileva i passaggi**: le reti con
         * stazioni proprie, e Italo quando la corsa viene dal tabellone invece
         * che dal suo dettaglio. Vedi `haFinito`: li' la fine la da' l'orologio.
         */
        val senzaRilevamenti: Boolean
            get() = (reteSua || status?.category.equals("Italo", ignoreCase = true)) &&
                status?.stops?.none { it.detected } != false

        /**
         * La corsa come la apre il dettaglio: la cascata intera, che comprende
         * le reti con stazioni proprie (orario del giorno, e il ritardo dove la
         * rete lo pubblica, come il pianificatore EAV) e Italo, che ViaggiaTreno
         * non ha.
         */
        suspend fun comeNelDettaglio(giorno: LocalDate): TrainStatus? = CaricatoreCorsa(
            trainNumber = numero,
            date = giorno,
            boardingCode = tratta.salitaRfi,
            boardingAt = tratta.partenzaNota,
            boardingName = tratta.salitaNome,
            alightingCode = tratta.discesaRfi,
            alightingName = tratta.discesaNome,
            origineCorsa = tratta.origineRfi,
        ).carica()

        /**
         * Il nome della stazione come lo scriviamo in notifica: quello del
         * viaggio se c'e', altrimenti quello della corsa. Seguendo un treno
         * solo dal tabellone il primo non esiste, e "Parte da  alle 18:09"
         * sarebbe una frase con un buco in mezzo.
         */
        val nomeSalita: String
            get() = tratta.salitaNome.ifBlank { boarding?.stationName.orEmpty() }

        val nomeDiscesa: String
            get() = tratta.discesaNome.ifBlank { alighting?.stationName.orEmpty() }

        /**
         * L'evento che questa corsa sta aspettando, e da cui dipende ogni quanto
         * la si interroga: la partenza da dove sali finche' non sei salito, poi
         * l'arrivo dove scendi.
         */
        fun prossimoEvento(): LocalDateTime? {
            val salito = boarding?.let { it.status == StopStatus.DONE || it.actualDeparture != null }
            return if (salito == true) {
                alighting?.effectiveArrival ?: alighting?.scheduledArrival ?: tratta.arrivoNoto
            } else {
                boarding?.effectiveDeparture ?: boarding?.scheduledDeparture ?: tratta.partenzaNota
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private var pollJob: Job? = null

    private var sorvegliate: List<Sorvegliata> = emptyList()

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

        val tratte = intent?.getStringExtra(EXTRA_TRATTE)?.let { tratteDaJson(it) }
            .orEmpty()
            .filter { it.numero != null }
        if (tratte.isEmpty()) return START_NOT_STICKY

        // Riavvio sullo stesso viaggio: non azzerare lo storico degli avvisi,
        // altrimenti la soglia dei 3 minuti riparte da capo.
        val stesse = sorvegliate.map { it.tratta } == tratte
        if (stesse && pollJob?.isActive == true) return START_REDELIVER_INTENT

        sorvegliate = tratte.map { Sorvegliata(it) }
        lastPollAt = null

        startForegroundSafely(
            buildOngoing(titolo(), "Ricerca dello stato in corso…", null, null),
        )
        _followed.value = tratte.mapNotNull { it.numero }.toSet()
        acquireWakeLock()
        startPolling()

        // REDELIVER e non STICKY: con STICKY il sistema puo' riavviare il servizio
        // con Intent nullo, e senza le tratte non saprebbe cosa seguire.
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

    private fun startPolling() {
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

            while (isActive) {
                val ora = System.currentTimeMillis()
                /*
                 * Solo le corse scadute. Ognuna ha la sua cadenza: quella su cui
                 * stai per salire si guarda al minuto, la coincidenza di fra due
                 * ore ogni cinque. Interrogarle tutte insieme moltiplicherebbe
                 * per tre le accensioni della radio senza dire niente di nuovo.
                 */
                val daFare = sorvegliate.filter { !it.finita && it.prossimoGiro <= ora }

                for (corsa in daFare) {
                    val giorno = corsa.tratta.giorno
                    if (!corsa.risolta && !corsa.reteSua) {
                        /*
                         * La corsa si risolve UNA volta sola. Risolverla a ogni
                         * giro costava una chiamata in piu' al minuto, ma
                         * soprattutto era fragile: lo stesso numero puo' avere
                         * piu' corse, e la scelta poteva cambiare spostando la
                         * notifica su un altro treno senza preavviso.
                         */
                        corsa.ref = runCatching {
                            trains.resolveFor(
                                corsa.numero, giorno, corsa.tratta.salitaRfi, corsa.tratta.partenzaNota,
                                origine = corsa.tratta.origineRfi,
                            )
                        }.getOrNull()
                        corsa.risolta = true
                    }

                    // Vero solo se la corsa l'ha data ViaggiaTreno: e' l'unico caso
                    // in cui ha senso chiedere a Trenord di completarne i binari.
                    var daViaggiaTreno = false
                    val status = runCatching {
                        val letto = if (corsa.reteSua) {
                            /*
                             * Le reti con stazioni proprie — EAV, ARST,
                             * Ferrotramviaria — ViaggiaTreno non le ha affatto:
                             * chiedergliele e' una chiamata buttata, e la notifica
                             * restava per sempre su «Ricerca dello stato in corso…»
                             * finche' il viaggio non smetteva di farsi seguire da
                             * solo (visto il 20/09/2026 seguendo Sorrento-Roma, con
                             * la Circumvesuviana in testa). Quelle corse le sa la
                             * loro rete, e la cascata che le interroga e' la stessa
                             * del dettaglio.
                             */
                            corsa.comeNelDettaglio(giorno)
                        } else {
                            val nazionale = corsa.ref?.let { trains.status(it) }
                                ?: trains.statusByNumber(
                                    corsa.numero,
                                    giorno,
                                    corsa.tratta.salitaRfi,
                                    corsa.tratta.partenzaNota,
                                    origine = corsa.tratta.origineRfi,
                                )
                            daViaggiaTreno = nazionale != null
                            /*
                             * Dove ViaggiaTreno tace resta la cascata intera, la
                             * stessa del dettaglio: e' il caso di **Italo**, che
                             * ViaggiaTreno non pubblica affatto e che dentro un
                             * viaggio misto e' proprio la gamba lunga. Senza, la sua
                             * notifica restava su «Ricerca dello stato in corso…»
                             * fino a spegnersi da sola.
                             */
                            nazionale ?: corsa.comeNelDettaglio(giorno)
                        }
                        /*
                         * Il binario e' la meta' del motivo per cui si segue un treno,
                         * e ViaggiaTreno da solo spesso non ce l'ha. Ma a Trenord si
                         * chiedono **solo le corse di ViaggiaTreno**: un numero EAV o
                         * Italo li' e' un altro treno, e i suoi binari finirebbero su
                         * questa corsa (e' il guasto dell'EAV 2093, in CLAUDE.md).
                         */
                        letto?.let {
                            if (trenordAcceso && daViaggiaTreno) trains.completaBinari(it, giorno) else it
                        }
                    }.getOrNull()

                    /*
                     * La scadenza si sposta anche quando la fonte tace, e prima
                     * di ogni altra cosa: senza, una corsa che non risponde
                     * resterebbe eternamente scaduta e il ciclo la richiederebbe
                     * ogni secondo — con la radio accesa a ogni giro.
                     */
                    corsa.prossimoGiro = System.currentTimeMillis() + pollIntervalMs(corsa)
                    if (status == null) {
                        if (corsa.status == null && ++corsa.vuoti >= LETTURE_A_VUOTO) {
                            corsa.finita = true
                            /*
                             * E lo si dice. Prima la notifica spariva in silenzio,
                             * ferma su «Ricerca dello stato in corso…»: succede
                             * toccando la campanella dove non prende — nel
                             * sottopasso di una stazione, per dirne una — e chi
                             * l'aveva accesa restava convinto di essere seguito.
                             */
                            notifyMai(corsa)
                        }
                        continue
                    }

                    lastPollAt = adessoInItalia()
                    corsa.status = status
                    corsa.boarding = status.fermata(corsa.tratta.salitaRfi, corsa.tratta.partenzaNota)
                    corsa.alighting = status.fermata(corsa.tratta.discesaRfi, corsa.tratta.arrivoNoto)

                    maybeAlert(corsa)

                    if (haFinito(corsa)) {
                        corsa.finita = true
                        notifyFinished(corsa)
                    }
                    // Ricalcolata con la lettura fresca: adesso si sa se sei
                    // gia' salito, e quindi quale evento si sta aspettando.
                    corsa.prossimoGiro = System.currentTimeMillis() + pollIntervalMs(corsa)
                }

                val vive = sorvegliate.filter { !it.finita }
                if (vive.isEmpty()) {
                    stopFollowing()
                    return@launch
                }

                if (daFare.isNotEmpty()) {
                    updateOngoing(vive)
                    _followed.value = vive.map { it.numero }.toSet()
                }

                // Si dorme fino alla prima scadenza, non un secondo di piu'.
                val attesa = (vive.minOf { it.prossimoGiro } - System.currentTimeMillis())
                    .coerceIn(1_000L, POLL_FAR_MS)
                delay(attesa)
            }
        }
    }

    /**
     * Quando una corsa esce di scena.
     *
     * Con una discesa dichiarata — cioe' dentro un viaggio — il treno smette di
     * riguardarti quando hai **superato la stazione dove scendi**: prima no,
     * perche' finche' ci sei sopra il suo ritardo e' quello che decide se
     * prendi la coincidenza. Senza discesa (si e' arrivati dalla ricerca per
     * numero, o dal tabellone) vale la regola di sempre: si chiude quando il
     * treno lascia la stazione da cui sali, e in mancanza anche di quella
     * quando arriva a destinazione.
     */
    private fun haFinito(corsa: Sorvegliata): Boolean {
        /*
         * Le corse di cui nessuno rileva i passaggi — EAV, ARST, Ferrotramviaria,
         * e Italo quando il suo dettaglio tace — non dicono mai che una fermata e'
         * stata fatta: restano «da fare» anche un'ora dopo essere arrivate, e
         * nessuna delle prove qui sotto scatterebbe mai. Li' la fine la da'
         * l'orologio: passato l'orario previsto di dove scendi, con un margine,
         * non c'e' piu' niente da annunciare. Senza, seguirne una avrebbe tenuto
         * acceso il servizio per sempre.
         */
        if (corsa.senzaRilevamenti) {
            val fine = corsa.alighting?.let { it.projectedArrival ?: it.scheduledArrival }
                ?: corsa.tratta.arrivoNoto
                ?: corsa.boarding?.let { it.projectedDeparture ?: it.scheduledDeparture }
                ?: corsa.tratta.partenzaNota
                ?: return false
            return adessoInItalia().isAfter(fine.plusMinutes(MARGINE_SENZA_RILEVAMENTI))
        }
        val discesa = corsa.alighting
        if (discesa != null) {
            return discesa.status == StopStatus.DONE ||
                discesa.status == StopStatus.CANCELLED ||
                discesa.actualArrival != null
        }
        val salita = corsa.boarding
        if (salita != null) {
            return salita.status == StopStatus.DONE ||
                salita.status == StopStatus.CANCELLED ||
                salita.actualDeparture != null
        }
        return corsa.status?.state == TrainState.ARRIVED
    }

    /**
     * Frequenza adattiva. Il consumo dipende soprattutto da quante volte si
     * accende la radio: lontano dall'evento il ritardo non cambia di minuto in
     * minuto, e interrogare ogni 60 secondi sarebbe spreco puro.
     */
    private fun pollIntervalMs(corsa: Sorvegliata): Long {
        val quando = corsa.prossimoEvento() ?: return POLL_NEAR_MS
        val minutes = Duration.between(adessoInItalia(), quando).toMinutes()
        return when {
            minutes > 30 -> POLL_FAR_MS
            minutes > 10 -> POLL_MID_MS
            else -> POLL_NEAR_MS
        }
    }

    /** La fermata di una stazione dentro la corsa, scelta anche per orario. */
    private fun TrainStatus.fermata(codice: String?, quando: LocalDateTime?): Stop? =
        stops.getOrNull(indiceFermata(codice, quando))

    // -------------------------------------------------------- notifiche

    private fun titolo(): String {
        val vive = sorvegliate.filter { !it.finita }
        val prima = vive.firstOrNull() ?: sorvegliate.firstOrNull()
        val ultima = sorvegliate.lastOrNull()
        return when {
            prima == null || ultima == null -> "Treno seguito"
            // Viaggio con cambi: il titolo e' dove stai andando, non il numero
            // del treno su cui sei — quello cambia per strada.
            sorvegliate.size > 1 -> "${prima.tratta.salitaNome} → ${ultima.tratta.discesaNome}"
            else -> prima.status?.label?.takeIf { it.isNotBlank() } ?: "Treno ${prima.numero}"
        }
    }

    private fun updateOngoing(vive: List<Sorvegliata>) {
        /*
         * Due orari diversi, e servono entrambi:
         *  - dove e quando il treno e' stato rilevato da RFI;
         *  - quando NOI abbiamo controllato l'ultima volta.
         * Il secondo e' l'unico modo per accorgersi che il polling si e' fermato.
         */
        val corrente = vive.first()
        val detection = corrente.status?.lastDetectionStation?.let { st ->
            val at = corrente.status?.lastDetectionTime?.format(ORA_DEL_GIORNO)
            if (at != null) "$st alle $at" else st
        }
        val checked = lastPollAt?.format(ORA_DEL_GIORNO)?.let { "aggiornato $it" }
        val sub = listOfNotNull(detection, checked).joinToString(" · ").ifBlank { null }

        /*
         * Nella riga sola c'e' la corsa che stai prendendo adesso; aprendo la
         * notifica ci sono tutte quelle ancora davanti a te, una per riga. E'
         * la ragione per cui si segue un viaggio invece di un treno: mentre sei
         * sul primo, il ritardo che ti interessa e' quello che decide se prendi
         * il secondo.
         */
        val righe = vive.map { corsa ->
            val descrizione = describe(corsa)
            if (vive.size > 1) "${etichetta(corsa)} · $descrizione" else descrizione
        }

        if (!hasNotificationPermission()) return
        NotificationManagerCompat.from(this).notify(
            NOTIF_ONGOING_ID,
            buildOngoing(
                titolo(),
                righe.first(),
                sub,
                if (righe.size > 1) righe.joinToString("\n") else null,
            ),
        )
    }

    private fun etichetta(corsa: Sorvegliata): String =
        corsa.status?.label?.takeIf { it.isNotBlank() } ?: corsa.tratta.etichetta

    /**
     * Il testo parla della **tua** partenza finche' non sei salito, poi della
     * tua discesa: il ritardo globale della corsa e' un'informazione peggiore,
     * perche' quello che ti riguarda e' a che ora passa da te.
     */
    private fun describe(corsa: Sorvegliata): String {
        val status = corsa.status ?: return "Ricerca dello stato in corso…"
        val parola = stateWord(status.state)
        // Una variazione non toglie l'orario: sta davanti, e il resto segue.
        // Prima «Percorso variato» era tutto il testo, anche a +193.
        if (parola != null && !status.state.variazione) return parola
        val orario = orarioTuo(corsa, status)
        return if (parola != null) "$parola · $orario" else orario
    }

    private fun orarioTuo(corsa: Sorvegliata, status: TrainStatus): String {
        val salita = corsa.boarding
        val salito = salita != null &&
            (salita.status == StopStatus.DONE || salita.actualDeparture != null)

        if (!salito && salita != null) {
            val time = salita.effectiveDeparture?.format(ORA_DEL_GIORNO)
                ?: salita.scheduledDeparture?.format(ORA_DEL_GIORNO)
            val delay = salita.departureDelayMinutes
            val where = corsa.nomeSalita
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
            val binario = salita.platform?.let {
                if (salita.platformChanged) " · bin. $it (era ${salita.scheduledPlatform})"
                else " · bin. $it"
            }.orEmpty()

            if (time != null) return "Parte da $where alle $time$suffix$binario"
        }

        val discesa = corsa.alighting
        if (salito && discesa != null) {
            val time = discesa.effectiveArrival?.format(ORA_DEL_GIORNO)
                ?: discesa.scheduledArrival?.format(ORA_DEL_GIORNO)
            val delay = discesa.arrivalDelayMinutes
            val suffix = when {
                delay > 0 -> " (+$delay)"
                delay < 0 -> " (${delay})"
                else -> ""
            }
            val binario = discesa.platform?.let { " · bin. $it" }.orEmpty()
            if (time != null) {
                return "Arrivo a ${corsa.nomeDiscesa} alle $time$suffix$binario"
            }
        }

        return when {
            status.delayMinutes > 0 -> "Ritardo ${status.delayMinutes} min"
            status.delayMinutes < 0 -> "In anticipo di ${-status.delayMinutes} min"
            else -> "In orario"
        }
    }

    /**
     * La parola di stato nella notifica: **la stessa dell'app**, da
     * `stateLabel`, perche' notifica e schermata non possono chiamare la stessa
     * cosa in due modi.
     *
     * L'unica eccezione e' «non ancora partito», che qui si tace: nella notifica
     * la partenza e' gia' scritta per esteso («Parte da Milano alle 18:09»), e
     * ripeterla come stato la renderebbe una riga piu' lunga senza dire niente
     * di nuovo.
     */
    private fun stateWord(state: TrainState): String? =
        if (state == TrainState.NOT_DEPARTED) null else stateLabel(state)

    /**
     * Vero se, dentro il viaggio seguito, dopo questa tratta ce n'e' un'altra
     * ancora da prendere: e' la differenza fra un diretto e una coincidenza.
     */
    private fun haUnaCoincidenzaDopo(corsa: Sorvegliata): Boolean {
        val i = sorvegliate.indexOf(corsa)
        return i >= 0 && sorvegliate.drop(i + 1).any { !it.finita }
    }

    /**
     * Avvisa solo se lo scarto rispetto all'**ultimo avviso** supera la soglia.
     * Confrontarlo con l'ultimo rilevamento farebbe suonare il telefono a ogni
     * oscillazione di un minuto.
     */
    private fun maybeAlert(corsa: Sorvegliata) {
        val status = corsa.status ?: return
        val boarding = corsa.boarding
        val salito = boarding != null &&
            (boarding.status == StopStatus.DONE || boarding.actualDeparture != null)

        /*
         * **Una volta a bordo si tace, a meno che ci sia un cambio da prendere.**
         *
         * Deciso con l'utente il 20/09/2026. Su un viaggio diretto, quando sei
         * salito non c'e' piu' niente su cui agire: il treno ti porta comunque, e
         * far suonare il telefono a ogni oscillazione del ritardo sarebbe rumore.
         * Se invece dopo questa tratta ce n'e' un'altra, il ritardo con cui arrivi
         * e' esattamente cio' che decide se la prendi, e va detto.
         *
         * Il ritardo del treno **successivo**, finche' non ci sali, lo annuncia la
         * sua sorvegliata, con la sua partenza: quella regola non cambia.
         */
        if (salito && !haUnaCoincidenzaDopo(corsa)) return

        /*
         * Lo scarto che conta e' quello dell'evento che stai aspettando: la tua
         * partenza finche' non sei salito, il tuo arrivo da li' in poi — la stessa
         * regola con cui la notifica scrive l'orario (`orarioTuo`).
         *
         * Prima si guardava sempre la partenza, e quel numero appena sali si
         * congela: il treno accumulava venti minuti fra Milano e Verona, la
         * notifica li mostrava, ma nessun avviso suonava piu' — proprio mentre il
         * ritardo stava mangiando la coincidenza.
         */
        val current = when {
            salito -> corsa.alighting?.arrivalDelayMinutes ?: status.delayMinutes
            boarding != null -> boarding.departureDelayMinutes
            else -> status.delayMinutes
        }

        if (status.state == TrainState.CANCELLED && !corsa.alertedCancellation) {
            corsa.alertedCancellation = true
            corsa.lastAlertedDelay = current
            emitAlert(corsa, "Il treno è stato soppresso.")
            return
        }

        /*
         * Il binario prima del ritardo. Se cambiano insieme, quello che ti fa
         * alzare e camminare e' il binario; il ritardo resta da dire e lo dira'
         * il giro dopo, perche' uscendo di qui `lastAlertedDelay` non e' stato
         * aggiornato e lo scarto risultera' ancora nuovo.
         */
        if (alertPlatform(corsa)) return

        val previous = corsa.lastAlertedDelay
        if (previous == null) {
            // Prima lettura: si stabilisce il riferimento, non si suona.
            corsa.lastAlertedDelay = current
            return
        }
        if (kotlin.math.abs(current - previous) <= DELAY_THRESHOLD_MIN) return

        corsa.lastAlertedDelay = current
        val where = corsa.nomeSalita
        val at = boarding?.effectiveDeparture?.format(ORA_DEL_GIORNO)
        val tail = if (at != null) " Partenza da $where prevista alle $at." else ""
        emitAlert(corsa, "Da ${describeDelay(previous)} a ${describeDelay(current)}.$tail")
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
     * e' un tuo problema. E solo finche' non sei salito, perche' dopo quel
     * binario non serve piu' a niente.
     */
    private fun alertPlatform(corsa: Sorvegliata): Boolean {
        val status = corsa.status ?: return false
        // Su una corsa soppressa il binario non e' piu' una notizia.
        if (status.state == TrainState.CANCELLED) return false
        val stop = corsa.boarding ?: return false
        if (stop.status != StopStatus.FUTURE) return false

        val current = stop.platform
        if (!corsa.platformRead) {
            // Prima lettura: come per il ritardo, si prende il riferimento e
            // non si suona. Anche quando e' null, ed e' il caso interessante:
            // e' da li' che si riconoscera' la prima assegnazione.
            corsa.platformRead = true
            corsa.lastAlertedPlatform = current
            return false
        }
        // La stessa banchina scritta in due modi non e' un cambio: ViaggiaTreno
        // dice "2" dove Trenord dice "II", e le due letture si alternano a
        // seconda di quale delle due fonti abbia risposto per prima.
        if (current == null || stessoBinario(current, corsa.lastAlertedPlatform)) return false

        val previous = corsa.lastAlertedPlatform
        corsa.lastAlertedPlatform = current
        val where = corsa.nomeSalita
        emitAlert(
            corsa,
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

    /**
     * Una corsa esce di scena, e lo dice. Se dietro ne restano altre lo dice
     * anche: il monitoraggio non e' finito, e' finito **per quel treno**.
     */
    private fun notifyFinished(corsa: Sorvegliata) {
        val restano = sorvegliate.count { !it.finita }
        val discesa = corsa.alighting
        val salita = corsa.boarding
        val coda = if (restano > 0) {
            " Continuo a seguire " + if (restano == 1) "la coincidenza." else "le $restano corse successive."
        } else {
            " Monitoraggio terminato."
        }
        val body = when {
            discesa != null && discesa.status == StopStatus.CANCELLED ->
                "La fermata di ${corsa.nomeDiscesa} è stata soppressa.$coda"
            discesa != null -> {
                val at = (discesa.actualArrival ?: discesa.effectiveArrival)?.format(ORA_DEL_GIORNO)
                if (at != null) "Arrivato a ${corsa.nomeDiscesa} alle $at.$coda"
                else "Arrivato a ${corsa.nomeDiscesa}.$coda"
            }
            salita != null -> {
                val at = salita.actualDeparture?.format(ORA_DEL_GIORNO)
                if (at != null) "Partito da ${corsa.nomeSalita} alle $at.$coda"
                else "Partito da ${corsa.nomeSalita}.$coda"
            }
            else -> "Arrivato a ${corsa.status?.destination.orEmpty()}.$coda"
        }
        emitAlert(corsa, body)
    }

    /** Quando di una corsa non si e' mai saputo niente e si smette di chiederlo. */
    private fun notifyMai(corsa: Sorvegliata) {
        emitAlert(
            corsa,
            "Di questa corsa non risulta nulla: nessuna fonte la conosce, o la rete non " +
                "rispondeva. Ho smesso di controllarla — riapri la corsa e tocca di nuovo " +
                "la campanella per riprovare.",
        )
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

    private fun emitAlert(corsa: Sorvegliata, body: String) {
        if (!hasNotificationPermission()) return
        NotificationManagerCompat.from(this).notify(
            /*
             * Un id per corsa, non uno per tutte. Su un viaggio con cambio due
             * tratte possono avere qualcosa da dire nello stesso giro — la prima
             * e' arrivata, la seconda ha preso ritardo — e con un id solo la
             * seconda notifica sostituiva la prima: l'utente ne vedeva una, e
             * dell'altra non restava traccia.
             */
            NOTIF_ALERT_ID + corsa.numero.hashCode().absoluteValue % ALERT_PER_CORSA,
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.mipmap.ic_launcher_foreground)
                .setContentTitle(etichetta(corsa))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    private fun buildOngoing(
        title: String,
        text: String,
        sub: String?,
        /** Le altre corse del viaggio: si leggono aprendo la notifica. */
        esteso: String?,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sub)
            .apply { esteso?.let { setStyle(NotificationCompat.BigTextStyle().bigText(it)) } }
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
     * Toccare la notifica apre **quel** viaggio, non genericamente l'app: la
     * pagina unica se le tratte sono piu' d'una, il dettaglio della corsa se e'
     * una sola. Gli extra viaggiano nell'Intent e MainActivity li trasforma in
     * navigazione.
     */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val vive = sorvegliate.filter { !it.finita }.ifEmpty { sorvegliate }
        if (vive.size > 1) {
            intent.putExtra(EXTRA_OPEN_VIAGGIO, vive.map { it.tratta }.comeJson())
        } else {
            val sola = vive.firstOrNull()
            intent.putExtra(EXTRA_OPEN_TRAIN, sola?.numero)
            intent.putExtra(
                EXTRA_OPEN_DATE,
                sola?.tratta?.giornoEpoch ?: oggiInItalia().toEpochDay(),
            )
            /*
             * E la tratta intera, non il solo numero: dice da dove sali e dove
             * scendi. Senza quelle due, una corsa di un giorno futuro non si sa
             * nemmeno cercare — la si cerca per tratta — e lo stesso numero puo'
             * essere di due treni diversi nello stesso giorno.
             */
            sola?.let { intent.putExtra(EXTRA_OPEN_TRATTA, listOf(it.tratta).comeJson()) }
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

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
        sorvegliate = emptyList()
        _followed.value = emptySet()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        _followed.value = emptySet()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "it.zawardo.treni.FOLLOW_STOP"
        private const val EXTRA_TRATTE = "tratte"

        /** Letti da MainActivity per aprire direttamente cio' che si sta seguendo. */
        const val EXTRA_OPEN_TRAIN = "open_train_number"
        const val EXTRA_OPEN_DATE = "open_train_epoch_day"
        const val EXTRA_OPEN_VIAGGIO = "open_viaggio"

        /** La tratta della corsa sola, per aprirla sapendo salita e discesa. */
        const val EXTRA_OPEN_TRATTA = "open_train_tratta"

        private const val CHANNEL_ONGOING = "follow_ongoing"
        private const val CHANNEL_ALERTS = "follow_alerts"
        private const val NOTIF_ONGOING_ID = 1001
        private const val NOTIF_ALERT_ID = 1002

        /**
         * Quanti id distinti riservare agli avvisi delle corse seguite: vedi
         * [emitAlert]. Un viaggio ne ha al piu' tre o quattro, e restano tutti
         * sopra [NOTIF_ONGOING_ID] senza avvicinarsi ad altri id dell'app.
         */
        private const val ALERT_PER_CORSA = 50

        /** Ultimi dieci minuti: qui cambiano binario e ritardo, si guarda spesso. */
        private const val POLL_NEAR_MS = 60_000L

        /** Fra dieci e trenta minuti: cambia qualcosa, ma non ogni minuto. */
        private const val POLL_MID_MS = 120_000L

        /** Oltre la mezz'ora: il dato e' quasi fermo, la radio puo' riposare. */
        private const val POLL_FAR_MS = 300_000L

        /** Si avvisa oltre i 3 minuti di scarto, non a ogni sussulto. */
        private const val DELAY_THRESHOLD_MIN = 3

        /**
         * Dopo cinque letture a vuoto una corsa mai vista si lascia perdere: e'
         * un numero che quelle fonti non conoscono, non una rete che tossisce.
         * Una corsa gia' letta almeno una volta non si molla mai per un buco.
         */
        private const val LETTURE_A_VUOTO = 5

        /**
         * Quanto si aspetta, oltre l'orario previsto, prima di dare per conclusa
         * una corsa di una rete che i passaggi non li rileva: vedi `haFinito`.
         */
        private const val MARGINE_SENZA_RILEVAMENTI = 10L

        private const val WAKE_LOCK_TAG = "ZawardoTreni:segui-treno"

        /** Limite di guardia del wake lock: nessuna attesa dura otto ore. */
        private const val MAX_FOLLOW_MS = 8 * 60 * 60 * 1000L

        /**
         * I numeri seguiti in questo momento, che sono meno di quelli di
         * partenza: una corsa esce dall'insieme appena l'hai lasciata. Serve
         * alle schermate per accendere la campanella sul treno giusto.
         */
        private val _followed = MutableStateFlow<Set<String>>(emptySet())
        val followed: StateFlow<Set<String>> = _followed.asStateFlow()

        /** Una corsa sola: dal tabellone, dalla ricerca per numero, dal dettaglio. */
        fun start(
            context: Context,
            trainNumber: String,
            date: LocalDate,
            boardingRfi: String? = null,
            boardingName: String? = null,
        ) {
            start(
                context,
                listOf(
                    TrattaViaggio(
                        numero = trainNumber,
                        etichetta = "Treno $trainNumber",
                        treno = true,
                        giornoEpoch = date.toEpochDay(),
                        salitaRfi = boardingRfi,
                        salitaNome = boardingName.orEmpty(),
                        // Nessuna discesa dichiarata: il monitoraggio si chiude
                        // alla partenza da dove sali, come ha sempre fatto.
                        discesaRfi = null,
                        discesaNome = "",
                        // Orari ignoti: qui non c'e' un viaggio, c'e' un numero.
                        partenzaEpochSec = 0L,
                        arrivoEpochSec = 0L,
                    ),
                ),
            )
        }

        /** Il viaggio intero: le corse si seguono insieme e si dimettono una per volta. */
        fun start(context: Context, tratte: List<TrattaViaggio>) {
            if (tratte.isEmpty()) return
            val intent = Intent(context, TrainFollowService::class.java)
                .putExtra(EXTRA_TRATTE, tratte.comeJson())
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
