package com.expensetracker.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A stretch of days spent away from your home city. */
data class Trip(
    val cities: List<String>,     // in visit order, e.g. ["Patiala"] or ["Jaipur", "Bikaner"]
    val start: LocalDate,
    val end: LocalDate,
    val entries: List<Expense>,   // spending only (no income), oldest first
) {
    val title: String get() = cities.joinToString(" → ")
    val totalPaise: Long get() = entries.sumOf { it.amountPaise }
    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
    val byCategory: List<Pair<Category, Long>>
        get() = entries.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
            .toList().sortedByDescending { it.second }
}

object Places {

    /** Treat "gurgaon", " Gurugram " etc. consistently when comparing. */
    fun key(place: String?): String = place?.trim()?.lowercase().orEmpty()

    /** The home city you set, else the city with the most tagged entries. null when nothing is tagged. */
    fun homeCity(all: List<Expense>, settings: Settings): String? {
        settings.homeCity.trim().takeIf { it.isNotEmpty() }?.let { return it }
        return all.mapNotNull { it.place?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .groupingBy { key(it) }.eachCount()
            .maxByOrNull { it.value }
            ?.let { best -> all.first { key(it.place) == best.key }.place!!.trim() }
    }

    /** Distinct places, most used first (for one-tap chips). */
    fun recent(all: List<Expense>, limit: Int = 6): List<String> =
        all.mapNotNull { it.place?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .groupBy { key(it) }
            .entries.sortedByDescending { it.value.size }
            .take(limit)
            .map { it.value.first() }

    /** Spending per place for [list] (untagged entries grouped under null), biggest first. */
    fun totals(list: List<Expense>): List<Pair<String?, Long>> =
        list.filter { !it.isIncome }
            .groupBy { it.place?.trim()?.takeIf { p -> p.isNotEmpty() }?.let { p -> key(p) } }
            .map { (k, v) -> (if (k == null) null else v.first().place!!.trim()) to v.sumOf { it.amountPaise } }
            .sortedByDescending { it.second }

    /**
     * Groups spending outside [home] into trips: days away (tagged with another city) that are
     * at most [maxGapDays] apart join the same trip. Untagged days don't break a trip.
     * Newest trip first.
     */
    fun trips(all: List<Expense>, home: String?, maxGapDays: Int = 2): List<Trip> {
        val homeKey = key(home)
        val away = all.filter { !it.isIncome && key(it.place).isNotEmpty() && key(it.place) != homeKey }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))
        if (away.isEmpty()) return emptyList()

        val trips = mutableListOf<Trip>()
        var bucket = mutableListOf(away.first())
        for (e in away.drop(1)) {
            val gap = ChronoUnit.DAYS.between(bucket.last().date, e.date)
            if (gap <= maxGapDays) bucket += e else {
                trips += toTrip(bucket)
                bucket = mutableListOf(e)
            }
        }
        trips += toTrip(bucket)
        return trips.reversed()
    }

    private fun toTrip(items: List<Expense>): Trip {
        val cities = items.map { it.place!!.trim() }.fold(mutableListOf<String>()) { acc, c ->
            if (acc.none { key(it) == key(c) }) acc += c
            acc
        }
        return Trip(cities, items.first().date, items.last().date, items)
    }
}
