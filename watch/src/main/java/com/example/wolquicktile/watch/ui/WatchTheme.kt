package com.example.wolquicktile.watch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1557C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF4A5F7B),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF111318),
    surface = Color.White,
    onSurface = Color(0xFF111318),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF454A54),
    outlineVariant = Color(0xFFD0D6E0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6C),
    primaryContainer = Color(0xFF164589),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFBAC7E1),
    background = Color(0xFF0F1116),
    onBackground = Color(0xFFE3E5EB),
    surface = Color(0xFF17191F),
    onSurface = Color(0xFFE3E5EB),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outlineVariant = Color(0xFF45474F)
)

@Composable
fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
