package it.zawardo.treni.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import it.zawardo.treni.ui.theme.TreniBrand

/**
 * La barra in cima, una sola per tutta l'app.
 *
 * Prima ogni schermata si costruiva la sua: stesso componente, ma titoli di peso
 * diverso, sottotitoli disallineati e il colore lasciato a Material, che le
 * rendeva sei rettangoli bianchi indistinguibili dal contenuto. Tenerla in un
 * posto solo e' anche l'unico modo perche' restino uguali fra loro quando se ne
 * aggiunge una settima.
 *
 * Il blu notte non e' decorazione: e' il bordo superiore dell'app, quello che
 * dice dove finisce il sistema e comincia il programma.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreniTopBar(
    title: String,
    modifier: Modifier = Modifier,
    /** Seconda riga piccola: la data della corsa, la tratta cercata, l'orario. */
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    title,
                    style = if (subtitle == null) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        // Piu' tenue del titolo, ma sempre bianco: sul blu il
                        // grigio di Material sparirebbe.
                        color = TreniBrand.onTopBar.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = TreniBrand.topBar,
            // Uguale anche sotto il contenuto che scorre: la barra non schiarisce
            // a meta' gesto, che era l'effetto piu' casereccio di tutti.
            scrolledContainerColor = TreniBrand.topBar,
            titleContentColor = TreniBrand.onTopBar,
            navigationIconContentColor = TreniBrand.onTopBar,
            actionIconContentColor = TreniBrand.onTopBar,
        ),
    )
}
