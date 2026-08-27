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
 * Blu notte e rosso locomotiva, gli stessi dell'icona.
 *
 * Lo schema e' scritto per intero, ruolo per ruolo. Prima ne erano valorizzati
 * otto: tutti gli altri restavano quelli di serie di Material, che nascono da un
 * viola. Il risultato era un'app blu con la pillola del menu in basso lilla, i
 * contorni dei campi grigio-viola e le tinte tenui fuori tono - il tipo di
 * stonatura che non si sa nominare ma si vede.
 */

/** Blu della barra in cima, in chiaro. E' il colore con cui l'app si riconosce. */
private val NavyLight = Color(0xFF002171)

/** In scuro il blu si schiarisce appena: nero su nero non si legge come barra. */
private val NavyDark = Color(0xFF0B1E4A)

private val LightColors = lightColorScheme(
    primary = Color(0xFF21469B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001648),
    inversePrimary = Color(0xFFB0C6FF),

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

    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    surfaceTint = Color(0xFF21469B),

    // Le tinte su cui poggiano schede e tabelloni: appena azzurrate, non grigie.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFF1F3FB),
    surfaceContainerHigh = Color(0xFFEBEDF6),
    surfaceContainerHighest = Color(0xFFE5E8F2),

    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF14458F),
    onPrimaryContainer = Color(0xFFDCE2FF),
    inversePrimary = Color(0xFF21469B),

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

    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB0C6FF),

    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),

    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    scrim = Color.Black,
)

/**
 * I colori della barra in cima, che non stanno nello schema Material.
 *
 * Material darebbe due strade: barra del colore della superficie, cioe' quasi
 * invisibile, oppure `primary`, che in scuro diventa azzurro chiaro e ribalta
 * l'app. Qui la barra e' un elemento di identita' e resta blu notte in tutti e
 * due i temi, con il testo bianco sopra.
 */
object TreniBrand {

    val topBar: Color
        @Composable @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) NavyDark else NavyLight

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
}

@Composable
fun ZawardoTreniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /*
     * Material You resta spento.
     *
     * Prendere i colori dallo sfondo del telefono voleva dire un'app diversa su
     * ogni dispositivo e mai quella dell'icona: il blu notte spariva, e con lui
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
    MaterialTheme(colorScheme = colorScheme, content = content)
}
