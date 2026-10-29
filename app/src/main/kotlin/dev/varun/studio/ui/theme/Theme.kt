package dev.varun.studio.ui.theme

import android.content.Context
import android.os.Build
import android.os.LocaleManager
import android.os.LocaleList
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- palette

private val DarkBg = Color(0xFF0B1220)
private val DarkSurface = Color(0xFF121A2B)
private val DarkSurfaceAlt = Color(0xFF1A2438)
private val DarkPrimary = Color(0xFF7DD3FC)
private val DarkSecondary = Color(0xFFA5B4FC)
private val DarkTertiary = Color(0xFF6EE7B7)
private val DarkError = Color(0xFFFCA5A5)
private val DarkOnBg = Color(0xFFE6EDF7)

private val LightBg = Color(0xFFFBFCFE)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceAlt = Color(0xFFF1F5F9)
private val LightPrimary = Color(0xFF0369A1)
private val LightSecondary = Color(0xFF4338CA)
private val LightTertiary = Color(0xFF047857)
private val LightError = Color(0xFFB91C1C)
private val LightOnBg = Color(0xFF0F172A)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF04121F),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = DarkSecondary,
    onSecondary = Color(0xFF111827),
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF052E1B),
    error = DarkError,
    onError = Color(0xFF450A0A),
    background = DarkBg,
    onBackground = DarkOnBg,
    surface = DarkSurface,
    onSurface = DarkOnBg,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = Color(0xFFB6C2D4),
    outline = Color(0xFF334155),
)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = LightSecondary,
    onSecondary = Color.White,
    tertiary = LightTertiary,
    onTertiary = Color.White,
    error = LightError,
    onError = Color.White,
    background = LightBg,
    onBackground = LightOnBg,
    surface = LightSurface,
    onSurface = LightOnBg,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
)

/** Monospace family for code and diffs. */
val CodeFamily = FontFamily.Monospace

private val VarunTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

/** Diff / code colour tokens, exposed through composition locals. */
data class CodeColors(
    val addedBg: Color,
    val addedFg: Color,
    val removedBg: Color,
    val removedFg: Color,
    val lineNumber: Color,
    val gutter: Color,
)

val LightCodeColors = CodeColors(
    addedBg = Color(0xFFDCFCE7),
    addedFg = Color(0xFF14532D),
    removedBg = Color(0xFFFEE2E2),
    removedFg = Color(0xFF7F1D1D),
    lineNumber = Color(0xFF94A3B8),
    gutter = Color(0xFFF1F5F9),
)

val DarkCodeColors = CodeColors(
    addedBg = Color(0xFF14532D),
    addedFg = Color(0xFFBBF7D0),
    removedBg = Color(0xFF7F1D1D),
    removedFg = Color(0xFFFECACA),
    lineNumber = Color(0xFF475569),
    gutter = Color(0xFF0F172A),
)

val LocalCodeColors = staticCompositionLocalOf { LightCodeColors }

/** Scales text for small screens / accessibility without breaking layout. */
val LocalFontScale = staticCompositionLocalOf { 1.0f }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LanguageMode { SYSTEM, EN, TA }

@Composable
fun VarunTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    val codeColors = if (dark) DarkCodeColors else LightCodeColors

    val scaled = Typography(
        headlineSmall = VarunTypography.headlineSmall.copy(fontSize = VarunTypography.headlineSmall.fontSize * fontScale),
        titleLarge = VarunTypography.titleLarge.copy(fontSize = VarunTypography.titleLarge.fontSize * fontScale),
        titleMedium = VarunTypography.titleMedium.copy(fontSize = VarunTypography.titleMedium.fontSize * fontScale),
        titleSmall = VarunTypography.titleSmall.copy(fontSize = VarunTypography.titleSmall.fontSize * fontScale),
        bodyLarge = VarunTypography.bodyLarge.copy(fontSize = VarunTypography.bodyLarge.fontSize * fontScale),
        bodyMedium = VarunTypography.bodyMedium.copy(fontSize = VarunTypography.bodyMedium.fontSize * fontScale),
        bodySmall = VarunTypography.bodySmall.copy(fontSize = VarunTypography.bodySmall.fontSize * fontScale),
        labelLarge = VarunTypography.labelLarge.copy(fontSize = VarunTypography.labelLarge.fontSize * fontScale),
        labelSmall = VarunTypography.labelSmall.copy(fontSize = VarunTypography.labelSmall.fontSize * fontScale),
    )

    CompositionLocalProvider(
        LocalCodeColors provides codeColors,
        LocalFontScale provides fontScale,
    ) {
        MaterialTheme(colorScheme = colors, typography = scaled, content = content)
    }
}

/**
 * Apply the user's language choice WITHOUT adding androidx.appcompat.
 *
 * On API 33+ the framework has a real per-app locale API, which also surfaces
 * in system Settings. Below 33 we return a wrapped Context from
 * [LocaleHelper.wrap] that MainActivity installs in attachBaseContext.
 */
fun applyLanguage(context: Context, mode: LanguageMode) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    runCatching {
        val lm = context.getSystemService(LocaleManager::class.java) ?: return@runCatching
        lm.applicationLocales = when (mode) {
            LanguageMode.SYSTEM -> LocaleList.getEmptyLocaleList()
            LanguageMode.EN -> LocaleList.forLanguageTags("en")
            LanguageMode.TA -> LocaleList.forLanguageTags("ta")
        }
    }
}

fun themeModeFromString(s: String): ThemeMode = when (s.lowercase()) {
    "light" -> ThemeMode.LIGHT
    "dark" -> ThemeMode.DARK
    else -> ThemeMode.SYSTEM
}

fun languageModeFromString(s: String): LanguageMode = when (s.lowercase()) {
    "en" -> LanguageMode.EN
    "ta" -> LanguageMode.TA
    else -> LanguageMode.SYSTEM
}
