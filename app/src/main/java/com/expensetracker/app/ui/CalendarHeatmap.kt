package com.expensetracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.HeatDay
import com.expensetracker.app.data.Heatmap
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.ui.kit.FilterChip
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Month calendar shaded by spending. "Everyday" (default) leaves out one-offs, fixed costs and
 * savings so rent day and a flight don't drown out normal days; "All" shows every rupee.
 * Tap a day to see its entries underneath.
 */
@Composable
fun CalendarHeatmap(
    month: YearMonth,
    all: List<Expense>,
    spendInfo: Map<Long, SpendInfo>,
    excludeSavings: Boolean,
    onEdit: (Expense) -> Unit,
) {
    val style = LocalAppStyle.current
    val today = LocalDate.now()
    var everydayOnly by rememberSaveable { mutableStateOf(true) }
    var selectedText by rememberSaveable(month) {
        mutableStateOf(if (month.contains(today)) today.toString() else null)
    }
    val selected = selectedText?.let { LocalDate.parse(it) }
    val firstDow = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }

    val inMonth = remember(all, month, excludeSavings) { all.filter { month.contains(it.date) && it.countsAsSpend(excludeSavings) } }
    val counted = remember(inMonth, spendInfo, everydayOnly) {
        if (everydayOnly) inMonth.filter { it.isEveryday(spendInfo) } else inMonth
    }
    val heat = remember(counted, month, firstDow) { Heatmap.build(month, counted, firstDow) }

    // Mode toggle
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = everydayOnly, onClick = { everydayOnly = true }, label = { Text("Everyday") })
        FilterChip(selected = !everydayOnly, onClick = { everydayOnly = false }, label = { Text("All spending") })
    }
    Spacer(Modifier.height(10.dp))

    // Weekday header
    Row(Modifier.fillMaxWidth()) {
        Heatmap.weekdayInitials(firstDow).forEach { d ->
            Text(
                d,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(6.dp))

    // Grid
    val cells: List<HeatDay?> = List(heat.leadingBlanks) { null } + heat.days
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            week.forEach { day ->
                Box(Modifier.weight(1f)) {
                    if (day != null) {
                        DayCell(
                            day = day,
                            isToday = day.date == today,
                            isFuture = day.date.isAfter(today),
                            isSelected = day.date == selected,
                            onClick = { selectedText = if (day.date == selected) null else day.date.toString() },
                        )
                    }
                }
            }
            repeat(7 - week.size) { Box(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(5.dp))
    }

    // Legend + summary
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${heat.activeDays} of ${if (month.contains(today)) today.dayOfMonth else month.lengthOfMonth()} days" +
                (heat.busiest?.let { " · busiest ${it.date.dayOfMonth} ${month.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        (0..4).forEach { lv ->
            Box(
                Modifier
                    .padding(horizontal = 1.5.dp)
                    .size(12.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(heatColor(lv))
                    .then(if (style.isBrutal) Modifier.border(1.dp, style.borderColor, MaterialTheme.shapes.extraSmall) else Modifier),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // Selected day
    if (selected != null && month.contains(selected)) {
        val dayEntries = remember(inMonth, selected) { inMonth.filter { it.date == selected } }
        val dayTotal = dayEntries.sumOf { it.amountPaise }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(formatRupees(dayTotal), style = style.money(MaterialTheme.typography.titleSmall))
        }
        if (dayEntries.isEmpty()) {
            Text(
                if (selected.isAfter(today)) "That's still ahead." else "No spending — a zero day. 🎉",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            dayEntries.forEach { e -> ExpenseRow(e, onClick = { onEdit(e) }, spend = spendInfo[e.id], background = Color.Transparent) }
        }
    }
}

@Composable
private fun DayCell(day: HeatDay, isToday: Boolean, isFuture: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val style = LocalAppStyle.current
    val shape = MaterialTheme.shapes.small
    val fill = if (isFuture) Color.Transparent else heatColor(day.level)
    // Shades run from the card colour to the accent, so the readable text colour depends on the
    // shade, the theme and light/dark: work it out from the actual fill.
    val ink = if (isFuture) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else textOn(fill)
    val outline = when {
        isSelected -> MaterialTheme.colorScheme.onSurface
        isToday -> MaterialTheme.colorScheme.primary
        style.isBrutal && !isFuture -> style.borderColor
        else -> Color.Transparent
    }
    val outlineWidth = when {
        isSelected -> 2.5.dp
        isToday -> 2.dp
        style.isBrutal -> 1.5.dp
        else -> 0.dp
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(fill)
            .border(outlineWidth, outline, shape)
            .clickable(enabled = !isFuture, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = MaterialTheme.typography.bodySmall.letterSpacing),
            fontWeight = if (isToday || isSelected) FontWeight.Black else FontWeight.Medium,
            color = ink,
        )
    }
}

@Composable
private fun heatColor(level: Int): Color = heatShade(level, LocalAppStyle.current, MaterialTheme.colorScheme)

/** Level 0..4 → colour. Built from each theme's own accent so it looks native everywhere. */
internal fun heatShade(level: Int, style: AppStyle, c: androidx.compose.material3.ColorScheme): Color {
    val hot = when {
        style.isBrutal -> style.accent(1)       // brutal pink
        style.theme == AppThemeId.MIDNIGHT -> c.primary
        else -> c.primary
    }
    val empty = if (style.isBrutal) c.surfaceContainerLowest else c.surfaceContainerHigh
    return when (level) {
        0 -> empty
        1 -> lerp(empty, hot, 0.22f)
        2 -> lerp(empty, hot, 0.45f)
        // Not 0.72: that lands mid-way between dark and light, where no text colour reads well
        // (ThemeContrastTest checks every theme).
        3 -> lerp(empty, hot, 0.86f)
        else -> hot
    }
}
