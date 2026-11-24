package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.EntryOrder
import com.expensetracker.app.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class EntryOrderTest {
    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val day = LocalDate.of(2026, 11, 24)
    private fun loggedAt(d: LocalDate, h: Int, m: Int) = LocalDateTime.of(d, LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    private fun e(note: String, time: LocalTime?, logged: Long, date: LocalDate = day, id: Long = 0) = Expense(
        id = id, amountPaise = 100, note = note, category = Category.FOOD, date = date, time = time, createdAt = logged,
    )

    private fun order(vararg list: Expense) = list.toList().shuffled(java.util.Random(7)).sortedWith(EntryOrder.comparator(zone)).map { it.note }

    @Test fun editedTimeMovesEntry() {
        // Breakfast was typed last but happened first: time wins over typing order.
        val lunch = e("lunch", LocalTime.of(13, 0), loggedAt(day, 13, 0))
        val dinner = e("dinner", LocalTime.of(20, 30), loggedAt(day, 20, 30))
        val breakfast = e("breakfast", LocalTime.of(9, 0), loggedAt(day, 21, 0))
        assertEquals(listOf("dinner", "lunch", "breakfast"), order(lunch, dinner, breakfast))
    }

    @Test fun untimedSameDayUsesLoggedClock() {
        val coffee = e("coffee", LocalTime.of(16, 0), loggedAt(day, 16, 0))
        val bulkLunch = e("bulk lunch", null, loggedAt(day, 14, 10))     // Add multiple, no time
        val bulkSnack = e("bulk snack", null, loggedAt(day, 18, 0))
        assertEquals(listOf("bulk snack", "coffee", "bulk lunch"), order(coffee, bulkLunch, bulkSnack))
    }

    @Test fun backdatedAndImportedGoBelowTimed_keepTypingOrder() {
        val timed = e("timed", LocalTime.of(8, 0), loggedAt(day, 8, 0))
        val imp1 = e("import 1", null, loggedAt(day.plusDays(5), 10, 0) + 0)
        val imp2 = e("import 2", null, loggedAt(day.plusDays(5), 10, 0) + 1)
        assertEquals(listOf("timed", "import 2", "import 1"), order(timed, imp1, imp2))
        assertNull(EntryOrder.effectiveTime(imp1, zone))
    }

    @Test fun daysStillNewestFirst() {
        val y = e("yesterday late", LocalTime.of(23, 59), loggedAt(day.minusDays(1), 23, 59), date = day.minusDays(1))
        val t = e("today early", LocalTime.of(0, 1), loggedAt(day, 0, 1))
        assertEquals(listOf("today early", "yesterday late"), order(y, t))
    }

    @Test fun fullTieFallsBackToId() {
        val a = e("a", LocalTime.of(12, 0), 1_000L, id = 1)
        val b = e("b", LocalTime.of(12, 0), 1_000L, id = 2)
        assertEquals(listOf("b", "a"), order(a, b))
    }
}
