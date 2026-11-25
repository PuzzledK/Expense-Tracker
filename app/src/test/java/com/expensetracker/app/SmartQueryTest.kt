package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryInfo
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SmartQuery
import com.expensetracker.app.data.SmartQuery.Kind
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import com.expensetracker.app.ui.smartSummary
import com.expensetracker.app.ui.smartTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmartQueryTest {
    private val today = LocalDate.of(2026, 11, 25) // a Wednesday
    private val cats = listOf(
        CategoryInfo("FOOD", "Food", "🍔", 0L),
        CategoryInfo("GROCERIES", "Groceries", "🛒", 0L),
        CategoryInfo("TRANSPORT", "Transport", "🛺", 0L),
        CategoryInfo("RENT", "Rent & Bills", "🏠", 0L),
        CategoryInfo("SHOPPING", "Shopping", "🛍️", 0L),
        CategoryInfo("SAVINGS", "Savings", "🏦", 0L),
    )
    private fun p(q: String, places: List<String> = listOf("Goa", "Gurugram")) = SmartQuery.parse(q, today, cats, places)

    @Test fun categoryAmountAndMonth() {
        val q = p("food over 500 last month")
        assertEquals(setOf("FOOD"), q.categories)
        assertEquals(500_00L, q.minPaise)
        assertNull(q.maxPaise)
        assertEquals(LocalDate.of(2026, 10, 1), q.from)
        assertEquals(LocalDate.of(2026, 10, 31), q.to)
        assertTrue(q.words.isEmpty())
        assertEquals(listOf("🍔 Food", "Over ₹500", "Last month"), q.parts.map { it.label })
    }

    @Test fun merchantTextThisYear() {
        val q = p("Swiggy this year")
        assertEquals(listOf("swiggy"), q.words)
        assertEquals(LocalDate.of(2026, 1, 1), q.from)
        assertEquals(today, q.to)
    }

    @Test fun amountForms() {
        assertEquals(1000_00L, p("under 1k").maxPaise)
        assertEquals(2000_00L, p(">2000").minPaise)
        assertEquals(300_00L, p("<300").maxPaise)
        p("500-1000").let { assertEquals(500_00L, it.minPaise); assertEquals(1000_00L, it.maxPaise) }
        p("between 1000 and 200").let { assertEquals(200_00L, it.minPaise); assertEquals(1000_00L, it.maxPaise) }
        p("₹450").let { assertEquals(450_00L, it.minPaise); assertEquals(450_00L, it.maxPaise) }
        assertEquals(150_000_00L, p("more than 1.5l").minPaise)
        assertEquals(250_00L, p("at least 250").minPaise)
    }

    @Test fun dates() {
        p("today").let { assertEquals(today, it.from); assertEquals(today, it.to) }
        p("yesterday").let { assertEquals(today.minusDays(1), it.from) }
        p("this week").let { assertEquals(LocalDate.of(2026, 11, 23), it.from); assertEquals(today, it.to) }
        p("last week").let { assertEquals(LocalDate.of(2026, 11, 16), it.from); assertEquals(LocalDate.of(2026, 11, 22), it.to) }
        p("last 30 days").let { assertEquals(today.minusDays(29), it.from); assertEquals(today, it.to) }
        // A month later than now means last year's.
        p("in december").let { assertEquals(LocalDate.of(2025, 12, 1), it.from); assertEquals(LocalDate.of(2025, 12, 31), it.to) }
        p("in march").let { assertEquals(LocalDate.of(2026, 3, 1), it.from) }
        p("march 2025").let { assertEquals(LocalDate.of(2025, 3, 1), it.from); assertEquals(LocalDate.of(2025, 3, 31), it.to) }
        p("in 2025").let { assertEquals(LocalDate.of(2025, 1, 1), it.from); assertEquals(LocalDate.of(2025, 12, 31), it.to) }
    }

    @Test fun kindsPlacesAndTrip() {
        assertEquals(Kind.INCOME, p("income in march").kind)
        assertEquals(Kind.REFUND, p("refunds").kind)
        assertEquals(Kind.TRANSFER, p("transfers").kind)
        assertEquals(Kind.ONE_OFF, p("one-off").kind)
        assertEquals(Kind.EVERYDAY, p("everyday food").kind)
        assertEquals("Goa", p("in goa").place)
        assertEquals("Gurugram", p("Gurugram cabs").place)
        assertEquals(setOf("TRANSPORT"), p("Gurugram cabs").categories)
        assertEquals("Goa trip", p("trip:Goa trip").let { it.trip + " " + it.words.single() })
    }

    @Test fun anyOrderAndAliasesAndUnknownWords() {
        val q = p("LAST MONTH under 200 grocery milk")
        assertEquals(setOf("GROCERIES"), q.categories)
        assertEquals(200_00L, q.maxPaise)
        assertEquals(listOf("milk"), q.words)
        assertEquals(setOf("RENT"), p("bills").categories)
        assertEquals(listOf("zomato"), p("zomato").words)
        // A plain unknown place-like word just matches text.
        assertEquals(listOf("delhi"), p("in delhi").words)
    }

    @Test fun removingAChipRewritesTheQuery() {
        val text = "food over 500 last month"
        val q = p(text)
        assertEquals("food last month", SmartQuery.without(text, q.parts[1]))
        assertEquals("food over 500", SmartQuery.without(text, q.parts[2]))
        assertEquals("over 500 last month", SmartQuery.without(text, q.parts[0]))
    }

    private fun e(id: Long, rs: Long, c: Category, d: LocalDate, note: String, income: Boolean = false) =
        Expense(id = id, amountPaise = rs * 100, note = note, category = c, date = d, isIncome = income)

    private val list = listOf(
        e(1, 650, Category.FOOD, LocalDate.of(2026, 10, 3), "Swiggy dinner"),
        e(2, 120, Category.FOOD, LocalDate.of(2026, 10, 9), "Chai"),
        e(3, 900, Category.FOOD, LocalDate.of(2026, 11, 2), "Swiggy"),
        e(4, 2000, Category.SHOPPING, LocalDate.of(2026, 10, 12), "Shoes"),
        e(5, 500, Category.SHOPPING, LocalDate.of(2026, 10, 20), "Shoes refund", income = true).copy(refundOf = 4),
        e(6, 5000, Category.SAVINGS, LocalDate.of(2026, 10, 25), "To HDFC").copy(isTransfer = true),
        e(7, 40000, Category.OTHER, LocalDate.of(2026, 10, 1), "Salary", income = true),
    )

    @Test fun filtersASampleList() {
        fun ids(q: String, info: Map<Long, SpendInfo> = emptyMap()) = list.filter { p(q).matches(it, info) }.map { it.id }
        assertEquals(listOf(1L), ids("food over 500 last month"))
        assertEquals(listOf(1L, 3L), ids("swiggy"))
        assertEquals(listOf(5L), ids("refunds"))
        assertEquals(listOf(6L), ids("transfers"))
        assertEquals(listOf(5L, 7L), ids("income"))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids("spent"))
        assertEquals(listOf(4L), ids("one-off", mapOf(4L to SpendInfo(SpendType.ONE_OFF, "big"))))
        assertEquals(listOf(2L), ids("chai in october"))
    }

    @Test fun summaryUsesTheLedger() {
        val counted = com.expensetracker.app.data.Ledger.counted(list)
        val oct = list.filter { p("last month").matches(it) }
        // Shoes 2000 - 500 refund + food 650 + 120; transfer ignored; salary received.
        assertEquals((2000 - 500 + 650 + 120) * 100L to 40000 * 100L, smartTotals(oct, counted))
        assertTrue(smartSummary(listOf(list[4]), counted).startsWith("1 entry · ₹500"))
    }
}
