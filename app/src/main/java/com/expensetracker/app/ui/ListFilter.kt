package com.expensetracker.app.ui

import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType

/** "Show only…" choice for the expense list. [type] is the classifier verdict it keeps. */
enum class KindFilter(val label: String, val type: SpendType?) {
    ALL("All", null),
    EVERYDAY("☕ Everyday", SpendType.EVERYDAY),
    ONE_OFF("✈️ One-off", SpendType.ONE_OFF),
    FIXED("🔁 Fixed", SpendType.FIXED),
    SAVINGS("🏦 Savings", SpendType.SAVINGS),
    INCOME("💰 Income", null),
}

/** Filters for the Home list: one spend kind plus any number of categories (empty = all). */
data class ListFilter(val kind: KindFilter = KindFilter.ALL, val categories: Set<String> = emptySet()) {
    val active: Boolean get() = kind != KindFilter.ALL || categories.isNotEmpty()

    fun matches(e: Expense, info: Map<Long, SpendInfo>): Boolean {
        if (categories.isNotEmpty() && e.category.id !in categories) return false
        return when (kind) {
            KindFilter.ALL -> true
            KindFilter.INCOME -> e.isIncome
            // Same default as everywhere else: no verdict yet = everyday.
            else -> !e.isIncome && (info[e.id]?.type ?: SpendType.EVERYDAY) == kind.type
        }
    }

    /** Compact form for rememberSaveable: "EVERYDAY|food,groceries". */
    fun encode(): String = kind.name + "|" + categories.joinToString(",")

    companion object {
        fun decode(s: String): ListFilter {
            val kind = KindFilter.entries.firstOrNull { it.name == s.substringBefore('|') } ?: KindFilter.ALL
            val cats = s.substringAfter('|', "").split(',').filter { it.isNotBlank() }.toSet()
            return ListFilter(kind, cats)
        }
    }
}
