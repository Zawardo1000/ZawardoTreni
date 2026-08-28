package it.zawardo.treni.data.mapper

import it.zawardo.treni.data.remote.fnb.FnbCorsaDto
import it.zawardo.treni.domain.model.BoardEntry
import it.zawardo.treni.domain.model.TrainRef
import it.zawardo.treni.domain.model.TrainState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Da Ferrotramviaria al modello comune.
 *
 * Il portale pubblica gia' tutto quello che serve a un tabellone — numero,
 * direzione, orario, ritardo, binario, soppressione — quindi qui non si
 * ricostruisce niente: si traduce e si scarta cio' che non regge.
 */

/** `yyyyMMddHHmmss`, l'unico formato in cui il portale scrive gli orari. */
private val ORARIO_FNB: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/**
 * Le sigle di servizio, sciolte.
 *
 * `S` e' l'unica che conti davvero: e' la corsa fatta in parte in treno e in
 * parte in bus, che sulla Andria - Barletta in lavori e' la norma. Chiamarla
 * "Treno" e basta vuol dire far aspettare un treno a chi salira' su un pullman.
 */
private fun etichettaServizio(sigla: String?): String? = when (sigla?.uppercase()) {
    "T" -> "Treno"
    "B" -> "Bus"
    "S" -> "Treno e bus"
    else -> null
}

/**
 * Una riga di tabellone.
 *
 * Null quando manca il numero o l'orario: sono le due cose senza le quali la
 * riga non identifica niente e non si puo' collocare nel tempo. Meglio una
 * corsa in meno che una riga che non si sa cosa sia.
 *
 * [arrivals] non cambia solo quale campo porta l'orario: cambia il significato
 * di `nomeDestinazione`, che fra gli arrivi e' l'origine. Vedi [FnbCorsaDto].
 */
fun FnbCorsaDto.toBoardEntry(arrivals: Boolean): BoardEntry? {
    val numero = numero?.takeIf { it.isNotBlank() } ?: return null
    val grezzo = (if (arrivals) arrivo else partenza)?.takeIf { it.length == 14 } ?: return null
    val quando = runCatching { LocalDateTime.parse(grezzo, ORARIO_FNB) }.getOrNull() ?: return null

    /*
     * Un ritardo assente non e' un ritardo di zero.
     *
     * Il portale omette il campo finche' la corsa non e' monitorata. Il modello
     * comune vuole un intero, quindi diventa zero come per le altre sorgenti,
     * ma lo stato resta REGULAR e non si scrive "in orario" da nessuna parte:
     * quello che non si sa non si racconta.
     */
    val ritardo = ritardo?.coerceAtLeast(0) ?: 0
    val categoria = etichettaServizio(servizio)

    return BoardEntry(
        trainRef = TrainRef(
            number = numero,
            // Il portale non espone un codice di origine: la corsa si identifica
            // col numero, che e' anche quello stampato sull'orario cartaceo.
            originCode = "",
            departureDateMillis = quando.toLocalDate().atStartOfDay(ROME).toInstant().toEpochMilli(),
        ),
        label = listOfNotNull(categoria, numero).joinToString(" "),
        category = categoria,
        direction = nomeDestinazione?.takeIf { it.isNotBlank() },
        scheduledTime = "%02d:%02d".format(quando.hour, quando.minute),
        delayMinutes = ritardo,
        // Un solo binario, quello vero: non c'e' il programmato da confrontare.
        scheduledPlatform = null,
        actualPlatform = binarioEffettivo?.takeIf { it.isNotBlank() },
        state = when {
            soppressa.equals("Y", ignoreCase = true) -> TrainState.CANCELLED
            ritardo > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        // Il tabellone non dice se il treno e' gia' in banchina.
        inStation = false,
    )
}
