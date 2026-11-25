package com.expensetracker.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's looks. Stored by [id] in settings ("themeId"). */
enum class AppThemeId(val id: String, val label: String, val blurb: String) {
    CLASSIC("classic", "Evergreen", "Calm greens, soft cards"),
    DYNAMIC("dynamic", "Material You", "Follows your wallpaper"),
    BRUTAL("brutal", "Neo-brutalism", "Bold ink, loud colour"),
    MIDNIGHT("midnight", "Midnight", "Deep navy with neon glow"),
    PAPER("paper", "Paper", "Warm, quiet, editorial");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** 0 = follow system, 1 = light, 2 = dark. */
object DarkMode { const val SYSTEM = 0; const val LIGHT = 1; const val DARK = 2 }

/**
 * Design tokens beyond Material's colour scheme: how surfaces are drawn (borders, hard shadows,
 * corners), fonts, and the hero card. Read with [LocalAppStyle] / `AppStyle.current`.
 */
@Immutable
data class AppStyle(
    val theme: AppThemeId,
    val dark: Boolean,
    val corner: Dp,
    /** Outline around cards/buttons; 0 = none. */
    val borderWidth: Dp,
    val borderColor: Color,
    /** Neo-brutalist offset shadow (solid, no blur); 0 = none. */
    val hardShadow: Dp,
    val shadowColor: Color,
    /** Soft Material elevation for cards when there's no hard shadow. */
    val elevation: Dp,
    val headingFont: FontFamily,
    val headingWeight: FontWeight,
    /** Used for money amounts (with tabular figures). */
    val numberFont: FontFamily,
    val numberWeight: FontWeight,
    /** Section titles in CAPS with letter spacing. */
    val capsLabels: Boolean,
    /** Background of the big "Spent this month" card. */
    val hero: Brush,
    val onHero: Color,
    val income: Color,
    /** Accent colours used for decoration (badges, stat tiles), cycled by index. */
    val accents: List<Color>,
    /** AMOLED mode is active (dark + pure black): surfaces sit on true black. */
    val pureBlack: Boolean = false,
) {
    val isBrutal get() = theme == AppThemeId.BRUTAL
    fun accent(i: Int) = accents[Math.floorMod(i, accents.size)]

    /** Style for money: tabular digits so columns line up. */
    fun money(base: TextStyle) = base.copy(fontFamily = numberFont, fontWeight = numberWeight, fontFeatureSettings = "tnum")
}

val LocalAppStyle = staticCompositionLocalOf { buildStyle(AppThemeId.CLASSIC, dark = false, scheme = lightColorScheme()) }

/** Shorthand: `AppStyle.current` inside composables. */
val AppStyleCurrent: AppStyle @Composable get() = LocalAppStyle.current

// ---------------------------------------------------------------- palettes

private val EvergreenLight = lightColorScheme(
    primary = Color(0xFF1F6B4A), onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1CF), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), secondaryContainer = Color(0xFFD2E9DB), onSecondaryContainer = Color(0xFF0A1F15),
    tertiary = Color(0xFF2F6479), tertiaryContainer = Color(0xFFC3E8FA),
    background = Color(0xFFF5F8F5), surface = Color(0xFFF5F8F5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEFF4F0),
    surfaceContainer = Color(0xFFE9EFEA), surfaceContainerHigh = Color(0xFFE3EAE5), surfaceContainerHighest = Color(0xFFDDE5DF),
)
private val EvergreenDark = darkColorScheme(
    primary = Color(0xFF8FD9B2), onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0F5236), onPrimaryContainer = Color(0xFFB4F1CF),
    secondary = Color(0xFFB4CCBE), secondaryContainer = Color(0xFF354B40),
    tertiary = Color(0xFFA6CDE2), tertiaryContainer = Color(0xFF214C5E),
    background = Color(0xFF0E1512), surface = Color(0xFF0E1512),
    surfaceContainerLow = Color(0xFF161E1A), surfaceContainer = Color(0xFF1A231F),
    surfaceContainerHigh = Color(0xFF242E29), surfaceContainerHighest = Color(0xFF2F3934),
)

// Neo-brutalism: cream paper, pure ink, loud flat accents.
private val Ink = Color(0xFF111111)
private val BrutalYellow = Color(0xFFFFD23F)
private val BrutalPink = Color(0xFFFF6FB5)
private val BrutalBlue = Color(0xFF4CC9F0)
private val BrutalGreen = Color(0xFF7ED957)
private val BrutalViolet = Color(0xFFA685FF)
private val BrutalOrange = Color(0xFFFF8A3D)

