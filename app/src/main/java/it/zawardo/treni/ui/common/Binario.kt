package it.zawardo.treni.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import it.zawardo.treni.domain.model.binarioCambiato
import it.zawardo.treni.domain.model.binarioDaMostrare
import it.zawardo.treni.domain.model.binarioPulito

/**
 * Il binario, con la stessa grammatica degli orari: quello annunciato resta
 * scritto e barrato, quello vero gli sta accanto in evidenza.
 *
 * Prima il cambio si leggeva "7  (era 4)", che dice la stessa cosa ma con una
 * forma tutta sua: la riga degli orari, subito sopra, fa gia' "18:01 18:09" col
 * primo barrato, e due modi diversi di dire "era previsto cosi', invece e'
 * cosi'" nella stessa fermata si leggono come due informazioni diverse.
 *
 * Sta qui e non dentro una schermata perche' il binario di partenza si scrive
 * in tre posti — il dettaglio della corsa, l'elenco dei risultati, la notifica
 * di «Segui treno» — e prima ognuno se lo scriveva da se'. Il tabellone fa
 * eccezione e resta con la sua: li' il binario e' una colonna incolonnata a
 * destra, non una riga di testo.
 */
@Composable
fun BinarioRiga(
    programmato: String?,
    effettivo: String?,
    modifier: Modifier = Modifier,
    /**
     * Se vero, quando il binario manca lo dice invece di tacere.
     *
     * Serve dove l'assenza si nota: nelle stazioni grandi il binario non sta in
     * orario, lo assegnano un quarto d'ora prima, e vedere "bin. 4" sulla
     * fermata dopo e niente su quella da cui sali sembra un dato perso invece
     * di un dato che ancora non esiste.
     */
    atteso: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val binario = binarioDaMostrare(programmato, effettivo)

    if (binario == null) {
        if (!atteso) return
        Text(
            "bin. non ancora assegnato",
            modifier,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        return
    }

    val cambiato = binarioCambiato(programmato, effettivo)

    Row(modifier) {
        Text("bin. ", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

        // Il binario cambiato e' l'informazione che fa perdere i treni: quello
        // di partenza resta leggibile, cosi' chi l'aveva memorizzato capisce
        // che il numero nuovo riguarda proprio lui.
        Text(
            if (cambiato) binarioPulito(programmato).orEmpty() else binario,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (cambiato) TextDecoration.LineThrough else null,
            color = if (cambiato) scheme.onSurfaceVariant else scheme.onSurface,
        )

        if (cambiato) {
            Text(
                " $binario",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.tertiary,
            )
        }
    }
}
