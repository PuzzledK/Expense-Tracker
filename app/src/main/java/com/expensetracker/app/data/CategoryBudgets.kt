package com.expensetracker.app.data

import java.time.YearMonth

/** How a budgeted category is doing this month. */
data class BudgetProgress(val category: Category, val limitPaise: Long, val spentPaise: Long) {
    val fraction: Float get() = if (limitPaise > 0) spentPaise.toFloat() / limitPaise else 0f
    val level: BudgetLevel get() = BudgetLevel.of(spentPaise, limitPaise)
    val leftPaise: Long get() = limitPaise - spentPaise
}

enum class BudgetLevel {
    OK, NEAR, OVER;

    companion object {
        /** NEAR from 80% of the limit, OVER from 100%. */
        fun of(spent: Long, limit: Long): BudgetLevel = when {
            limit <= 0 -> OK
            spent >= limit -> OVER
            spent * 100 >= limit * CategoryBudgets.NEAR_PERCENT -> NEAR
            else -> OK
        }
    }
}

/** One nudge to send: [percent] is 80 or 100. */
data class BudgetAlert(val category: Category, val percent: Int, val spentPaise: Long, val limitPaise: Long) {
    /** Remembered once sent so the same nudge never repeats within the month. */
    fun key(month: YearMonth) = "$month|${category.id}|$percent"
}

/**
 * Monthly limits per category (category id → paise). Pure logic, no Android, so it's unit-tested.
 * Stored as "FOOD=600000;c_abc=150000" in settings and backups.
 */
object CategoryBudgets {
    const val NEAR_PERCENT = 80

    fun encode(limits: Map<String, Long>): String =
        limits.filter { it.value > 0 && it.key.isNotBlank() }.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, Long> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.split(';').mapNotNull { part ->
            val id = part.substringBefore('=', "").trim()
            val v = part.substringAfter('=', "").trim().toLongOrNull()
            if (id.isEmpty() || v == null || v <= 0) null else id to v
        }.toMap()
    }

    /** Spending per category id in [month] (income never counts; savings only if not excluded). */
    fun spent(expenses: List<Expense>, month: YearMonth, excludeSavings: Boolean): Map<String, Long> =
        expenses.asSequence()
            .filter { !it.isIncome && !(excludeSavings && it.category == Category.SAVINGS) }
            .filter { it.date.year == month.year && it.date.month == month.month }
            .groupBy { it.category.id }
            .mapValues { (_, v) -> v.sumOf { it.amountPaise } }

    /** Every budgeted category, the one closest to (or furthest over) its limit first. */
    fun progress(limits: Map<String, Long>, expenses: List<Expense>, month: YearMonth, excludeSavings: Boolean): List<BudgetProgress> {
        if (limits.isEmpty()) return emptyList()
        val spent = spent(expenses, month, excludeSavings)
        return limits.filter { it.value > 0 }
            .map { (id, limit) -> BudgetProgress(Category.fromId(id), limit, spent[id] ?: 0L) }
            .sortedByDescending { it.fraction }
    }

    /**
     * Nudges due for [month] that aren't in [sent] yet. Only the highest threshold reached is
     * returned per category (jumping straight past 100% doesn't also send the 80% one).
     */
    fun dueAlerts(
        limits: Map<String, Long>,
        expenses: List<Expense>,
        month: YearMonth,
        excludeSavings: Boolean,
        sent: Set<String>,
    ): List<BudgetAlert> = progress(limits, expenses, month, excludeSavings).mapNotNull { p ->
        val percent = when (p.level) {
            BudgetLevel.OVER -> 100
            BudgetLevel.NEAR -> NEAR_PERCENT
            BudgetLevel.OK -> return@mapNotNull null
        }
        val a = BudgetAlert(p.category, percent, p.spentPaise, p.limitPaise)
        if (a.key(month) in sent) null else a
    }

    /** Keys to remember after sending [alerts]: a 100% nudge also marks 80% as done. Old months are dropped. */
    fun markSent(sent: Set<String>, alerts: List<BudgetAlert>, month: YearMonth): Set<String> {
        val prefix = "$month|"
        val kept = sent.filter { it.startsWith(prefix) }.toMutableSet()
        for (a in alerts) {
            kept += a.key(month)
            if (a.percent >= 100) kept += BudgetAlert(a.category, NEAR_PERCENT, a.spentPaise, a.limitPaise).key(month)
        }
        return kept
    }
}