private val BrutalLight = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    primaryContainer = BrutalYellow, onPrimaryContainer = Ink,
    secondary = Ink, onSecondary = Color.White,
    secondaryContainer = BrutalBlue, onSecondaryContainer = Ink,
    tertiary = Color(0xFFD6247A), onTertiary = Color.White,
    tertiaryContainer = BrutalPink, onTertiaryContainer = Ink,
    background = Color(0xFFFFF4DE), onBackground = Ink,
    surface = Color(0xFFFFF4DE), onSurface = Ink,
    surfaceVariant = Color(0xFFFFE8B8), onSurfaceVariant = Color(0xFF3A3A3A),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFFFFBF2), surfaceContainerHighest = Color(0xFFFFEFCC),
    outline = Ink, outlineVariant = Ink,
    error = Color(0xFFE5243B), errorContainer = Color(0xFFFFB3BC), onErrorContainer = Ink,
)
private val BrutalDark = darkColorScheme(
    primary = BrutalYellow, onPrimary = Ink,
    primaryContainer = BrutalYellow, onPrimaryContainer = Ink,
    secondary = BrutalBlue, onSecondary = Ink,
    secondaryContainer = BrutalBlue, onSecondaryContainer = Ink,
    tertiary = BrutalPink, onTertiary = Ink,
    tertiaryContainer = BrutalPink, onTertiaryContainer = Ink,
    background = Color(0xFF151515), onBackground = Color(0xFFF7F3EA),
    surface = Color(0xFF151515), onSurface = Color(0xFFF7F3EA),
    surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFCFCAC0),
    surfaceContainerLowest = Color(0xFF1E1E1E), surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF232323), surfaceContainerHigh = Color(0xFF2A2A2A), surfaceContainerHighest = Color(0xFF333333),
    outline = Color(0xFFF7F3EA), outlineVariant = Color(0xFFF7F3EA),
    error = Color(0xFFFF5A6E), errorContainer = Color(0xFFFF5A6E), onErrorContainer = Ink,
)

private val MidnightScheme = darkColorScheme(
    primary = Color(0xFFA29BFE), onPrimary = Color(0xFF1B1446),
    primaryContainer = Color(0xFF2E2A6B), onPrimaryContainer = Color(0xFFE4E1FF),
    secondary = Color(0xFF5EEAD4), onSecondary = Color(0xFF00332C),
    secondaryContainer = Color(0xFF113F3A), onSecondaryContainer = Color(0xFFB8FFF1),
    tertiary = Color(0xFFFF9EC7), onTertiary = Color(0xFF4A0B2B),
    tertiaryContainer = Color(0xFF5A1C3D), onTertiaryContainer = Color(0xFFFFD9E8),
    background = Color(0xFF080B18), onBackground = Color(0xFFE6E8F5),
    surface = Color(0xFF080B18), onSurface = Color(0xFFE6E8F5),
    surfaceVariant = Color(0xFF1C2238), onSurfaceVariant = Color(0xFFA7ADC9),
    surfaceContainerLowest = Color(0xFF0B0F20), surfaceContainerLow = Color(0xFF10152A),
    surfaceContainer = Color(0xFF141A32), surfaceContainerHigh = Color(0xFF1A213D), surfaceContainerHighest = Color(0xFF212A4A),
    outline = Color(0xFF3B4466), outlineVariant = Color(0xFF262E4D),
)

