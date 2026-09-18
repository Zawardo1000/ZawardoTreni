package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Proietta il ritardo corrente sulle fermate non ancora effettuate.
 *
 * ViaggiaTreno lascia `ritardoArrivo` e `ritardoPartenza` a zero su tutte le
 * fermate future, anche quando la corsa e' dichiarata in ritardo: verificato su
 * un FR a +8 minuti con quattro fermate future tutte a zero. Senza questo
 * ricalcolo l'app direbbe che il treno arriva in orario mentre e' in ritardo.
 *
 * E' una stima lineare: non sa nulla di recuperi di orario sulle tratte veloci
 * ne' di soste comprimibili. Resta molto piu' vicina al vero dello zero.
 */
internal fun Stop.projectedBy(delayMinutes: Int): Stop {
    if (status != StopStatus.FUTURE || delayMinutes == 0) return this
    return copy(
        arrivalDelayMinutes = delayMinutes,
        departureDelayMinutes = delayMinutes,
        projectedArrival = scheduledArrival?.plusMinutes(delayMinutes.toLong()),
        projectedDeparture = scheduledDeparture?.plusMinutes(delayMinutes.toLong()),
    )
}

/**
 * Il ritardo di un treno fermo all'origine oltre la sua ora.
 *
 * ViaggiaTreno lo lascia a zero finche' il treno non si muove. Il 17/09/2026
 * alle 12:29 il RE 2824 delle 12:20 da Milano Centrale era «non partito» e
 * basta: nell'elenco nessun ritardo, e la soluzione mancava fra quelle ancora
 * prendibili, perche' a ritardo zero risultava partita da nove minuti.
 * Sondato il 18/09/2026 alle 10:00: il FR 9628 delle 09:50 e il REG 2934 delle
 * 09:55 erano fermi all'origine, tutti e due con `ritardo = 0`.
 *
 * Un treno ancora in partenza dopo la sua ora e' in ritardo almeno di quanto
 * e' passato, e lo diventa di minuto in minuto. E' un minimo: quando
 * ViaggiaTreno annuncia di piu' — lo fa, un FR in attesa del materiale dato a
 * +43 prima di muoversi — vale il suo.
 *
 * **Solo all'origine.** Alle fermate intermedie un'ora di partenza mancante non
 * prova che il treno sia ancora li': dove manca il rilevamento la partenza non
 * la scrive nessuno. E va applicato solo al «non partito» di ViaggiaTreno, che
 * lo dichiara: le altre fonti lo deducono dall'assenza di fermate fatte, che su
 * una corsa non tracciata e' la regola, non un indizio.
 */
fun TrainStatus.conRitardoDaFermo(adesso: LocalDateTime): TrainStatus {
    if (!realtime || state != TrainState.NOT_DEPARTED) return this
    val origine = stops.firstOrNull { it.status != StopStatus.CANCELLED } ?: return this
    if (origine.actualDeparture != null) return this
    val tabella = origine.scheduledDeparture ?: return this
    val fermo = Duration.between(tabella, adesso).toMinutes().toInt()
    if (fermo <= delayMinutes) return this
    return copy(delayMinutes = fermo, stops = stops.map { it.projectedBy(fermo) })
}

/**
 * Lo stesso minimo sul tabellone delle partenze, nella stazione d'origine della
 * corsa: li' l'ora della riga e' proprio quella di partenza dall'origine.
 *
 * Nelle altre stazioni la riga non sa quando il treno doveva lasciare
 * l'origine, e il ritardo lo porta la corsa quando la si interroga: vedi
 * [conRitardoDa].
 */
fun BoardEntry.conRitardoDaFermo(stazione: String, adesso: LocalDateTime): BoardEntry {
    if (!realtime || state != TrainState.NOT_DEPARTED) return this
    if (!stessaStazione(trainRef.originCode, stazione)) return this
    val tabella = runCatching { LocalTime.parse(scheduledTime) }.getOrNull() ?: return this
    val fermo = ChronoUnit.MINUTES.between(tabella, adesso.toLocalTime()).toInt().let {
        // Mezzanotte in mezzo: le 23:55 viste alle 00:05 sono dieci minuti fa.
        when {
            it < -MEZZA_GIORNATA -> it + GIORNATA
            it > MEZZA_GIORNATA -> it - GIORNATA
            else -> it
        }
    }
    if (fermo <= delayMinutes) return this
    return copy(delayMinutes = fermo)
}

/**
 * Il ritardo della corsa sulla riga del tabellone, quando il treno non e'
 * ancora partito dall'origine: la riga lo porta a zero, la corsa col minimo di
 * [TrainStatus.conRitardoDaFermo].
 *
 * Solo in quel caso. A treno partito il ritardo del tabellone e' una misura, e
 * sostituirlo con quello della corsa vorrebbe dire scegliere fra due misure;
 * qui invece c'e' una misura sola, e l'altro e' uno zero che non dice niente.
 */
fun BoardEntry.conRitardoDa(corsa: TrainStatus): BoardEntry {
    if (state != TrainState.NOT_DEPARTED || corsa.state != TrainState.NOT_DEPARTED) return this
    if (corsa.delayMinutes <= delayMinutes) return this
    return copy(delayMinutes = corsa.delayMinutes)
}

private const val MEZZA_GIORNATA = 720
private const val GIORNATA = 1440
