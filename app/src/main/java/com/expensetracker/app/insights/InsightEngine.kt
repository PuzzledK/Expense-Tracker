package com.expensetracker.app.insights

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Places
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import com.expensetracker.app.data.noteKey
import com.expensetracker.app.ui.formatRupees
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class InsightPeriod { WEEK, MONTH }
enum class InsightTone { GOOD, BAD, NEUTRAL }

/** A labelled bar for the small chart under an insight. */
data class InsightBar(val label: String, val valuePaise: Long, val highlight: Boolean = false)

/**
 * One finding. [score] ranks it (roughly 0–100): ≥ [InsightEngine.HEADLINE_SCORE] is worth a spot on
 * Home, ≥ [InsightEngine.MIN_SCORE] is shown on the Insights screen, anything lower is dropped.
 */
data class Insight(
    /** Stable type id, e.g. "category_change:FOOD". The part before ':' is the insight type. */
    val id: String,
    val period: InsightPeriod,
    val emoji: String,
    val title: String,
    val detail: String,
    val score: Double,
    val tone: InsightTone = InsightTone.NEUTRAL,
    val bars: List<InsightBar> = emptyList(),
    /** Values for a tiny trend line, oldest first. */
    val spark: List<Long> = emptyList(),
) {
    val type: String get() = id.substringBefore(':')
}

/** Inclusive date range. */
data class DateSpan(val start: LocalDate, val end: LocalDate) {
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
    operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    fun dates(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
}

data class WeeklySummary(
    val week: DateSpan,
    val everydayPaise: Long,
    val topCategory: Category?,
    val topCategoryPaise: Long,
    val noSpendDays: Int,
    /** Everyday spend vs the average of the 4 weeks before, in %; null when there's no history. */
    val vsUsualPct: Int?,
    val oneOffPaise: Long,
) {
    /** "Your week: ₹4,210 everyday spend (12% less than usual), top: Food ₹2,100, 2 no-spend days". */
    fun text(): String = buildString {
        append("Your week: ${formatRupees(everydayPaise)} everyday spend")
        vsUsualPct?.takeIf { abs(it) >= 5 }?.let { append(" (${abs(it)}% ${if (it > 0) "more" else "less"} than usual)") }
        if (topCategory != null && topCategoryPaise > 0) append(", top: ${topCategory.label} ${formatRupees(topCategoryPaise)}")
        if (oneOffPaise > 0) append(", one-offs ${formatRupees(oneOffPaise)}")
        if (noSpendDays > 0) append(", $noSpendDays no-spend day${if (noSpendDays > 1) "s" else ""}")
    }
}

/**
 * Turns your history into ranked, de-noised insights. Pure Kotlin (unit-tested).
 *
 * Ground rules: nothing at all until there are 14+ days of history; comparisons only between
 * windows that history fully covers; every insight needs both a rupee and a % threshold, so
 * "Food up 3%" or "₹40 more on chai" never show up.
 */
object InsightEngine {
    const val MIN_HISTORY_DAYS = 14
    const val MIN_SCORE = 15.0
    const val HEADLINE_SCORE = 35.0

    private const val R = 100L // paise per rupee

    fun build(all: List<Expense>, info: Map<Long, SpendInfo>, today: LocalDate, homeCity: String? = null): List<Insight> {
        val c = Data(all, info, today)
        if (!c.hasHistory) return emptyList()
        val out = mutableListOf<Insight>()
        val m = c.monthWindow()
        out += categoryChanges(c, m)
        out += merchantGrowth(c, m)
        out += everydayAverage(c, m)
        out += noSpendDays(c, m)
        out += oneOffs(c, m)
        out += topNotes(c, m)
        out += trips(c, m, homeCity)
        out += projection(c)
        out += weekdayLunch(c)
        out += weekendVsWeekday(c)
        out += lateNight(c)
        out += weekVsUsual(c)
        out += weekTopCategory(c)
        out += streak(c)
        return out.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }
    }

    /** Top insights for Home. */
    fun headlines(list: List<Insight>, n: Int = 2) = list.filter { it.score >= HEADLINE_SCORE }.take(n)

