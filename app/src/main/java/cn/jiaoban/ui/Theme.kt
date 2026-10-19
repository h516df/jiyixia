package cn.jiaoban.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF5F4EF)
private val PaperNight = Color(0xFF191D1C)
private val Ink = Color(0xFF263A34)
private val InkSoft = Color(0xFF65736B)
private val Indigo = Color(0xFF486859)
private val Green = Color(0xFF486859)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDE7),
    onSurfaceVariant = InkSoft,
    primaryContainer = Color(0xFFDEE8DC),
    onPrimaryContainer = Color(0xFF294638),
    secondaryContainer = Color(0xFFE8E6DC),
    onSecondaryContainer = Color(0xFF4B5745),
    outline = Color(0xFF8D9C91),
    outlineVariant = Color(0xFFDCE2D9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3CBB6),
    onPrimary = Color(0xFF213C2E),
    primaryContainer = Color(0xFF314B3C),
    onPrimaryContainer = Color(0xFFDCEBD8),
    secondary = Color(0xFF7CC4AE),
    onSecondary = Color(0xFF07231C),
    background = PaperNight,
    onBackground = Color(0xFFF2EBE0),
    surface = Color(0xFF222A25),
    onSurface = Color(0xFFF2EBE0),
    surfaceVariant = Color(0xFF2B352E),
    onSurfaceVariant = Color(0xFFBCC8BC),
    secondaryContainer = Color(0xFF354338),
    onSecondaryContainer = Color(0xFFD0DFCD),
    outline = Color(0xFF8F9E91),
    outlineVariant = Color(0xFF3E4D42),
)

@Composable
fun JiaobanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
