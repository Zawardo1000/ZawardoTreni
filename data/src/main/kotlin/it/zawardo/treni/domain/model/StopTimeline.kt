package it.zawardo.treni.domain.model

/**
 * Rende coerente la lista delle fermate.
 *
 * I dati di ViaggiaTreno arrivano a pezzi, e i buchi non sono in fondo ma in
 * mezzo: una fermata senza orari reali fra due che ce li hanno, oppure ancora
 * marcata futura mentre il treno e' gia' oltre. Presa alla lettera, quella lista
 * disegna un treno che torna indietro.
 *
 * La regola e' una sola e viene dalla geografia, non dai dati: quello che sta
 * prima dell'ultima fermata effettuata e' passato. Se di quel passaggio non
 * risulta nulla lo si dichiara, invece di far credere che sia filato in orario.
 *
 * Le soppresse restano intoccate: non sono un buco, sono un fatto.
 */
fun List<Stop>.consolidate(): List<Stop> = colmaBuchiPassati().segnaPosizione()

private fun List<Stop>.colmaBuchiPassati(): List<Stop> {
    val ultimaFatta = indexOfLast { it.status == StopStatus.DONE }
    if (ultimaFatta <= 0) return this
    return mapIndexed { i, fermata ->
        when {
            i >= ultimaFatta -> fermata
            fermata.status == StopStatus.CANCELLED -> fermata
            /*
             * Data per futura ma il treno l'ha passata. Si azzerano anche i
             * minuti proiettati: erano una stima sul futuro, e sul passato
             * diventerebbero un ritardo inventato.
             */
            fermata.status == StopStatus.FUTURE -> fermata.copy(
                status = StopStatus.DONE,
                detected = false,
                arrivalDelayMinutes = 0,
                departureDelayMinutes = 0,
                projectedArrival = null,
                projectedDeparture = null,
            )
            // Effettuata ma senza un solo orario reale: passaggio non registrato.
            fermata.actualArrival == null && fermata.actualDeparture == null ->
                fermata.copy(detected = false)
            else -> fermata
        }
    }
}

/**
 * Dove si trova il treno adesso.
 *
 * ViaggiaTreno non lo dice: dice quali fermate risultano effettuate. La
 * posizione e' l'ultima di quelle, ed evidenziarla ha senso solo finche' davanti
 * resta qualcosa: a corsa finita il treno non e' "in" nessuna stazione.
 */
private fun List<Stop>.segnaPosizione(): List<Stop> {
    if (none { it.status == StopStatus.FUTURE }) return this
    val ultima = indexOfLast { it.status == StopStatus.DONE }
    if (ultima < 0) return this
    return mapIndexed { i, f -> if (i == ultima) f.copy(status = StopStatus.CURRENT) else f }
}
