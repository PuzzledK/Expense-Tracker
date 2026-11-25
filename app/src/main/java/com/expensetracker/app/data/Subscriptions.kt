package com.expensetracker.app.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A payment that repeats about once a month (rent, Netflix, gym fee…). */
data class Subscription(
    /** [noteKey] of the note — stable id used for hiding. */
    val key: String,
    /** The note as you last wrote it. */
    val name: String,
    val category: Category,
    /** What you'll likely pay next time (the latest amount). */
    val amountPaise: Long,
    val lastPaid: LocalDate,
    /** Predicted next payment. May be a few days in the past when it's overdue. */
    val nextDue: LocalDate,
    /** The usual amount before the latest payment, when the latest one differs (199 → 249). */
    val previousAmountPaise: Long? = null,
    /** Distinct months it was paid in. */
    val months: Int,
) {
    val priceChanged: Boolean get() = previousAmountPaise != null
    fun isOverdue(today: LocalDate) = nextDue.isBefore(today)
    fun daysUntilDue(today: LocalDate) = ChronoUnit.DAYS.between(today, nextDue)
}

/**
 * Finds subscriptions and bills in your history. Shares its "about once a month, steady amount,
 * 3+ months" rule with [SpendClassifier], so what shows here is what the app counts as Fixed.
 */
object SubscriptionDetector {
    private const val MIN_RECURRING = 150_00L   // ₹150
    private const val MIN_MONTHS = 3
    /** Gone quiet for this long → treat as cancelled. */
    private const val STALE_DAYS = 62L
    /** Overdue this long → assume it was skipped and predict the following month. */
    private const val OVERDUE_GRACE_DAYS = 7L

    /** Frequent, consumable categories: "Zepto" every month isn't a subscription. */
    private val CONSUMABLES = setOf(Category.FOOD, Category.GROCERIES, Category.TRANSPORT)

    /** A recurring note: its entries (oldest first) and median amount. */
    internal data class Group(val key: String, val items: List<Expense>, val medianPaise: Long)

    /** Notes that recur roughly monthly across 3+ months at a steady amount. Spends only. */
    internal fun recurringGroups(spends: List<Expense>): List<Group> =
        spends.filter { !it.isIncome && it.category !in CONSUMABLES }
            .groupBy { noteKey(it.note) }
            .filterKeys { it.isNotEmpty() }
            .mapNotNull { (key, items) ->
                val months = items.map { YearMonth.from(it.date) }.toSet().size
                val perMonth = items.size.toDouble() / months
                val sorted = items.map { it.amountPaise }.sorted()
                val median = sorted[sorted.size / 2]
                val steady = sorted.first() > 0 && sorted.last().toDouble() / sorted.first() <= 1.6
                if (months >= MIN_MONTHS && perMonth <= 1.5 && steady && median >= MIN_RECURRING) {
                    Group(key, items.sortedWith(compareBy({ it.date }, { it.createdAt })), median)
                } else null
            }

    /** Active subscriptions, soonest due first. [hidden] holds keys you marked "not a subscription". */
    fun detect(all: List<Expense>, today: LocalDate, hidden: Set<String> = emptySet()): List<Subscription> =
        recurringGroups(all)
            .filter { it.key !in hidden }
            .mapNotNull { g ->
                val latest = g.items.last()
                if (ChronoUnit.DAYS.between(latest.date, today) > STALE_DAYS) return@mapNotNull null
                val earlier = g.items.dropLast(1).map { it.amountPaise }.sorted()
                val before = if (earlier.isEmpty()) null else earlier[earlier.size / 2]
                Subscription(
                    key = g.key,
                    name = latest.note.trim(),
                    category = latest.category,
                    amountPaise = latest.amountPaise,
                    lastPaid = latest.date,
                    nextDue = nextDue(g.items.map { it.date }, today),
                    previousAmountPaise = before?.takeIf { priceChanged(it, latest.amountPaise) },
                    months = g.items.map { YearMonth.from(it.date) }.toSet().size,
                )
            }
            .sortedWith(compareBy({ it.nextDue }, { it.name.lowercase() }))

    /** A real change, not rounding: at least ₹1 and 3%. */
    internal fun priceChanged(before: Long, now: Long): Boolean {
        val d = abs(now - before)
        return d >= 100 && d * 100 >= before * 3
    }

    /**
     * The day of month it's usually paid on. A payment on the last day of a month counts as
     * "month-end" (31), so paying on 30 Nov after 31 Oct keeps predicting 31 Dec.
     */
    internal fun anchorDay(dates: List<LocalDate>): Int {
        val recent = dates.sorted().takeLast(3)
        val days = recent.map { if (it.dayOfMonth == it.lengthOfMonth()) 31 else it.dayOfMonth }
        // Most common recent day; ties go to the latest.
        return days.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { days.lastIndexOf(it.key) }).key
    }

    /** One month after the last payment, on the usual day (clamped to short months). */
    internal fun nextDue(dates: List<LocalDate>, today: LocalDate): LocalDate {
        val last = dates.max()
        val anchor = anchorDay(dates)
        fun inMonth(ym: YearMonth) = ym.atDay(minOf(anchor, ym.lengthOfMonth()))
        var ym = YearMonth.from(last).plusMonths(1)
        var due = inMonth(ym)
        // Paid early in a month the anchor hadn't reached yet (anchor 28, paid 2nd)? Then it's this month.
        if (ChronoUnit.DAYS.between(last, due) > 45) { ym = ym.minusMonths(1); due = inMonth(ym) }
        while (ChronoUnit.DAYS.between(due, today) > OVERDUE_GRACE_DAYS) { ym = ym.plusMonths(1); due = inMonth(ym) }
        return due
    }

    /** What these cost per month (latest amounts). */
    fun monthlyTotal(subs: List<Subscription>): Long = subs.sumOf { it.amountPaise }

    /** Subscriptions due exactly tomorrow — for the reminder. */
    fun dueTomorrow(subs: List<Subscription>, today: LocalDate): List<Subscription> =
        subs.filter { it.nextDue == today.plusDays(1) }
}
