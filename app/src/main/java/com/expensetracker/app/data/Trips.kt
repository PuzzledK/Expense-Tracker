package com.expensetracker.app.data

import java.time.LocalDate

/** The trip you're on right now: new spends get tagged with [name] until [end] (inclusive) passes. */
data class ActiveTrip(val name: String, val start: LocalDate, val end: LocalDate? = null) {
    fun isOver(today: LocalDate): Boolean = end != null && today.isAfter(end)
}

/** One trip's spending, from the tagged entries. */
data class TripSummary(
    val name: String,
    val from: LocalDate,
    val to: LocalDate,
    val totalPaise: Long,
    val count: Int,
    val byCategory: List<Pair<Category, Long>>,
)

/** Pure trip logic (no Android), so it's unit-tested. */
object Trips {
    const val MAX_NAME = 40

    fun cleanName(raw: String): String? = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).takeIf { it.isNotEmpty() }

    /** Trip mode: a new spend without a trip gets the active one. Income and transfers are left alone. */
    fun autoTag(e: Expense, active: ActiveTrip?, today: LocalDate): Expense {
        if (active == null || active.isOver(today)) return e
        if (e.trip != null || e.isIncome || e.isTransfer) return e
        if (e.date.isBefore(active.start)) return e
        if (active.end != null && e.date.isAfter(active.end)) return e
        return e.copy(trip = active.name)
    }

    /** Trip names, most recently used first. */
    fun recent(all: List<Expense>): List<String> =
        all.asSequence().filter { it.trip != null }
            .groupBy { it.trip!! }
            .map { (name, v) -> name to v.maxOf { it.date } }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Spending per trip (refunds netted, transfers ignored), newest trip first. */
    fun summaries(all: List<Expense>): List<TripSummary> =
        Ledger.counted(all).asSequence()
            .filter { it.trip != null && !it.isIncome }
            .groupBy { it.trip!! }
            .map { (name, v) ->
                TripSummary(
                    name = name,
                    from = v.minOf { it.date },
                    to = v.maxOf { it.date },
                    totalPaise = v.sumOf { it.amountPaise },
                    count = v.size,
                    byCategory = v.groupBy { it.category }
                        .map { (c, l) -> c to l.sumOf { it.amountPaise } }
                        .sortedByDescending { it.second },
                )
            }
            .sortedByDescending { it.to }

    /** Total so far for one trip name. */
    fun totalFor(all: List<Expense>, name: String): Long =
        Ledger.counted(all).filter { it.trip == name && !it.isIncome }.sumOf { it.amountPaise }

    /** Entries to rewrite when renaming (or merging into an existing name). */
    fun renamed(all: List<Expense>, from: String, to: String): List<Expense> =
        all.filter { it.trip == from }.map { it.copy(trip = to) }

    /** Entries to rewrite when deleting a trip: only the tag goes, the entries stay. */
    fun cleared(all: List<Expense>, name: String): List<Expense> =
        all.filter { it.trip == name }.map { it.copy(trip = null) }
}