    /** Summary for the Monday–Sunday week containing [day] (the Sunday notification). null if nothing was logged. */
    fun weekly(all: List<Expense>, info: Map<Long, SpendInfo>, day: LocalDate): WeeklySummary? {
        val start = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val week = DateSpan(start, start.plusDays(6))
        val c = Data(all, info, week.end)
        val inWeek = c.spends.filter { it.date in week }
        if (inWeek.isEmpty()) return null
        val everyday = c.sum(week) { c.type(it) == SpendType.EVERYDAY }
        val byCat = inWeek.filter { c.isVariable(it) }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        val top = byCat.maxByOrNull { it.value }
        val prior = (1..4).map { k -> DateSpan(week.start.minusDays(7L * k), week.end.minusDays(7L * k)) }.filter { c.covers(it) && c.active(it) }
        val usual = if (prior.size >= 2 && c.active(DateSpan(week.start, minOf(week.end, c.lastLogged ?: week.end)))) prior.map { c.sum(it) { e -> c.type(e) == SpendType.EVERYDAY } }.average() else null
        return WeeklySummary(
            week = week,
            everydayPaise = everyday,
            topCategory = top?.key,
            topCategoryPaise = top?.value ?: 0,
            noSpendDays = c.noSpendDays(DateSpan(week.start, minOf(week.end, c.lastLogged ?: week.end))),
            vsUsualPct = usual?.takeIf { it > 0 }?.let { ((everyday - it) * 100 / it).roundToInt() },
            oneOffPaise = c.sum(week) { c.type(it) == SpendType.ONE_OFF },
        )
    }

    // ---------------------------------------------------------------- data access

    private class Data(all: List<Expense>, private val info: Map<Long, SpendInfo>, val today: LocalDate) {
        val spends: List<Expense> = all.filter { !it.isIncome && it.amountPaise > 0 && !it.date.isAfter(today) }
        val loggedDays: Set<LocalDate> = all.filter { !it.date.isAfter(today) }.map { it.date }.toSet()
        val firstDate: LocalDate? = loggedDays.minOrNull()
        val lastLogged: LocalDate? = loggedDays.maxOrNull()
        val hasHistory = firstDate != null && ChronoUnit.DAYS.between(firstDate, today) + 1 >= MIN_HISTORY_DAYS &&
            // Stale data (nothing in the last 3 weeks) says nothing about "now".
            ChronoUnit.DAYS.between(lastLogged, today) <= 21

        fun type(e: Expense): SpendType = info[e.id]?.type ?: SpendType.EVERYDAY
        /** Spending you control day to day: everyday + one-offs (no rent/subscriptions/savings). */
        fun isVariable(e: Expense) = type(e).let { it == SpendType.EVERYDAY || it == SpendType.ONE_OFF }
        fun sum(span: DateSpan, pred: (Expense) -> Boolean) = spends.filter { it.date in span && pred(it) }.sumOf { it.amountPaise }
        fun everyday(span: DateSpan) = sum(span) { type(it) == SpendType.EVERYDAY }
        /**
         * You were actually logging during [span] (entries on 60%+ of its days). Guards against
         * reading a break in logging as "you spent ₹0".
         */
        fun active(span: DateSpan) = span.days > 0 && span.dates().count { it in loggedDays } >= span.days * 0.6
        /** No break in logging of 6+ days in a row inside [span] (short runs can be genuine no-spend days). */
        fun noLongBreak(span: DateSpan): Boolean {
            var run = 0
            span.dates().forEach { d -> run = if (d in loggedDays) 0 else run + 1; if (run >= 6) return false }
            return true
        }
        /** History reaches back to (about) the start of [span]. */
        fun covers(span: DateSpan) = firstDate != null && !firstDate.isAfter(span.start.plusDays(2))
        /** Days in [span] without any everyday/one-off spend. */
        fun noSpendDays(span: DateSpan): Int {
            if (span.end.isBefore(span.start)) return 0
            val spent = spends.filter { it.date in span && isVariable(it) }.map { it.date }.toSet()
            return span.dates().count { it !in spent }
        }

