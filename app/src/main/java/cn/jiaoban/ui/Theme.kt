package cn.jiaoban.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Neutral Notes / Journal palette: contrast and typographic hierarchy first.
// The amber tint is intentionally reserved for active controls; photos supply color.
private val Paper = Color(0xFFF8F8F7)
private val PaperNight = Color(0xFF151515)
private val Ink = Color(0xFF1D1D1F)
private val InkSoft = Color(0xFF747477)
private val Accent = Color(0xFF936317)
private val SurfacePaper = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = SurfacePaper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F1EF),
    onSurfaceVariant = InkSoft,
    primaryContainer = Color(0xFFFFF0D4),
    onPrimaryContainer = Color(0xFF684308),
    secondaryContainer = Color(0xFFF4F1EA),
    onSecondaryContainer = Color(0xFF47433D),
    outline = Color(0xFFA2A2A6),
    outlineVariant = Color(0xFFE8E8EB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE5B96B),
    onPrimary = Color(0xFF3F2603),
    primaryContainer = Color(0xFF4B371B),
    onPrimaryContainer = Color(0xFFFFE7B7),
    secondary = Color(0xFFE5B96B),
    onSecondary = Color(0xFF3F2603),
    background = PaperNight,
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF262628),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF323235),
    onSurfaceVariant = Color(0xFFB7B7BE),
    secondaryContainer = Color(0xFF363330),
    onSecondaryContainer = Color(0xFFD9D2C7),
    outline = Color(0xFF8B8B91),
    outlineVariant = Color(0xFF414144),
)

private val WritingTypography = Typography(
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
)

@Composable
fun JiaobanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = WritingTypography,
        content = content,
    )
}
