package fr.kinwaypoint.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kinwaypoint.app.R

// --- Palette -----------------------------------------------------------------
// Indigo « periwinkle » (confiance, calme) + violet pour les dégradés,
// corail chaleureux pour l'accent famille / SOS, menthe pour « tout va bien ».

private val Indigo = Color(0xFF5B5BD6)
private val IndigoLight = Color(0xFFA5A6F6)
private val Violet = Color(0xFF8B5CF6)
private val Coral = Color(0xFFFF7A59)
private val Rose = Color(0xFFE5484D)
private val Mint = Color(0xFF12B886)
private val Amber = Color(0xFFF59F00)

/** Couleurs sémantiques de l'appli, déclinées en clair et en sombre. */
@Immutable
data class AppColors(
    val muted: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val idle: Color,
    val me: Color,
    val softPrimary: Color,
    val softSuccess: Color,
    val softWarning: Color,
    val softDanger: Color,
    val onSoftWarning: Color,
    val card: Color,
    val keypad: Color,
    val heroGradient: List<Color>,
    val sosGradient: List<Color>,
    val alarmGradient: List<Color>,
)

private val LightAppColors = AppColors(
    muted = Color(0xFF6B6F86),
    success = Mint,
    warning = Amber,
    danger = Rose,
    idle = Color(0xFF9AA0B4),
    me = Indigo,
    softPrimary = Color(0xFFEEEEFD),
    softSuccess = Color(0xFFE3F8F1),
    softWarning = Color(0xFFFFF4DC),
    softDanger = Color(0xFFFDECEC),
    onSoftWarning = Color(0xFF7A5300),
    card = Color.White,
    keypad = Color(0xFFEFEEF8),
    heroGradient = listOf(Indigo, Violet),
    sosGradient = listOf(Coral, Rose),
    alarmGradient = listOf(Rose, Color(0xFFFF6B6B)),
)

private val DarkAppColors = AppColors(
    muted = Color(0xFFA3A7BE),
    success = Color(0xFF38D9A9),
    warning = Color(0xFFFFC34D),
    danger = Color(0xFFFF6369),
    idle = Color(0xFF6E7389),
    me = IndigoLight,
    softPrimary = Color(0xFF23244A),
    softSuccess = Color(0xFF113A31),
    softWarning = Color(0xFF3D3015),
    softDanger = Color(0xFF441E22),
    onSoftWarning = Color(0xFFFFD98A),
    card = Color(0xFF1A1C30),
    keypad = Color(0xFF23253D),
    heroGradient = listOf(Color(0xFF4A4AC4), Color(0xFF7C4DE0)),
    sosGradient = listOf(Coral, Rose),
    alarmGradient = listOf(Color(0xFFC9363B), Rose),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/** Accès court : `AppTheme.colors.success`. */
object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Noms historiques, conservés pour les écrans existants : ils suivent désormais
// le thème (clair / sombre) au lieu d'être des couleurs figées.
val Green: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted
val Danger: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.danger
val Warning: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning
val Ok: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.success
val Idle: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.idle
val SoftGreen: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.softPrimary
val SoftOrange: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.softWarning
val SoftRed: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.softDanger
val CardSurface: Color @Composable @ReadOnlyComposable get() = LocalAppColors.current.card

// --- Typographie : Plus Jakarta Sans (police variable, licence OFL) -------------

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun jakarta(weight: FontWeight) = Font(
    R.font.plus_jakarta_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Jakarta = FontFamily(
    jakarta(FontWeight.Normal),
    jakarta(FontWeight.Medium),
    jakarta(FontWeight.SemiBold),
    jakarta(FontWeight.Bold),
    jakarta(FontWeight.ExtraBold),
)

private val AppTypography = Typography().let { base ->
    fun TextStyle.j(weight: FontWeight? = null) = copy(fontFamily = Jakarta, fontWeight = weight ?: fontWeight)
    Typography(
        displayLarge = base.displayLarge.j(FontWeight.ExtraBold),
        displayMedium = base.displayMedium.j(FontWeight.ExtraBold),
        displaySmall = base.displaySmall.j(FontWeight.Bold),
        headlineLarge = base.headlineLarge.j(FontWeight.ExtraBold).copy(letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.j(FontWeight.Bold).copy(letterSpacing = (-0.3).sp),
        headlineSmall = base.headlineSmall.j(FontWeight.Bold),
        titleLarge = base.titleLarge.j(FontWeight.Bold),
        titleMedium = base.titleMedium.j(FontWeight.SemiBold),
        titleSmall = base.titleSmall.j(FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.j(),
        bodyMedium = base.bodyMedium.j(),
        bodySmall = base.bodySmall.j(),
        labelLarge = base.labelLarge.j(FontWeight.SemiBold),
        labelMedium = base.labelMedium.j(FontWeight.SemiBold),
        labelSmall = base.labelSmall.j(FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4FF),
    onPrimaryContainer = Color(0xFF1E1B6B),
    secondary = Coral,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6DE),
    onSecondaryContainer = Color(0xFF5C1D0C),
    tertiary = Mint,
    onTertiary = Color.White,
    background = Color(0xFFF6F5FB),
    onBackground = Color(0xFF16172B),
    surface = Color(0xFFF6F5FB),
    onSurface = Color(0xFF16172B),
    surfaceVariant = Color(0xFFE9E8F4),
    onSurfaceVariant = Color(0xFF5B5F75),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAFF),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F0FA),
    surfaceContainerHighest = Color(0xFFEAE9F5),
    outline = Color(0xFFC9C8DA),
    outlineVariant = Color(0xFFE2E1EE),
    error = Rose,
)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF15145A),
    primaryContainer = Color(0xFF34348F),
    onPrimaryContainer = Color(0xFFE4E4FF),
    secondary = Color(0xFFFF9C80),
    onSecondary = Color(0xFF4A1405),
    secondaryContainer = Color(0xFF6A2A17),
    onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFF38D9A9),
    background = Color(0xFF0F1020),
    onBackground = Color(0xFFE7E7F4),
    surface = Color(0xFF0F1020),
    onSurface = Color(0xFFE7E7F4),
    surfaceVariant = Color(0xFF26283F),
    onSurfaceVariant = Color(0xFFB4B7CC),
    surfaceContainerLowest = Color(0xFF0B0C19),
    surfaceContainerLow = Color(0xFF15162A),
    surfaceContainer = Color(0xFF1A1C30),
    surfaceContainerHigh = Color(0xFF212338),
    surfaceContainerHighest = Color(0xFF2A2C43),
    outline = Color(0xFF4A4D66),
    outlineVariant = Color(0xFF30324A),
    error = Color(0xFFFF6369),
)

@Composable
fun KinWaypointTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides if (darkTheme) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
