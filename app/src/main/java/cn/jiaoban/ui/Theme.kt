package cn.jiaoban.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFFBF6EE)
private val PaperNight = Color(0xFF17140F)
private val Ink = Color(0xFF1C1A17)
private val InkSoft = Color(0xFF6B6157)
private val Orange = Color(0xFFC2410C)
private val Green = Color(0xFF27705F)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFDF9),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1E9DC),
    onSurfaceVariant = InkSoft,
    secondaryContainer = Color(0xFFF7E4D2),
    onSecondaryContainer = Color(0xFF5A2A12),
    outline = Color(0xFFD8CCB9),
    outlineVariant = Color(0xFFE7DDCC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF08A4B),
    onPrimary = Color(0xFF2A1206),
    secondary = Color(0xFF7CC4AE),
    onSecondary = Color(0xFF07231C),
    background = PaperNight,
    onBackground = Color(0xFFF2EBE0),
    surface = Color(0xFF221E18),
    onSurface = Color(0xFFF2EBE0),
    surfaceVariant = Color(0xFF2E2820),
    onSurfaceVariant = Color(0xFFCBBFAF),
    secondaryContainer = Color(0xFF4A2A16),
    onSecondaryContainer = Color(0xFFF6D9C0),
    outline = Color(0xFF4A4238),
    outlineVariant = Color(0xFF3A342C),
)

@Composable
fun JiaobanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
