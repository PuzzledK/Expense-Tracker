package com.expensetracker.app.quick

import com.expensetracker.app.data.BudgetLevel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InfoWidgetModelsTest {
    private var nextId = 1L
    private val today = LocalDate.of(2026, 11, 25)
    private fun e(rs: Long, cat: Category, date: LocalDate = today, note: String = "x", income: Boolean = false) =
        Expense(id = nextId++, amountPaise = rs * 100, note = note, category = cat, date = date, isIncome = income)
    private val rupees: (Long) -> String = { "₹${it / 100}" }

    @Test fun noBudgetsIsEmpty() {
        val m = InfoWidgetModels.budget(listOf(e(100, Category.FOOD)), 0, emptyMap(), true, today)
        assertTrue(m.isEmpty)
        assertNull(m.overall)
        assertEquals(100_00L, m.spentPaise)
    }

    @Test fun overallBarUsesThisMonthOnlyAndNetsRefunds() {
        val shoe = e(2000, Category.SHOPPING)
        val list = listOf(
            shoe,
            e(500, Category.SHOPPING, income = true).copy(refundOf = shoe.id),
            e(9000, Category.SHOPPING).copy(isTransfer = true),
            e(700, Category.FOOD, date = today.minusMonths(1)),
            e(300, Category.SAVINGS),
        )
        val m = InfoWidgetModels.budget(list, 10_000_00, emptyMap(), excludeSavings = true, today = today)
        assertEquals(1500_00L, m.overall!!.spentPaise)
        assertEquals(15, m.overall!!.percent)
        assertEquals("Nov", m.monthLabel)
    }

    @Test fun topThreeCategoriesClosestToLimitFirst() {
        val list = listOf(e(900, Category.FOOD), e(100, Category.TRANSPORT), e(600, Category.SHOPPING), e(50, Category.BILLS))
        val limits = mapOf(Category.FOOD.id to 1000_00L, Category.TRANSPORT.id to 1000_00L, Category.SHOPPING.id to 500_00L, Category.BILLS.id to 1000_00L)
        val m = InfoWidgetModels.budget(list, 0, limits, true, today)
        assertEquals(3, m.categories.size)
        assertEquals(listOf(BudgetLevel.OVER, BudgetLevel.NEAR, BudgetLevel.OK), m.categories.map { it.level })
        assertEquals(100, m.categories[0].percent) // over budget = full bar
    }

    @Test fun spentOfText() {
        assertEquals("₹200 of ₹1000", InfoWidgetModels.spentOf(InfoWidgetModels.Bar("a", 200_00, 1000_00), rupees))
        assertEquals("₹100 over ₹500", InfoWidgetModels.spentOf(InfoWidgetModels.Bar("a", 600_00, 500_00), rupees))
    }

    @Test fun dueShortLabels() {
        assertEquals("Overdue", InfoWidgetModels.dueShort(today.minusDays(2), today))
        assertEquals("Today", InfoWidgetModels.dueShort(today, today))
        assertEquals("Tomorrow", InfoWidgetModels.dueShort(today.plusDays(1), today))
        assertEquals("In 4 days", InfoWidgetModels.dueShort(today.plusDays(4), today))
        assertEquals("12 Dec", InfoWidgetModels.dueShort(LocalDate.of(2026, 12, 12), today))
    }

    @Test fun billsSortedByDueAndHiddenSkipped() {
        val list = mutableListOf<Expense>()
        for (m in 1L..4L) {
            list += e(199, Category.BILLS, today.minusMonths(m).withDayOfMonth(27), note = "Netflix")
            list += e(999, Category.BILLS, today.minusMonths(m).withDayOfMonth(3), note = "Broadband")
            list += e(15000, Category.BILLS, today.minusMonths(m).withDayOfMonth(26), note = "Rent")
        }
        val all = InfoWidgetModels.bills(list, emptySet(), today)
        assertEquals(listOf("Rent", "Netflix", "Broadband"), all.rows.map { it.name })
        assertEquals(listOf("Tomorrow", "In 2 days", "3 Dec"), all.rows.map { it.due })
        val names = all.rows.map { it.name }
        val hidden = InfoWidgetModels.bills(list, setOf(com.expensetracker.app.data.noteKey("Netflix")), today)
        assertTrue("Netflix" in names)
        assertTrue("Netflix" !in hidden.rows.map { it.name })
        assertEquals(all.monthlyTotalPaise - 199_00, hidden.monthlyTotalPaise)
    }

    @Test fun noBills() {
        val m = InfoWidgetModels.bills(listOf(e(100, Category.FOOD)), emptySet(), today)
        assertEquals(0, m.count)
        assertTrue(m.rows.isEmpty())
    }
}
