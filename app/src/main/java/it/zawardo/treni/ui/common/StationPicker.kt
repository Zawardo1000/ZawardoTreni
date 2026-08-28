package it.zawardo.treni.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.zawardo.treni.domain.model.NearbyStation
import it.zawardo.treni.domain.model.Station
import java.util.Locale

/**
 * La lista da cui si sceglie una stazione, uguale ovunque si scelga.
 *
 * Va messa **subito sotto il campo che si sta compilando**: appesa in fondo alla
 * schermata finiva dietro la tastiera, e di dieci suggerimenti se ne vedevano
 * due. Chi la usa e' responsabile di posizionarla li'.
 *
 * Mostra [nearby] se il mirino ha appena proposto qualcosa, altrimenti
 * [suggestions]. Le due cose non convivono mai: sono due risposte alla stessa
 * domanda, e sovrapporle vorrebbe dire far scegliere fra due elenchi.
 */
@Composable
fun StationPicker(
    suggestions: List<Station>,
    nearby: List<NearbyStation>,
    loading: Boolean,
    onPick: (Station) -> Unit,
    modifier: Modifier = Modifier,
    // Vuoto = nessun avviso: la ricerca tratta funziona con qualsiasi stazione
    // (le basta il locationId), quindi li' non c'e' niente da segnalare. Il
    // tabellone invece passa un testo, perche' senza codice RFI non ha partenze.
    untrackedNote: String = "",
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        if (nearby.isNotEmpty()) {
            SectionHeader(
                icon = Icons.Filled.MyLocation,
                title = "Le più vicine a te",
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            )
            nearby.forEach { vicina ->
                StationRow(
                    station = vicina.station,
                    untrackedNote = untrackedNote,
                    trailing = formatDistance(vicina.distanceKm),
                    onPick = onPick,
                )
            }
            return@Card
        }

        if (loading && suggestions.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        // Tetto all'altezza: la lista deve restare dentro la parte di schermo
        // che la tastiera lascia libera, altrimenti tanto valeva lasciarla in fondo.
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(suggestions, key = { it.locationId }) { s ->
                StationRow(station = s, untrackedNote = untrackedNote, onPick = onPick)
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    untrackedNote: String,
    onPick: (Station) -> Unit,
    trailing: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(station) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    station.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Le reti fuori-RFI portano la loro sigla, cosi' si distingue a
                // colpo d'occhio la Sorrento della Circumvesuviana dal resto.
                reteFuoriRfi(station.rfiCode)?.let { sigla -> ReteBadge(sigla) }
            }
            if (!station.trackable && untrackedNote.isNotBlank()) {
                // Senza codice RFI il treno non è tracciabile: meglio dirlo prima.
                // Ma solo dove conta davvero — il tabellone — non nella ricerca.
                Text(
                    untrackedNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            // Monospace: le distanze restano incolonnate e si confrontano a colpo d'occhio.
            Text(
                trailing,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/**
 * La distanza in linea d'aria, arrotondata a quanto serve per scegliere.
 *
 * Sotto il chilometro si contano i metri a cinquantine: e' una distanza a piedi,
 * e "450 m" darebbe una precisione che il GPS non ha.
 */
private fun formatDistance(km: Double): String = when {
    km < 1.0 -> "${(km * 1000 / 50).toInt() * 50} m"
    km < 10.0 -> String.format(Locale.ITALIAN, "%.1f km", km)
    else -> "${km.toInt()} km"
}

/**
 * La sigla della rete fuori-RFI di una stazione, dal prefisso del suo codice.
 *
 * Le reti fuori dal registro nazionale usano codici sintetici col prefisso della
 * rete; le stazioni RFI — nazionale, Trenord, Italo — hanno codici `S…`/`Z…` e
 * nessun badge, perche' sono la norma e marcarle tutte sarebbe rumore.
 */
private fun reteFuoriRfi(rfiCode: String?): String? = when {
    rfiCode == null -> null
    rfiCode.startsWith("EAV") -> "EAV"
    rfiCode.startsWith("FNB") -> "FNB"
    rfiCode.startsWith("ARST") -> "ARST"
    rfiCode.startsWith("CH") -> "CH"
    else -> null
}

/** Il gettone colorato con la sigla della rete, accanto al nome. */
@Composable
private fun ReteBadge(sigla: String) {
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
