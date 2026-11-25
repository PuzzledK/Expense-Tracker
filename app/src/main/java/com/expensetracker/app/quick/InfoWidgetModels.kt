package com.expensetracker.app.quick

import com.expensetracker.app.data.BudgetLevel
import com.expensetracker.app.data.BudgetProgress
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryBudgets
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Ledger
import com.expensetracker.app.data.Subscription
import com.expensetracker.app.data.SubscriptionDetector
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What the Budget and Upcoming-bills widgets show, worked out from the entries alone (pure, so
 * it's unit-tested). The widget providers only turn these into RemoteViews.
 */
internal object InfoWidgetModels {

    /** Most category rows a budget widget shows. */
    const val MAX_CATEGORY_ROWS = 3
    /** Most bills an upcoming-bills widget shows. */
    const val MAX_BILL_ROWS = 3

    data class Bar(val label: String, val spentPaise: Long, val limitPaise: Long) {
        val level: BudgetLevel get() = BudgetLevel.of(spentPaise, limitPaise)
        /** 0..100 for a ProgressBar (over budget = full). */
        val percent: Int get() = if (limitPaise <= 0) 0 else (spentPaise * 100 / limitPaise).coerceIn(0, 100).toInt()
    }

    data class Budget(
        val monthLabel: String,
        val spentPaise: Long,
        /** null = no overall budget set. */
        val overall: Bar?,
        val categories: List<Bar>,
    ) {
        /** Nothing to draw a bar for: show "No budget set — tap to set". */
        val isEmpty: Boolean get() = overall == null && categories.isEmpty()
    }

    fun budget(
        all: List<Expense>,
        monthlyBudgetPaise: Long,
        categoryLimits: Map<String, Long>,
        excludeSavings: Boolean,
        today: LocalDate = LocalDate.now(),
    ): Budget {
        val month = YearMonth.from(today)
        val spent = Ledger.counted(all).asSequence()
            .filter { !it.isIncome && !(excludeSavings && it.category == Category.SAVINGS) }
            .filter { YearMonth.from(it.date) == month }
            .sumOf { it.amountPaise }
        val overall = if (monthlyBudgetPaise > 0) Bar("This month", spent, monthlyBudgetPaise) else null
        val cats = CategoryBudgets.progress(categoryLimits, all, month, excludeSavings)
            .take(MAX_CATEGORY_ROWS)
            .map { p: BudgetProgress -> Bar("${p.category.emoji} ${p.category.label}", p.spentPaise, p.limitPaise) }
        val monthLabel = month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        return Budget(monthLabel, spent, overall, cats)
    }

    data class BillRow(val name: String, val amountPaise: Long, val due: String)
    data class Bills(val rows: List<BillRow>, val monthlyTotalPaise: Long, val count: Int)

    fun bills(all: List<Expense>, hidden: Set<String>, today: LocalDate = LocalDate.now()): Bills {
        val subs = SubscriptionDetector.detect(all, today, hidden).sortedBy { it.nextDue }
        return Bills(
            rows = subs.take(MAX_BILL_ROWS).map { BillRow(it.name, it.amountPaise, dueShort(it, today)) },
            monthlyTotalPaise = SubscriptionDetector.monthlyTotal(subs),
            count = subs.size,
        )
    }

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Short enough for a widget row: "Today", "Tomorrow", "In 3 days", "4 Dec", "Overdue". */
    fun dueShort(s: Subscription, today: LocalDate): String = dueShort(s.nextDue, today)

    fun dueShort(due: LocalDate, today: LocalDate): String {
        val d = ChronoUnit.DAYS.between(today, due)
        return when {
            d < 0 -> "Overdue"
            d == 0L -> "Today"
            d == 1L -> "Tomorrow"
            d < 7 -> "In $d days"
            else -> due.format(dayMonth)
        }
    }

    /** "₹1,240 of ₹6,000" / "₹200 over ₹6,000". */
    fun spentOf(bar: Bar, rupees: (Long) -> String): String =
        if (bar.spentPaise > bar.limitPaise) "${rupees(bar.spentPaise - bar.limitPaise)} over ${rupees(bar.limitPaise)}"
        else "${rupees(bar.spentPaise)} of ${rupees(bar.limitPaise)}"
}
