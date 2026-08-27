package it.zawardo.treni.domain.model

import java.time.LocalTime
import java.time.temporal.ChronoUnit

private const val HALF_DAY = 720
private const val DAY = 1440

/**
 * Tiene solo le corse che si fanno ancora in tempo a prendere.
 *
 * ViaggiaTreno apre la finestra un quarto d'ora prima dell'ora richiesta, quindi
 * le prime righe di un tabellone sono sempre treni gia' andati.
 *
 * Il confronto non puo' essere sull'orario di tabella: un treno in ritardo ce
 * l'ha nel passato e parte ancora. Conta l'orario reale, cioe' tabella piu'
 * ritardo.
 *
 * Si salva solo chi non e' ancora partito dall'origine: quel treno di qui non
 * puo' essere passato, per quanto indietro sia la sua tabella. Essere fermo in
 * stazione invece non salva, perche' oltre il proprio orario ha gia' chiuso le
 * porte.
 */
fun List<BoardEntry>.stillCatchable(now: LocalTime = LocalTime.now()): List<BoardEntry> =
    filter { it.state == TrainState.NOT_DEPARTED || it.minutesFrom(now) >= 0 }

/**
 * Minuti da adesso all'orario reale della corsa. Una riga senza orario vale
 * zero, cioe' resta: meglio una di troppo che nasconderne una che non sappiamo
 * collocare.
 */
fun BoardEntry.minutesFrom(now: LocalTime): Int {
    val scheduled = runCatching { LocalTime.parse(scheduledTime) }.getOrNull() ?: return 0
    val diff = ChronoUnit.MINUTES.between(now, scheduled).toInt() + delayMinutes
    // La finestra copre due ore: uno scarto di mezza giornata e' soltanto
    // mezzanotte in mezzo, non un treno con dodici ore di anticipo.
    return when {
        diff > HALF_DAY -> diff - DAY
        diff < -HALF_DAY -> diff + DAY
        else -> diff
    }
}
