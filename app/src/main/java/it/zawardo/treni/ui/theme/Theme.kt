package it.zawardo.treni.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Palette derivata dall'icona: blu notte primario, rosso locomotiva come accento.
private val LightColors = lightColorScheme(
    primary = Color(0xFF002171),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565E71),
    tertiary = Color(0xFFC62828),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    tertiary = Color(0xFFFF5252),
    onTertiary = Color(0xFF690005),
    error = Color(0xFFFFB4AB),
)

@Composable
fun ZawardoTreniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: disponibile solo da Android 12
    dynamicColor: Boolean = true,
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
