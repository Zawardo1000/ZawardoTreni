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
 * Quanto ritardo al cambio si conta che venga riassorbito: il primo treno che
 * recupera per strada, il secondo che lo aspetta.
 *
 * Dieci minuti, deciso con l'utente il 18/09/2026. Oltre, la soluzione si
 * tacerebbe comunque: un treno che arriva un quarto d'ora dopo la partenza
 * della coincidenza non la prende.
 */
private val RECUPERO: Duration = Duration.ofMinutes(10)

/**
 * La coincidenza regge ancora, con il primo treno che arriva ad [arrivo]
 * invece che all'ora di tabella, e il secondo che parte a [partenzaSecondo].
 *
 * Si chiede un margine, **mai piu' di quanto ne lasciasse l'orario**: il cambio
 * che Le Frecce pianifica in due minuti sulla stessa banchina resta valido con
 * due, uno da dieci ne vuole almeno [MARGINE_CAMBIO]. Il margine si misura
 * sull'orario di tabella dei due treni, perche' e' quello a dire quanto sia
 * lungo il cambio a piedi; il ritardo del secondo sposta solo la scadenza.
 */
fun coincidenzaRegge(
    arrivo: LocalDateTime,
    primo: Leg,
    secondo: Leg,
    partenzaSecondo: LocalDateTime = secondo.departure,
): Boolean {
    val pianificato = Duration.between(primo.arrival, secondo.departure)
    val margine = minOf(MARGINE_CAMBIO, maxOf(pianificato, Duration.ZERO))
    return !arrivo.plus(margine).isAfter(partenzaSecondo)
}

/**
 * La partenza stimata di una soluzione gia' passata in tabella prima di
 * [dalle], se il primo treno lo si prende ancora; null altrimenti.
 *
 * [primo] e' lo stato del primo treno. Senza — una corsa che ViaggiaTreno non
 * conosce — resta il ritardo che Trenord dichiara sulla soluzione stessa: non
 * dice se il treno sia gia' partito, ma una stima che cade dopo [dalle] parla
 * di un treno che non puo' esserlo.
 *
 * Su un viaggio con cambio non basta: il ritardo del primo treno puo' costare la
 * coincidenza. Quello lo dice [coincidenza], a parte, perche' per saperlo puo'
 * servire interrogare anche il secondo treno.
 */
fun Journey.partenzaAncoraUtile(primo: TrainStatus?, dalle: LocalDateTime): LocalDateTime? {
    if (cancelled) return null
    val salita = legs.firstOrNull()?.takeIf { it.isTrain } ?: return null

    val partenza = if (primo != null) {
        primo.partenzaStimataDa(salita.from.rfiCode, salita.departure) ?: return null
    } else {
        val ritardo = delayMinutes?.takeIf { it > 0 } ?: return null
        salita.departure.plusMinutes(ritardo.toLong())
    }

    // Al minuto, come la ricerca: "adesso" porta anche i secondi.
    if (partenza.isBefore(dalle.withSecond(0).withNano(0))) return null
    return partenza
}

/**
 * Il primo cambio del viaggio: il primo treno e la tratta con cui prosegue, se
 * quella ha un orario da prendere; null altrimenti.
 *
 * Il primo **treno**, non la prima tratta: «Urbano › RE 10911» comincia col
 * tratto urbano, e il ritardo che si conosce e' quello del treno. La tratta dopo
 * deve partire a un'ora fissa, come un treno o un bus sostitutivo. Il tratto
 * urbano no, perche' la metropolitana passa ogni pochi minuti; e nemmeno il
 * tratto a piedi, dopo il quale il cambio vero e' con la tratta successiva, a
 * una distanza che il margine di [coincidenzaRegge] non conosce. Li' la
 * coincidenza non si giudica, invece di giudicarla male.
 */
fun Journey.primoCambio(): Pair<Leg, Leg>? {
    val i = legs.indexOfFirst { it.isTrain }
    if (i < 0) return null
    val poi = legs.getOrNull(i + 1)?.takeIf { !it.isWalk && !it.urbano } ?: return null
    return legs[i] to poi
}

/** Come sta la coincidenza di un viaggio il cui primo treno e' in ritardo. */
enum class Coincidenza {
    REGGE,

    /** Coi ritardi di adesso si perde, ma di poco: vedi [RECUPERO]. */
    A_RISCHIO,

    PERSA,
}

/**
 * La coincidenza al primo cambio ([primoCambio]), col ritardo che il primo
 * treno ha adesso. Senza un cambio da giudicare, regge.
 *
 * Il secondo treno e' [secondo]; senza, lo si considera in orario. Chi chiama
 * lo interroga solo se in orario la coincidenza non reggerebbe: se regge
 * gia' cosi', il suo ritardo non puo' che allargarla.
 *
 * **Una coincidenza persa di poco si propone lo stesso**, dichiarata a rischio.
 * Il 17/09/2026 il RE 2824 delle 12:20 da Milano Centrale, con cambio a Monza,
 * partiva in ritardo, e l'S8 della coincidenza lo era anche lui: con due treni
 * in ritardo sulla stessa linea, tacere la soluzione era la risposta sbagliata.
 * Cosi' si decide fino a
 * [RECUPERO] oltre la partenza del secondo; piu' in la', o col secondo gia'
 * partito dal cambio prima dell'arrivo del primo, o soppresso, e' persa davvero.
 * Partito dopo, l'ha aspettato, e regge.
 */
fun Journey.coincidenza(primo: TrainStatus?, secondo: TrainStatus?): Coincidenza {
    val (salita, poi) = primoCambio() ?: return Coincidenza.REGGE

    val ritardo = (primo?.delayMinutes ?: delayMinutes ?: 0).coerceAtLeast(0)
    val arrivo = primo?.arrivoStimatoA(salita.to.rfiCode, salita.arrival)
        ?: salita.arrival.plusMinutes(ritardo.toLong())

    val partenzaPoi = when {
        secondo == null -> poi.departure
        secondo.state == TrainState.CANCELLED -> return Coincidenza.PERSA
        // Una corsa che non passa di li' e' quella sbagliata: resta la tabella.
        secondo.fermataA(poi.from.rfiCode, poi.departure.toLocalTime()) == null -> poi.departure
        else -> secondo.partenzaStimataDa(poi.from.rfiCode, poi.departure) ?: run {
            /*
             * Gia' partito dal cambio. Nell'elenco si guardano anche i viaggi di
             * stamattina, e un secondo treno partito dopo l'arrivo del primo l'ha
             * aspettato: la coincidenza c'e' stata. Partito prima, e' persa, senza
             * il margine di [RECUPERO]: un treno partito non recupera piu' niente.
             */
            val partito = secondo.fermataA(poi.from.rfiCode, poi.departure.toLocalTime())
                ?.actualDeparture ?: return Coincidenza.PERSA
            return if (partito.isBefore(arrivo)) Coincidenza.PERSA else Coincidenza.REGGE
        }
    }

    return when {
        coincidenzaRegge(arrivo, salita, poi, partenzaPoi) -> Coincidenza.REGGE
        !arrivo.isAfter(partenzaPoi.plus(RECUPERO)) -> Coincidenza.A_RISCHIO
        else -> Coincidenza.PERSA
    }
}
