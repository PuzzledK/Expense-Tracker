package com.expensetracker.app.quick

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.expensetracker.app.R
import com.expensetracker.app.ui.AppThemeId
import com.expensetracker.app.ui.DarkMode
import com.expensetracker.app.ui.previewColors

/**
 * The home-screen widget dressed in the app's current theme: same surface, text, accent and,
 * for Neo-brutalism, the ink border and hard shadow. Colours come from the same palettes the
 * app uses (ui/Theme.kt), so a new theme there shows up here too.
 */
internal object WidgetTheme {

    /** Colours for one light/dark variant. */
    private data class Look(
        val bg: Int, val text: Int, val dim: Int,
        val border: Int, val shadow: Int,
        val addBg: Int, val addFg: Int, val bulkBg: Int, val bulkFg: Int,
    )

    private fun look(ctx: Context, theme: AppThemeId, dark: Boolean): Look {
        val (c, s) = previewColors(theme, dark, ctx)
        return when (theme) {
            AppThemeId.BRUTAL -> Look(
                bg = c.surfaceContainerLowest.toArgb(), text = c.onSurface.toArgb(), dim = c.onSurfaceVariant.toArgb(),
                border = s.borderColor.toArgb(), shadow = s.shadowColor.toArgb(),
                addBg = s.accent(0).toArgb(), addFg = 0xFF111111.toInt(),
                bulkBg = s.accent(2).toArgb(), bulkFg = 0xFF111111.toInt(),
            )
            AppThemeId.PAPER -> Look(
                bg = c.surfaceContainerLowest.toArgb(), text = c.onSurface.toArgb(), dim = c.onSurfaceVariant.toArgb(),
                border = c.outlineVariant.toArgb(), shadow = 0,
                addBg = c.primary.toArgb(), addFg = c.onPrimary.toArgb(),
                bulkBg = c.primaryContainer.toArgb(), bulkFg = c.onPrimaryContainer.toArgb(),
            )
            AppThemeId.MIDNIGHT -> Look(
                bg = c.surfaceContainer.toArgb(), text = c.onSurface.toArgb(), dim = c.onSurfaceVariant.toArgb(),
                border = c.outlineVariant.toArgb(), shadow = 0,
                addBg = c.primary.toArgb(), addFg = c.onPrimary.toArgb(),
                bulkBg = c.secondaryContainer.toArgb(), bulkFg = c.onSecondaryContainer.toArgb(),
            )
            AppThemeId.CLASSIC, AppThemeId.DYNAMIC -> Look(
                bg = (if (dark) c.surfaceContainer else c.surfaceContainerLow).toArgb(),
                text = c.onSurface.toArgb(), dim = c.onSurfaceVariant.toArgb(),
                border = 0, shadow = 0,
                addBg = c.primary.toArgb(), addFg = c.onPrimary.toArgb(),
                bulkBg = c.secondaryContainer.toArgb(), bulkFg = c.onSecondaryContainer.toArgb(),
            )
        }
    }

    fun apply(ctx: Context, v: RemoteViews) {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val theme = AppThemeId.of(prefs.getString("themeId", null))
        val mode = prefs.getInt("darkMode", DarkMode.SYSTEM)
        val systemDark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // "Follow system": hand Android both variants (12+) so the widget flips with the phone.
        val followSystem = theme != AppThemeId.MIDNIGHT && mode == DarkMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val darkNow = when {
            theme == AppThemeId.MIDNIGHT -> true
            mode == DarkMode.LIGHT -> false
            mode == DarkMode.DARK -> true
            else -> systemDark
        }
        val light = if (followSystem) look(ctx, theme, false) else look(ctx, theme, darkNow)
        val dark = if (followSystem) look(ctx, theme, true) else light

        fun color(id: Int, method: String, pick: (Look) -> Int) {
            val l = pick(light); val d = pick(dark)
            if (l != d && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) v.setColorInt(id, method, l, d)
            else v.setInt(id, method, l)
        }

        val brutal = theme == AppThemeId.BRUTAL
        val (fill, stroke) = when (theme) {
            AppThemeId.BRUTAL -> R.drawable.widget_fill_r6 to R.drawable.widget_stroke_r6
            AppThemeId.PAPER -> R.drawable.widget_fill_r10 to R.drawable.widget_stroke_r10
            else -> R.drawable.widget_fill_r24 to R.drawable.widget_stroke_r24
        }
        val bordered = light.border != 0

        v.setImageViewResource(R.id.widget_bg, fill)
        color(R.id.widget_bg, "setColorFilter") { it.bg }
        v.setImageViewResource(R.id.widget_border, stroke)
        v.setViewVisibility(R.id.widget_border, if (bordered) View.VISIBLE else View.GONE)
        if (bordered) color(R.id.widget_border, "setColorFilter") { it.border }

        // Hard shadow: card shrinks 4dp right/bottom and the shadow peeks out (needs Android 12 for margins).
        val canShift = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        v.setViewVisibility(R.id.widget_shadow, if (brutal && canShift) View.VISIBLE else View.GONE)
        if (canShift) {
            val off = if (brutal) 4f else 0f
            v.setViewLayoutMargin(R.id.widget_card, RemoteViews.MARGIN_END, off, android.util.TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutMargin(R.id.widget_card, RemoteViews.MARGIN_BOTTOM, off, android.util.TypedValue.COMPLEX_UNIT_DIP)
            if (brutal) color(R.id.widget_shadow, "setColorFilter") { it.shadow }
        }

        color(R.id.widget_today, "setTextColor") { it.text }
        color(R.id.widget_today_label, "setTextColor") { it.dim }
        color(R.id.widget_month, "setTextColor") { it.dim }

        val btnFill = if (brutal) R.drawable.widget_btn_fill_square else R.drawable.widget_btn_fill_round
        val btnStroke = if (brutal) R.drawable.widget_btn_stroke_square else R.drawable.widget_btn_stroke_round
        for ((bg, border, text, pickBg, pickFg) in listOf(
            Btn(R.id.widget_add_bg, R.id.widget_add_border, R.id.widget_add_text, { l: Look -> l.addBg }, { l: Look -> l.addFg }),
            Btn(R.id.widget_bulk_bg, R.id.widget_bulk_border, R.id.widget_bulk_text, { l: Look -> l.bulkBg }, { l: Look -> l.bulkFg }),
        )) {
            v.setImageViewResource(bg, btnFill)
            color(bg, "setColorFilter", pickBg)
            color(text, "setTextColor", pickFg)
            v.setImageViewResource(border, btnStroke)
            v.setViewVisibility(border, if (brutal) View.VISIBLE else View.GONE)
            if (brutal) color(border, "setColorFilter") { it.border }
        }
    }

    private data class Btn(val bg: Int, val border: Int, val text: Int, val pickBg: (Look) -> Int, val pickFg: (Look) -> Int)
}