private val PaperLight = lightColorScheme(
    primary = Color(0xFFB4492F), onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DCCF), onPrimaryContainer = Color(0xFF3B0F04),
    secondary = Color(0xFF5B6B5D), secondaryContainer = Color(0xFFE4E8DC), onSecondaryContainer = Color(0xFF1B241D),
    tertiary = Color(0xFF3F5A7A), tertiaryContainer = Color(0xFFDCE5F2),
    background = Color(0xFFFAF6EF), onBackground = Color(0xFF26221D),
    surface = Color(0xFFFAF6EF), onSurface = Color(0xFF26221D),
    surfaceVariant = Color(0xFFEFE8DC), onSurfaceVariant = Color(0xFF6A6257),
    surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerLow = Color(0xFFFFFCF6),
    surfaceContainer = Color(0xFFF7F1E7), surfaceContainerHigh = Color(0xFFF2EBDF), surfaceContainerHighest = Color(0xFFECE4D6),
    outline = Color(0xFFCFC4B2), outlineVariant = Color(0xFFE2D9C9),
)
private val PaperDark = darkColorScheme(
    primary = Color(0xFFF0A58C), onPrimary = Color(0xFF4A1405),
    primaryContainer = Color(0xFF5C2717), onPrimaryContainer = Color(0xFFF6DCCF),
    secondary = Color(0xFFBFCABF), secondaryContainer = Color(0xFF3A443B),
    tertiary = Color(0xFFAFC6E4), tertiaryContainer = Color(0xFF2A3E58),
    background = Color(0xFF1A1714), onBackground = Color(0xFFEDE6DA),
    surface = Color(0xFF1A1714), onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF2B2621), onSurfaceVariant = Color(0xFFBDB3A4),
    surfaceContainerLow = Color(0xFF201C18), surfaceContainer = Color(0xFF25211C),
    surfaceContainerHigh = Color(0xFF2D2822), surfaceContainerHighest = Color(0xFF363029),
    outline = Color(0xFF4F473E), outlineVariant = Color(0xFF3A342D),
)

// ---------------------------------------------------------------- building

/**
 * [buildStyle] for the pure-black (AMOLED) variant. Themes that separate cards from the background
 * only by a lighter fill (Evergreen, Material You) lose them on true black, so they get a hairline
 * outline in the theme's own outline colour; themes that already draw borders keep theirs.
 */
fun buildStyle(theme: AppThemeId, dark: Boolean, scheme: ColorScheme, pureBlack: Boolean): AppStyle {
    val s = buildStyle(theme, dark, scheme)
    if (!(dark && pureBlack)) return s
    return if (s.borderWidth == 0.dp) s.copy(borderWidth = 1.dp, borderColor = scheme.outlineVariant, pureBlack = true)
    else s.copy(pureBlack = true)
}

fun buildStyle(theme: AppThemeId, dark: Boolean, scheme: ColorScheme): AppStyle = when (theme) {
    AppThemeId.BRUTAL -> AppStyle(
        theme, dark,
        corner = 6.dp, borderWidth = 2.5.dp, borderColor = scheme.outline,
        hardShadow = 4.dp, shadowColor = scheme.outline, elevation = 0.dp,
        headingFont = FontFamily.SansSerif, headingWeight = FontWeight.Black,
        numberFont = FontFamily.Monospace, numberWeight = FontWeight.Bold,
        capsLabels = true,
        hero = Brush.linearGradient(listOf(BrutalYellow, BrutalYellow)), onHero = Ink,
        income = if (dark) BrutalGreen else Color(0xFF0B8A3E),
        accents = listOf(BrutalYellow, BrutalPink, BrutalBlue, BrutalGreen, BrutalViolet, BrutalOrange),
    )
    AppThemeId.MIDNIGHT -> AppStyle(
        theme, true,
        corner = 24.dp, borderWidth = 1.dp, borderColor = Color(0xFF262E4D),
        hardShadow = 0.dp, shadowColor = Color.Transparent, elevation = 0.dp,
        headingFont = FontFamily.SansSerif, headingWeight = FontWeight.SemiBold,
        numberFont = FontFamily.SansSerif, numberWeight = FontWeight.Bold,
        capsLabels = true,
        hero = Brush.linearGradient(listOf(Color(0xFF5B4BDB), Color(0xFF7C3AED), Color(0xFF0EA5A0))),
        onHero = Color.White,
        income = Color(0xFF5EEAD4),
        accents = listOf(Color(0xFFA29BFE), Color(0xFF5EEAD4), Color(0xFFFF9EC7), Color(0xFFFFD580), Color(0xFF7DD3FC)),
    )
    AppThemeId.PAPER -> AppStyle(
        theme, dark,
        corner = 10.dp, borderWidth = 1.dp, borderColor = scheme.outlineVariant,
        hardShadow = 0.dp, shadowColor = Color.Transparent, elevation = 0.dp,
        headingFont = FontFamily.Serif, headingWeight = FontWeight.Bold,
        numberFont = FontFamily.Serif, numberWeight = FontWeight.Bold,
        capsLabels = false,
        hero = Brush.linearGradient(listOf(scheme.primaryContainer, scheme.primaryContainer)),
        onHero = scheme.onPrimaryContainer,
        income = if (dark) Color(0xFFA9D8A0) else Color(0xFF3E7B3A),
        accents = listOf(scheme.primary, scheme.tertiary, scheme.secondary, Color(0xFFC9A227)),
    )
    AppThemeId.CLASSIC, AppThemeId.DYNAMIC -> AppStyle(
        theme, dark,
        corner = 22.dp, borderWidth = 0.dp, borderColor = Color.Transparent,
        hardShadow = 0.dp, shadowColor = Color.Transparent, elevation = 0.dp,
        headingFont = FontFamily.SansSerif, headingWeight = FontWeight.SemiBold,
        numberFont = FontFamily.SansSerif, numberWeight = FontWeight.Bold,
        capsLabels = false,
        hero = Brush.linearGradient(
            if (dark) listOf(scheme.primaryContainer, scheme.tertiaryContainer)
            else listOf(scheme.primary, scheme.tertiary),
        ),
        onHero = if (dark) scheme.onPrimaryContainer else scheme.onPrimary,
        income = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        accents = listOf(scheme.primary, scheme.tertiary, scheme.secondary),
    )
}