        /**
         * The month the MONTH insights talk about, and the window to compare it with. From the 14th
         * on it's this month so far vs the same days of last month; before that, last month vs the one before.
         */
        fun monthWindow(): MonthWindow {
            val ym = YearMonth.from(today)
            return if (today.dayOfMonth >= MIN_HISTORY_DAYS) {
                val prevYm = ym.minusMonths(1)
                val prevEnd = prevYm.atDay(minOf(today.dayOfMonth, prevYm.lengthOfMonth()))
                MonthWindow(
                    DateSpan(ym.atDay(1), today), DateSpan(prevYm.atDay(1), prevEnd),
                    curName = "this month", prevName = "the same days of ${monthName(prevYm)}", curYm = ym, partial = true,
                )
            } else {
                val cur = ym.minusMonths(1)
                val prev = ym.minusMonths(2)
                MonthWindow(
                    DateSpan(cur.atDay(1), cur.atEndOfMonth()), DateSpan(prev.atDay(1), prev.atEndOfMonth()),
                    curName = "in ${monthName(cur)}", prevName = monthName(prev), curYm = cur, partial = false,
                )
            }
        }

        /** Monthly totals for the 6 months up to [last] (oldest first). */
        fun monthly(last: YearMonth, pred: (Expense) -> Boolean): List<Pair<YearMonth, Long>> =
            (5 downTo 0).map { last.minusMonths(it.toLong()) }
                .filter { firstDate != null && !it.atEndOfMonth().isBefore(firstDate) }
                .map { ym -> ym to sum(DateSpan(ym.atDay(1), minOf(ym.atEndOfMonth(), today)), pred) }
    }

    private data class MonthWindow(
        val cur: DateSpan, val prev: DateSpan, val curName: String, val prevName: String,
        val curYm: YearMonth, val partial: Boolean,
    )

    private fun monthName(ym: YearMonth) = ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    private fun shortMonth(ym: YearMonth) = ym.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    private fun rs(p: Long) = formatRupees(p)
    private fun rsRound(p: Long) = formatRupees((p / R) * R)
    private fun pct(cur: Long, prev: Long): Int = ((cur - prev) * 100.0 / prev).roundToInt()

    // ---------------------------------------------------------------- MONTH insights

    /** Everyday spend per category vs the comparison window (≥ ₹500 and ≥ 25% change). */
    private fun categoryChanges(c: Data, m: MonthWindow): List<Insight> {
        if (!c.covers(m.prev) || !c.active(m.cur) || !c.active(m.prev)) return emptyList()
        val ev = { e: Expense -> c.type(e) == SpendType.EVERYDAY }
        val cur = c.spends.filter { it.date in m.cur && ev(it) }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        val prev = c.spends.filter { it.date in m.prev && ev(it) }.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        return (cur.keys + prev.keys).mapNotNull { cat ->
            val a = cur[cat] ?: 0L
            val b = prev[cat] ?: 0L
            val diff = a - b
            if (b <= 0 || maxOf(a, b) < 1_000 * R || abs(diff) < 500 * R) return@mapNotNull null
            val p = pct(a, b)
            if (abs(p) < 25) return@mapNotNull null
            val up = diff > 0
            Insight(
                id = "category_change:${cat.id}",
                period = InsightPeriod.MONTH,
                emoji = cat.emoji,
                title = "${cat.label} ${if (up) "up" else "down"} ${abs(p)}% ${if (m.partial) "this month" else m.curName}",
                detail = "${rs(a)} ${m.curName} vs ${rs(b)} in ${m.prevName} " +
                    "(${if (up) "+" else "−"}${rs(abs(diff))}, everyday spending only).",
                score = 30 + min(35.0, abs(diff) / (250.0 * R)) + min(20.0, abs(p) / 10.0),
                tone = if (up) InsightTone.BAD else InsightTone.GOOD,
                bars = c.monthly(m.curYm) { it.category == cat && ev(it) }.map { (ym, v) -> InsightBar(shortMonth(ym), v, ym == m.curYm) },
            )
        }.sortedByDescending { it.score }.take(3)
    }

    private val stopTokens = setOf("to", "from", "the", "and", "for", "with", "my", "of", "in", "at", "for", "2x", "x")

