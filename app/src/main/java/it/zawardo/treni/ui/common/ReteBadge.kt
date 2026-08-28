package it.zawardo.treni.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * La sigla della rete fuori-RFI di una stazione, dal prefisso del suo codice.
 *
 * Le reti fuori dal registro nazionale usano codici sintetici col prefisso della
 * rete; le stazioni RFI — nazionale, Trenord, Italo — hanno codici `S…`/`Z…` e
 * nessun badge, perche' sono la norma e marcarle tutte sarebbe rumore.
 */
fun reteFuoriRfi(rfiCode: String?): String? = when {
    rfiCode == null -> null
    rfiCode.startsWith("EAV") -> "EAV"
    rfiCode.startsWith("FNB") -> "FNB"
    rfiCode.startsWith("ARST") -> "ARST"
    rfiCode.startsWith("CH") -> "CH"
    else -> null
}

/** Il gettone colorato con la sigla della rete, accanto al nome. */
@Composable
fun ReteBadge(sigla: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            sigla,
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * Il nome di una stazione col suo badge di rete, se fuori-RFI.
 *
 * L'unico posto dove si decide come una stazione si presenta: la lista dei
 * suggerimenti, il campo dopo la scelta, la cronologia, le salvate, le preferite.
 * Cosi' Sorrento porta la sua sigla `EAV` dovunque compaia, e Roma Termini niente.
 */
@Composable
fun NomeStazione(
    name: String,
    rfiCode: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    maxLines: Int = 1,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            name,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        reteFuoriRfi(rfiCode)?.let { ReteBadge(it) }
    }
}

/**
 * Una tratta «partenza → arrivo», ciascuna col suo badge di rete.
 *
 * Per cronologia e salvate, dove prima c'era una stringa piatta: ora se una
 * delle due punte e' fuori-RFI si vede da qui, senza aprire nulla.
 */
@Composable
fun TrattaConBadge(
    fromName: String,
    fromRfi: String?,
    toName: String,
    toRfi: String?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NomeStazione(fromName, fromRfi, Modifier.weight(1f, fill = false), style = style)
        Text("→", style = style)
        NomeStazione(toName, toRfi, Modifier.weight(1f, fill = false), style = style)
    }
}
