package cn.jiaoban.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF5F6F8)
private val PaperNight = Color(0xFF111621)
private val Ink = Color(0xFF182334)
private val InkSoft = Color(0xFF596678)
private val Indigo = Color(0xFF3856A6)
private val Green = Color(0xFF247563)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Green,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDF4),
    onSurfaceVariant = InkSoft,
    primaryContainer = Color(0xFFE4EDFF),
    onPrimaryContainer = Color(0xFF183665),
    secondaryContainer = Color(0xFFE1F1EC),
    onSecondaryContainer = Color(0xFF194B3D),
    outline = Color(0xFFADB7C7),
    outlineVariant = Color(0xFFDDE3ED),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C6FF),
    onPrimary = Color(0xFF142D58),
    primaryContainer = Color(0xFF243B63),
    onPrimaryContainer = Color(0xFFDEEAFF),
    secondary = Color(0xFF7CC4AE),
    onSecondary = Color(0xFF07231C),
    background = PaperNight,
    onBackground = Color(0xFFF2EBE0),
    surface = Color(0xFF1B2332),
    onSurface = Color(0xFFF2EBE0),
    surfaceVariant = Color(0xFF293448),
    onSurfaceVariant = Color(0xFFC1CBDD),
    secondaryContainer = Color(0xFF193F35),
    onSecondaryContainer = Color(0xFFC7EFE2),
    outline = Color(0xFF8491A8),
    outlineVariant = Color(0xFF384457),
)

@Composable
fun JiaobanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
