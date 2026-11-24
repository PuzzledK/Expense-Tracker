package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Heatmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class HeatmapTest {
    private val nov = YearMonth.of(2026, 11)            // 1 Nov 2026 is a Sunday
    private fun e(day: Int, rupees: Long, income: Boolean = false, month: YearMonth = nov) =
        Expense(amountPaise = rupees * 100, note = "x", category = Category.FOOD, date = month.atDay(day), isIncome = income)

    @Test fun weekLayout_followsFirstDayOfWeek() {
        assertEquals(6, Heatmap.build(nov, emptyList(), DayOfWeek.MONDAY).leadingBlanks)   // Mon..Sat blank
        assertEquals(0, Heatmap.build(nov, emptyList(), DayOfWeek.SUNDAY).leadingBlanks)
        assertEquals(30, Heatmap.build(nov, emptyList()).days.size)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), Heatmap.weekdayInitials(DayOfWeek.MONDAY))
        assertEquals("S", Heatmap.weekdayInitials(DayOfWeek.SUNDAY).first())
    }

    @Test fun totalsCountsAndIgnoredEntries() {
        val m = Heatmap.build(nov, listOf(e(3, 100), e(3, 42), e(3, 5000, income = true), e(4, 10, month = YearMonth.of(2026, 10))))
        val d3 = m.days[2]
        assertEquals(14200L, d3.spendPaise)       // income and other months are ignored
        assertEquals(2, d3.count)
        assertEquals(1, m.activeDays)
        assertEquals(LocalDate.of(2026, 11, 3), m.busiest!!.date)
    }

    @Test fun levelsFromQuartiles_oneBigDayDoesNotFlattenTheRest() {
        // Typical days ₹100–₹400 plus one ₹11,201 flight day.
        val spends = listOf(e(1, 100), e(2, 150), e(3, 200), e(4, 250), e(5, 300), e(6, 350), e(7, 400), e(9, 11201))
        val m = Heatmap.build(nov, spends)
        val lv = m.days.associate { it.date.dayOfMonth to it.level }
        assertEquals(0, lv[8])                    // no spending
        assertEquals(1, lv[1])
        assertEquals(4, lv[9])                    // the flight
        assertEquals(4, lv[7])                    // ₹400 is still "heavy" for a normal day, not washed out
        assertEquals(true, (1..7).map { lv[it]!! }.distinct().size >= 3)
    }

    @Test fun emptyAndSingleDay() {
        val empty = Heatmap.build(nov, emptyList())
        assertNull(empty.busiest)
        assertEquals(0, empty.days.maxOf { it.level })
        val one = Heatmap.build(nov, listOf(e(5, 120)))
        assertEquals(1, one.days.count { it.level > 0 })
    }
}
