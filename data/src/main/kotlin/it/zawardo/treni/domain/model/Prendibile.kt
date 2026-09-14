package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

/**
 * La fermata di una corsa in una stazione; se la corsa ci passa piu' volte,
 * quella all'ora giusta.
 *
 * Il codice RFI da solo non basta: una corsa puo' ripassare dalla stessa
 * stazione, ed e' l'orario a dire di quale dei due passaggi si parli. Vale sia
 * l'arrivo sia la partenza, perche' chi chiede puo' avere in mano l'uno o
 * l'altra: il tabellone degli arrivi scrive l'ora d'arrivo, una soluzione di
 * viaggio l'ora in cui sali. Si confronta l'ora del giorno e non l'istante,
 * come in [conBinariDa]: una corsa a cavallo della mezzanotte due fonti la
 * datano in modo diverso. Senza orario, il primo passaggio.
 *
 * La stazione si riconosce con [stessaStazione], non col codice nudo: le Frecce
 * di Bologna Centrale nel dettaglio fermano a "Bologna C.le/AV", che ha un codice
 * suo.
 */
fun TrainStatus.fermataA(codice: String?, ora: LocalTime?): Stop? {
    if (codiceStazione(codice) == null) return null
    return stops
        .filter { stessaStazione(it.stationCode, codice) }
        .minByOrNull { fermata -> ora?.let { fermata.secondiDa(it) } ?: 0L }
}

/** Quanto la fermata dista da un'ora del giorno, d'arrivo o di partenza che sia. */
private fun Stop.secondiDa(ora: LocalTime): Long =
    listOfNotNull(scheduledArrival, scheduledDeparture).minOfOrNull { t ->
        val secondi = abs(Duration.between(t.toLocalTime(), ora).seconds)
        minOf(secondi, 86_400 - secondi)
    } ?: Long.MAX_VALUE

/**
 * Quando la corsa partira' davvero da [codice], se da li' non e' ancora
 * partita; null se e' gia' partita, se li' e' soppressa, o se non si sa.
 *
 * **"Gia' partita" lo dice l'orario reale di partenza, non lo stato della
 * fermata.** ViaggiaTreno la segna effettuata gia' all'arrivo, e un treno fermo
 * in banchina e' proprio quello che si sta rincorrendo. Conta invece come
 * partita la fermata dopo la quale il treno e' gia' stato visto, e quella
 * effettuata senza rilevamento: il passaggio c'e' stato, anche se l'ora no.
 *
 * La stima e' quella del resto dell'app — il ritardo corrente portato in
 * avanti, vedi [Stop.projectedDeparture] — e un recupero la puo' smentire. Non
 * scende mai sotto la tabella: un treno in anticipo non riparte prima dell'ora
 * scritta.
 */
fun TrainStatus.partenzaStimataDa(codice: String?, previsto: LocalDateTime): LocalDateTime? {
    if (state == TrainState.CANCELLED) return null
    val fermata = fermataA(codice, previsto.toLocalTime()) ?: return null
    if (fermata.status == StopStatus.CANCELLED) return null

    val dopo = stops.drop(stops.indexOf(fermata) + 1)
    val partita = fermata.actualDeparture != null ||
        (fermata.status == StopStatus.DONE && !fermata.detected) ||
        dopo.any { it.status == StopStatus.DONE || it.status == StopStatus.CURRENT }
    if (partita) return null

    val tabella = fermata.scheduledDeparture ?: return null
    val stima = fermata.projectedDeparture ?: tabella.plusMinutes(delayMinutes.toLong())
    return maxOf(tabella, stima)
}

/**
 * Quando la corsa arrivera' davvero a [codice]: l'orario reale se c'e' gia',
 * altrimenti la tabella piu' il ritardo corrente.
 */
fun TrainStatus.arrivoStimatoA(codice: String?, previsto: LocalDateTime): LocalDateTime? {
    val fermata = fermataA(codice, previsto.toLocalTime()) ?: return null
    fermata.actualArrival?.let { return it }
    val tabella = fermata.scheduledArrival ?: return null
    return fermata.projectedArrival ?: tabella.plusMinutes(delayMinutes.toLong())
}

/**
 * Il margine che si chiede a un cambio, quando l'orario ne lasciava di piu'.
 *
 * Non e' il tempo minimo di interscambio della stazione, che nessuna fonte
 * pubblica: e' quanto basta per scendere e cercare il binario, e resta sotto
 * quello che Le Frecce pianifica di solito.
 */
private val MARGINE_CAMBIO: Duration = Duration.ofMinutes(3)

/**
 * La coincidenza regge ancora, con il primo treno che arriva ad [arrivo]
 * invece che all'ora di tabella.
 *
 * Si chiede un margine, **mai piu' di quanto ne lasciasse l'orario**: il cambio
 * che Le Frecce pianifica in due minuti sulla stessa banchina resta valido con
 * due, uno da dieci ne vuole almeno [MARGINE_CAMBIO].
 *
 * Il secondo treno si considera in orario. Se e' in ritardo anche lui la
 * coincidenza magari regge, ma saperlo vorrebbe dire interrogarlo, e qui si
 * preferisce tacere una soluzione possibile che proporne una persa.
 */
fun coincidenzaRegge(arrivo: LocalDateTime, primo: Leg, secondo: Leg): Boolean {
    val pianificato = Duration.between(primo.arrival, secondo.departure)
    val margine = minOf(MARGINE_CAMBIO, maxOf(pianificato, Duration.ZERO))
    return !arrivo.plus(margine).isAfter(secondo.departure)
}

/**
 * La partenza stimata di una soluzione gia' passata in tabella prima di
 * [dalle], se la si prende ancora; null altrimenti.
 *
 * [primo] e' lo stato del primo treno. Senza — una corsa che ViaggiaTreno non
 * conosce — resta il ritardo che Trenord dichiara sulla soluzione stessa: non
 * dice se il treno sia gia' partito, ma una stima che cade dopo [dalle] parla
 * di un treno che non puo' esserlo.
 *
 * Su un viaggio con cambio non basta arrivare in tempo al primo treno: il suo
 * ritardo puo' costare la coincidenza, e una soluzione che non si puo' fare non
 * e' "ancora prendibile". Vedi [coincidenzaRegge].
 */
fun Journey.partenzaAncoraUtile(primo: TrainStatus?, dalle: LocalDateTime): LocalDateTime? {
    if (cancelled) return null
    val salita = legs.firstOrNull()?.takeIf { it.isTrain } ?: return null

    val partenza: LocalDateTime
    val arrivo: LocalDateTime
    if (primo != null) {
        partenza = primo.partenzaStimataDa(salita.from.rfiCode, salita.departure) ?: return null
        arrivo = primo.arrivoStimatoA(salita.to.rfiCode, salita.arrival)
            ?: salita.arrival.plus(Duration.between(salita.departure, partenza))
    } else {
        val ritardo = delayMinutes?.takeIf { it > 0 } ?: return null
        partenza = salita.departure.plusMinutes(ritardo.toLong())
        arrivo = salita.arrival.plusMinutes(ritardo.toLong())
    }

    // Al minuto, come la ricerca: "adesso" porta anche i secondi.
    if (partenza.isBefore(dalle.withSecond(0).withNano(0))) return null
    val poi = legs.getOrNull(1)
    if (poi != null && !coincidenzaRegge(arrivo, salita, poi)) return null
    return partenza
}
