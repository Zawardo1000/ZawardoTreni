package it.zawardo.treni.ui.train

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.treni.domain.model.Stop
import it.zawardo.treni.domain.model.StopStatus
import it.zawardo.treni.domain.model.TrainState
import it.zawardo.treni.domain.model.TrainStatus
import it.zawardo.treni.domain.model.variazione
import it.zawardo.treni.ui.common.ORA_DEL_GIORNO
import it.zawardo.treni.ui.common.scartoVisibile
import it.zawardo.treni.ui.common.BinarioPillola
import it.zawardo.treni.ui.common.avvisiDaMostrare
import it.zawardo.treni.ui.common.delayLabel
import it.zawardo.treni.ui.common.fermoInRitardo
import it.zawardo.treni.ui.common.lateColor
import it.zawardo.treni.ui.common.scartoColor
import it.zawardo.treni.ui.common.stateColor
import it.zawardo.treni.ui.common.stateLabel
import it.zawardo.treni.ui.theme.Cifre
import it.zawardo.treni.ui.theme.TreniBrand
import java.time.LocalDateTime

/*
 * Il percorso di una corsa, disegnato una volta sola.
 *
 * Le stesse fermate si leggono in due schermate: il dettaglio di un treno
 * singolo e la pagina di un viaggio con cambi, dove le corse stanno in fila una
 * sotto l'altra. Erano due disegni identici destinati a divergere alla prima
 * modifica fatta da una parte sola — e a divergere in silenzio, perche' nessuno
 * apre le due schermate affiancate.
 *
 * **Colonne come su un tabellone**: orario di tabella, orario reale, la linea
 * del percorso, la fermata, il binario. Prima ogni fermata era una riga di testo
 * — «arr 09:50 10:12 +22   par 10:05 10:27 +22 min» — che andava a capo dove
 * capitava, e da una fermata all'altra niente cadeva alla stessa altezza. Ora
 * ogni dato ha la sua colonna, di larghezza fissa, con le cifre tabulari.
 */

internal fun LocalDateTime?.hhmm(): String = this?.format(ORA_DEL_GIORNO) ?: "--:--"

/** Le larghezze delle colonne, le stesse per l'intestazione e per ogni fermata. */
internal object ColonneCorsa {
    val orario = 50.dp
    val reale = 50.dp
    val linea = 28.dp
    val binario = 52.dp
}

/** Dove sta una fermata rispetto al pezzo di corsa che fai tu. */
internal enum class NelTratto { INIZIO, MEZZO, FINE }

/**
 * La posizione della fermata [i] nel tratto fra [salita] e [discesa], o `null`
 * se sta fuori, o se uno dei due capi non si conosce: senza la discesa non c'e'
 * un tratto da evidenziare, solo la fermata da cui si parte.
 */
internal fun nelTratto(i: Int, salita: Int?, discesa: Int?): NelTratto? {
    if (salita == null || discesa == null || salita >= discesa) return null
    return when {
        i == salita -> NelTratto.INIZIO
        i == discesa -> NelTratto.FINE
        i in (salita + 1) until discesa -> NelTratto.MEZZO
        else -> null
    }
}

/** Dove sta il treno: l'ultima fermata fatta, e se ci e' ancora fermo. */
internal data class DoveTreno(val fermata: Int, val inStazione: Boolean)

/**
 * Dove sta il treno, se sta viaggiando.
 *
 * `currentStopIndex` e' l'ultima fermata fatta. Prima della partenza e dopo
 * l'arrivo non c'e' niente da mettere sulla linea. Una funzione sola per il
 * dettaglio della corsa e per il viaggio con cambi, che altrimenti deciderebbero
 * ciascuno per conto suo.
 */
