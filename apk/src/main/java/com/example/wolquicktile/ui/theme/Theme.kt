package com.example.wolquicktile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0B2F6B),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F1FF),
    onSecondaryContainer = Color(0xFF12376D),
    tertiary = Color(0xFF3B82F6),
    background = Color(0xFFF5F8FE),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE7EEF8),
    onSurfaceVariant = Color(0xFF526173),
    outline = Color(0xFF8CA5C4),
    outlineVariant = Color(0xFFD3DEEC)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF08204A),
    primaryContainer = Color(0xFF143B73),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFFA8C7FA),
    onSecondary = Color(0xFF0E2A55),
    secondaryContainer = Color(0xFF173B6C),
    onSecondaryContainer = Color(0xFFE8F1FF),
    tertiary = Color(0xFF6EA8FF),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFE5EAF3),
    surface = Color(0xFF172033),
    onSurface = Color(0xFFE5EAF3),
    surfaceVariant = Color(0xFF26354C),
    onSurfaceVariant = Color(0xFFB9C7DA),
    outline = Color(0xFF7890AD),
    outlineVariant = Color(0xFF344761)
)

@Composable
fun WolQuickTileTheme(
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme: ColorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