    /** First meaningful word of a note: "Zepto stuff" → "zepto", "Rapido to Metro" → "rapido". */
    internal fun merchantKey(note: String): String? =
        noteKey(note).split(' ').firstOrNull { it.length >= 3 && it.any(Char::isLetter) && it !in stopTokens }

    /** The merchant/note word whose spend grew most (≥ ₹400 and ≥ 30%, 3+ entries). */
    private fun merchantGrowth(c: Data, m: MonthWindow): List<Insight> {
        if (!c.covers(m.prev) || !c.active(m.cur) || !c.active(m.prev)) return emptyList()
        fun group(span: DateSpan) = c.spends.filter { it.date in span && c.isVariable(it) }
            .groupBy { merchantKey(it.note) }.filterKeys { it != null }
        val cur = group(m.cur)
        val prev = group(m.prev)
        val best = cur.mapNotNull { (k, items) ->
            val a = items.sumOf { it.amountPaise }
            val before = prev[k].orEmpty()
            val b = before.sumOf { it.amountPaise }
            if (items.size < 3 || a < 600 * R || b < 300 * R || a - b < 400 * R) return@mapNotNull null
            val p = pct(a, b)
            if (p < 30) null else Triple(items, before, p)
        }.maxByOrNull { (items, before, _) -> items.sumOf { it.amountPaise } - before.sumOf { it.amountPaise } } ?: return emptyList()
        val (items, before, p) = best
        val a = items.sumOf { it.amountPaise }
        val b = before.sumOf { it.amountPaise }
        val name = displayName(items)
        return listOf(
            Insight(
                id = "merchant_growth:${merchantKey(items.first().note)}",
                period = InsightPeriod.MONTH,
                emoji = "📈",
                title = "$name +$p%",
                detail = "${rs(a)} across ${items.size} entries ${m.curName} vs ${rs(b)} (${before.size}) in ${m.prevName}.",
                score = 28 + min(35.0, (a - b) / (200.0 * R)) + min(15.0, p / 20.0),
                tone = InsightTone.BAD,
                bars = listOf(InsightBar(m.prevName.substringAfterLast(' ').take(3), b), InsightBar(if (m.partial) "Now" else shortMonth(m.curYm), a, true)),
            ),
        )
    }

    /** "Zepto" from notes like "Zepto stuff", "zepto groceries". */
    private fun displayName(items: List<Expense>): String {
        val key = merchantKey(items.first().note) ?: return items.first().note
        val word = items.first().note.split(Regex("[^\\p{L}\\p{N}.]+")).firstOrNull { it.lowercase() == key }
        return (word ?: key).replaceFirstChar { it.uppercase() }
    }

    /** Everyday spend per day vs the comparison window (≥ 10% and ≥ ₹30/day). */
    private fun everydayAverage(c: Data, m: MonthWindow): List<Insight> {
        if (!c.covers(m.prev) || !c.active(m.cur) || !c.active(m.prev)) return emptyList()
        val a = c.everyday(m.cur) / m.cur.days
        val b = c.everyday(m.prev) / m.prev.days
        if (b <= 0) return emptyList()
        val p = pct(a, b)
        if (abs(p) < 10 || abs(a - b) < 30 * R) return emptyList()
        val up = a > b
        return listOf(
            Insight(
                id = "everyday_average",
                period = InsightPeriod.MONTH,
                emoji = if (up) "☕" else "👏",
                title = "Everyday spend ${rsRound(a)}/day, ${if (up) "up" else "down"} ${abs(p)}%",
                detail = "Daily average ${m.curName} vs ${rsRound(b)}/day in ${m.prevName}. Rent, subscriptions, savings and one-offs are left out.",
                score = 35 + min(40.0, abs(p).toDouble()),
                tone = if (up) InsightTone.BAD else InsightTone.GOOD,
                spark = c.monthly(m.curYm) { c.type(it) == SpendType.EVERYDAY }.map { (ym, v) ->
                    val days = if (ym == YearMonth.from(c.today)) c.today.dayOfMonth else ym.lengthOfMonth()
                    v / days
                },
            ),
        )
    }

