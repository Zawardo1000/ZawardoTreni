package it.zawardo.treni.ui.about

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import it.zawardo.treni.BuildConfig
import it.zawardo.treni.R

private const val SOURCE_URL = "https://github.com/Zawardo1000/ZawardoTreni"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

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
                    // Il commit esatto da cui nasce questa build: toccandolo si
                    // apre su GitHub. Serve per sapere cosa si ha in mano quando
                    // si segnala un problema.
                    Text(
                        "build ${BuildConfig.GIT_SHA} · ${BuildConfig.BUILD_DATE}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    "$SOURCE_URL/commit/${BuildConfig.GIT_SHA}".toUri(),
                                ),
                            )
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Crafted by Zawardo with AI slop.", style = MaterialTheme.typography.bodyLarge)
                Text("Without any monetization pattern.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Free app for modern slaves.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider()

            Section(
                title = "I tuoi dati restano tuoi",
                body = "Questa app non raccoglie niente di te. Nessun account, nessun " +
                    "profilo, nessun identificativo.\n\n" +
                    "Di rete esce solo quello che serve a fare la domanda: le stazioni " +
                    "che cerchi, l'orario che hai scelto, il numero del treno. Nient'altro: " +
                    "nessun identificativo, niente che dica chi sei. E comunque niente che " +
                    "torni a me, perché server miei non ne esistono. Cronologia, ricerche " +
                    "salvate e treni preferiti restano sul telefono e non escono da lì; " +
                    "disinstallando l'app spariscono con lei.\n\n" +
                    "La posizione, se la concedi, serve solo a trovare la stazione più " +
                    "vicina. Per saperlo le coordinate vengono mandate a Trenitalia, che è " +
                    "chi ha l'elenco delle stazioni: da solo non saprei rispondere. Non le " +
                    "salvo e non le tengo, ma è giusto tu sappia che in quel momento " +
                    "escono dal telefono.\n\n" +
                    "Non è una promessa generosa: è che i fatti degli altri non mi " +
                    "interessano, e rivenderli mi interessa ancora meno.",
            )

            Section(
                title = "Niente monetizzazione",
                body = "Nessuna pubblicità. Nessun tracciamento, nessuna analitica, " +
                    "nessun SDK di terze parti che guarda cosa fai.\n\n" +
                    "Nessun acquisto in-app, nessun abbonamento, nessuna versione " +
                    "«pro», nessuna funzione tenuta in ostaggio. Nessuna newsletter, " +
                    "nessuna notifica che non abbia chiesto tu.\n\n" +
                    "L'app non vende biglietti e non accede al tuo account Trenitalia.",
            )

            Section(
                title = "Da dove arrivano i dati",
                body = "Tre sorgenti, perché nessuna da sola basta.\n\n" +
                    "• Le Frecce — orari e itinerari sulla rete nazionale.\n\n" +
                    "• ViaggiaTreno (RFI) — ritardi, fermate, binari e posizione " +
                    "dei treni in tempo reale.\n\n" +
                    "• Trenord — il servizio regionale e suburbano lombardo, " +
                    "comprese le linee S del Passante milanese, che le altre due " +
                    "non conoscono. È anche l'unica che segnala lavori, " +
                    "sospensioni di linea e servizi sostitutivi.\n\n" +
                    "Sono servizi pubblici ma non documentati: possono cambiare " +
                    "senza preavviso.",
            )

            SourceLink(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                },
            )

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            ) {
                Text(
                    stringResource(R.string.disclaimer),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceLink(onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Codice sorgente", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SOURCE_URL,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Tutto quello che l'app fa è leggibile lì dentro. Se qualcosa qui sopra " +
                "non ti convince, puoi controllarlo invece di crederci.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
