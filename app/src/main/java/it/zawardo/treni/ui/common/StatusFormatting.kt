package it.zawardo.treni.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import it.zawardo.treni.domain.model.TrainState
import java.time.Duration
import java.time.LocalDateTime
import it.zawardo.treni.domain.model.TrainStatus

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

private val OnLateLight = Color.White
private val OnLateDark = Color(0xFF3B0907)
private val ConfirmedBgLight = Color(0xFFE3F2E6)
private val ConfirmedBgDark = Color(0xFF16301F)

/** Il testo su un fondo pieno di rosso: il binario cambiato, il bollo «Soppresso». */
@Composable
@ReadOnlyComposable
fun onLateColor(): Color = if (isSystemInDarkTheme()) OnLateDark else OnLateLight

private val LateBgLight = Color(0xFFFDECEA)
private val LateBgDark = Color(0xFF3A1B1A)

/** Il fondo tenue di un allarme che non deve urlare: la coincidenza persa. */
@Composable
@ReadOnlyComposable
fun lateBackground(): Color = if (isSystemInDarkTheme()) LateBgDark else LateBgLight

/** Il fondo tenue del binario confermato: verde, ma senza gridare. */
@Composable
@ReadOnlyComposable
fun confirmedBackground(): Color = if (isSystemInDarkTheme()) ConfirmedBgDark else ConfirmedBgLight

/**
 * Il colore di un orario reale: verde in orario o in anticipo, rosso in ritardo.
 *
 * Diverso da [delayColor], che lo zero lo lascia neutro. Li' lo zero e'
 * "nessuna notizia"; qui accompagna un orario misurato che coincide con quello
 * di tabella, cioe' una buona notizia — la stessa grammatica del binario
 * confermato. Nero previsto, verde confermato, rosso diverso dal previsto.
 */
@Composable
@ReadOnlyComposable
fun scartoColor(minutes: Int): Color = if (minutes > 0) lateColor() else earlyColor()

/** Colore di un ritardo in minuti: negativo verde, positivo rosso, zero neutro. */
@Composable
@ReadOnlyComposable
fun delayColor(minutes: Int?): Color = when {
    minutes == null || minutes == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    minutes < 0 -> earlyColor()
    else -> lateColor()
}

/**
 * Lo scarto in minuti, col segno davanti: "+8 min", "-3 min", "in orario".
 *
 * L'anticipo si scriveva "3 min in anticipo", e nella stessa riga della stessa
 * fermata l'arrivo diceva gia' "-3": due grafie per la stessa cosa a due
 * centimetri di distanza, e la piu' lunga delle due mandava la partenza a capo. Il segno lo dice in un carattere, e lo dice ovunque allo
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
 * Lo scarto fra i due orari **come si leggono a schermo**, in minuti.
 *
 * Il ritardo della fermata non va bene per colorare: ViaggiaTreno lo arrotonda
 * per eccesso, e un rilevamento trenta secondi dopo l'orario di tabella diventa
 * "+1". Misurato il 20/09/2026 su 24 corse: 15 fermate su 140 avevano lo stesso
 * minuto in tutte e due le colonne e ritardo 1 — il FR 9583 a Torino Porta
 * Nuova, 08:00 e 08:00, l'ICN 1962 a Paola, 20:52 e 20:52 — e uscivano in rosso
 * accanto a un orario identico a quello previsto. Il colore deve spiegare quel
 * che si vede: due orari uguali sono una conferma, non un ritardo. Il ritardo
 * della corsa, quello vero, resta scritto in cima com'e'.
 */
fun scartoVisibile(tabella: LocalDateTime?, reale: LocalDateTime?): Int {
    if (tabella == null || reale == null) return 0
    val alMinuto = { q: LocalDateTime -> q.withSecond(0).withNano(0) }
    return Duration.between(alMinuto(tabella), alMinuto(reale)).toMinutes().toInt()
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

/**
 * Vero per un treno fermo all'origine oltre la sua ora: lo stato da solo non
 * basta, serve anche il numero.
 *
 * «Non ancora partito» su un treno che doveva partire nove minuti fa racconta
 * meta' della storia, e la meta' meno utile. Il ritardo lo calcola
 * `conRitardoDaFermo`, perche' ViaggiaTreno lo lascia a zero.
 */
fun fermoInRitardo(state: TrainState, delayMinutes: Int?): Boolean =
    state == TrainState.NOT_DEPARTED && (delayMinutes ?: 0) > 0

/** Colore dello stato: gli stati anomali vincono sul colore del ritardo. */
@Composable
@ReadOnlyComposable
fun stateColor(state: TrainState, delayMinutes: Int?): Color = when (state) {
    TrainState.CANCELLED, TrainState.PARTIALLY_CANCELLED -> lateColor()
    TrainState.DIVERTED -> MaterialTheme.colorScheme.tertiary
    TrainState.ARRIVED -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> delayColor(delayMinutes)
}

/**
 * Gli avvisi di una corsa, nell'ordine in cui si leggono: cosa cambia oggi,
 * perche', e cosa succede sulla linea.
 *
 * Il "cosa" e il "perche'" vengono di solito da due fonti — ViaggiaTreno scrive
 * il percorso variato, Trenord il motivo — e stanno uno sotto l'altro perche'
 * il secondo spiega il primo. Una funzione sola per il dettaglio e per il
 * viaggio con cambi, che altrimenti li mostrerebbero ciascuno a modo suo.
 */
fun TrainStatus.avvisiDaMostrare(): List<String> =
    listOfNotNull(notice, motivo?.let { "Motivo: $it" }) + avvisi
