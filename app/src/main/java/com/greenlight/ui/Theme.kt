package com.greenlight.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GoGreen = Color(0xFF1B8E4A)
val WaitAmber = Color(0xFFC0820F)
val StopRed = Color(0xFFB3403F)
val Neutral = Color(0xFF3A4150)

private val DarkScheme = darkColorScheme(
    primary = GoGreen,
    secondary = WaitAmber,
    error = StopRed,
)

private val LightScheme = lightColorScheme(
    primary = GoGreen,
    secondary = WaitAmber,
    error = StopRed,
)

@Composable
fun GreenLightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