    /** Days with no everyday/one-off spend (only counted while you were logging). */
    private fun noSpendDays(c: Data, m: MonthWindow): List<Insight> {
        val last = c.lastLogged ?: return emptyList()
        val first = c.firstDate ?: return emptyList()
        val span = DateSpan(maxOf(m.cur.start, first), minOf(m.cur.end, last))
        if (span.days < MIN_HISTORY_DAYS) return emptyList()
        val n = c.noSpendDays(span)
        if (n < 2) return emptyList()
        if (!c.noLongBreak(span)) return emptyList()
        val before = if (c.covers(m.prev) && c.noLongBreak(m.prev)) c.noSpendDays(m.prev) else null
        return listOf(
            Insight(
                id = "no_spend_days",
                period = InsightPeriod.MONTH,
                emoji = "🌱",
                title = "$n no-spend days ${m.curName}",
                detail = "Days without any everyday or one-off spending" +
                    (before?.let { " — $it in ${m.prevName}." } ?: "."),
                score = 18 + min(30.0, n * 3.0) + if (before != null && n > before) 8.0 else 0.0,
                tone = InsightTone.GOOD,
            ),
        )
    }

    /** One-offs in the month (total ≥ ₹2,000). */
    private fun oneOffs(c: Data, m: MonthWindow): List<Insight> {
        val items = c.spends.filter { it.date in m.cur && c.type(it) == SpendType.ONE_OFF }.sortedByDescending { it.amountPaise }
        val total = items.sumOf { it.amountPaise }
        if (items.isEmpty() || total < 2_000 * R) return emptyList()
        val everyday = c.everyday(m.cur).coerceAtLeast(1)
        val biggest = items.first()
        return listOf(
            Insight(
                id = "one_offs",
                period = InsightPeriod.MONTH,
                emoji = "✈️",
                title = "${items.size} one-off${if (items.size > 1) "s" else ""} ${m.curName}: ${rs(total)}",
                detail = "Biggest: ${biggest.note.ifBlank { biggest.category.label }} ${rs(biggest.amountPaise)}. " +
                    "One-offs are kept out of your everyday average.",
                score = 25 + min(35.0, total * 20.0 / everyday),
                bars = items.take(4).map { InsightBar(it.note.ifBlank { it.category.label }.take(14), it.amountPaise) },
            ),
        )
    }

    /** The three notes you spent most on (informational, low score). */
    private fun topNotes(c: Data, m: MonthWindow): List<Insight> {
        if (m.cur.days < MIN_HISTORY_DAYS) return emptyList()
        val groups = c.spends.filter { it.date in m.cur && c.type(it) == SpendType.EVERYDAY }
            .groupBy { noteKey(it.note) }.filterKeys { it.isNotEmpty() }
            .filterValues { it.size >= 2 }
            .map { (_, v) -> v to v.sumOf { it.amountPaise } }
            .sortedByDescending { it.second }
        if (groups.size < 3) return emptyList()
        val top = groups.take(3)
        return listOf(
            Insight(
                id = "top_notes",
                period = InsightPeriod.MONTH,
                emoji = "🏷️",
                title = "Top entries ${m.curName}: ${top.joinToString(", ") { it.first.first().note.take(18) }}",
                detail = top.joinToString(" · ") { (v, sum) -> "${v.first().note.take(20)} ${rs(sum)} (${v.size}×)" },
                score = 16.0,
                bars = top.map { (v, sum) -> InsightBar(v.first().note.take(14), sum) },
            ),
        )
    }

