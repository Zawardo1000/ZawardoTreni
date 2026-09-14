package it.zawardo.treni.ui.train

import it.zawardo.treni.ServiceLocator
import it.zawardo.treni.data.mapper.ROME
import it.zawardo.treni.domain.model.DataSource
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.soloOrarioPrevistoPer
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Come si arriva a una corsa, da qualunque schermata la si guardi.
 *
 * La cascata e' una sola e non e' banale — ViaggiaTreno, poi Trenord, poi
 * Italo, poi le reti col solo orario, infine l'orario ricavato dalla corsa di
 * oggi — e adesso serve in due posti: il dettaglio di un treno singolo e la
 * pagina di un viaggio con cambi, che ne carica una per tratta. Scriverla due
 * volte avrebbe voluto dire, alla prima correzione fatta da una parte sola,
 * due schermate che rispondono in modo diverso sullo stesso treno.
 *
 * Le sorgenti accese si rileggono a ogni caricamento e non una volta all'avvio:
 * spegnere una rete dalle impostazioni deve avere effetto subito.
 */
internal class CaricatoreCorsa(
    private val trainNumber: String,
    private val date: LocalDate,
    /**
     * Stazione da cui si sale, quando si arriva da una ricerca per tratta.
     *
     * Non e' un dettaglio: due treni diversi possono avere lo stesso numero
     * nello stesso giorno, e questa e' l'unica cosa che dice quale dei due sia
     * quello che si sta guardando.
     */
    private val boardingCode: String? = null,
    /** Ora di salita: distingue due corse dello stesso numero in giorni diversi. */
    private val boardingAt: LocalDateTime? = null,
    /** Nome della stazione di salita: serve a Italo, che di suo non lo dice. */
    private val boardingName: String? = null,
    /** Dove si scende: con Italo e' anche il modo piu' diretto di avere il percorso. */
    private val alightingCode: String? = null,
    /**
     * Corsa gia' identificata da chi ci ha portati qui.
     *
     * Tabellone ed elenco corse sanno esattamente di quale treno si tratta:
     * passarlo evita di ricercarlo per numero e, soprattutto, di sceglierne uno
     * diverso fra quelli che quel numero lo condividono.
     */
    private val originCode: String? = null,
    private val departureMillis: Long? = null,
) {

    private val trains = ServiceLocator.trainStatusRepository
    private val trenord = ServiceLocator.trenordRepository
    private val italo = ServiceLocator.italoRepository
    private val eav = ServiceLocator.eavRepository
    private val arst = ServiceLocator.arstRepository
    private val settings = ServiceLocator.settings

    /**
     * La corsa come la si puo' conoscere oggi, o null se per quel giorno non
     * esiste. Le eccezioni di rete escono di qui: e' chi mostra la schermata a
     * decidere se dirlo o tenersi il dato vecchio.
     */
    suspend fun carica(): TrainStatus? {
        val sources = runCatching { settings.enabledSources.first() }
            .getOrDefault(DataSource.defaultEnabled)

        // Il tempo reale per la data cercata, dalle fonti che lo hanno.
        val status = realtime(date, sources)
            /*
             * Poi le reti col solo orario. EAV per le corse che il suo monitor
             * non copre — quelle di domani, e le linee senza monitor — e ARST,
             * che il tempo reale non lo ha affatto. Danno l'orario previsto del
             * giorno giusto: si riconosce di chi e' la corsa dal codice di salita.
             */
            ?: eav.takeIf { DataSource.EAV in sources && it.covers(boardingCode) }
                ?.dettaglioCorsa(trainNumber, date)
            ?: arst.takeIf { DataSource.ARST in sources && it.covers(boardingCode) }
                ?.dettaglioCorsa(trainNumber, date)
            /*
             * Ultimo, per una data futura: l'orario previsto dalla corsa di
             * **oggi** con lo stesso numero, da qualunque fonte in tempo reale.
             * Un treno che circola ogni giorno ha lo stesso tragitto; si prende
             * quello di oggi, gli si tolgono i dati di oggi e si sposta la data.
             * Niente se oggi quel numero non circola: meglio nessun percorso che
             * quello di un altro treno.
             */
            ?: previstoDaOggi(sources)

        /*
         * Del futuro nessuno conosce il tempo reale, nemmeno le fonti che per
         * quel giorno rispondono: quel che torna e' orario, e come tale va detto.
         */
        return if (date.isAfter(LocalDate.now())) status?.perGiornoFuturo() else status
    }

    /**
     * La corsa gia' identificata, ma solo se e' del giorno che si sta guardando.
     *
     * Un riferimento porta con se' la sua data, ed e' quella a decidere quale
     * corsa apre: chiedere `andamentoTreno` con la data di ieri e intestare la
     * risposta a domani e' il modo piu' diretto per mostrare il ritardo di un
     * giorno sopra il treno di un altro. Chi ci porta qui le tiene gia'
     * d'accordo — tabellone ed elenco corse ricavano la data proprio dal
     * riferimento — quindi qui non si perde niente: si chiude una strada.
     */
    private fun exactRef(): TrainRef? {
        val origine = originCode?.takeIf { it.isNotBlank() } ?: return null
        val millis = departureMillis?.takeIf { it > 0 } ?: return null
        if (Instant.ofEpochMilli(millis).atZone(ROME).toLocalDate() != date) return null
        return TrainRef(trainNumber, origine, millis)
    }

    /**
     * Il tempo reale di una corsa in un dato giorno, dalle fonti che lo hanno.
     *
     * La stessa cascata per la data cercata e per il ripiego su oggi: prima
     * ViaggiaTreno — per la rete nazionale e, dove tace, Trenord — poi Trenord
     * diretto per il regionale lombardo, infine Italo. `exactRef` vale solo per
     * la corsa gia' identificata, quindi solo sulla data originale.
     */
    private suspend fun realtime(giorno: LocalDate, sources: Set<DataSource>): TrainStatus? {
        val at = if (giorno == date) boardingAt else null
        val nazionale = (if (giorno == date) exactRef()?.let { trains.status(it) } else null)
            ?: trains.statusByNumber(trainNumber, giorno, boardingCode, at)

        /*
         * I binari che ViaggiaTreno non ha spesso li ha Trenord, e viceversa:
         * vedi `completaBinari`. Non e' un ripiego ma un'aggiunta, quindi si fa
         * anche quando la risposta nazionale c'e' ed e' completa di tutto il
         * resto — ed e' l'unico modo perche' la fermata da cui sali abbia un
         * binario invece di essere l'unica riga senza.
         */
        if (nazionale != null) {
            return if (DataSource.TRENORD in sources) {
                trains.completaBinari(nazionale, giorno)
            } else {
                nazionale
            }
        }

        return trenord.takeIf { DataSource.TRENORD in sources }?.trainStatus(trainNumber, giorno)
            ?: italo.takeIf { DataSource.ITALO in sources }
                ?.trainStatus(trainNumber, giorno, boardingCode, boardingName, alightingCode)
    }

    /**
     * L'orario previsto ricavato dalla corsa di oggi con lo stesso numero.
     *
     * Vale per **qualsiasi fonte in tempo reale** — Trenitalia, Trenord, Italo —
     * non solo per la rete nazionale: un treno che circola ogni giorno con lo
     * stesso numero ha lo stesso tragitto, e da chiunque lo pubblichi oggi si
     * ricava il percorso di domani. Della corsa di oggi si tiene **solo** il
     * tragitto, binari di tabella compresi: ritardo, stato, binario effettivo e
     * orari reali restano a oggi, dove sono veri — vedi [soloOrarioPrevistoPer].
     *
     * Vale solo per una data futura: per oggi risponde gia' [realtime], e
     * ricopiare se stessi non avrebbe senso.
     */
    private suspend fun previstoDaOggi(sources: Set<DataSource>): TrainStatus? {
        if (date == LocalDate.now()) return null
        val oggi = runCatching { realtime(LocalDate.now(), sources) }.getOrNull() ?: return null

        // Se la corsa di oggi non tocca la stazione da cui si sale, e' un altro
        // treno con lo stesso numero: non lo si spaccia per quello cercato.
        if (boardingCode != null && oggi.stops.none { it.stationCode == boardingCode }) return null

        return oggi.soloOrarioPrevistoPer(
            giorno = date,
            notice = "Percorso, orari e binari di tabella dalla corsa di oggi con lo " +
                "stesso numero. Ritardo, stato e binario effettivo saranno disponibili " +
                "il giorno della partenza.",
        )
    }

    /**
     * Cio' che si sa di una corsa di un **giorno futuro**, dichiarato per quel
     * che e'.
     *
     * Del domani nessuna fonte conosce il tempo reale, ma qualcuna risponde lo
     * stesso, e non a vuoto: il REG 2813 di domani si apriva come "Arrivato",
     * ultimo rilevamento a Lecco alle 06:48, coi ritardi e i binari di ogni
     * fermata. Erano i dati della corsa di stamattina, su un treno che deve
     * ancora partire.
     *
     * Quale fonte l'abbia detto conta meno del fatto che possa capitare: la
     * data queste API la accettano senza promettere di rispettarla, e nessuna
     * avverte quando risponde per un giorno diverso da quello chiesto. Quindi
     * per una data futura vale come orario **qualunque cosa arrivi**, da
     * chiunque. Chi si e' gia' dichiarato senza tempo reale — EAV, ARST —
     * resta com'e', notice compreso: l'ha gia' spiegato da se'.
     */
    private fun TrainStatus.perGiornoFuturo(): TrainStatus =
        if (!realtime) {
            this
        } else {
            soloOrarioPrevistoPer(
                giorno = date,
                notice = "Orario previsto per il giorno scelto. Ritardo, stato e " +
                    "binario effettivo saranno disponibili il giorno della partenza.",
            )
        }
}
