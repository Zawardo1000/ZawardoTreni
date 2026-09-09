package it.zawardo.treni.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import it.zawardo.treni.domain.model.TrainState

/*
 * Verde = anticipo, rosso = ritardo, su tutta l'app.
 *
 * Non si usa `colorScheme.error` per il ritardo: con Material You l'utente puo'
 * avere un tema in cui error non e' rosso, e qui il rosso e' il significato,
 * non la decorazione. Le due tonalita' sono scelte a mano per restare leggibili
 * su fondo chiaro e su fondo scuro.
 */
private val LateLight = Color(0xFFC62828)
private val LateDark = Color(0xFFFF8A80)
private val EarlyLight = Color(0xFF1B7A32)
private val EarlyDark = Color(0xFF7BE495)

@Composable
@ReadOnlyComposable
fun lateColor(): Color = if (isSystemInDarkTheme()) LateDark else LateLight

@Composable
@ReadOnlyComposable
fun earlyColor(): Color = if (isSystemInDarkTheme()) EarlyDark else EarlyLight

/**
 * Il verde di "confermato", che e' lo stesso dell'anticipo.
 *
 * Serve al binario: due letture che dicono la stessa banchina sono una buona
 * notizia — quella banchina si puo' raggiungere — e finora si leggeva nera,
 * identica al binario ancora solo previsto. Il rosso del cambio ha gia' il suo
 * posto; questo e' il caso opposto e merita lo stesso trattamento.
 */
@Composable
@ReadOnlyComposable
fun confirmedColor(): Color = earlyColor()

/** Colore di un ritardo in minuti: negativo verde, positivo rosso, zero neutro. */
@Composable
@ReadOnlyComposable
fun delayColor(minutes: Int?): Color = when {
    minutes == null || minutes == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    minutes < 0 -> earlyColor()
    else -> lateColor()
}

/** Solo il numero con segno: "+8", "-3", "0". */
fun delayNumber(minutes: Int): String = when {
    minutes > 0 -> "+$minutes"
    minutes < 0 -> "$minutes"
    else -> "0"
}

/**
 * Lo scarto in minuti, col segno davanti: "+8 min", "-3 min", "in orario".
 *
 * L'anticipo si scriveva "3 min in anticipo", e nella stessa riga della stessa
 * fermata l'arrivo diceva gia' "-3" (vedi [delayNumber]): due grafie per la
 * stessa cosa a due centimetri di distanza, e la piu' lunga delle due mandava
 * la partenza a capo. Il segno lo dice in un carattere, e lo dice ovunque allo
 * stesso modo — tabellone, risultati, dettaglio della corsa.
 *
 * Il colore continua a distinguerli senza doverli leggere: vedi [delayColor].
 */
fun delayLabel(minutes: Int): String = when {
    minutes > 0 -> "+$minutes min"
    minutes < 0 -> "$minutes min"
    else -> "in orario"
}

/**
 * Etichetta dello stato **senza** i minuti: il ritardo lo mostra chi chiama,
 * una volta sola e colorato. Ripeterlo qui lo faceva comparire due volte.
 */
fun stateLabel(state: TrainState): String? = when (state) {
    TrainState.CANCELLED -> "Soppresso"
    TrainState.PARTIALLY_CANCELLED -> "Soppresso in parte"
    TrainState.DIVERTED -> "Percorso variato"
    TrainState.NOT_DEPARTED -> "Non ancora partito"
    TrainState.ARRIVED -> "Arrivato"
    // In orario o in ritardo: lo dice gia' il numero, non serve un'etichetta.
    TrainState.REGULAR, TrainState.DELAYED -> null
}

/** Colore dello stato: gli stati anomali vincono sul colore del ritardo. */
@Composable
@ReadOnlyComposable
fun stateColor(state: TrainState, delayMinutes: Int?): Color = when (state) {
    TrainState.CANCELLED, TrainState.PARTIALLY_CANCELLED -> lateColor()
    TrainState.DIVERTED -> MaterialTheme.colorScheme.tertiary
    TrainState.ARRIVED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> delayColor(delayMinutes)
}