internal fun TrainStatus.posizioneInViaggio(): DoveTreno? {
    val inViaggio = realtime &&
        state != TrainState.NOT_DEPARTED &&
        state != TrainState.ARRIVED &&
        state != TrainState.CANCELLED
    val fermata = currentStopIndex.takeIf { inViaggio && it >= 0 && it < stops.lastIndex } ?: return null
    val qui = stops[fermata]
    /*
     * Fermo in stazione, o gia' ripartito?
     *
     * Fermata fatta vuol dire che il treno ci e' arrivato, non che ne sia
     * ripartito. Segnalato l'11/09/2026 guardando un regionale fermo a
     * Pioltello, disegnato gia' fuori dalla stazione come se l'avesse lasciata.
     * Fuori lo si mette solo con una prova: la partenza rilevata da li', oppure
     * un rilevamento in un punto che non e' quella stazione, e che quindi viene
     * dopo.
     */
    val ripartito = qui.actualDeparture != null ||
        (lastDetectionStation != null && !lastDetectionStation.equals(qui.stationName, ignoreCase = true))
    return DoveTreno(fermata, inStazione = !ripartito)
}

private fun formaTratto(tratto: NelTratto?): Shape = when (tratto) {
    NelTratto.INIZIO -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    NelTratto.FINE -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    NelTratto.MEZZO, null -> RectangleShape
}

/**
 * Lo stato della corsa in una riga, da tenere sempre in vista.
 *
 * La colonna «Reale» delle fermate mostra l'orario, non lo scarto: e' la
 * condizione con cui la scelta e' stata fatta, che lo scarto resti leggibile
 * anche scorrendo fino all'ultima fermata. Per questo sta fuori dalla lista,
 * fisso sotto la barra.
 */
