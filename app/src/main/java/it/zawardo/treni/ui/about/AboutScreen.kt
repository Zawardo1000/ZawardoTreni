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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoneyOff
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import it.zawardo.treni.BuildConfig
import it.zawardo.treni.ui.common.SectionHeader
import it.zawardo.treni.ui.common.TreniTopBar
import it.zawardo.treni.R

private const val SOURCE_URL = "https://github.com/Zawardo1000/ZawardoTreni"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TreniTopBar(title = "Info", onBack = onBack)
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
                icon = Icons.Outlined.Lock,
                title = "I tuoi dati restano tuoi",
                body = "Questa app non raccoglie niente di te. Nessun account, nessun " +
                    "profilo, nessun identificativo.\n\n" +
                    "Di rete esce solo quello che serve a fare la domanda: le stazioni " +
                    "che cerchi, l'orario che hai scelto, il numero del treno. Nient'altro: " +
                    "nessun identificativo, niente che dica chi sei. E comunque niente che " +
                    "torni a me, perché server miei non ne esistono. Cronologia, ricerche " +
                    "salvate e treni preferiti restano sul telefono: non li mando da " +
                    "nessuna parte, e disinstallando l'app spariscono con lei.\n\n" +
                    "Un'eccezione, che preferisco dirti invece di lasciartela scoprire: " +
                    "se hai attivo il backup di Android, è il sistema operativo a " +
                    "copiarli sul tuo Google Drive, come fa con le altre app. Non " +
                    "passano da me e restano roba tua, ma «non escono mai dal telefono» " +
                    "sarebbe falso. Si spegne dalle impostazioni di backup del " +
                    "telefono.\n\n" +
                    "La posizione, se la concedi, serve solo a trovare la stazione più " +
                    "vicina. Per saperlo le coordinate vengono mandate a Trenitalia, che è " +
                    "chi ha l'elenco delle stazioni: da solo non saprei rispondere. Non le " +
                    "salvo e non le tengo, ma è giusto tu sappia che in quel momento " +
                    "escono dal telefono.\n\n" +
                    "Non è una promessa generosa: è che i fatti degli altri non mi " +
                    "interessano, e rivenderli mi interessa ancora meno.",
            )

            Section(
                icon = Icons.Outlined.MoneyOff,
                title = "Niente monetizzazione",
                body = "Nessuna pubblicità. Nessun tracciamento, nessuna analitica, " +
                    "nessun SDK di terze parti che guarda cosa fai.\n\n" +
                    "Nessun acquisto in-app, nessun abbonamento, nessuna versione " +
                    "«pro», nessuna funzione tenuta in ostaggio. Nessuna newsletter, " +
                    "nessuna notifica che non abbia chiesto tu.\n\n" +
                    "L'app non vende biglietti e non accede al tuo account Trenitalia.",
            )

            Section(
                icon = Icons.Outlined.Hub,
                title = "Da dove arrivano i dati",
                body = "Più sorgenti, perché nessuna da sola basta.\n\n" +
                    "• Le Frecce — orari e itinerari sulla rete nazionale.\n\n" +
                    "• ViaggiaTreno (RFI) — ritardi, fermate, binari e posizione " +
                    "dei treni in tempo reale.\n\n" +
                    "• Trenord — il servizio regionale e suburbano lombardo, " +
                    "comprese le linee S del Passante milanese, che le altre due " +
                    "non conoscono. È anche l'unica che segnala lavori, " +
                    "sospensioni di linea e servizi sostitutivi.\n\n" +
                    "• Italo — le corse NTV, che nessuna delle altre tre pubblica: " +
                    "su ViaggiaTreno un treno Italo non compare affatto. Dal loro " +
                    "tabellone arrivano ritardo e binario.\n\n" +
                    "• EAV — la Circumvesuviana, la Cumana e la Circumflegrea: " +
                    "Napoli, il Vesuvio, Sorrento e i Campi Flegrei. Quelle " +
                    "stazioni sulla rete nazionale non esistono, quindi nessuna " +
                    "delle fonti qui sopra sa dirti niente di quei treni. Il " +
                    "ritardo c'è dove EAV tiene un monitor; per le linee che non " +
                    "ne hanno, e per i giorni futuri, resta l'orario.\n\n" +
                    "• Ferrotramviaria — la Bari–Barletta e il collegamento con " +
                    "l'aeroporto di Bari. Bitonto, Terlizzi, Ruvo, Corato e Andria " +
                    "sulla rete nazionale non hanno stazione: quei treni non " +
                    "esistono in nessuna delle altre fonti.\n\n" +
                    "• Orario dei trasporti svizzeri — la Vigezzina–Centovalli, " +
                    "Domodossola–Locarno. Non è rete RFI, ma la Svizzera la " +
                    "pubblica per intero, fermate italiane comprese: è l'unico " +
                    "modo per avere la Val Vigezzo in tempo reale.\n\n" +
                    "• ARST — le quattro ferrovie sarde a scartamento ridotto: " +
                    "Monserrato–Mandas–Isili, Macomer–Nuoro, Sassari–Alghero e " +
                    "Sassari–Sorso. È l'unica sorgente senza tempo reale: ARST " +
                    "non pubblica tabelloni, quindi di quelle corse si conosce " +
                    "l'orario previsto e mai il ritardo. In compenso è l'unica " +
                    "che sappia rispondere anche per i giorni futuri.\n\n" +
                    "Sono servizi pubblici ma non documentati: possono cambiare " +
                    "senza preavviso.\n\n" +
                    "Gli orari di EAV e ARST viaggiano dentro l'app e si " +
                    "riscaricano da soli quando superano i tre mesi, ma solo se " +
                    "quella rete è accesa nelle impostazioni. Mentre succede " +
                    "l'app lo dice: il file ARST pesa una ventina di megabyte, e " +
                    "non è traffico da consumarti alle spalle.\n\n" +
                    "Le coordinate delle fermate Ferrotramviaria vengono da " +
                    "OpenStreetMap, © i suoi contributori, con licenza ODbL: " +
                    "l'orario aperto dell'azienda non è più raggiungibile.",
            )

            Section(
                icon = Icons.Outlined.Info,
                title = "Dove i dati sono limitati",
                body = "Nessuna fonte è completa, e dirti il contrario sarebbe il primo " +
                    "modo di non meritare fiducia. Ecco dove l'app sa meno, e perché.\n\n" +
                    "• Italo compare solo per le corse in circolazione in quel momento — " +
                    "in viaggio o in partenza a breve, non l'intera giornata — e senza " +
                    "prezzo. Orario completo, giorni futuri e tariffe mancano per una mia " +
                    "scelta, spiegata qui sotto. Per la coincidenza veloce nei giorni " +
                    "futuri c'è la Freccia, che l'orario completo lo pubblica.\n\n" +
                    "• I prezzi ci sono per Trenitalia e Trenord, non per Italo — stesso " +
                    "motivo. È sempre la corsa singola a tariffa intera, seconda classe: " +
                    "le riduzioni per ragazzi e anziani esistono e le vedi al momento " +
                    "dell'acquisto, ma scriverle qui farebbe sembrare il viaggio più " +
                    "economico di quanto sia per quasi tutti. " +
                    "Su un viaggio con cambio interno alla stessa rete è il prezzo " +
                    "dell'intera soluzione. Su un misto fra operatori diversi, dove una " +
                    "tratta il prezzo lo pubblica — la Freccia, o Trenord — e l'altra no, " +
                    "mostro il parziale che c'è, etichettato «solo Trenitalia» (o Trenord): " +
                    "la parte che conosco, dichiarata per quella che è, vale più del " +
                    "silenzio; sarebbe spacciarla per il totale, quello sì, a ingannare. " +
                    "Se nessuna tratta ha un prezzo, come EAV più Italo, non mostro nulla.\n\n" +
                    "• Il ritardo è misurato all'ultimo rilevamento, e il punto di " +
                    "rilevamento spesso non è una fermata: è un posto di controllo o un " +
                    "bivio lungo la linea, dove il confronto è con un orario di transito. " +
                    "Per questo il numero in cima al dettaglio corsa può non coincidere " +
                    "col ritardo dell'ultima fermata effettuata — è più recente. La " +
                    "scheda scrive sempre dove e quando è stato preso, e le fermate " +
                    "ancora da fare riportano quello.\n\n" +
                    "• ARST, in Sardegna, dà l'orario previsto ma mai il ritardo o il " +
                    "binario: quelle ferrovie un tempo reale non lo pubblicano affatto. In " +
                    "cambio è l'unica che risponda anche per i giorni futuri.\n\n" +
                    "• Di un giorno che non è oggi si conosce l'orario e basta. Ritardo, " +
                    "binario e stato esistono solo per la giornata in corso: sul treno di " +
                    "domani l'app mostra fermate e orari previsti e dice che sono quelli, " +
                    "invece di ripeterti il ritardo che quel numero ha avuto oggi.\n\n" +
                    "• Gli orari di EAV e ARST viaggiano dentro l'app: sono una fotografia, " +
                    "rinfrescata quando invecchia di qualche mese. Un cambiamento d'orario " +
                    "appena entrato in vigore può non esserci ancora.\n\n" +
                    "• Sono tutti servizi pubblici ma non documentati: nessuno garantisce " +
                    "che domani rispondano come oggi. Se una fonte si rompe, l'app te lo " +
                    "dice invece di inventare.",
            )

            Section(
                icon = Icons.Outlined.Handshake,
                title = "Su Italo: una scelta, non un limite tecnico",
                body = buildAnnotatedString {
                    append(
                        "Il resto dei dati Italo si prenderebbe lo stesso: il modo " +
                            "tecnico c'è, e altre app lo usano.\n\n" +
                            "Io no. Chi quei dati li possiede ha scelto di non darli a " +
                            "un'app come questa, ed è una scelta che ha il diritto di " +
                            "fare. Aggirarla resterebbe un aggiramento anche riuscendo " +
                            "benissimo: il rispetto di una scelta altrui vale poco se " +
                            "dura solo finché costa niente.\n\n" +
                            "Quindi di Italo trovi quello che Italo pubblica in chiaro: le " +
                            "corse in circolazione, col ritardo e il binario. Il resto no.\n\n" +
                            "Il codice però è aperto. Chi la pensa diversamente può " +
                            "forkarlo, ",
                    )
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("rispettandone la licenza")
                    }
                    append(
                        ", e aggiungere quei dati come preferisce: da lì in poi la " +
                            "responsabilità è sua, non mia.",
                    )
                },
            )

            SourceLink(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
                },
            )

            LicenseLink(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, LICENSE_URL.toUri()))
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
        SectionHeader(icon = Icons.Outlined.Code, title = "Codice sorgente")
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

/**
 * La licenza sta anche qui, non solo nel repository.
 *
 * La GPL chiede che chi riceve il programma sappia con quali diritti lo sta
 * usando: nascondere la cosa in un file di testo che nessuno apre sarebbe
 * rispettarla solo a parole.
 */
@Composable
private fun LicenseLink(onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionHeader(icon = Icons.Outlined.Gavel, title = "Licenza")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "GNU GPL v3 o successive",
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
            "Copyright © 2026 Zawardo.\n\n" +
                "Puoi usarla, studiarla, modificarla e ridistribuirla. A due condizioni: " +
                "che quello che ne ricavi resti aperto con questa stessa licenza — niente " +
                "versioni chiuse — e che continui a dire da dove viene, citando " +
                "ZawardoTreni e il suo autore.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, body: String) =
    Section(icon, title, AnnotatedString(body))

@Composable
private fun Section(icon: ImageVector, title: String, body: AnnotatedString) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader(icon = icon, title = title)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
