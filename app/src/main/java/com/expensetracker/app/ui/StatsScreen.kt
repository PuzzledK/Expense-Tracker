@file:OptIn(ExperimentalMaterial3Api::class)

package com.expensetracker.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.expensetracker.app.ui.kit.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import com.expensetracker.app.ui.kit.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.expensetracker.app.ui.kit.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensetracker.app.ExpenseViewModel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    vm: ExpenseViewModel,
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    bottomBar: @Composable () -> Unit,
    onEdit: (Expense) -> Unit,
    onOpen: (Overlay) -> Unit = {},
) {
    val all by vm.expenses.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spendInfo by vm.spendInfo.collectAsStateWithLifecycle()
    val excl = settings.excludeSavingsFromSpend
    val today = LocalDate.now()

    val spendInMonth = remember(all, month, excl) { all.filter { month.contains(it.date) && it.countsAsSpend(excl) } }
    val total = spendInMonth.sumOf { it.amountPaise }
    val prevTotal = remember(all, month, excl) {
        val prev = month.minusMonths(1)
        all.filter { prev.contains(it.date) && it.countsAsSpend(excl) }.sumOf { it.amountPaise }
    }
    val daysElapsed = if (month == YearMonth.from(today)) today.dayOfMonth else month.lengthOfMonth()
    val savings = if (excl) all.filter { month.contains(it.date) && !it.isIncome && it.category == Category.SAVINGS }.sumOf { it.amountPaise } else 0L
    val income = all.filter { month.contains(it.date) && it.isIncome }.sumOf { it.amountPaise }

    val byCategory = remember(spendInMonth) {
        spendInMonth.groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amountPaise } }
            .toList()
            .sortedByDescending { it.second }
    }
    val split = remember(spendInMonth, spendInfo) { splitSpend(spendInMonth, spendInfo) }
    val everydayPerDay = if (daysElapsed > 0) split.everyday / daysElapsed else 0L
    // Day-by-day chart and "typical day" use everyday spending only, so a flight does not flatten the rest.
    val daily = remember(spendInMonth, month, spendInfo) {
        LongArray(month.lengthOfMonth()).also { arr ->
            spendInMonth.filter { it.isEveryday(spendInfo) }.forEach { arr[it.date.dayOfMonth - 1] += it.amountPaise }
        }
    }
    val typicalDay = remember(daily) {
        val days = daily.filter { it > 0 }.sorted()
        when {
            days.isEmpty() -> 0L
            days.size % 2 == 1 -> days[days.size / 2]
            else -> (days[days.size / 2 - 1] + days[days.size / 2]) / 2
        }
    }
    val unusual = remember(spendInMonth, spendInfo) {
        spendInMonth.filter { e ->
            val t = spendInfo[e.id]?.type
            t == SpendType.ONE_OFF || t == SpendType.FIXED
        }.sortedByDescending { it.amountPaise }
    }
    val lastMonths = remember(all, excl) {
        val end = YearMonth.from(today)
        (11 downTo 0).map { i ->
            val ym = end.minusMonths(i.toLong())
            ym to all.filter { ym.contains(it.date) && it.countsAsSpend(excl) }.sumOf { it.amountPaise }
        }
    }
    val top5 = remember(spendInMonth) { spendInMonth.sortedByDescending { it.amountPaise }.take(5) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Stats", fontWeight = FontWeight.SemiBold) }) },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            MonthSelector(month, onMonthChange, Modifier.padding(horizontal = 4.dp))
            com.expensetracker.app.goals.GoalsCard(vm, onOpen = { onOpen(Overlay.GOALS) })

            // Headline numbers
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Spent", formatRupees(total), Modifier.weight(1f))
                StatTile("Everyday/day", formatRupees(everydayPerDay), Modifier.weight(1f))
                val change = if (prevTotal > 0) ((total - prevTotal) * 100.0 / prevTotal).roundToInt() else null
                StatTile(
                    "vs ${formatMonthShort(month.minusMonths(1))}",
                    when {
                        change == null -> "—"
                        change > 0 -> "▲ $change%"
                        change < 0 -> "▼ ${abs(change)}%"
                        else -> "0%"
                    },
                    Modifier.weight(1f),
                    valueColor = when {
                        change == null || change == 0 -> Color.Unspecified
                        change > 0 -> MaterialTheme.colorScheme.error
                        else -> incomeColor()
                    },
                )
            }
            if (income > 0 || savings > 0) {
                Text(
                    buildString {
                        if (income > 0) append("Received +${formatRupees(income)}")
                        if (income > 0 && savings > 0) append("   ·   ")
                        if (savings > 0) append("Saved ${formatRupees(savings)} (not counted as spend)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SectionCard("Everyday vs one-offs") {
                if (total == 0L) {
                    EmptyHint()
                } else {
                    SpendSplitView(split, if (excl) savings else 0L, typicalDay, everydayPerDay)
                    if (unusual.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Not counted in everyday averages \u00b7 tap to change",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        unusual.forEach { e -> UnusualRow(e, spendInfo[e.id], onClick = { onEdit(e) }) }
                    }
                }
            }

            SectionCard("Where it went") {
                if (byCategory.isEmpty()) {
                    EmptyHint()
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Donut(byCategory, total, Modifier.size(140.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            byCategory.take(4).forEach { (c, v) ->
                                LegendLine(c, v, total)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    byCategory.forEach { (c, v) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row {
                                Text("${c.emoji} ${c.label}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(formatRupees(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                            LinearProgressIndicator(
                                progress = { if (total > 0) v.toFloat() / total else 0f },
                                color = Color(c.color),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            }

            SectionCard("Places & travel") {
                PlacesSection(all = all, spendInMonth = spendInMonth, month = month, settings = settings, onEdit = onEdit)
            }

            SectionCard("Day by day") {
                if (split.everyday == 0L) EmptyHint() else DailyBars(daily, month, everydayPerDay)
            }

            SectionCard("Last 12 months") {
                MonthBars(lastMonths, selected = month, onSelect = onMonthChange)
            }

            SectionCard("Biggest expenses") {
                if (top5.isEmpty()) EmptyHint()
                top5.forEach { e -> ExpenseRow(e, onClick = { onEdit(e) }, showDate = true, spend = spendInfo[e.id]) }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    val style = LocalAppStyle.current
    Card(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                if (style.capsLabels) label.uppercase() else label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(value, style = style.money(MaterialTheme.typography.titleLarge), color = valueColor, maxLines = 1)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val style = LocalAppStyle.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (style.isBrutal) {
                    Box(Modifier.size(12.dp).background(style.accent(title.length), MaterialTheme.shapes.extraSmall))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    if (style.isBrutal) title.uppercase() else title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun EmptyHint() {
    Text("Nothing to show for this month.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LegendLine(c: Category, v: Long, total: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).background(Color(c.color), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(c.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            if (total > 0) "${(v * 100.0 / total).roundToInt()}%" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Donut(data: List<Pair<Category, Long>>, total: Long, modifier: Modifier) {
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.18f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(surface, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            var start = -90f
            val gap = if (data.size > 1) 1.5f else 0f
            data.forEach { (c, v) ->
                val sweep = if (total > 0) v * 360f / total else 0f
                if (sweep > gap) {
                    drawArc(Color(c.color), start + gap / 2, sweep - gap, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                }
                start += sweep
            }
        }
        Text(formatRupeesShort(total), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DailyBars(daily: LongArray, month: YearMonth, avg: Long) {
    var selected by remember(month) { mutableStateOf<Int?>(null) }
    val max = (daily.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val avgColor = MaterialTheme.colorScheme.tertiary

    val sel = selected
    Text(
        if (sel != null) "${sel + 1} ${formatMonthShort(month)}: ${formatRupees(daily[sel])}"
        else "Tap a bar · peak ${formatRupees(daily.maxOrNull() ?: 0)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(daily) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width / daily.size.toFloat())).toInt().coerceIn(0, daily.lastIndex)
                    selected = if (selected == i) null else i
                }
            },
    ) {
        val slot = size.width / daily.size
        val w = slot * 0.7f
        daily.forEachIndexed { i, v ->
            val h = size.height * v / max
            drawRoundRect(
                color = if (sel == null || sel == i) barColor else dim,
                topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3, w / 3),
            )
        }
        val avgY = size.height - size.height * avg / max
        drawLine(avgColor, Offset(0f, avgY), Offset(size.width, avgY), strokeWidth = 2f)
    }
    Row(Modifier.fillMaxWidth()) {
        Text("1", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text("${daily.size / 2}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Text("${daily.size}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
    Text(
        "— line = everyday average ${formatRupees(avg)}/day · everyday spending only, one-offs listed above",
        style = MaterialTheme.typography.labelSmall,
        color = avgColor,
    )
}

@Composable
private fun MonthBars(data: List<Pair<YearMonth, Long>>, selected: YearMonth, onSelect: (YearMonth) -> Unit) {
    val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val barColor = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val avgNonZero = data.map { it.second }.filter { it > 0 }.let { if (it.isEmpty()) 0L else it.sum() / it.size }
    Text(
        "Average ${formatRupees(avgNonZero)}/month (months with entries) · tap to open",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        data.forEach { (_, v) ->
            Text(
                if (v > 0) formatRupeesShort(v) else "",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(data) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width / data.size.toFloat())).toInt().coerceIn(0, data.lastIndex)
                    onSelect(data[i].first)
                }
            },
    ) {
        val slot = size.width / data.size
        val w = slot * 0.6f
        data.forEachIndexed { i, (ym, v) ->
            val h = (size.height * v / max).coerceAtLeast(if (v > 0) 2f else 0f)
            drawRoundRect(
                color = if (ym == selected) barColor else dim,
                topLeft = Offset(i * slot + (slot - w) / 2, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 4, w / 4),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        data.forEach { (ym, _) ->
            Text(
                formatMonthShort(ym).take(3),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                fontWeight = if (ym == selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

/** Stacked bar + legend: everyday / fixed / one-offs / savings, plus typical-day numbers. */
@Composable
private fun SpendSplitView(split: SpendSplit, savings: Long, typicalDay: Long, everydayPerDay: Long) {
    val parts = listOf(
        Triple("☕ Everyday", split.everyday, MaterialTheme.colorScheme.primary),
        Triple("🔁 Fixed / recurring", split.fixed, MaterialTheme.colorScheme.tertiary),
        Triple("✈️ One-offs", split.oneOff, MaterialTheme.colorScheme.error),
        Triple("🏦 Savings", savings + split.savings, MaterialTheme.colorScheme.outline),
    ).filter { it.second > 0 }
    val sum = parts.sumOf { it.second }.coerceAtLeast(1L)

    Row(
        Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp)),
    ) {
        parts.forEach { (_, v, c) ->
            Box(Modifier.weight(v.toFloat() / sum).fillMaxSize().background(c))
        }
    }
    Spacer(Modifier.height(8.dp))
    parts.forEach { (label, v, c) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            Box(Modifier.size(10.dp).background(c, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${(v * 100.0 / sum).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(formatRupees(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Everyday avg", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatRupees(everydayPerDay)}/day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text("Typical day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatRupees(typicalDay), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
    Text(
        "Typical day = middle value of the days you spent on everyday things",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A one-off or fixed entry with the reason the classifier gave. */
@Composable
private fun UnusualRow(e: Expense, info: SpendInfo?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(info?.type?.emoji ?: "•", modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.note.ifBlank { e.category.label }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatShortDate(e.date)} · ${info?.reason ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Text(formatRupees(e.amountPaise), fontWeight = FontWeight.SemiBold)
    }
}

/** Per-city spend this month and trips away from home (auto-grouped from tagged days). */
@Composable
private fun PlacesSection(
    all: List<Expense>,
    spendInMonth: List<Expense>,
    month: YearMonth,
    settings: com.expensetracker.app.data.Settings,
    onEdit: (Expense) -> Unit,
) {
    val home = remember(all, settings.homeCity) { com.expensetracker.app.data.Places.homeCity(all, settings) }
    val totals = remember(spendInMonth) { com.expensetracker.app.data.Places.totals(spendInMonth) }
    val allTrips = remember(all, home) { com.expensetracker.app.data.Places.trips(all, home) }
    val monthTrips = allTrips.filter { !it.end.isBefore(month.atDay(1)) && !it.start.isAfter(month.atEndOfMonth()) }
    val yearAgo = LocalDate.now().minusYears(1)
    val recentTrips = allTrips.filter { it.end.isAfter(yearAgo) }

    if (all.none { !it.place.isNullOrBlank() }) {
        Text(
            "No places yet. Turn on “Tag expenses with location” in Settings, or use “Tag a past trip” for older spending.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val monthTotal = totals.sumOf { it.second }.coerceAtLeast(1)
    totals.forEach { (city, v) ->
        val isHome = city != null && com.expensetracker.app.data.Places.key(city) == com.expensetracker.app.data.Places.key(home)
        Column(Modifier.padding(vertical = 3.dp)) {
            Row {
                Text(
                    when {
                        city == null -> "❔ No place"
                        isHome -> "🏠 $city"
                        else -> "📍 $city"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(formatRupees(v), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { v.toFloat() / monthTotal },
                color = when {
                    city == null -> MaterialTheme.colorScheme.outline
                    isHome -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.tertiary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
                drawStopIndicator = {},
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Trips", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (home == null) {
        Text("Set your home city in Settings to spot trips.", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (monthTrips.isEmpty()) {
        Text(
            "No trips away from $home in ${formatMonth(month)}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    monthTrips.forEach { trip -> TripRow(trip, onEdit) }
    if (recentTrips.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Last 12 months: ${recentTrips.size} trip${if (recentTrips.size == 1) "" else "s"} · ${formatRupees(recentTrips.sumOf { it.totalPaise })} spent away from $home",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TripRow(trip: com.expensetracker.app.data.Trip, onEdit: (Expense) -> Unit) {
    var open by remember(trip.start, trip.title) { mutableStateOf(false) }
    val range = if (trip.start == trip.end) formatShortDate(trip.start) else "${formatShortDate(trip.start)} – ${formatShortDate(trip.end)}"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("✈️ ${trip.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$range · ${trip.days} day${if (trip.days == 1) "" else "s"} · ${formatRupees(trip.totalPaise / trip.days)}/day",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(formatRupees(trip.totalPaise), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            trip.byCategory.take(4).joinToString("   ") { (c, v) -> "${c.emoji} ${formatRupees(v)}" },
            style = MaterialTheme.typography.bodySmall,
        )
        if (open) {
            trip.entries.sortedByDescending { it.date }.forEach { e -> ExpenseRow(e, onClick = { onEdit(e) }, showDate = true) }
        }
    }
}