@Composable
internal fun StatoCorsa(status: TrainStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(scheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val anomalia = stateLabel(status.state)
        val fermo = fermoInRitardo(status.state, status.delayMinutes)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when {
                // Senza tempo reale nessuna cifra: vedi il commento in [DettagliCorsa].
                !status.realtime -> Text(
                    "Orario previsto",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurfaceVariant,
                )
                // Una variazione non prende il posto del ritardo: vedi `variazione`.
                anomalia != null && !fermo && !status.state.variazione -> Text(
                    anomalia,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor(status.state, status.delayMinutes),
                )
                else -> {
                    Text(
                        if (status.delayMinutes == 0) "In orario" else delayLabel(status.delayMinutes),
                        Modifier.alignByBaseline(),
                        style = Cifre.scarto,
                        color = scartoColor(status.delayMinutes),
                    )
                    // Accanto al ritardo, al posto di «di ritardo»: la riga e' una sola.
                    if (anomalia != null && status.state.variazione) {
                        Text(
                            "  · " + anomalia.lowercase(),
                            Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = stateColor(status.state, status.delayMinutes),
                        )
                    } else if (status.delayMinutes != 0) {
                        Text(
                            when {
                                fermo -> "  non ancora partito"
                                status.delayMinutes > 0 -> "  di ritardo"
                                else -> "  di anticipo"
                            },
                            Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (status.realtime) {
            status.lastDetectionTime?.let {
                Text(
                    "rilevato ${it.hhmm()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Cio' che si sa della corsa nel suo insieme: capolinea, ritardo, dove e' stata
 * vista l'ultima volta, avvisi.
 *
 * E' il contenuto della scheda in cima, non la scheda: chi chiama ci mette
 * attorno la propria. Il dettaglio del treno singolo lo scarto lo tiene fisso
 * sopra la lista ([StatoCorsa]) e qui chiede di ometterlo ([conStato]); la
 * pagina del viaggio lo vuole dentro la scheda di ogni corsa.
 */
@Composable
internal fun ColumnScope.DettagliCorsa(
    status: TrainStatus,
    conStato: Boolean = true,
    /** Falso quando il rilevamento lo dice gia' la riga del treno sul percorso. */
    conRilevamento: Boolean = true,
) {
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
    if (conStato) {
        Text(
            when {
                !status.realtime -> "Orario previsto"
                fermoInRitardo(status.state, status.delayMinutes) ->
                    delayLabel(status.delayMinutes) + " · non ancora partito"
                status.state.variazione ->
                    delayLabel(status.delayMinutes) + " · " + stateLabel(status.state).orEmpty().lowercase()
                else -> stateLabel(status.state) ?: delayLabel(status.delayMinutes)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (!status.realtime) MaterialTheme.colorScheme.onSurfaceVariant
            else stateColor(status.state, status.delayMinutes),
        )
    }
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
        if (conRilevamento) {
            when {
                rilevamento != null -> Text(
                    "Ultimo rilevamento: $rilevamento alle ${status.lastDetectionTime.hhmm()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // "Non ancora partito" lo dice gia' lo stato: ripeterlo era una
                // riga che non aggiungeva niente.
                status.state != TrainState.NOT_DEPARTED -> Text(
                    "Posizione non ancora rilevata",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }

        /*
         * La proiezione e' nostra, non di ViaggiaTreno, e va detto anche da dove
         * viene: e' quello scarto li', preso dal rilevamento, non una misura
         * fatta sull'ultima fermata. Gli orari stimati si distinguono anche a
         * vista: in tondo, dove quelli misurati sono in neretto.
         */
        if (rilevamento != null && status.stops.any { it.isEstimate } && status.delayMinutes != 0) {
            Text(
                "Gli orari delle fermate non ancora raggiunte sono stimati con lo scarto " +
                    "dell'ultimo rilevamento, che spesso non è una fermata: può non " +
                    "coincidere col ritardo dell'ultima fermata fatta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // La riga separa il ritardo dagli avvisi di servizio: senza niente sotto
    // sarebbe un taglio in fondo alla scheda.
    val avvisi = status.avvisiDaMostrare()
    if (avvisi.isNotEmpty()) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }

    avvisi.forEach {
        Text(
            it,
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

/**
 * L'intestazione delle colonne, come in cima a un tabellone.
 *
 * Senza, due orari affiancati non dicono quale sia quello di tabella e quale
 * quello vero. Nel dettaglio resta fissa mentre si scorre.
 */
@Composable
internal fun IntestazioneColonne(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val stile = MaterialTheme.typography.labelSmall.copy(
        letterSpacing = 0.8.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Column(modifier.fillMaxWidth().background(scheme.surface)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("ORARIO", Modifier.width(ColonneCorsa.orario), style = stile, color = scheme.onSurfaceVariant)
            Text("REALE", Modifier.width(ColonneCorsa.reale), style = stile, color = scheme.onSurfaceVariant)
            Spacer(Modifier.width(ColonneCorsa.linea))
            Text(
                "FERMATA",
                Modifier.weight(1f).padding(start = 8.dp),
                style = stile,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "BIN.",
                Modifier.width(ColonneCorsa.binario),
                style = stile,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = scheme.outlineVariant)
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
    /** Il pezzo di corsa che fai tu: una fascia gialla da «Sali» a «Scendi». */
    tratto: NelTratto? = null,
    /** Il treno e' gia' ripartito da qui: la linea verso la prossima e' percorsa fino a lui. */
    trenoDopo: Boolean = false,
    /** Senza tempo reale la colonna «Reale» resta vuota: non c'e' niente di misurato. */
    realtime: Boolean = true,
    /** Una fermata si' e una no, un fondo appena piu' scuro. */
    zebra: Boolean = false,
    /** Il treno e' fermo qui: il suo segno sta sulla stazione, non dopo. */
    trenoInStazione: Boolean = false,
    /** Quando e' stato visto qui, per la riga sotto il nome. */
    rilevatoAlle: LocalDateTime? = null,
    onOpenStation: (String, String) -> Unit = { _, _ -> },
) {
    // Senza codice RFI non esiste un tabellone da aprire: la riga resta inerte
    // invece di portare a una schermata vuota.
    val code = stop.stationCode?.takeIf { it.isNotBlank() }
    val done = stop.status == StopStatus.DONE
    val current = stop.status == StopStatus.CURRENT
    val passata = done || current
    val stopCancelled = stop.status == StopStatus.CANCELLED
    val cancelled = stopCancelled || trainCancelled

    val scheme = MaterialTheme.colorScheme
    /*
     * Zebratura leggera, una fermata si' e una no.
     *
     * Chiesta guardando il dettaglio nuovo: con le righe cosi' vicine, e gli
     * orari in colonne strette a sinistra, non si capiva a colpo d'occhio a
     * quale fermata appartenessero. Dentro la fascia del tuo tratto continua, in
     * giallo, perche' il tratto resti un blocco solo. I pallini vuoti si
     * riempiono dello stesso fondo, o si vedrebbe un disco chiaro sul grigio.
     */
    val fondo = when {
        tratto != null -> if (zebra) TreniBrand.tuoTrattoZebra else TreniBrand.tuoTratto
        zebra -> TreniBrand.zebra
        else -> scheme.surface
    }

    // Al capolinea di partenza non esiste un arrivo, a quello finale non esiste una partenza.
    val showArrival = !isFirst && stop.scheduledArrival != null
    val showDeparture = !isLast && stop.scheduledDeparture != null

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(IntrinsicSize.Min)
            .clip(formaTratto(tratto))
            .background(fondo)
            .then(if (code != null) Modifier.clickable { onOpenStation(code, stop.stationName) } else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /*
         * Arrivo sopra, partenza sotto, stesso corpo e stesso colore. Con due
         * corpi diversi la colonna smette di sembrare una colonna: lo ha fatto
         * notare chi l'ha vista, ed e' vero.
         */
        Column(Modifier.width(ColonneCorsa.orario), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val colore = if (passata || cancelled) scheme.onSurfaceVariant else scheme.onSurface
            val barra = if (cancelled) TextDecoration.LineThrough else null
            if (showArrival) Text(stop.scheduledArrival.hhmm(), style = Cifre.riga, color = colore, textDecoration = barra)
            if (showDeparture) Text(stop.scheduledDeparture.hhmm(), style = Cifre.riga, color = colore, textDecoration = barra)
            if (!showArrival && !showDeparture) Text("—", style = Cifre.riga, color = scheme.onSurfaceVariant)
        }

        Column(Modifier.width(ColonneCorsa.reale), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val mostra = realtime && !cancelled
            if (showArrival) {
                OrarioReale(
                    stop.effectiveArrival.takeIf { mostra },
                    stop.actualArrival != null,
                    scartoVisibile(stop.scheduledArrival, stop.effectiveArrival),
                )
            }
            if (showDeparture) {
                OrarioReale(
                    stop.effectiveDeparture.takeIf { mostra },
                    stop.actualDeparture != null,
                    scartoVisibile(stop.scheduledDeparture, stop.effectiveDeparture),
                )
            }
        }

        Box(
            Modifier.width(ColonneCorsa.linea).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            LineaPercorso(
                isFirst = isFirst,
                isLast = isLast,
                passata = passata,
                sottoPercorsa = done || (current && trenoDopo),
                cancellata = cancelled,
                capolinea = isFirst || isLast,
                fondo = fondo,
                modifier = Modifier.fillMaxSize(),
            )
            // Fermo in stazione: il treno sta sulla fermata, al posto del pallino.
            if (trenoInStazione) SegnoTreno(fondo)
        }

        Column(
            Modifier.weight(1f).padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                stop.stationName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = when {
                    isBoarding || isAlighting -> FontWeight.SemiBold
                    passata -> FontWeight.Normal
                    else -> FontWeight.Medium
                },
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                // Le fermate passate si spengono: l'occhio va a quelle che mancano.
                color = when {
                    cancelled -> lateColor()
                    passata -> scheme.onSurfaceVariant
                    else -> scheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isBoarding) EtichettaTratto("Sali", piena = true)
            if (isAlighting) EtichettaTratto("Scendi", piena = false)
            if (trenoInStazione) {
                Text(
                    rilevatoAlle?.let { "Il treno è qui · rilevato alle ${it.hhmm()}" } ?: "Il treno è qui",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
            if (stopCancelled) {
                Text("Fermata soppressa", style = MaterialTheme.typography.bodySmall, color = lateColor())
            } else if (stop.straordinaria) {
                // Stesso colore di "Percorso variato": e' quella variazione, vista da qui.
                Text("Fermata straordinaria", style = MaterialTheme.typography.bodySmall, color = scheme.tertiary)
            } else if (!stop.detected) {
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

        // Minima e non fissa, come nell'elenco e sul tabellone: vedi `BinarioPillola`.
        Box(Modifier.widthIn(min = ColonneCorsa.binario), contentAlignment = Alignment.CenterEnd) {
            when {
                stopCancelled -> Unit
                /*
                 * Senza binario, un trattino.
                 *
                 * Chiesto guardando Venezia S.Lucia, capolinea di un FR, rimasta
                 * con la colonna vuota: il vuoto non dice se il dato manca o se
                 * la riga e' venuta male. Dove sali, invece, la pillola
                 * tratteggiata dice di piu': quel binario lo stai aspettando, e
                 * arrivera'.
                 */
                stop.platform == null && !binarioAtteso -> Text(
                    "–",
                    Modifier.width(36.dp),
                    style = Cifre.binario.copy(fontSize = 16.sp, lineHeight = 20.sp),
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> BinarioPillola(
                    stop.scheduledPlatform,
                    stop.actualPlatform,
                    piccola = true,
                    segnaposto = binarioAtteso,
                )
            }
        }
    }
}

/**
 * L'orario vero, colorato: verde in orario o in anticipo, rosso in ritardo.
 *
 * In neretto se misurato, in tondo se e' una proiezione dello scarto: e' la
 * differenza che prima spiegava solo un paragrafo in cima alla scheda. Una
 * cella vuota tiene comunque l'altezza della riga, cosi' arrivo e partenza
 * restano allineati alla colonna accanto.
 */
@Composable
private fun OrarioReale(quando: LocalDateTime?, misurato: Boolean, scarto: Int) {
    if (quando == null) {
        Text("", style = Cifre.riga)
        return
    }
    Text(
        quando.hhmm(),
        style = Cifre.riga,
        fontWeight = if (misurato) FontWeight.SemiBold else FontWeight.Normal,
        color = scartoColor(scarto),
    )
}

/** «Sali» pieno, «Scendi» a contorno: sono i due capi del tuo tratto. */
@Composable
internal fun EtichettaTratto(testo: String, piena: Boolean) {
    val colore = TreniBrand.segnale
    val forma = RoundedCornerShape(5.dp)
    Text(
        testo.uppercase(),
        Modifier
            .then(if (piena) Modifier.background(colore, forma) else Modifier.border(1.5.dp, colore, forma))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
        fontWeight = FontWeight.Bold,
        color = if (piena) TreniBrand.suSegnale else colore,
    )
}

/**
 * Il binario visivo del percorso.
 *
 * Tratto gia' percorso: linea piena e spessa, pallino pieno. Tratto ancora da
 * fare: linea tratteggiata e sottile, pallino vuoto. I capolinea sono quadrati,
 * le fermate intermedie tonde. La posizione del treno non sta piu' su una
 * fermata ma fra due, con una riga sua: vedi [PosizioneTreno].
 *
 * Tutte le misure passano per dp.toPx(): in pixel grezzi la linea sarebbe
 * quasi invisibile su uno schermo ad alta densita'.
 */
@Composable
private fun LineaPercorso(
    isFirst: Boolean,
    isLast: Boolean,
    passata: Boolean,
    sottoPercorsa: Boolean,
    cancellata: Boolean,
    capolinea: Boolean,
    fondo: Color,
    modifier: Modifier,
) {
    val fatto = MaterialTheme.colorScheme.primary
    val daFare = MaterialTheme.colorScheme.outline
    val rosso = lateColor()
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        if (!isFirst) segmento(Offset(cx, 0f), Offset(cx, cy), passata, fatto, daFare)
        if (!isLast) segmento(Offset(cx, cy), Offset(cx, size.height), sottoPercorsa, fatto, daFare)

        val c = Offset(cx, cy)
        val bordo = 2.dp.toPx()
        val pieno = passata && !cancellata
        val contorno = if (cancellata) rosso else daFare
        if (capolinea) {
            val lato = 12.dp.toPx()
            val angolo = CornerRadius(3.dp.toPx())
            val alto = Offset(cx - lato / 2, cy - lato / 2)
            if (pieno) {
                drawRoundRect(fatto, alto, Size(lato, lato), angolo)
            } else {
                drawRoundRect(fondo, alto, Size(lato, lato), angolo)
                drawRoundRect(
                    contorno,
                    alto + Offset(bordo / 2, bordo / 2),
                    Size(lato - bordo, lato - bordo),
                    angolo,
                    style = Stroke(bordo),
                )
            }
        } else {
            val r = 5.5.dp.toPx()
            if (pieno) {
                drawCircle(fatto, r, c)
            } else {
                drawCircle(fondo, r, c)
                drawCircle(contorno, r - bordo / 2, c, style = Stroke(bordo))
            }
        }
    }
}

private fun DrawScope.segmento(da: Offset, a: Offset, percorso: Boolean, fatto: Color, daFare: Color) {
    drawLine(
        color = if (percorso) fatto else daFare,
        start = da,
        end = a,
        strokeWidth = (if (percorso) 3.dp else 2.dp).toPx(),
        pathEffect = if (percorso) null else PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f),
    )
}

/**
 * Il treno sul percorso, fra l'ultima fermata fatta e la prossima.
 *
 * Prima la posizione era un alone rosso sull'ultima fermata fatta — lo stesso
 * rosso del ritardo e della soppressione, che la faceva sembrare un errore — e
 * il punto in cui il treno era stato visto stava in un paragrafo in cima. L'11
 * settembre 2026 l'alone era su Domodossola, mentre l'EC 41 era gia' stato
 * rilevato a Cuzzago. Qui il rilevamento sta dove e' avvenuto.
 */
@Composable
internal fun PosizioneTreno(
    dove: String?,
    quando: LocalDateTime?,
    /** Dentro la fascia del tuo tratto, perche' la fascia non si interrompa. */
    nelTuoTratto: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val fatto = scheme.primary
    val daFare = scheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(IntrinsicSize.Min)
            .background(if (nelTuoTratto) TreniBrand.tuoTratto else Color.Transparent)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(ColonneCorsa.orario + ColonneCorsa.reale))
        Box(
            Modifier.width(ColonneCorsa.linea).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2
                val cy = size.height / 2
                segmento(Offset(cx, 0f), Offset(cx, cy), true, fatto, daFare)
                segmento(Offset(cx, cy), Offset(cx, size.height), false, fatto, daFare)
            }
            SegnoTreno(if (nelTuoTratto) TreniBrand.tuoTratto else scheme.surface)
        }
        /*
         * Il nome del punto in cui il treno e' stato visto l'ultima volta, e
         * basta: la stazione appena lasciata se e' li', il bivio o il posto di
         * controllo se e' uno di quelli. Per qualche ora, quando il rilevamento
         * era la fermata appena fatta, qui c'era scritto «Verso» la fermata
         * successiva, per non ripetere il nome della riga sopra. Scartato
         * l'11/09/2026: meglio il nome vero del punto, anche ripetuto.
         */
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                dove ?: "In viaggio",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            quando?.let {
                Text(
                    "rilevato alle ${it.hhmm()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Il segno del treno sulla linea: un tondo col treno dentro.
 *
 * Lo stesso sulla fermata, quando il treno e' fermo in stazione, e fra due
 * fermate, quando e' ripartito. L'anello del colore della riga lo stacca dalla
 * linea che gli passa dietro.
 */
@Composable
private fun SegnoTreno(fondo: Color) {
    Box(
        Modifier
            .size(28.dp)
            .background(fondo, CircleShape)
            .padding(2.dp)
            .background(TreniBrand.segnale, CircleShape)
            .semantics { contentDescription = "Posizione del treno" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Train,
            contentDescription = null,
            tint = TreniBrand.suSegnale,
            modifier = Modifier.size(15.dp),
        )
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
            .padding(horizontal = 8.dp)
            .height(IntrinsicSize.Min)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(ColonneCorsa.orario + ColonneCorsa.reale))
        Canvas(
            Modifier
                .width(ColonneCorsa.linea)
                .fillMaxHeight(),
        ) {
            val cx = size.width / 2
            val cy = size.height / 2
            // La linea passa dietro i punti: il percorso non si interrompe,
            // e' solo ripiegato.
            segmento(Offset(cx, 0f), Offset(cx, size.height), false, scheme.primary, scheme.outline)
            val passo = 7.dp.toPx()
            for (i in -1..1) {
                drawCircle(scheme.outline, radius = 2.5.dp.toPx(), center = Offset(cx, cy + i * passo))
            }
        }
        Text(
            if (espanse) "Nascondi $etichetta" else "Vedi $etichetta ($quante)",
            Modifier.weight(1f).padding(start = 8.dp, top = 12.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            // Blu come un collegamento: e' l'unica riga dell'elenco su cui si
            // tocca per cambiare cosa si vede, e deve dirlo da se'.
            color = scheme.primary,
        )
    }
}
