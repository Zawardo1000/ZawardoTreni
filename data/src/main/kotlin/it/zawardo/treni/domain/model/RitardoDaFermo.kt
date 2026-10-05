package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

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
 *
 * **E non dove il treno da li' non parte, o ViaggiaTreno non lo vede partire**,
 * cosa che questa funzione non puo' sapere: lo decide chi la chiama, con
 * [vedePartireDa] sulla corsa del giorno prima quando c'e'. Vedi
 * `TrainStatusRepository.nonPartitoVuolDireFermo`.
 *
 * **Ed e' una deduzione, e lo dichiara** ([TrainStatus.ritardoDedotto]): «non
 * partito» vuol dire anche «nessuno l'ha visto partire», e le due cose non si
 * distinguono finche' nessuna fonte rileva niente. Il REG 24860 del 04/10/2026
 * era partito, e ViaggiaTreno non l'ha visto in nessuna stazione. Prima di
 * fidarsene si chiede a Trenord: vedi [conPassaggiDa].
 */
fun TrainStatus.conRitardoDaFermo(adesso: LocalDateTime): TrainStatus {
    if (!realtime || state != TrainState.NOT_DEPARTED) return this
    val origine = stops.firstOrNull { it.status != StopStatus.CANCELLED } ?: return this
    if (origine.actualDeparture != null) return this
    val tabella = origine.scheduledDeparture ?: return this
    val fermo = Duration.between(tabella, adesso).toMinutes().toInt()
    if (fermo <= delayMinutes) return this
    return copy(delayMinutes = fermo, stops = stops.map { it.projectedBy(fermo) }, ritardoDedotto = true)
}

/**
 * I passaggi di un'altra lettura della stessa corsa, su una corsa che non ne ha
 * nessuno.
 *
 * Per il treno che ViaggiaTreno non ha visto partire ([TrainStatus.ritardoDedotto]):
 * Trenord rileva le sue corse per conto suo — il 05/10/2026 sull'S8 24829 aveva
 * partenza e arrivo reali a ognuna delle 14 fermate, al mezzo minuto — e se li'
 * il treno e' partito, il «non partito» era solo silenzio. Allora la corsa
 * prende gli orari veri di Trenord fermata per fermata, e ritardo, stato e
 * ultimo rilevamento si ricalcolano da quelli.
 *
 * Solo su una corsa **senza alcun rilevamento**: dove ViaggiaTreno ha misurato
 * qualcosa le due misure non si mescolano. E solo se le due letture sono **la
 * stessa corsa**, per stazione e orario di tabella come in [conBinariDa]: lo
 * stesso numero puo' essere di due treni diversi.
 *
 * Se anche Trenord tace, la corsa resta com'era, col suo ritardo dedotto e col
 * segno che lo dice.
 */
