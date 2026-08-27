package it.zawardo.treni.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.zawardo.treni.BuildConfig
import it.zawardo.treni.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.avatar_zawardo),
                    contentDescription = "Avatar di Zawardo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text("ZawardoTreni", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Versione ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Crafted by Zawardo with AI slop.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Without any monetization pattern.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Free app for modern slaves.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider()

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    stringResource(R.string.disclaimer),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            Section(
                title = "Da dove arrivano i dati",
                body = "Orari e itinerari dal backend Le Frecce. Ritardi, fermate, binari e " +
                    "posizione dei treni da ViaggiaTreno, il portale di RFI.\n\n" +
                    "Sono servizi pubblici ma non documentati: possono cambiare senza preavviso.",
            )

            Section(
                title = "Perché sulle date future non c'è il ritardo",
                body = "I dati in tempo reale esistono solo per la giornata in corso. " +
                    "Per una data futura si può mostrare soltanto l'orario previsto: " +
                    "il ritardo non è ancora un'informazione esistente, e inventarlo " +
                    "sarebbe peggio che ometterlo.",
            )

            Section(
                title = "Biglietti",
                body = "L'app non vende biglietti e non accede al tuo account Trenitalia. " +
                    "Mostra soltanto informazioni di viaggio.",
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
