package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import com.expensetracker.app.ui.KindFilter
import com.expensetracker.app.ui.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class ListFilterTest {
    private val d = LocalDate.of(2026, 11, 25)
    private val lunch = Expense(id = 1, amountPaise = 14200, note = "Lunch", category = Category.FOOD, date = d)
    private val flight = Expense(id = 2, amountPaise = 800000, note = "Flight", category = Category.TRANSPORT, date = d)
    private val rent = Expense(id = 3, amountPaise = 2000000, note = "Rent", category = Category.RENT, date = d)
    private val salary = Expense(id = 4, amountPaise = 5000000, note = "Salary", category = Category.OTHER, date = d, isIncome = true)
    private val unclassified = Expense(id = 5, amountPaise = 6600, note = "Rapido", category = Category.TRANSPORT, date = d)
    private val all = listOf(lunch, flight, rent, salary, unclassified)
    private val info = mapOf(
        1L to SpendInfo(SpendType.EVERYDAY, ""),
        2L to SpendInfo(SpendType.ONE_OFF, ""),
        3L to SpendInfo(SpendType.FIXED, ""),
    )

    private fun ids(f: ListFilter) = all.filter { f.matches(it, info) }.map { it.id }

    @Test fun noFilterKeepsEverything() {
        assertFalse(ListFilter().active)
        assertEquals(listOf(1L, 2, 3, 4, 5), ids(ListFilter()))
    }

    @Test fun everydayIncludesUnclassifiedButNotIncome() =
        assertEquals(listOf(1L, 5), ids(ListFilter(KindFilter.EVERYDAY)))

    @Test fun oneOffAndFixed() {
        assertEquals(listOf(2L), ids(ListFilter(KindFilter.ONE_OFF)))
        assertEquals(listOf(3L), ids(ListFilter(KindFilter.FIXED)))
    }

    @Test fun incomeOnly() = assertEquals(listOf(4L), ids(ListFilter(KindFilter.INCOME)))

    @Test fun categoriesCombineWithKind() {
        assertEquals(listOf(2L, 5), ids(ListFilter(categories = setOf("TRANSPORT"))))
        assertEquals(listOf(5L), ids(ListFilter(KindFilter.EVERYDAY, setOf("TRANSPORT"))))
        assertEquals(listOf(1L, 2, 5), ids(ListFilter(categories = setOf("TRANSPORT", "FOOD"))))
    }

    @Test fun encodeRoundTrips() {
        val f = ListFilter(KindFilter.EVERYDAY, setOf("FOOD", "c_custom1"))
        assertEquals(f, ListFilter.decode(f.encode()))
        assertEquals(ListFilter(), ListFilter.decode(""))
        assertEquals(ListFilter(), ListFilter.decode(ListFilter().encode()))
    }
}
