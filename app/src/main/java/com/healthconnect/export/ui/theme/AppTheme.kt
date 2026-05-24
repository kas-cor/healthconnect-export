package com.healthconnect.export.ui.theme

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// MD3 Light theme — health-focused palette (green accent)
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F5C9),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4E6356),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E9D8),
    onSecondaryContainer = Color(0xFF0C1F15),
    tertiary = Color(0xFF3C6473),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFE9FB),
    onTertiaryContainer = Color(0xFF001F29),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC0C9C0),
)

// MD3 Dark theme
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD8AF),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFA7F5C9),
    secondary = Color(0xFFB4CCBC),
    onSecondary = Color(0xFF213529),
    secondaryContainer = Color(0xFF374B3E),
    onSecondaryContainer = Color(0xFFD0E9D8),
    tertiary = Color(0xFFA3CDDE),
    onTertiary = Color(0xFF043544),
    tertiaryContainer = Color(0xFF234C5B),
    onTertiaryContainer = Color(0xFFBFE9FB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1A),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C0),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF414942),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        else -> if (darkTheme) DarkColors else LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