fun TrainStatus.conPassaggiDa(altra: TrainStatus): TrainStatus {
    if (!realtime) return this
    if (stops.any { it.actualArrival != null || it.actualDeparture != null }) return this
    val fatte = altra.stops.filter { it.actualArrival != null || it.actualDeparture != null }
    if (fatte.isEmpty()) return this

    var copiate = 0
    val nuove = stops.map { fermata ->
        if (fermata.status == StopStatus.CANCELLED) return@map fermata
        val fonte = fatte.firstOrNull {
            stessaStazione(fermata.stationCode, it.stationCode) && fermata.eLaStessaFermataDi(it)
        } ?: return@map fermata
        copiate++
        fermata.copy(
            actualArrival = fonte.actualArrival,
            arrivalDelayMinutes = fonte.actualArrival?.let { fonte.arrivalDelayMinutes } ?: 0,
            actualDeparture = fonte.actualDeparture,
            departureDelayMinutes = fonte.actualDeparture?.let { fonte.departureDelayMinutes } ?: 0,
            status = StopStatus.DONE,
            projectedArrival = null,
            projectedDeparture = null,
        )
    }
    if (copiate == 0) return this

    /*
     * Le fermate prima dell'ultima rilevata che Trenord non ha accoppiato sono
     * passate anche loro: fatte e non rilevate, come le da' ViaggiaTreno dove
     * manca il punto di rilevamento. Lasciate da fare, avrebbero portato un
     * orario stimato prima di una fermata gia' fatta.
     */
    val indiceUltima = nuove.indexOfLast { it.status == StopStatus.DONE }
    val coerenti = nuove.mapIndexed { i, fermata ->
        if (i < indiceUltima && fermata.status == StopStatus.FUTURE) {
            fermata.copy(status = StopStatus.DONE, detected = false, projectedArrival = null, projectedDeparture = null)
        } else {
            fermata
        }
    }
    val ultima = coerenti[indiceUltima]
    val ritardo = if (ultima.actualDeparture != null) ultima.departureDelayMinutes else ultima.arrivalDelayMinutes
    val arrivata = coerenti.all { it.status == StopStatus.DONE || it.status == StopStatus.CANCELLED }
    return copy(
        delayMinutes = ritardo,
        state = when {
            arrivata -> TrainState.ARRIVED
            // Le variazioni che il «non partito» copriva, dalle fermate come nel
            // mapper di ViaggiaTreno: Trenord dice solo che il treno si muove.
            coerenti.any { it.status == StopStatus.CANCELLED } -> TrainState.PARTIALLY_CANCELLED
            coerenti.any { it.straordinaria } -> TrainState.DIVERTED
            ritardo > 0 -> TrainState.DELAYED
            else -> TrainState.REGULAR
        },
        lastDetectionStation = ultima.stationName,
        lastDetectionTime = ultima.actualDeparture ?: ultima.actualArrival,
        stops = coerenti.map { it.projectedBy(ritardo) },
        ritardoDedotto = false,
    )
}

/**
 * Il ritardo che il tempo passato impone, fra un rilevamento e il successivo.
 *
 * Il ritardo di ViaggiaTreno e' quello dell'**ultimo rilevamento**, ed e' giusto
 * cosi'. Ma se il treno si pianta subito dopo, quel numero resta fermo mentre
 * l'orologio no: rilevato alle 08:30 a +2, con la fermata dopo prevista alle
 * 08:35, alle 08:40 la corsa diceva ancora +2 — e insieme che sarebbe arrivata
 * alle 08:37, tre minuti prima di adesso. Due cose che non possono essere vere
 * insieme, e quella sbagliata e' il ritardo: se alle 08:40 nessuno l'ha ancora
 * visto passare, il ritardo e' almeno di cinque minuti, e cresce ogni minuto
 * finche' un rilevamento non dice altro.
 *
 * E' la stessa regola del treno fermo all'origine ([conRitardoDaFermo]), spostata
 * al punto dove la corsa e' arrivata: **un orario previsto non puo' stare nel
 * passato**. Si misura sulla prima fermata non ancora effettuata, ed e' un
 * minimo — quando la fonte dichiara di piu', vale il suo.
 *
 * Tre condizioni, tutte necessarie:
 *
 *  - **la corsa dev'essere seguita davvero** ([lastDetectionTime] non nullo).
 *    Senza alcun rilevamento non si deduce niente: una corsa che nessuno traccia
 *    ha tutte le fermate future per sempre, e da qui uscirebbe un ritardo
 *    inventato che cresce all'infinito. Il treno non ancora partito ha la sua
 *    regola, che guarda l'origine;
 *  - **dev'essere in viaggio**: arrivata o soppressa, non c'e' piu' niente da
 *    aspettare;
 *  - **dev'esserci un orario di tabella** sulla prima fermata che manca.
 *
 * Resta un limite noto, ed e' lo stesso di ogni deduzione dal silenzio: dove i
 * rilevamenti sono radi il treno puo' essere passato in orario senza che nessuno
 * lo scriva, e li' questo ritardo e' di troppo. Vale comunque la pena: il caso
 * frequente e' il treno fermo, e dire «+2, arrivo alle 08:37» alle 08:40 e'
 * sbagliato in un modo che si vede.
 */