    /** Spending away from home in the month window (uses place tags). */
    private fun trips(c: Data, m: MonthWindow, home: String?): List<Insight> {
        if (home.isNullOrBlank()) return emptyList()
        val trip = Places.trips(c.spends, home).firstOrNull { !it.end.isBefore(m.cur.start) && !it.start.isAfter(m.cur.end) }
            ?: return emptyList()
        if (trip.totalPaise < 1_000 * R) return emptyList()
        val perDay = trip.totalPaise / trip.days
        val homeDays = DateSpan(m.cur.start, m.cur.end)
        val homeSpend = c.spends.filter { it.date in homeDays && it !in trip.entries && c.type(it) == SpendType.EVERYDAY }.sumOf { it.amountPaise }
        val homePerDay = homeSpend / (homeDays.days - trip.days).coerceAtLeast(1)
        return listOf(
            Insight(
                id = "trip",
                period = InsightPeriod.MONTH,
                emoji = "📍",
                title = "${trip.title} trip: ${rs(trip.totalPaise)}",
                detail = "${trip.days} day${if (trip.days > 1) "s" else ""}, about ${rsRound(perDay)}/day" +
                    (if (homePerDay > 0) " vs ${rsRound(homePerDay)}/day everyday spend at home." else "."),
                score = 30.0,
                bars = trip.byCategory.take(4).map { (cat, v) -> InsightBar("${cat.emoji} ${cat.label}".take(14), v) },
            ),
        )
    }

    /** Where this month's everyday spend is heading, at your last-4-weeks pace. */
    private fun projection(c: Data): List<Insight> {
        val ym = YearMonth.from(c.today)
        val left = ym.lengthOfMonth() - c.today.dayOfMonth
        if (left < 3) return emptyList()
        val paceSpan = DateSpan(c.today.minusDays(27), c.today)
        val monthSoFar = DateSpan(ym.atDay(1), c.today)
        if (monthSoFar.days >= 3 && !c.active(monthSoFar)) return emptyList()
        val pace = if (c.covers(paceSpan)) {
            if (!c.active(paceSpan)) return emptyList()
            c.everyday(paceSpan) / 28.0
        }
        else {
            val span = DateSpan(c.firstDate!!, c.today)
            if (span.days < MIN_HISTORY_DAYS || !c.active(span)) return emptyList()
            c.everyday(span) / span.days.toDouble()
        }
        if (pace <= 0) return emptyList()
        val soFar = c.everyday(DateSpan(ym.atDay(1), c.today))
        val projected = soFar + (pace * left).roundToLong()
        val lastYm = ym.minusMonths(1)
        val last = DateSpan(lastYm.atDay(1), lastYm.atEndOfMonth()).takeIf { c.covers(it) && c.active(it) }?.let { c.everyday(it) }
        val p = last?.takeIf { it > 0 }?.let { pct(projected, it) }
        return listOf(
            Insight(
                id = "projection",
                period = InsightPeriod.MONTH,
                emoji = "🔮",
                title = "On pace for ${rsRound(projected)} everyday spend in ${monthName(ym)}" +
                    (p?.takeIf { abs(it) >= 5 }?.let { " (${if (it > 0) "+" else "−"}${abs(it)}%)" } ?: ""),
                detail = "${rs(soFar)} so far + about ${rsRound(pace.roundToLong())}/day for the remaining $left days (your recent pace)" +
                    (last?.let { ". ${monthName(lastYm)}: ${rs(it)}." } ?: "."),
                score = 30 + (p?.let { min(30.0, abs(it) / 2.0) } ?: 0.0),
                tone = when {
                    p == null || abs(p) < 5 -> InsightTone.NEUTRAL
                    p > 0 -> InsightTone.BAD
                    else -> InsightTone.GOOD
                },
                bars = listOfNotNull(
                    last?.let { InsightBar(shortMonth(lastYm), it) },
                    InsightBar("So far", soFar),
                    InsightBar("Projected", projected, true),
                ),
            ),
        )
    }

