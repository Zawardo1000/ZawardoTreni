package it.zawardo.treni.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Il blu dei cartelli di stazione e il giallo del quadro partenze.
 *
 * Sono due colori che chiunque abbia preso un treno in Italia ha gia' visto: il
 * blu del cartello col nome della stazione, e il giallo del foglio con le
 * partenze appeso nell'atrio. Il blu fa da barra e da identita', il giallo
 * segna il tratto di viaggio che e' tuo. Il rosso locomotiva dell'icona resta
 * l'accento.
 *
 * Lo schema e' scritto per intero, ruolo per ruolo. Con i soli ruoli principali
 * valorizzati, tutti gli altri restavano quelli di serie di Material, che
 * nascono da un viola: la pillola del menu in basso lilla, i contorni dei campi
 * grigio-viola, le tinte tenui fuori tono. E' il tipo di stonatura che non si
 * sa nominare, ma si vede.
 */

/** Blu della barra in cima, in chiaro: quello dei cartelli di stazione. */
private val BluCartello = Color(0xFF0D3B8E)

/** In scuro il blu si scurisce ma resta blu: nero su nero non si legge come barra. */
private val BluCartelloNotte = Color(0xFF0A1F4D)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1747A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF001848),
    inversePrimary = Color(0xFFAFC4FF),

    secondary = Color(0xFF565E71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),

    // Il rosso locomotiva: accento, non allarme.
    tertiary = Color(0xFFC62828),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD6),
    onTertiaryContainer = Color(0xFF410002),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF141821),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF141821),
    surfaceVariant = Color(0xFFE2E6EE),
    onSurfaceVariant = Color(0xFF4A5162),
    surfaceTint = Color(0xFF1747A6),

    // Le tinte su cui poggiano schede e tabelloni: appena azzurrate, non grigie.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FB),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE6EAF2),
    surfaceContainerHighest = Color(0xFFDFE3EC),

    outline = Color(0xFF747B8C),
    outlineVariant = Color(0xFFC9CEDA),
    inverseSurface = Color(0xFF2E323B),
    inverseOnSurface = Color(0xFFF1F2F6),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC4FF),
    onPrimary = Color(0xFF002D6B),
    primaryContainer = Color(0xFF1C3F85),
    onPrimaryContainer = Color(0xFFDCE5FF),
    inversePrimary = Color(0xFF1747A6),

    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283041),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),

    tertiary = Color(0xFFFF8A80),
    onTertiary = Color(0xFF690005),
    tertiaryContainer = Color(0xFF93000A),
    onTertiaryContainer = Color(0xFFFFDAD6),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF101318),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF101318),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF3A404C),
    onSurfaceVariant = Color(0xFFB9BFCC),
    surfaceTint = Color(0xFFAFC4FF),

    surfaceContainerLowest = Color(0xFF0B0D11),
    surfaceContainerLow = Color(0xFF171A21),
    surfaceContainer = Color(0xFF1B1F27),
    surfaceContainerHigh = Color(0xFF242833),
    surfaceContainerHighest = Color(0xFF2E3340),

    outline = Color(0xFF8A91A0),
    outlineVariant = Color(0xFF3A404C),
    inverseSurface = Color(0xFFE6E8EE),
    inverseOnSurface = Color(0xFF2E323B),
    scrim = Color.Black,
)

/**
 * I colori dell'identita' che non stanno nello schema Material.
 *
 * Material darebbe due strade per la barra: il colore della superficie, cioe'
 * quasi invisibile, oppure `primary`, che in scuro diventa azzurro chiaro e
 * ribalta l'app. Qui la barra e' un elemento di identita' e resta blu in tutti
 * e due i temi, con il testo bianco sopra.
 */
object TreniBrand {

    val topBar: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BluCartelloNotte else BluCartello

    val onTopBar: Color
        @Composable @ReadOnlyComposable
        get() = Color.White

    /**
     * La stella dei preferiti quando e' accesa.
     *
     * Sul blu il rosso dell'accento si spegne: l'ambra si legge da lontano ed e'
     * il colore con cui "salvato" si capisce senza istruzioni.
     */
    val star: Color = Color(0xFFFFC947)

    /**
     * Il giallo del quadro partenze: la fascia del tuo tratto dentro la corsa,
     * dalla fermata in cui sali a quella in cui scendi.
     */
    val tuoTratto: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF362E10) else Color(0xFFFFF1C2)

    /** La stessa fascia, una fermata si' e una no: la zebratura dentro il tuo tratto. */
    val tuoTrattoZebra: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF3D3414) else Color(0xFFFBE9B0)

    /**
     * Il fondo delle fermate alterne nel percorso di una corsa: appena piu'
     * scuro della superficie, quanto basta a legare gli orari alla loro fermata.
     */
    val zebra: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF161A21) else Color(0xFFF2F4F8)

    /**
     * Il segno del treno sul percorso, e l'etichetta «Sali».
     *
     * In chiaro e' il blu del cartello. In scuro diventa giallo: un blu scuro
     * sul fondo scuro sparirebbe, e il treno e' la cosa da trovare per prima.
     */
    val segnale: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFFFFD34D) else BluCartello

    val suSegnale: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) Color(0xFF231A00) else Color.White
}

@Composable
fun ZawardoTreniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /*
     * Material You resta spento.
     *
     * Prendere i colori dallo sfondo del telefono voleva dire un'app diversa su
     * ogni dispositivo e mai quella dell'icona: il blu spariva, e con lui
     * l'unica cosa che rendeva l'app riconoscibile a colpo d'occhio.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, typography = TreniTypography, content = content)
}
