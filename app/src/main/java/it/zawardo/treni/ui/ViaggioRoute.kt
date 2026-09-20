package it.zawardo.treni.ui

import it.zawardo.treni.domain.model.Journey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Una tratta del viaggio come la si passa fra schermate: quel poco che serve a
 * ricaricarla da sola.
 *
 * Non e' [Leg] e non vuole esserlo. Deve attraversare una rotta di navigazione e
 * sopravvivere alla morte del processo, quindi contiene solo tipi che si
 * serializzano, e per ogni tratta le due cose che nessuna API sa da se': **dove
 * sali e dove scendi tu**. La discesa in particolare e' la ragione di tutta la
 * pagina: e' li' che il treno smette di riguardarti, ed e' da li' che le
 * fermate successive si possono chiudere.
 */
@Serializable
data class TrattaViaggio(
    /** Null sulla gamba a piedi, che un numero non ce l'ha. */
    val numero: String? = null,
    /** "REG 2874", "Bus 890A", "10 min a piedi": quel che si legge nel chip. */
    val etichetta: String,
    /** Solo i treni hanno un percorso da interrogare; bus e piedi no. */
    val treno: Boolean,
    /**
     * Il trasferimento a piedi di un viaggio misto, che non e' un mezzo ma
     * l'assenza di mezzo: si disegna con l'omino, non con l'autobus.
     */
    val piedi: Boolean = false,
    val giornoEpoch: Long,
    val salitaRfi: String? = null,
    val salitaNome: String,
    val discesaRfi: String? = null,
    val discesaNome: String,
    /**
     * `0` quando l'orario non e' noto.
     *
     * Succede seguendo **una corsa sola** arrivandoci dal tabellone o dalla
     * ricerca per numero: li' non esiste un viaggio, quindi non esiste un'ora a
     * cui sali. La sentinella a zero, invece di un `Long?`, per la stessa
     * ragione delle coordinate in `ResultsRoute`: i tipi nullable nelle rotte
     * costano un `NavType` scritto a mano.
     */
    val partenzaEpochSec: Long,
    val arrivoEpochSec: Long,
    /** L'origine della corsa, quando la ricerca la dice: vedi `Leg.origineCorsa`. */
    val origineRfi: String? = null,
) {
    val giorno: LocalDate get() = LocalDate.ofEpochDay(giornoEpoch)
    val partenza: LocalDateTime get() = LocalDateTime.ofEpochSecond(partenzaEpochSec, 0, ZoneOffset.UTC)
    val arrivo: LocalDateTime get() = LocalDateTime.ofEpochSecond(arrivoEpochSec, 0, ZoneOffset.UTC)

    /**
     * Gli stessi orari, ma nulli quando non si sanno: servono a distinguere la
     * fermata giusta su una corsa che ripassa dalla stessa stazione, e li' un
     * orario inventato sceglierebbe il passaggio sbagliato.
     */
    val partenzaNota: LocalDateTime? get() = partenza.takeIf { partenzaEpochSec != 0L }
    val arrivoNoto: LocalDateTime? get() = arrivo.takeIf { arrivoEpochSec != 0L }
}

/**
 * Il viaggio intero in una schermata sola, con il treno da cui partire a fuoco.
 *
 * Le tratte viaggiano come JSON dentro un solo parametro invece che come lista
 * tipizzata: le rotte type-safe di Navigation vogliono un `NavType` scritto a
 * mano per ogni tipo composto, e qui non ne varrebbe la pena. La stringa si
 * serializza da se', sopravvive alla morte del processo e non ha altri lettori.
 *
 * [focus] e' l'indice della tratta su cui aprire la pagina: toccando il chip di
 * un treno si arriva su quel treno, toccando la scheda altrove sul primo.
 */
@Serializable
data class ViaggioRoute(
    val tratteJson: String,
    val focus: Int = 0,
)

private val JSON = Json { ignoreUnknownKeys = true }

fun List<TrattaViaggio>.comeJson(): String = JSON.encodeToString(this)

fun tratteDaJson(json: String): List<TrattaViaggio> =
    runCatching { JSON.decodeFromString<List<TrattaViaggio>>(json) }.getOrDefault(emptyList())

/**
 * Le tratte di una soluzione, nell'ordine in cui le si percorre.
 *
 * Il giorno e' quello della **partenza della tratta**, non quello cercato: un
 * viaggio che scavalca la mezzanotte ha la coincidenza il giorno dopo, e
 * chiedere quella corsa con la data di ieri risponde con un altro treno.
 */
fun Journey.tratteDelViaggio(): List<TrattaViaggio> = legs.map { leg ->
    TrattaViaggio(
        numero = leg.trainNumber,
        etichetta = leg.label,
        treno = leg.isTrain,
        piedi = leg.isWalk,
        giornoEpoch = leg.departure.toLocalDate().toEpochDay(),
        salitaRfi = leg.from.rfiCode,
        salitaNome = leg.from.name,
        discesaRfi = leg.to.rfiCode,
        discesaNome = leg.to.name,
        partenzaEpochSec = leg.departure.toEpochSecond(ZoneOffset.UTC),
        arrivoEpochSec = leg.arrival.toEpochSecond(ZoneOffset.UTC),
        origineRfi = leg.origineCorsa,
    )
}
