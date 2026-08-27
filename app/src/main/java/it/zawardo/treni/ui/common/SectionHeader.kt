package it.zawardo.treni.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Il titolo di una sezione, con la sua icona.
 *
 * L'icona non e' un ornamento: in una lista che scorre e' quella che si trova
 * per prima, prima ancora di leggere. Un orologio dice "roba di prima", un
 * dischetto dice "roba che ho messo da parte", e la differenza si coglie senza
 * passare dalle parole.
 *
 * Sta qui perche' prima ogni schermata se lo disegnava a mano, allineando icona
 * e testo con due spazi dentro la stringa: bastava un carattere in piu' e le
 * sezioni non erano piu' incolonnate fra loro.
 */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tint,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
