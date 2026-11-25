package com.expensetracker.app

import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.BudgetLevel
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryBudgets
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CategoryBudgetsTest {
    private val nov = YearMonth.of(2026, 11)
    private fun e(rs: Long, c: Category, d: LocalDate = LocalDate.of(2026, 11, 10), income: Boolean = false) =
        Expense(amountPaise = rs * 100, note = "x", category = c, date = d, isIncome = income)

    @Test fun encodeDecodeRoundTrip() {
        val m = mapOf("FOOD" to 600000L, "c_abc" to 150000L)
        assertEquals(m, CategoryBudgets.decode(CategoryBudgets.encode(m)))
        assertEquals(emptyMap<String, Long>(), CategoryBudgets.decode(""))
        assertEquals(emptyMap<String, Long>(), CategoryBudgets.decode(null))
        assertEquals(mapOf("FOOD" to 5L), CategoryBudgets.decode("FOOD=5;bad;X=-3;=4;Y=abc"))
        assertEquals("", CategoryBudgets.encode(mapOf("FOOD" to 0L)))
    }

    @Test fun spentCountsOnlyThisMonthsSpending() {
        val list = listOf(
            e(100, Category.FOOD), e(50, Category.FOOD),
            e(999, Category.FOOD, d = LocalDate.of(2026, 10, 31)),   // last month
            e(500, Category.FOOD, income = true),                     // income
            e(300, Category.SAVINGS),
        )
        val s = CategoryBudgets.spent(list, nov, excludeSavings = true)
        assertEquals(15000L, s["FOOD"])
        assertEquals(null, s["SAVINGS"])
        assertEquals(30000L, CategoryBudgets.spent(list, nov, excludeSavings = false)["SAVINGS"])
    }

    @Test fun levels() {
        assertEquals(BudgetLevel.OK, BudgetLevel.of(79, 100))
        assertEquals(BudgetLevel.NEAR, BudgetLevel.of(80, 100))
        assertEquals(BudgetLevel.NEAR, BudgetLevel.of(99, 100))
        assertEquals(BudgetLevel.OVER, BudgetLevel.of(100, 100))
        assertEquals(BudgetLevel.OK, BudgetLevel.of(500, 0))
    }

    @Test fun progressSortedByHowCloseToLimit() {
        val limits = mapOf("FOOD" to 100_00L, "TRANSPORT" to 1000_00L, "SHOPPING" to 50_00L)
        val p = CategoryBudgets.progress(limits, listOf(e(90, Category.FOOD), e(100, Category.TRANSPORT)), nov, true)
        assertEquals(listOf("FOOD", "TRANSPORT", "SHOPPING"), p.map { it.category.id })
        assertEquals(0L, p.last().spentPaise)
    }

    @Test fun alertAt80ThenAt100OncePerMonth() {
        val limits = mapOf("FOOD" to 1000_00L)
        var sent = emptySet<String>()
        // 70%: nothing
        assertTrue(CategoryBudgets.dueAlerts(limits, listOf(e(700, Category.FOOD)), nov, true, sent).isEmpty())
        // 85%: the 80% nudge
        var list = listOf(e(700, Category.FOOD), e(150, Category.FOOD))
        var due = CategoryBudgets.dueAlerts(limits, list, nov, true, sent)
        assertEquals(listOf(80), due.map { it.percent })
        sent = CategoryBudgets.markSent(sent, due, nov)
        // still 85% (or 95%): no repeat
        assertTrue(CategoryBudgets.dueAlerts(limits, list, nov, true, sent).isEmpty())
        list = list + e(100, Category.FOOD)
        assertTrue(CategoryBudgets.dueAlerts(limits, list, nov, true, sent).isEmpty())
        // 105%: the 100% nudge, once
        list = list + e(100, Category.FOOD)
        due = CategoryBudgets.dueAlerts(limits, list, nov, true, sent)
        assertEquals(listOf(100), due.map { it.percent })
        sent = CategoryBudgets.markSent(sent, due, nov)
        assertTrue(CategoryBudgets.dueAlerts(limits, list + e(500, Category.FOOD), nov, true, sent).isEmpty())
    }

    @Test fun jumpingPastTheLimitSendsOnlyTheOverNudge() {
        val limits = mapOf("FOOD" to 100_00L)
        val due = CategoryBudgets.dueAlerts(limits, listOf(e(150, Category.FOOD)), nov, true, emptySet())
        assertEquals(listOf(100), due.map { it.percent })
        val sent = CategoryBudgets.markSent(emptySet(), due, nov)
        assertTrue("2026-11|FOOD|80" in sent)
    }

    @Test fun newMonthStartsFreshAndOldKeysAreDropped() {
        val limits = mapOf("FOOD" to 100_00L)
        val oct = YearMonth.of(2026, 10)
        val octDue = CategoryBudgets.dueAlerts(limits, listOf(e(150, Category.FOOD, d = LocalDate.of(2026, 10, 3))), oct, true, emptySet())
        val sent = CategoryBudgets.markSent(emptySet(), octDue, oct)
        val due = CategoryBudgets.dueAlerts(limits, listOf(e(90, Category.FOOD)), nov, true, sent)
        assertEquals(listOf(80), due.map { it.percent })
        val after = CategoryBudgets.markSent(sent, due, nov)
        assertEquals(setOf("2026-11|FOOD|80"), after)
    }

    @Test fun backupCarriesCategoryBudgetsAndOldBackupsKeepThem() {
        val s = Settings(categoryBudgets = mapOf("FOOD" to 600000L))
        val json = BackupIo.toJson(emptyList(), s)
        val restored = BackupIo.parse(json).settingsPatch!!(Settings())
        assertEquals(mapOf("FOOD" to 600000L), restored.categoryBudgets)
        // A backup without the key leaves the current limits alone.
        val old = json.replace(Regex(""", "categoryBudgets": "[^"]*""""), "")
        val kept = BackupIo.parse(old).settingsPatch!!(Settings(categoryBudgets = mapOf("SHOPPING" to 1L)))
        assertEquals(mapOf("SHOPPING" to 1L), kept.categoryBudgets)
    }
}
