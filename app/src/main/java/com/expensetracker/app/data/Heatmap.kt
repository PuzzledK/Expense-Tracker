package com.expensetracker.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** One calendar cell. [level] 0 = no spending, 1..4 = light → heavy. */
data class HeatDay(val date: LocalDate, val spendPaise: Long, val count: Int, val level: Int)

data class HeatMonth(
    val month: YearMonth,
    /** Empty cells before day 1 so it lands under the right weekday column. */
    val leadingBlanks: Int,
    val days: List<HeatDay>,
    /** Upper bounds (paise) of levels 1..3; anything above the last is level 4. */
    val thresholds: List<Long>,
) {
    val activeDays get() = days.count { it.spendPaise > 0 }
    val busiest get() = days.maxByOrNull { it.spendPaise }?.takeIf { it.spendPaise > 0 }
}

/**
 * Builds a month heatmap from the entries you want counted (the caller filters: everyday only,
 * or all spending). Levels come from quartiles of *your* non-zero days this month, so a ₹11,000
 * flight doesn't wash every other day out to the lightest shade.
 */
object Heatmap {

    fun build(month: YearMonth, spends: List<Expense>, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY): HeatMonth {
        val totals = LongArray(month.lengthOfMonth())
        val counts = IntArray(month.lengthOfMonth())
        spends.forEach { e ->
            if (!e.isIncome && e.date.year == month.year && e.date.month == month.month) {
                totals[e.date.dayOfMonth - 1] += e.amountPaise
                counts[e.date.dayOfMonth - 1]++
            }
        }
        val thresholds = thresholds(totals.filter { it > 0 })
        val days = (1..month.lengthOfMonth()).map { d ->
            val v = totals[d - 1]
            HeatDay(month.atDay(d), v, counts[d - 1], levelOf(v, thresholds))
        }
        val blanks = Math.floorMod(month.atDay(1).dayOfWeek.value - firstDayOfWeek.value, 7)
        return HeatMonth(month, blanks, days, thresholds)
    }

    /** 25th / 50th / 75th percentile of non-zero day totals (nearest-rank). */
    internal fun thresholds(nonZero: List<Long>): List<Long> {
        if (nonZero.isEmpty()) return emptyList()
        val s = nonZero.sorted()
        fun pct(p: Double) = s[((s.size - 1) * p).toInt()]
        return listOf(pct(0.25), pct(0.5), pct(0.75))
    }

    internal fun levelOf(v: Long, thresholds: List<Long>): Int = when {
        v <= 0 -> 0
        thresholds.isEmpty() -> 4
        else -> 1 + thresholds.count { v > it }
    }

    /** Weekday header letters starting from [firstDayOfWeek]. */
    fun weekdayInitials(firstDayOfWeek: DayOfWeek): List<String> =
        (0 until 7).map { firstDayOfWeek.plus(it.toLong()).name.take(1) }
}
