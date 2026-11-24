package com.expensetracker.app.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Display order for entries: newest day first, and within a day the latest time first.
 *
 * An entry without a time uses the clock time it was logged at, if it was logged on that same
 * day (e.g. something added via "Add multiple" this evening). Entries with no time at all
 * (imports, back-dated adds) go below the timed ones, in the order they were added.
 */
object EntryOrder {

    fun effectiveTime(e: Expense, zone: ZoneId = ZoneId.systemDefault()): LocalTime? {
        e.time?.let { return it }
        val logged = Instant.ofEpochMilli(e.createdAt).atZone(zone)
        return if (logged.toLocalDate() == e.date) logged.toLocalTime().withSecond(0).withNano(0) else null
    }

    fun comparator(zone: ZoneId = ZoneId.systemDefault()): Comparator<Expense> =
        compareByDescending<Expense> { it.date }
            // Descending + nullsFirst puts untimed entries last within the day.
            .thenByDescending(nullsFirst(naturalOrder())) { effectiveTime(it, zone) }
            .thenByDescending { it.createdAt }
            .thenByDescending { it.id }
}
