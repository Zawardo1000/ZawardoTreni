package it.zawardo.treni.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import it.zawardo.treni.domain.model.TrainState

/**
 * Un ritardo negativo e' un anticipo: mostrarlo come "-3 min di ritardo"
 * confonderebbe. Qui la parola cambia insieme al segno.
 */
fun delayLabel(minutes: Int): String = when {
    minutes > 0 -> "+$minutes min"
    minutes < 0 -> "${-minutes} min in anticipo"
    else -> "in orario"
}

fun stateLabel(state: TrainState, delayMinutes: Int?): String = when (state) {
    TrainState.CANCELLED -> "Soppresso"
    TrainState.PARTIALLY_CANCELLED -> "Soppresso in parte"
    TrainState.DIVERTED -> "Percorso variato"
    TrainState.NOT_DEPARTED -> "Non ancora partito"
    TrainState.ARRIVED -> "Arrivato"
    TrainState.DELAYED -> delayMinutes?.let { "Ritardo $it min" } ?: "In ritardo"
    TrainState.REGULAR -> when {
        delayMinutes == null || delayMinutes == 0 -> "In orario"
        delayMinutes < 0 -> "In anticipo di ${-delayMinutes} min"
        else -> "Ritardo $delayMinutes min"
    }
}

/**
 * Il colore segue la gravita', non lo stato nominale: un "REGULAR" con 12 minuti
 * di ritardo deve comunque allarmare quanto un DELAYED.
 */
@Composable
fun stateColor(state: TrainState, delayMinutes: Int?): Color {
    val scheme = MaterialTheme.colorScheme
    return when (state) {
        TrainState.CANCELLED, TrainState.PARTIALLY_CANCELLED -> scheme.error
        TrainState.DIVERTED -> scheme.tertiary
        TrainState.ARRIVED -> scheme.onSurfaceVariant
        else -> when {
            delayMinutes == null -> scheme.onSurfaceVariant
            delayMinutes >= 15 -> scheme.error
            delayMinutes >= 5 -> scheme.tertiary
            else -> scheme.primary
        }
    }
}
