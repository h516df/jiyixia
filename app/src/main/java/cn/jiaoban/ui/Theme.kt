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

// A warm paper-and-pine system. Hierarchy comes from type and spacing, not shadows.
private val Paper = Color(0xFFF5F4EF)
private val PaperNight = Color(0xFF191D1C)
private val Ink = Color(0xFF263A34)
private val InkSoft = Color(0xFF596A60)
private val Pine = Color(0xFF486859)
private val SurfacePaper = Color(0xFFFFFEFA)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    secondary = Pine,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = SurfacePaper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAEDE7),
    onSurfaceVariant = InkSoft,
    primaryContainer = Color(0xFFDEE8DC),
    onPrimaryContainer = Color(0xFF294638),
    secondaryContainer = Color(0xFFE8E6DC),
    onSecondaryContainer = Color(0xFF4B5745),
    outline = Color(0xFF8D9C91),
    outlineVariant = Color(0xFFDDE3DB),
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

private val WritingTypography = Typography(
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
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