/**
 * AMOLED variant of a dark scheme: true-black background (pixels off), with each surface level
 * pulled towards black but keeping a trace of the theme's tint so cards still read as cards.
 */
internal fun ColorScheme.pureBlack(): ColorScheme {
    fun toward(c: Color, keep: Float) = lerp(Color.Black, c, keep)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = toward(surfaceContainerLow, 0.45f),
        surfaceContainer = toward(surfaceContainer, 0.55f),
        surfaceContainerHigh = toward(surfaceContainerHigh, 0.6f),
        surfaceContainerHighest = toward(surfaceContainerHighest, 0.65f),
        surfaceVariant = toward(surfaceVariant, 0.6f),
    )
}

private fun isPureBlack(ctx: Context) =
    ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("pureBlack", false)

private fun schemeFor(theme: AppThemeId, dark: Boolean, ctx: Context, pureBlack: Boolean = isPureBlack(ctx)): ColorScheme =
    baseSchemeFor(theme, dark, ctx).let { if (dark && pureBlack) it.pureBlack() else it }

/** The built-in palettes without a Context (Material You falls back to Evergreen), for tests. */
internal fun staticScheme(theme: AppThemeId, dark: Boolean, pureBlack: Boolean): ColorScheme {
    val d = dark || theme == AppThemeId.MIDNIGHT
    val base = when (theme) {
        AppThemeId.BRUTAL -> if (d) BrutalDark else BrutalLight
        AppThemeId.MIDNIGHT -> MidnightScheme
        AppThemeId.PAPER -> if (d) PaperDark else PaperLight
        AppThemeId.CLASSIC, AppThemeId.DYNAMIC -> if (d) EvergreenDark else EvergreenLight
    }
    return if (d && pureBlack) base.pureBlack() else base
}

private fun baseSchemeFor(theme: AppThemeId, dark: Boolean, ctx: Context): ColorScheme = when (theme) {
    AppThemeId.DYNAMIC -> when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> EvergreenDark
        else -> EvergreenLight
    }
    AppThemeId.BRUTAL -> if (dark) BrutalDark else BrutalLight
    AppThemeId.MIDNIGHT -> MidnightScheme
    AppThemeId.PAPER -> if (dark) PaperDark else PaperLight
    AppThemeId.CLASSIC -> if (dark) EvergreenDark else EvergreenLight
}

private fun typographyFor(s: AppStyle): Typography {
    val base = Typography()
    fun TextStyle.heading() = copy(fontFamily = s.headingFont, fontWeight = s.headingWeight)
    fun TextStyle.caps() = if (s.capsLabels) copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold) else this
    return base.copy(
        displayLarge = base.displayLarge.heading(),
        displayMedium = base.displayMedium.heading(),
        displaySmall = base.displaySmall.heading(),
        headlineLarge = base.headlineLarge.heading(),
        headlineMedium = base.headlineMedium.heading(),
        headlineSmall = base.headlineSmall.heading(),
        titleLarge = base.titleLarge.heading(),
        titleMedium = base.titleMedium.copy(fontFamily = s.headingFont, fontWeight = if (s.isBrutal) FontWeight.ExtraBold else FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = s.headingFont, fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontWeight = if (s.isBrutal) FontWeight.ExtraBold else FontWeight.SemiBold),
        labelMedium = base.labelMedium.caps(),
        labelSmall = base.labelSmall.caps(),
    )
}

