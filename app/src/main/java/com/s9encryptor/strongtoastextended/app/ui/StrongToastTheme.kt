package com.s9encryptor.strongtoastextended.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.s9encryptor.strongtoastextended.app.model.ThemeMode

internal val Neon = Color(0xFF0BFC03)
internal val Midnight = Color(0xFF080B09)
internal val Rainbow = listOf(Color(0xFFFA6558), Color(0xFFFFCE57), Neon, Color(0xFF41D9FF), Color(0xFFA98CFF), Color(0xFFFA6558))

private val DarkPalette = darkColorScheme(
    primary = Neon, onPrimary = Color(0xFF052600), primaryContainer = Color(0xFF133B13),
    onPrimaryContainer = Color(0xFFC8FFC0), secondary = Color(0xFFB8D4B3),
    background = Midnight, onBackground = Color(0xFFF2F5EF),
    surface = Color(0xFF101612), onSurface = Color(0xFFECF1E9),
    surfaceVariant = Color(0xFF1B241E), onSurfaceVariant = Color(0xFF9DADA0),
    outline = Color(0xFF354139), outlineVariant = Color(0xFF25312A),
    error = Color(0xFFFF897F), onError = Color(0xFF3E0905),
)
private val LightPalette = lightColorScheme(
    primary = Color(0xFF168600), onPrimary = Color.White, primaryContainer = Color(0xFFD4FFCD),
    onPrimaryContainer = Color(0xFF052900), secondary = Color(0xFF476642),
    background = Color(0xFFF3F7F0), onBackground = Color(0xFF101D12),
    surface = Color.White, onSurface = Color(0xFF17251A),
    surfaceVariant = Color(0xFFE7EEE3), onSurfaceVariant = Color(0xFF52644F),
    outline = Color(0xFF82927C), outlineVariant = Color(0xFFD5DFCF),
)
private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 64.sp, letterSpacing = (-3).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 46.sp, letterSpacing = (-2).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.sp),
)

@Composable
internal fun StrongToastTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette, typography = AppTypography, content = content)
}