    /** Typical (median) weekday lunch over the last 60 days, and how it moved. */
    private fun weekdayLunch(c: Data): List<Insight> {
        fun lunches(span: DateSpan) = c.spends.filter {
            it.date in span && it.date.dayOfWeek.value <= 5 && "lunch" in noteKey(it.note).split(' ') && c.type(it) == SpendType.EVERYDAY
        }.map { it.amountPaise }
        val recentSpan = DateSpan(c.today.minusDays(59), c.today)
        val recent = lunches(recentSpan)
        if (recent.size < 8) return emptyList()
        val med = median(recent)
        val older = lunches(DateSpan(c.today.minusDays(179), c.today.minusDays(60)))
        val oldMed = if (older.size >= 8) median(older) else null
        val p = oldMed?.let { pct(med, it) }?.takeIf { abs(it) >= 10 && abs(med - oldMed) >= 10 * R }
        val ym = YearMonth.from(c.today)
        return listOf(
            Insight(
                id = "weekday_lunch",
                period = InsightPeriod.MONTH,
                emoji = "🍱",
                title = "A weekday lunch costs you about ${rsRound(med)}" + (p?.let { " (${if (it > 0) "up" else "down"} ${abs(it)}%)" } ?: ""),
                detail = "Median of ${recent.size} weekday lunches in the last 2 months" +
                    (oldMed?.let { "; it was ${rsRound(it)} before." } ?: ".") +
                    " Over a month of workdays that's ~${rsRound(med * 22)}.",
                score = 20 + (p?.let { min(30.0, abs(it).toDouble()) } ?: 0.0),
                tone = p?.let { if (it > 0) InsightTone.BAD else InsightTone.GOOD } ?: InsightTone.NEUTRAL,
                spark = (5 downTo 0).map { ym.minusMonths(it.toLong()) }.mapNotNull { m ->
                    lunches(DateSpan(m.atDay(1), minOf(m.atEndOfMonth(), c.today))).takeIf { it.size >= 3 }?.let(::median)
                },
            ),
        )
    }

