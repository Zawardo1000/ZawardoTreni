package it.zawardo.treni.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * La stessa corsa ridotta al suo **orario previsto** di un dato giorno: le
 * fermate e gli orari di tabella, e nient'altro.
 *
 * Serve dove il percorso si conosce ma il tempo reale no, e capita in due modi
 * che si somigliano piu' di quanto sembri:
 *
 *  - il giorno diverso da oggi ricostruito dalla corsa odierna con lo stesso
 *    numero, che di quel giorno non sa niente;
 *  - la fonte che a una data futura **risponde lo stesso**, ma coi dati di
 *    oggi. Succede: il REG 2813 di domani si apriva come "Arrivato", ultimo
 *    rilevamento a Lecco alle 06:48, coi ritardi e i binari fermata per
 *    fermata. Erano i dati della corsa di stamattina. Tutto vero, tutto di un
 *    altro giorno. Queste API la data la accettano senza promettere di
 *    rispettarla, e nessuna avverte quando risponde per un giorno diverso.
 *
 * Quel che si tiene e' il tragitto — le fermate, in che ordine, a che ora — e
 * quel che si butta e' tutto cio' che appartiene alla giornata da cui viene:
 * ritardo, stato, ultimo rilevamento, orari reali e proiettati, binari. Anche i
 * binari, benche' siano di tabella: quello scritto e' quello della corsa di
 * oggi, e per domani non l'ha confermato nessuno.
 *
 * Non e' una pulizia cosmetica. Un treno di domani con addosso il ritardo di
 * oggi non dice una cosa imprecisa: dice una cosa falsa su un treno che non e'
 * ancora partito, e la dice con l'aria di saperla.
 *
 * Gli orari si spostano ancorandosi al **giorno della corsa stessa**, non a
 * oggi: cosi' funziona sia con chi risponde per il giorno chiesto (spostamento
 * zero) sia con chi risponde per il proprio, e i treni che scavallano la
 * mezzanotte mantengono le distanze fra una fermata e l'altra.
 *
 * Lo [TrainStatus.state] diventa [TrainState.NOT_DEPARTED] perche' e' quel che
 * dicono le fermate, tutte future e nessuna effettuata — la stessa regola del
 * mapper Trenord. Non e' un giudizio sulla puntualita': quello lo nega
 * [TrainStatus.realtime], che resta l'unica cosa da guardare prima di scrivere
 * un ritardo a schermo.
 *
 * @param giorno il giorno a cui la corsa si riferisce davvero.
 * @param notice il perche', da mostrare in cima al dettaglio. Per default resta
 *   quello che c'e' gia'.
 */
fun TrainStatus.soloOrarioPrevistoPer(
    giorno: LocalDate,
    notice: String? = this.notice,
): TrainStatus {
    val partenza = stops.firstNotNullOfOrNull { it.scheduledDeparture ?: it.scheduledArrival }
    val giorni = partenza?.let { ChronoUnit.DAYS.between(it.toLocalDate(), giorno) } ?: 0L

    fun sposta(t: LocalDateTime?): LocalDateTime? =
        if (giorni == 0L) t else t?.plusDays(giorni)

    return copy(
        delayMinutes = 0,
        state = TrainState.NOT_DEPARTED,
        realtime = false,
        lastDetectionStation = null,
        lastDetectionTime = null,
        notice = notice,
        stops = stops.map { fermata ->
            fermata.copy(
                scheduledArrival = sposta(fermata.scheduledArrival),
                scheduledDeparture = sposta(fermata.scheduledDeparture),
                actualArrival = null,
                actualDeparture = null,
                arrivalDelayMinutes = 0,
                departureDelayMinutes = 0,
                scheduledPlatform = null,
                actualPlatform = null,
                projectedArrival = null,
                projectedDeparture = null,
                status = StopStatus.FUTURE,
                /*
                 * Non "non rilevata": una fermata futura non e' un passaggio
                 * mancato, e scriverlo su venti righe direbbe che manca
                 * qualcosa che non poteva esserci.
                 */
                detected = true,
            )
        },
    )
}
