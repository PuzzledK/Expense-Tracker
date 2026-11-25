package com.expensetracker.app.ui

import androidx.compose.runtime.setValue

import androidx.compose.runtime.getValue

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Indian digit grouping: 1234567 -> "12,34,567". */
private fun groupIndian(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val last3 = s.takeLast(3)
    var rest = s.dropLast(3)
    val parts = ArrayList<String>()
    while (rest.length > 2) {
        parts.add(0, rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return parts.joinToString(",") + "," + last3
}

/** ₹29,495 or ₹57.55 — paise shown only when non-zero. */
fun formatRupees(paise: Long, withSymbol: Boolean = true): String {
    val neg = paise < 0
    val p = abs(paise)
    val rupees = p / 100
    val frac = p % 100
    val body = groupIndian(rupees) + if (frac != 0L) "." + frac.toString().padStart(2, '0') else ""
    return (if (neg) "-" else "") + (if (withSymbol) "₹" else "") + body
}

/** One line of text that shrinks (down to 60%) to fit its width instead of being cut off. */
@androidx.compose.runtime.Composable
fun FitText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    var scale by androidx.compose.runtime.saveable.rememberSaveable(text) { androidx.compose.runtime.mutableFloatStateOf(1f) }
    androidx.compose.material3.Text(
        text,
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
        onTextLayout = { r -> if (r.hasVisualOverflow && scale > 0.6f) scale -= 0.08f },
    )
}

/** Averages and per-day figures: rounded to whole rupees (₹96, not ₹96.12). */
fun formatRupeesWhole(paise: Long): String = formatRupees(Math.round(paise / 100.0) * 100)

/** Compact form for chart labels: ₹1.2L, ₹12k, ₹950. */
fun formatRupeesShort(paise: Long): String {
    val r = paise / 100.0
    return when {
        r >= 100_000 -> "₹" + trim1(r / 100_000) + "L"
        r >= 1_000 -> "₹" + trim1(r / 1_000) + "k"
        else -> "₹" + r.toLong()
    }
}

/** Tighter than [formatRupeesShort] for narrow chart columns: ₹46k, ₹6.4k, ₹1.2L, ₹950. */
fun formatRupeesCompact(paise: Long): String {
    val r = paise / 100.0
    return when {
        r >= 100_000 -> "₹" + trim1(r / 100_000) + "L"
        r >= 10_000 -> "₹" + Math.round(r / 1_000) + "k"
        r >= 1_000 -> "₹" + trim1(r / 1_000) + "k"
        else -> "₹" + Math.round(r)
    }
}

private fun trim1(d: Double): String {
    val s = String.format(Locale.US, "%.1f", d)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * Parses an amount typed by the user: "120", "29,495", "57.55", "115+120", "115 + 120".
 * Returns paise or null.
 */
fun parseAmountInput(text: String): Long? {
    val cleaned = text.replace(",", "").replace("₹", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split('+').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    var total = 0L
    for (p in parts) {
        val d = p.toBigDecimalOrNull() ?: return null
        if (d.signum() < 0) return null
        total += d.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }
    return total
}

/** Paise -> editable text: 12000 -> "120", 5755 -> "57.55". */
fun paiseToInput(paise: Long): String {
    val r = paise / 100
    val f = paise % 100
    return if (f == 0L) r.toString() else "$r." + f.toString().padStart(2, '0')
}

private val dayHeaderFmt = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
private val dayHeaderYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
private val shortDateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

fun formatDayHeader(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val base = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> null
    }
    val d = if (date.year == today.year) date.format(dayHeaderFmt) else date.format(dayHeaderYearFmt)
    val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    return if (base != null) "$base · $d" else "$dow, $d"
}

fun formatShortDate(date: LocalDate): String = date.format(shortDateFmt)

fun formatMonth(ym: YearMonth): String =
    ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.year

fun formatMonthShort(ym: YearMonth): String =
    ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

fun formatDateChip(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
}

/** True if this expense counts toward "spend" totals given the savings setting. */
fun Expense.countsAsSpend(excludeSavings: Boolean): Boolean =
    !isIncome && !(excludeSavings && category == Category.SAVINGS)

fun YearMonth.contains(date: LocalDate): Boolean = date.year == year && date.month == month

/** Spending split by how it counts towards averages (see SpendClassifier). */
data class SpendSplit(val everyday: Long, val oneOff: Long, val fixed: Long, val savings: Long)

/** Income is ignored; an expense without a verdict yet counts as everyday. */
fun splitSpend(list: List<Expense>, info: Map<Long, SpendInfo>): SpendSplit {
    var everyday = 0L; var oneOff = 0L; var fixed = 0L; var savings = 0L
    list.forEach { e ->
        if (e.isIncome) return@forEach
        when (info[e.id]?.type ?: SpendType.EVERYDAY) {
            SpendType.EVERYDAY -> everyday += e.amountPaise
            SpendType.ONE_OFF -> oneOff += e.amountPaise
            SpendType.FIXED -> fixed += e.amountPaise
            SpendType.SAVINGS -> savings += e.amountPaise
        }
    }
    return SpendSplit(everyday, oneOff, fixed, savings)
}

/** True when the verdict says this expense counts as everyday spending. */
fun Expense.isEveryday(info: Map<Long, SpendInfo>): Boolean =
    !isIncome && (info[id]?.type ?: SpendType.EVERYDAY) == SpendType.EVERYDAY