    /** Everyday spend per weekend day vs per weekday over the last 8 weeks. */
    private fun weekendVsWeekday(c: Data): List<Insight> {
        val span = DateSpan(maxOf(c.today.minusDays(55), c.firstDate!!), minOf(c.today, c.lastLogged!!))
        if (span.days < 28 || !c.active(span)) return emptyList()
        // Only days you logged, so a break in logging doesn't look like cheap days.
        val days = span.dates().filter { it in c.loggedDays }.toList()
        val byDow = DayOfWeek.entries.associateWith { dow ->
            val ds = days.filter { it.dayOfWeek == dow }
            if (ds.isEmpty()) 0L else c.spends.filter { it.date in ds && c.type(it) == SpendType.EVERYDAY }.sumOf { it.amountPaise } / ds.size
        }
        val weekendDays = days.count { it.dayOfWeek.value >= 6 }
        if (weekendDays < 6) return emptyList()
        val we = (byDow[DayOfWeek.SATURDAY]!! + byDow[DayOfWeek.SUNDAY]!!) / 2.0
        val wd = DayOfWeek.entries.take(5).sumOf { byDow[it]!! } / 5.0
        if (wd <= 0 || we <= 0) return emptyList()
        val ratio = we / wd
        if (ratio in 0.75..1.3 || abs(we - wd) < 100 * R) return emptyList()
        val more = ratio > 1
        return listOf(
            Insight(
                id = "weekend_vs_weekday",
                period = InsightPeriod.MONTH,
                emoji = if (more) "🎉" else "🧘",
                title = if (more) "Weekends cost ${"%.1f".format(Locale.US, ratio)}× a weekday"
                else "You spend ${((1 - ratio) * 100).roundToInt()}% less on weekends",
                detail = "Everyday spend per day over the last 8 weeks: ${rsRound(we.roundToLong())} on Sat/Sun vs ${rsRound(wd.roundToLong())} on weekdays.",
                score = 20 + min(35.0, abs(ratio - 1) * 30),
                bars = DayOfWeek.entries.map { InsightBar(it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), byDow[it]!!, it.value >= 6) },
            ),
        )
    }

    /** Share of spending logged between 10 pm and 4 am (needs entries with a time). */
    private fun lateNight(c: Data): List<Insight> {
        val timed = c.spends.filter { it.time != null && it.date in DateSpan(c.today.minusDays(59), c.today) && c.isVariable(it) }
        if (timed.size < 15) return emptyList()
        fun late(t: LocalTime) = t.hour >= 22 || t.hour < 4
        val lateItems = timed.filter { late(it.time!!) }
        if (lateItems.size < 3) return emptyList()
        val share = lateItems.sumOf { it.amountPaise }.toDouble() / timed.sumOf { it.amountPaise }
        if (share < 0.15) return emptyList()
        return listOf(
            Insight(
                id = "late_night",
                period = InsightPeriod.MONTH,
                emoji = "🌙",
                title = "${(share * 100).roundToInt()}% of your spending happens after 10 pm",
                detail = "${lateItems.size} of ${timed.size} timed entries in the last 2 months, ${rs(lateItems.sumOf { it.amountPaise })} in all.",
                score = 20 + min(30.0, share * 60),
                tone = InsightTone.BAD,
            ),
        )
    }

    // ---------------------------------------------------------------- WEEK insights

    private fun lastWeek(c: Data) = DateSpan(c.today.minusDays(6), c.today)

    /** Last 7 days' everyday spend vs the 4 weeks before (≥ 20% and ≥ ₹300). */
    private fun weekVsUsual(c: Data): List<Insight> {
        val week = lastWeek(c)
        if (!c.active(week)) return emptyList()
        val prior = (1..4).map { k -> DateSpan(week.start.minusDays(7L * k), week.end.minusDays(7L * k)) }.filter { c.covers(it) && c.active(it) }
        if (prior.size < 2) return emptyList()
        val a = c.everyday(week)
        val usual = prior.map { c.everyday(it) }.average().roundToLong()
        if (usual <= 0) return emptyList()
        val p = pct(a, usual)
        if (abs(p) < 20 || abs(a - usual) < 300 * R) return emptyList()
        val up = a > usual
        return listOf(
            Insight(
                id = "week_vs_usual",
                period = InsightPeriod.WEEK,
                emoji = if (up) "📊" else "✅",
                title = "${rs(a)} everyday spend in the last 7 days, ${abs(p)}% ${if (up) "above" else "below"} usual",
                detail = "Your usual week is about ${rsRound(usual)} (average of the ${prior.size} weeks before).",
                score = 35 + min(40.0, abs(p) / 2.0),
                tone = if (up) InsightTone.BAD else InsightTone.GOOD,
                spark = (7 downTo 0).map { k -> DateSpan(week.start.minusDays(7L * k), week.end.minusDays(7L * k)) }
                    .filter { c.covers(it) && c.active(it) }.map { c.everyday(it) },
            ),
        )
    }

    /** Where the last 7 days' money went (informational). */
    private fun weekTopCategory(c: Data): List<Insight> {
        val week = lastWeek(c)
        val items = c.spends.filter { it.date in week && c.isVariable(it) }
        if (items.size < 3) return emptyList()
        val total = items.sumOf { it.amountPaise }
        val byCat = items.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }.toList().sortedByDescending { it.second }
        val (cat, amt) = byCat.first()
        val share = amt * 100 / total
        if (share < 35 || byCat.size < 2) return emptyList()
        return listOf(
            Insight(
                id = "week_top_category",
                period = InsightPeriod.WEEK,
                emoji = cat.emoji,
                title = "$share% of this week went on ${cat.label}",
                detail = "${rs(amt)} of ${rs(total)} in the last 7 days (rent, subscriptions and savings not counted).",
                score = 15 + min(15.0, (share - 35) / 3.0),
                bars = byCat.take(4).map { (k, v) -> InsightBar("${k.emoji} ${k.label}".take(14), v, k == cat) },
            ),
        )
    }

    /** Consecutive days with at least one entry, ending today or yesterday (≥ 7 days). */
    private fun streak(c: Data): List<Insight> {
        var d = if (c.today in c.loggedDays) c.today else c.today.minusDays(1)
        var n = 0
        while (d in c.loggedDays) { n++; d = d.minusDays(1) }
        if (n < 7) return emptyList()
        return listOf(
            Insight(
                id = "streak",
                period = InsightPeriod.WEEK,
                emoji = "🔥",
                title = "$n-day logging streak",
                detail = "You've logged something every day since ${d.plusDays(1).dayOfMonth} ${shortMonth(YearMonth.from(d.plusDays(1)))}. Keep it going!",
                score = 15 + min(25.0, n / 2.0),
                tone = InsightTone.GOOD,
            ),
        )
    }

    private fun median(v: List<Long>): Long {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
