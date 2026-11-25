package com.expensetracker.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.expensetracker.app.ui.AppStyle
import com.expensetracker.app.ui.AppThemeId
import com.expensetracker.app.ui.buildStyle
import com.expensetracker.app.ui.contrastRatio
import com.expensetracker.app.ui.heatShade
import com.expensetracker.app.ui.readableText
import com.expensetracker.app.ui.staticScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every theme × light/dark × pure black: the colour pairs the app actually draws text with must
 * be readable. WCAG AA: 4.5:1 for body text, 3:1 for secondary/large text and outlines.
 */
class ThemeContrastTest {

    private data class Variant(val theme: AppThemeId, val dark: Boolean, val pureBlack: Boolean) {
        val scheme: ColorScheme = staticScheme(theme, dark, pureBlack)
        val style: AppStyle = buildStyle(theme, dark || theme == AppThemeId.MIDNIGHT, scheme, pureBlack)
        val card: Color get() = when {
            style.isBrutal -> scheme.surfaceContainerLowest
            style.dark -> scheme.surfaceContainer
            else -> scheme.surfaceContainerLowest
        }
        override fun toString() = "${theme.name}${if (dark) " dark" else " light"}${if (pureBlack) " +black" else ""}"
    }

    private val variants = AppThemeId.entries.flatMap { t ->
        listOf(Variant(t, false, false), Variant(t, true, false), Variant(t, true, true))
    }.distinctBy { it.toString() }

    private val problems = mutableListOf<String>()

    private fun check(v: Variant, what: String, fg: Color, bg: Color, min: Float) {
        val solidBg = if (bg.alpha < 1f) bg.compositeOver(v.scheme.background) else bg
        val r = contrastRatio(fg.compositeOver(solidBg), solidBg)
        if (r < min) problems += "$v: $what %.2f < $min".format(r)
    }

    @Test fun textIsReadableEverywhere() {
        for (v in variants) {
            val c = v.scheme
            check(v, "text on background", c.onBackground, c.background, 4.5f)
            check(v, "text on surface", c.onSurface, c.surface, 4.5f)
            check(v, "text on card", c.onSurface, v.card, 4.5f)
            check(v, "secondary text on card", c.onSurfaceVariant, v.card, 3f)
            check(v, "secondary text on background", c.onSurfaceVariant, c.background, 3f)
            check(v, "button text", c.onPrimary, c.primary, 4.5f)
            check(v, "selected chip text", c.onPrimaryContainer, c.primaryContainer, 4.5f)
            check(v, "tonal chip text", c.onSecondaryContainer, c.secondaryContainer, 4.5f)
            check(v, "error text on card", c.error, v.card, 3f)
            check(v, "primary text on card", c.primary, v.card, 3f)
            check(v, "income text on card", v.style.income, v.card, 3f)
            check(v, "hero text", v.style.onHero, heroSample(v), 4.5f)
            // A card must stand out from the page by its fill or its border (pure black once lost both).
            val byFill = contrastRatio(v.card, c.background)
            val byBorder = if (v.style.borderWidth.value > 0f) contrastRatio(v.style.borderColor.compositeOver(c.background), c.background) else 1f
            // OLED screens crush near-blacks together, so true black needs a clearer edge.
            val need = if (v.pureBlack && v.style.dark) 1.25f else 1.05f
            if (maxOf(byFill, byBorder) < need) problems += "$v: cards blend into the page (fill %.2f, border %.2f)".format(byFill, byBorder)
            for (level in 0..4) {
                val shade = heatShade(level, v.style, c)
                check(v, "heatmap day number (level $level)", readableText(shade, v.card, c.onSurface), shade, 4.5f)
            }
            v.style.accents.forEachIndexed { i, a ->
                check(v, "text on accent $i", readableText(a, c.background, c.onSurface), a, 4.5f)
            }
        }
        assertTrue("Unreadable colour pairs:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** The hero card is a gradient; check against its first stop. */
    private fun heroSample(v: Variant): Color = when (v.theme) {
        AppThemeId.BRUTAL -> Color(0xFFFFD23F)
        AppThemeId.MIDNIGHT -> Color(0xFF5B4BDB)
        AppThemeId.PAPER -> v.scheme.primaryContainer
        else -> if (v.style.dark) v.scheme.primaryContainer else v.scheme.primary
    }
}
