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

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA5D6A7),
    onPrimaryContainer = Color(0xFF002106),
    secondary = Color(0xFF52634F),
    tertiary = Color(0xFF00600F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD98F),
    onPrimary = Color(0xFF00390B),
    primaryContainer = Color(0xFF005313),
    onPrimaryContainer = Color(0xFFA5D6A7),
    secondary = Color(0xFFB9CCB4),
    tertiary = Color(0xFF6FDB7C),
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