private fun shapesFor(s: AppStyle): Shapes {
    val c = s.corner
    return Shapes(
        extraSmall = RoundedCornerShape(minOf(c, 4.dp)),
        small = RoundedCornerShape(if (s.isBrutal) c else minOf(c, 10.dp)),
        medium = RoundedCornerShape(if (s.isBrutal) c else minOf(c, 14.dp)),
        large = RoundedCornerShape(c),
        extraLarge = RoundedCornerShape(if (s.isBrutal) c + 2.dp else c + 6.dp),
    )
}

/** Resolves light/dark from the setting (Midnight is always dark). */
@Composable
fun isDarkFor(theme: AppThemeId, mode: Int): Boolean = when {
    theme == AppThemeId.MIDNIGHT -> true
    mode == DarkMode.LIGHT -> false
    mode == DarkMode.DARK -> true
    else -> isSystemInDarkTheme()
}

// ---------------------------------------------------------------- readable text

private val InkText = Color(0xFF111111)

/** WCAG contrast ratio between two opaque colours (1..21). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance(); val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * Text colour for [bg] (see [textOn]): [preferred] if it reads well (WCAG AA, 4.5:1) on the colour
 * actually shown — [bg] composited over [behind] when it's translucent — else ink or white,
 * whichever contrasts more.
 */
fun readableText(bg: Color, behind: Color, preferred: Color): Color {
    val solid = if (bg.alpha < 1f) bg.compositeOver(behind) else bg
    if (contrastRatio(preferred.copy(alpha = 1f).compositeOver(solid), solid) >= 4.5f) return preferred
    return if (contrastRatio(InkText, solid) >= contrastRatio(Color.White, solid)) InkText else Color.White
}

/**
 * Readable text/icon colour on any fill: the theme's text colour when it works, otherwise ink or
 * white. Use it wherever text sits on an accent, a heat shade or a translucent tint, instead of a
 * fixed colour that only works in one theme or mode.
 */
@Composable
fun textOn(bg: Color, behind: Color = MaterialTheme.colorScheme.background, preferred: Color = MaterialTheme.colorScheme.onSurface): Color =
    readableText(bg, behind, preferred)

/** Income green that fits the active theme. */
@Composable
fun incomeColor(): Color = LocalAppStyle.current.income

/**
 * App theme. With no arguments it reads the saved choice (for the quick-log popup and lock
 * screen); MainActivity passes live values so switching themes is instant.
 */
@Composable
fun ExpenseTheme(themeId: String? = null, darkMode: Int? = null, pureBlack: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = AppThemeId.of(themeId ?: prefs.getString("themeId", null))
    val dark = isDarkFor(theme, darkMode ?: prefs.getInt("darkMode", DarkMode.SYSTEM))
    val black = pureBlack ?: prefs.getBoolean("pureBlack", false)
    val scheme = schemeFor(theme, dark, ctx, black)
    val style = buildStyle(theme, dark, scheme, black)
    CompositionLocalProvider(LocalAppStyle provides style) {
        MaterialTheme(colorScheme = scheme, typography = typographyFor(style), shapes = shapesFor(style), content = content)
    }
}

/**
 * Accent for notifications, from the saved theme. Android tints the small icon, app name and
 * action buttons with it (and adjusts contrast for the shade itself).
 */
fun notificationAccent(ctx: Context): Int {
    val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val theme = AppThemeId.of(prefs.getString("themeId", null))
    val dark = when (prefs.getInt("darkMode", DarkMode.SYSTEM)) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val (scheme, style) = previewColors(theme, dark, ctx)
    // Neo-brutalism's primary is plain ink; its signature colour is the yellow accent.
    val c = if (theme == AppThemeId.BRUTAL) style.accent(0) else scheme.primary
    return c.toArgb()
}

/** Preview colours for the theme picker (no composition needed). */
fun previewColors(theme: AppThemeId, dark: Boolean, ctx: Context): Pair<ColorScheme, AppStyle> {
    val d = if (theme == AppThemeId.MIDNIGHT) true else dark
    val black = isPureBlack(ctx)
    val scheme = schemeFor(theme, d, ctx, black)
    return scheme to buildStyle(theme, d, scheme, black)
}