fun TrainStatus.conRitardoDalTempoPassato(adesso: LocalDateTime): TrainStatus {
    if (!realtime) return this
    if (state == TrainState.CANCELLED || state == TrainState.ARRIVED) return this
    // Il non partito ha la sua regola, ed e' l'unica che sappia guardare l'origine.
    if (state == TrainState.NOT_DEPARTED) return this
    if (lastDetectionTime == null) return this

    val prossima = stops.firstOrNull { it.status == StopStatus.FUTURE } ?: return this
    val tabella = prossima.scheduledArrival ?: prossima.scheduledDeparture ?: return this
    val passato = Duration.between(tabella, adesso).toMinutes().toInt()
    if (passato <= delayMinutes) return this
    return copy(delayMinutes = passato, stops = stops.map { it.projectedBy(passato) })
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
    // Mezzanotte in mezzo: le 23:55 viste alle 00:05 sono dieci minuti fa.
    val fermo = minutiCircolari(tabella, adesso.toLocalTime())
    if (fermo <= delayMinutes) return this
    return copy(delayMinutes = fermo, ritardoDedotto = true)
}

/**
 * Se in questa corsa ViaggiaTreno ha visto il treno partire da [origine].
 *
 * Vero con l'orario reale di partenza. Falso se l'ha visto solo piu' avanti:
 * o da li' la partenza non si rileva, o da li' il treno non e' partito affatto,
 * come il 2987 del 18/09/2026 che la corsa dava da Gallarate e partiva da
 * Saronno. In tutti e due i casi il suo «non partito» non vuol dire fermo li'.
 * Null se la corsa non lo dice, perche' non ha rilevamenti o perche' li' era
 * soppressa.
 *
 * Si chiede alla corsa del giorno prima, gia' fatta, come prova per oggi: vedi
 * `TrainStatusRepository.nonPartitoVuolDireFermo`.
 */
fun TrainStatus.vedePartireDa(origine: String): Boolean? {
    val fermata = stops.firstOrNull { stessaStazione(it.stationCode, origine) } ?: return null
    if (fermata.status == StopStatus.CANCELLED) return null
    if (fermata.actualDeparture != null) return true
    val rilevata = stops.any { it.actualArrival != null || it.actualDeparture != null }
    return if (rilevata) false else null
}

/**
 * Il ritardo della corsa sulla riga del tabellone, quando il treno non e'
 * ancora partito dall'origine: la riga lo porta a zero, la corsa col minimo di
 * [TrainStatus.conRitardoDaFermo].
 *
 * Solo in quel caso. A treno partito il ritardo del tabellone e' una misura, e
 * sostituirlo con quello della corsa vorrebbe dire scegliere fra due misure;
 * qui invece c'e' una misura sola, e l'altro e' uno zero che non dice niente.
 *
 * **Salvo un ritardo dedotto che la corsa smentisce**: se la riga contava il
 * tempo passato da un treno mai visto partire ([BoardEntry.ritardoDedotto]) e
 * la corsa, coi passaggi di Trenord ([conPassaggiDa]), l'ha visto partire, quel
 * «non partito» era solo silenzio, e valgono stato e ritardo della corsa, che
 * sono misure. Senza ritardo dedotto la regola di sopra resta com'era.
 */
fun BoardEntry.conRitardoDa(corsa: TrainStatus): BoardEntry {
    if (state != TrainState.NOT_DEPARTED) return this
    val partita = corsa.stops.any { it.actualArrival != null || it.actualDeparture != null }
    if (ritardoDedotto && corsa.state != TrainState.NOT_DEPARTED && partita) {
        return copy(delayMinutes = corsa.delayMinutes, state = corsa.state, ritardoDedotto = false)
    }
    if (corsa.state != TrainState.NOT_DEPARTED) return this
    if (corsa.delayMinutes <= delayMinutes) return this
    return copy(delayMinutes = corsa.delayMinutes, ritardoDedotto = corsa.ritardoDedotto)
}
