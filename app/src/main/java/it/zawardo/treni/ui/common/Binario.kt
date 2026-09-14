package it.zawardo.treni.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.treni.domain.model.binarioCambiato
import it.zawardo.treni.domain.model.binarioConfermato
import it.zawardo.treni.domain.model.binarioDaMostrare
import it.zawardo.treni.domain.model.binarioPulito
import it.zawardo.treni.ui.theme.Cifre

/**
 * Il binario come oggetto: una pillola col numero, sempre nello stesso posto.
 *
 * Prima era una riga di testo, «bin. 7» in corpo piccolo sotto gli orari, che
 * compariva solo quando c'era: senza un posto fisso l'occhio non sapeva dove
 * cercarlo. Sui tabelloni veri il binario e' una colonna, e qui lo diventa in
 * tutte le schermate: elenco delle soluzioni, dettaglio della corsa, viaggio con
 * cambi, tabellone. La grandezza cambia, la grammatica no:
 *
 *  - **previsto**: contorno e numero neri. Una sola lettura, dall'orario.
 *  - **confermato**: verde. L'effettivo e' arrivato e coincide con l'annunciato,
 *    oppure e' l'unico che esista (vedi `binarioConfermato`). Ci si puo' avviare.
 *  - **cambiato**: rosso pieno, con il binario vecchio barrato sotto. Chi era
 *    andato al 5 e legge 7 deve riconoscere il proprio treno, e il barrato fa si'
 *    che l'informazione non stia soltanto nel colore.
 *  - **da assegnare**: contorno tratteggiato e un trattino. Solo dove l'assenza
 *    si nota ([segnaposto]): tiene il posto invece di far saltare la colonna.
 *
 * A ognuno dei quattro si puo' aggiungere una rotella ([inCaricamento]): quel
 * che si legge e' cio' che si sapeva, e una lettura piu' fresca sta arrivando.
 *
 * Il binario di tabella di un'altra fonte a volte e' scritto in cifre romane o
 * con le qualifiche abbreviate: qui arriva gia' passato per `binarioPulito`.
 */
@Composable
fun BinarioPillola(
    programmato: String?,
    effettivo: String?,
    modifier: Modifier = Modifier,
    /** Nel dettaglio della corsa e sul tabellone, dove la colonna e' stretta. */
    piccola: Boolean = false,
    /** Se vero, quando il binario manca tiene il posto invece di sparire. */
    segnaposto: Boolean = false,
    /**
     * La sigla «bin» davanti al numero. Serve dove non c'e' un'intestazione di
     * colonna a dire che quel numero e' un binario, cioe' nell'elenco.
     */
    conSigla: Boolean = false,
    /**
     * Si sta chiedendo il binario vero. La pillola resta quella di cio' che si
     * sa — di solito il previsto — e una rotella nell'angolo dice che non e'
     * l'ultima parola: sul tabellone un binario nero che poi diventa verde,
     * senza, sembrerebbe un binario previsto e basta.
     */
    inCaricamento: Boolean = false,
) {
    val binario = binarioDaMostrare(programmato, effettivo)
    if (binario == null && !segnaposto && !inCaricamento) return

    val cambiato = binarioCambiato(programmato, effettivo)
    val confermato = binarioConfermato(programmato, effettivo)
    val scheme = MaterialTheme.colorScheme
    val raggio = if (piccola) 7.dp else 8.dp
    val forma = RoundedCornerShape(raggio)

    val fondo: Color
    val tratto: Color
    val inchiostro: Color
    when {
        binario == null -> {
            fondo = Color.Transparent
            tratto = scheme.outline
            inchiostro = scheme.onSurfaceVariant
        }
        cambiato -> {
            fondo = lateColor()
            tratto = lateColor()
            inchiostro = onLateColor()
        }
        confermato -> {
            fondo = confirmedBackground()
            tratto = confirmedColor()
            inchiostro = confirmedColor()
        }
        else -> {
            fondo = Color.Transparent
            tratto = scheme.onSurface
            inchiostro = scheme.onSurface
        }
    }

    // Come SBB: chi non vede il colore sente la parola «nuovo».
    val descrizione = when {
        binario == null -> "Binario non ancora assegnato"
        cambiato -> "Binario $binario, nuovo: era ${binarioPulito(programmato)}"
        confermato -> "Binario $binario, confermato"
        else -> "Binario $binario, previsto"
    } + if (inCaricamento) ", in aggiornamento" else ""

    val bordo = if (binario == null) {
        Modifier.drawBehind {
            val w = 1.5.dp.toPx()
            drawRoundRect(
                color = tratto,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(raggio.toPx()),
                style = Stroke(
                    width = w,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                ),
            )
        }
    } else {
        Modifier.border(1.5.dp, tratto, forma)
    }

    Column(
        modifier.clearAndSetSemantics { contentDescription = descrizione },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box {
            /*
             * Il numero al centro della pillola, anche in verticale.
             *
             * Il Box centra, la riga dentro allinea sigla e numero sulla linea di
             * base. Con l'allineamento sulla linea di base nella riga esterna,
             * Compose metteva il gruppo in cima e il numero stava schiacciato
             * contro il bordo superiore: si notava a colpo d'occhio (11/09/2026).
             */
            Box(
                Modifier
                    .defaultMinSize(
                        minWidth = if (piccola) 36.dp else 44.dp,
                        minHeight = if (piccola) 28.dp else 32.dp,
                    )
                    .background(fondo, forma)
                    .then(bordo)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row {
                    if (conSigla) {
                        Text(
                            "bin ",
                            Modifier.alignByBaseline(),
                            style = MaterialTheme.typography.labelSmall,
                            color = inchiostro.copy(alpha = 0.75f),
                        )
                    }
                    Text(
                        binario ?: "–",
                        Modifier.alignByBaseline(),
                        style = if (piccola) Cifre.binario.copy(fontSize = 16.sp, lineHeight = 20.sp) else Cifre.binario,
                        color = inchiostro,
                    )
                }
            }
            /*
             * Nell'angolo e non accanto: la colonna del binario e' stretta, e una
             * rotella che compare e scompare di fianco farebbe ballare la riga.
             * Il fondo pieno la stacca dal bordo della pillola che scavalca.
             */
            if (inCaricamento) {
                CircularProgressIndicator(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(14.dp)
                        .background(scheme.surface, CircleShape)
                        .padding(1.5.dp),
                    strokeWidth = 1.5.dp,
                )
            }
        }
        if (cambiato) {
            Text(
                binarioPulito(programmato).orEmpty(),
                style = Cifre.riga.copy(fontSize = 12.sp, lineHeight = 14.sp),
                color = scheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )
        }
    }
}
