package it.zawardo.treni.ui.train

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.ui.common.BinarioRiga
import it.zawardo.treni.ui.common.delayColor
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.delayNumber
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * Il percorso di una corsa, disegnato una volta sola.
 *
 * Le stesse fermate si leggono in due schermate: il dettaglio di un treno
 * singolo e la pagina di un viaggio con cambi, dove le corse stanno in fila una
 * sotto l'altra. Erano due disegni identici destinati a divergere alla prima
 * modifica fatta da una parte sola — e a divergere in silenzio, perche' nessuno
 * apre le due schermate affiancate.
 */

internal val ORARIO: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun LocalDateTime?.hhmm(): String = this?.format(ORARIO) ?: "--:--"

/**
 * Cio' che si sa della corsa nel suo insieme: capolinea, ritardo, dove e' stata
 * vista l'ultima volta, avvisi.
 *
 * E' il contenuto della scheda in cima, non la scheda: chi chiama ci mette
 * attorno la propria: il dettaglio del treno singolo una `Card` e basta, la
 * pagina del viaggio la stessa `Card` con sopra il numero del treno e la
 * stella dei preferiti.
 */
@Composable
internal fun ColumnScope.DettagliCorsa(status: TrainStatus) {
    Text(
        "${status.origin.orEmpty()} → ${status.destination.orEmpty()}",
        style = MaterialTheme.typography.titleMedium,
    )
    /*
     * Senza tempo reale non si scrive niente sul ritardo, e lo si dice.
     *
     * La corsa porta `delayMinutes = 0` perche' il modello vuole un intero, ma
     * quello zero non e' una misura: di un treno che non e' ancora partito
     * nessuno sa se ritardera'. Scrivere "in orario" in verde, come si faceva,
     * era la cosa precisa da non far credere — ed era il ritardo di **un altro
     * giorno**, quello della corsa da cui il percorso e' stato ricavato. Stessa
     * scelta e stesse parole del tabellone: vedi `BoardEntry.realtime`.
     */
    Text(
        if (!status.realtime) "Orario previsto"
        else stateLabel(status.state) ?: delayLabel(status.delayMinutes),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (!status.realtime) MaterialTheme.colorScheme.onSurfaceVariant
        else stateColor(status.state, status.delayMinutes),
    )
    /*
     * Dove e' stato misurato quel numero, attaccato al numero.
     *
     * Il ritardo in cima e' quello dell'**ultimo rilevamento**, e finche' non si
     * dice dove sia avvenuto resta una cifra che ogni tanto contraddice le
     * fermate scritte sotto. Contate il 31/08/2026 su 22 corse in circolazione:
     * in tredici il punto di rilevamento non era una fermata della corsa ma un
     * posto di controllo o un bivio — "PC RUBIERA", "1° BIVIO CHIUSI SUD",
     * "BV/PC SETTEBAGNI" — e li' il confronto e' con un orario di transito, non
     * con quello di una fermata. Di qui gli scarti che paiono assurdi: il REG
     * 2813 del 31 agosto era ripartito da Lecco a +1 mentre la corsa dava -3, e
     * le fermate seguenti uscivano tutte "3 min in anticipo" senza che niente,
     * sullo schermo, dicesse da dove venisse quel -3.
     */
    if (status.realtime) {
        val rilevamento = status.lastDetectionStation
        when {
            rilevamento != null -> Text(
                "Ultimo rilevamento: $rilevamento alle ${status.lastDetectionTime.hhmm()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // "Non ancora partito" lo dice gia' il titolo qui sopra:
            // ripeterlo era una riga che non aggiungeva niente.
            status.state != TrainState.NOT_DEPARTED -> Text(
                "Posizione non ancora rilevata",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Unit
        }

        /*
         * La proiezione e' nostra, non di ViaggiaTreno, e va detto anche da dove
         * viene: e' quello scarto li', preso dal rilevamento appena nominato,
         * non una misura fatta sull'ultima fermata.
         */
        if (rilevamento != null && status.stops.any { it.isEstimate } && status.delayMinutes != 0) {
            Text(
                "Le fermate non ancora raggiunte riportano questo scarto. " +
                    "Il punto di rilevamento spesso non è una fermata, quindi " +
                    "può non coincidere col ritardo dell'ultima fermata fatta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // La riga separa il ritardo dall'avviso di servizio: senza niente sotto
    // sarebbe un taglio in fondo alla scheda.
    if (status.notice != null) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }

    status.notice?.let {
        Text(
            it,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
internal fun FermataRiga(
    stop: Stop,
    isFirst: Boolean,
    isLast: Boolean,
    isBoarding: Boolean = false,
    isAlighting: Boolean = false,
    trainCancelled: Boolean = false,
    /** Qui il binario, se manca, manca perche' non l'hanno ancora assegnato. */
    binarioAtteso: Boolean = false,
    onOpenStation: (String, String) -> Unit = { _, _ -> },
) {
    // Senza codice RFI non esiste un tabellone da aprire: la riga resta inerte
    // invece di portare a una schermata vuota.
    val code = stop.stationCode?.takeIf { it.isNotBlank() }
    val done = stop.status == StopStatus.DONE
    val current = stop.status == StopStatus.CURRENT
    val stopCancelled = stop.status == StopStatus.CANCELLED
    val cancelled = stopCancelled || trainCancelled

    val scheme = MaterialTheme.colorScheme
    val markerColor = when {
        cancelled -> scheme.error
        current -> scheme.tertiary
        done -> scheme.primary
        else -> scheme.outlineVariant
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (code != null) {
                    Modifier.clickable { onOpenStation(code, stop.stationName) }
                } else {
                    Modifier
                }
            )
            .then(
                if (isBoarding || isAlighting) {
                    Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                }
            ),
    ) {

        /*
         * Il binario visivo del percorso.
         *
         * Tratto già percorso: linea piena e spessa, pallino pieno.
         * Tratto ancora da fare: linea tratteggiata e sottile, pallino vuoto.
         * Fermata corrente: anello attorno al pallino, così si distingue a colpo d'occhio.
         *
         * Tutte le misure passano per dp.toPx(): in pixel grezzi la linea sarebbe
         * quasi invisibile su uno schermo ad alta densità.
         */
        Canvas(
            Modifier
                .width(36.dp)
                .fillMaxHeight(),
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val travelled = done || current
            val thick = 3.dp.toPx()
            val thin = 2.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)

            if (!isFirst) {
                drawLine(
                    color = if (travelled) scheme.primary else scheme.outlineVariant,
                    start = Offset(cx, 0f),
                    end = Offset(cx, cy),
                    strokeWidth = if (travelled) thick else thin,
                    cap = StrokeCap.Round,
                    pathEffect = if (travelled) null else dash,
                )
            }
            if (!isLast) {
                // Il tratto DOPO la fermata corrente è ancora da percorrere.
                drawLine(
                    color = if (done) scheme.primary else scheme.outlineVariant,
                    start = Offset(cx, cy),
                    end = Offset(cx, size.height),
                    strokeWidth = if (done) thick else thin,
                    cap = StrokeCap.Round,
                    pathEffect = if (done) null else dash,
                )
            }

            val r = if (current) 7.dp.toPx() else 5.dp.toPx()
            if (current) {
                drawCircle(markerColor.copy(alpha = 0.25f), radius = 12.dp.toPx(), center = Offset(cx, cy))
            }
            if (done || current || cancelled) {
                drawCircle(markerColor, radius = r, center = Offset(cx, cy))
            } else {
                // Fermata futura: anello vuoto, non un punto pieno.
                drawCircle(
                    color = scheme.outline,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Column(Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp)) {
            Text(
                stop.stationName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current || isBoarding || isAlighting) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                },
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                color = if (cancelled) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // In neretto sono uguali: a dire quale sia quale sono due parole.
            if (isBoarding || isAlighting) {
                Text(
                    if (isBoarding) "Sali qui" else "Scendi qui",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary,
                )
            }

            if (stopCancelled) {
                Text(
                    "Fermata soppressa",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            } else {
                Orari(stop, isFirst, isLast, cancelled = trainCancelled)
                BinarioRiga(stop.scheduledPlatform, stop.actualPlatform, atteso = binarioAtteso)
                if (!stop.detected) {
                    Text(
                        if (stop.effectiveArrival != null || stop.effectiveDeparture != null) {
                            "Orari ricostruiti: passaggio non rilevato"
                        } else {
                            "Passaggio non rilevato"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Il tratto di percorso chiuso: tre punti sulla linea, e si riapre toccandoli.
 *
 * Sta al posto delle fermate che il treno fa **dopo** che sei sceso, su una
 * corsa che per te finisce al cambio. Non e' un troncamento: la linea del
 * percorso continua a scorrere attraverso i tre punti, il capolinea resta
 * scritto sotto, e il numero dice quante fermate ci sono in mezzo — cosi' chi
 * vuole vederle sa che ci sono e come tornarci.
 *
 * [etichetta] arriva da fuori perche' lo stesso disegno serve anche al tratto
 * *prima* della salita — "vedi fermate precedenti" — il giorno in cui lo si
 * accendera'.
 */
@Composable
internal fun FermateNascoste(
    etichetta: String,
    quante: Int,
    espanse: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(
            Modifier
                .width(36.dp)
                .fillMaxHeight(),
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f)
            // La linea passa dietro i punti: il percorso non si interrompe,
            // e' solo ripiegato.
            drawLine(
                color = scheme.outlineVariant,
                start = Offset(cx, 0f),
                end = Offset(cx, size.height),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = dash,
            )
            val passo = 7.dp.toPx()
            for (i in -1..1) {
                drawCircle(scheme.outline, radius = 2.5.dp.toPx(), center = Offset(cx, cy + i * passo))
            }
        }
        Text(
            if (espanse) "Nascondi $etichetta" else "Vedi $etichetta ($quante)",
            Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            // Blu come un collegamento: e' l'unica riga dell'elenco su cui si
            // tocca per cambiare cosa si vede, e deve dirlo da se'.
            color = scheme.primary,
        )
    }
}

@Composable
private fun Orari(stop: Stop, isFirst: Boolean, isLast: Boolean, cancelled: Boolean = false) {
    val scheme = MaterialTheme.colorScheme

    // Al capolinea di partenza non esiste un arrivo, a quello finale non esiste una partenza.
    val showArrival = !isFirst && stop.scheduledArrival != null
    val showDeparture = !isLast && stop.scheduledDeparture != null

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showArrival) {
            Cella(
                prefix = "arr",
                scheduled = stop.scheduledArrival.hhmm(),
                effective = stop.effectiveArrival?.format(ORARIO),
                delay = stop.arrivalDelayMinutes,
                // In arrivo solo la cifra: la riga sarebbe troppo lunga con due testi.
                withText = false,
                cancelled = cancelled,
            )
        }
        if (showDeparture) {
            Cella(
                prefix = "par",
                scheduled = stop.scheduledDeparture.hhmm(),
                effective = stop.effectiveDeparture?.format(ORARIO),
                delay = stop.departureDelayMinutes,
                withText = true,
                cancelled = cancelled,
            )
        }
        if (!showArrival && !showDeparture) {
            Text(
                "orario non disponibile",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Cella(
    prefix: String,
    scheduled: String,
    effective: String?,
    delay: Int,
    withText: Boolean,
    /** Corsa soppressa: l'orario resta scritto, ma non lo fa nessuno. */
    cancelled: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val shifted = delay != 0 && effective != null

    Row {
        Text("$prefix ", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

        // L'orario di orario ufficiale resta sempre leggibile, barrato se superato.
        Text(
            scheduled,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (shifted || cancelled) TextDecoration.LineThrough else null,
            color = if (shifted) scheme.onSurfaceVariant else scheme.onSurface,
        )

        if (shifted) {
            Text(
                " $effective",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = delayColor(delay),
            )
        }
        if (delay != 0) {
            Text(
                " " + if (withText) delayLabel(delay) else delayNumber(delay),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = delayColor(delay),
            )
        }
    }
}
