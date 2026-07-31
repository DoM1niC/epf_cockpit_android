package eu.dns3d.epfcockpit.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import eu.dns3d.epfcockpit.data.ThemeMode

private val FallbackDark = darkColorScheme(
    primary = EpfTeal80,
    secondary = EpfAmber80,
    tertiary = EpfMagenta80,
)

private val FallbackLight = lightColorScheme(
    primary = EpfTeal40,
    secondary = EpfAmber40,
    tertiary = EpfMagenta40,
)

@Composable
fun EpfCockpitTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Kein window.statusBarColor mehr: ab targetSdk 35 erzwingt Android
            // Edge-to-Edge (MainActivity ruft enableEdgeToEdge()), die Property
            // ist wirkungslos und seit API 35 deprecated. Die Icon-Helligkeit
            // muss weiterhin explizit zur Theme-Wahl passen.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = EpfTypography,
        shapes = EpfShapes,
        content = content,
    )
}
