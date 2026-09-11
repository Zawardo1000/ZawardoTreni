package it.zawardo.treni.domain.model

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

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

/**
 * Il capolinea che riguarda chi viaggia.
 *
 * Il tabellone di ViaggiaTreno a volte nomina una stazione che la corsa non
 * serve: il REG 12977 da Acireale risulta diretto a Bicocca mentre il suo
 * record dice Catania Aeroporto Fontanarossa in ogni campo, orario compreso, e
 * Bicocca non e' fra le fermate. Fra le due fonti vince la corsa, che e' l'unica
 * a dire dove si scende.
 *
 * Le fermate soppresse non contano: un treno limitato finisce dove smette di
 * fermarsi, non dove sarebbe dovuto arrivare.
 */
fun TrainStatus.terminus(arrivals: Boolean = false): String? =
    stops.filter { it.status != StopStatus.CANCELLED }
        .let { if (arrivals) it.firstOrNull() else it.lastOrNull() }
        ?.stationName
        ?.takeIf { it.isNotBlank() }

/**
 * Dove sta, dentro questa corsa, la fermata di una stazione precisa. `-1` se la
 * corsa non ci passa.
 *
 * Il codice di stazione da solo non basta: una corsa puo' ripassare dalla stessa
 * stazione — le circolari lo fanno per mestiere — ed e' l'orario a dire di quale
 * dei due passaggi si stia parlando. Stessa regola con cui si accoppiano le
 * fermate di due letture diverse in `conBinariDa`, qui applicata a una lettura
 * sola.
 */
fun TrainStatus.indiceFermata(codice: String?, quando: LocalDateTime? = null): Int {
    val cercato = codice?.trim()?.takeIf { it.isNotEmpty() } ?: return -1
    val candidate = stops.withIndex()
        .filter { it.value.stationCode?.trim().equals(cercato, ignoreCase = true) }
    if (candidate.isEmpty()) return -1
    if (candidate.size == 1 || quando == null) return candidate.first().index
    return candidate.minByOrNull { (_, fermata) ->
        val ora = fermata.scheduledArrival ?: fermata.scheduledDeparture
        if (ora == null) Long.MAX_VALUE else abs(Duration.between(quando, ora).toMinutes())
    }!!.index
}

/**
 * Le fermate che, dopo la discesa, non riguardano piu' chi scende li'.
 *
 * Su un viaggio con cambio ogni treno tranne l'ultimo prosegue senza di te,
 * spesso per un'altra mezz'ora di fermate che finiscono per seppellire quella
 * che conta. Restano fuori dal taglio i due capi che si guardano davvero: la
 * discesa, e il **capolinea**, che dice dove va quel treno e senza il quale la
 * corsa perderebbe il suo nome.
 *
 * Null quando non c'e' niente da guadagnare: se le fermate da nascondere sono
 * meno di [FERMATE_MINIME_DA_NASCONDERE], il comando per riaprirle occuperebbe
 * lo stesso posto che occupano loro.
 */
fun List<Stop>.dopoLaDiscesa(discesa: Int): IntRange? {
    if (discesa < 0) return null
    val prima = discesa + 1
    // L'ultima resta sempre: e' il capolinea, la coda che chiude il percorso.
    val ultima = lastIndex - 1
    if (ultima - prima + 1 < FERMATE_MINIME_DA_NASCONDERE) return null
    return prima..ultima
}

/**
 * Le fermate prima della salita, che su un treno preso al cambio non ti
 * riguardano: e' la strada che il treno ha fatto prima di arrivare da te.
 *
 * E' lo specchio di [dopoLaDiscesa] e ne segue le regole. Resta fuori dal taglio
 * il **capolinea di partenza**, che dice da dove viene quel treno, oltre alla
 * salita. Chi chiama lo applica dal secondo treno di un viaggio in poi: sul
 * primo, o sull'unico, da dove arriva il treno che aspetti e' proprio cio' che
 * si guarda. Chiesto dall'utente l'11/09/2026.
 */
fun List<Stop>.primaDellaSalita(salita: Int): IntRange? {
    if (salita < 0) return null
    // La prima resta sempre: e' il capolinea di partenza, la testa del percorso.
    val prima = 1
    val ultima = salita - 1
    if (ultima - prima + 1 < FERMATE_MINIME_DA_NASCONDERE) return null
    return prima..ultima
}

/**
 * Sotto le due fermate non si comprime niente: i tre puntini per riaprirle
 * costerebbero la riga che fanno risparmiare, e in cambio nasconderebbero un
 * dato per il gusto di nasconderlo.
 */
private const val FERMATE_MINIME_DA_NASCONDERE = 2
