package com.expensetracker.app

import com.expensetracker.app.capture.MoneyMoves
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Ledger
import com.expensetracker.app.data.Settings
import com.expensetracker.app.quick.QuickLog
import com.expensetracker.app.ui.countsAsIncome
import com.expensetracker.app.ui.countsAsSpend
import com.expensetracker.app.ui.splitSpend
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class LedgerTest {
    @Before fun setUp() = CategoryRegistry.setForTesting(emptyList())
    @After fun tearDown() = CategoryRegistry.setForTesting(emptyList())

    private val d = LocalDate.of(2026, 11, 10)
    private fun spend(id: Long, rs: Long, note: String = "Shoes", date: LocalDate = d) =
        Expense(id = id, amountPaise = rs * 100, note = note, category = Category.SHOPPING, date = date, createdAt = 1000 + id)
    private fun income(id: Long, rs: Long, note: String = "Refund", refundOf: Long? = null, date: LocalDate = d.plusDays(3)) =
        Expense(id = id, amountPaise = rs * 100, note = note, category = Category.OTHER, date = date, isIncome = true, refundOf = refundOf, createdAt = 1000 + id)

    private fun spent(list: List<Expense>) = list.filter { it.countsAsSpend(true) }.sumOf { it.amountPaise }
    private fun earned(list: List<Expense>) = list.filter { it.isIncome }.sumOf { it.amountPaise }

    @Test fun fullRefundRemovesTheExpenseAndIsNotIncome() {
        val c = Ledger.counted(listOf(spend(1, 2000), spend(2, 300, "Lunch"), income(3, 2000, refundOf = 1)))
        assertEquals(300_00L, spent(c))
        assertEquals(0L, earned(c))
        assertTrue(c.none { it.id == 1L || it.id == 3L })
    }

    @Test fun partialRefundsNetAgainstTheExpenseInItsOwnMonth() {
        val old = spend(1, 2000, date = LocalDate.of(2026, 10, 30))
        val c = Ledger.counted(listOf(old, income(2, 500, refundOf = 1), income(3, 300, refundOf = 1)))
        val e = c.single()
        assertEquals(1200_00L, e.amountPaise)
        assertEquals(old.date, e.date)
        assertEquals(1200_00L, Ledger.counted(listOf(old)).sumOf { it.amountPaise } - 800_00L)
        assertEquals(800_00L, Ledger.refundedFor(listOf(old, income(2, 500, refundOf = 1), income(3, 300, refundOf = 1)), 1))
    }

    @Test fun refundBiggerThanExpenseNeverGoesNegative() {
        val c = Ledger.counted(listOf(spend(1, 100), income(2, 150, refundOf = 1)))
        assertTrue(c.isEmpty())
    }

    @Test fun refundOfDeletedExpenseStaysIncome() {
        val c = Ledger.counted(listOf(income(2, 150, refundOf = 99)))
        assertEquals(150_00L, earned(c))
    }

    @Test fun transfersCountAsNothing() {
        val t1 = spend(1, 5000, "To savings a/c").copy(isTransfer = true)
        val t2 = income(2, 5000, "From HDFC").copy(isTransfer = true)
        val list = listOf(t1, t2, spend(3, 200, "Chai"))
        val c = Ledger.counted(list)
        assertEquals(200_00L, spent(c))
        assertEquals(0L, earned(c))
        // Raw-list helpers agree.
        assertFalse(t1.countsAsSpend(true))
        assertFalse(t2.countsAsIncome())
        assertEquals(200_00L, splitSpend(list, emptyMap()).everyday)
        assertEquals(200_00L, QuickLog.summary(list, d).todayPaise)
    }

    @Test fun nothingSpecialReturnsSameList() {
        val list = listOf(spend(1, 10), income(2, 5))
        assertTrue(Ledger.counted(list) === list)
    }

    @Test fun candidatesPreferSameAmountThenSameMerchant() {
        val list = listOf(
            spend(1, 999, "Myntra jacket", d.minusDays(2)),
            spend(2, 450, "Swiggy", d.minusDays(1)),
            spend(3, 450, "Zomato", d.minusDays(5)),
            spend(4, 450, "Later", d.plusDays(1)), // after the refund: never a candidate
        )
        val c = Ledger.refundCandidates(list, 450_00, "Zomato refund", d)
        assertEquals(listOf(3L, 2L, 1L), c.map { it.id })
        assertEquals(3L, Ledger.bestRefundMatch(list, 450_00, "Zomato refund", d)?.id)
        assertEquals(1L, Ledger.bestRefundMatch(list, 120_00, "Refund from Myntra", d)?.id)
        assertNull(Ledger.bestRefundMatch(list, 120_00, "refund credited", d))
    }

    @Test fun backupRoundTripKeepsRefundLinkAndTransfer() {
        val list = listOf(spend(10, 2000), income(11, 500, refundOf = 10), spend(12, 70, "ATM").copy(isTransfer = true))
        val parsed = BackupIo.parse(BackupIo.toJson(list, Settings())).expenses
        assertEquals(1010L, parsed[1].refundOf) // target's createdAt until linked
        assertTrue(parsed[2].isTransfer)
        // Simulate insert with fresh ids, then link.
        val ids = listOf(100L, 101L, 102L)
        val stored = parsed.mapIndexed { i, e -> e.copy(id = ids[i], refundOf = null) }
        assertEquals(mapOf(101L to 100L), BackupIo.refundLinks(parsed, ids, stored))
    }

    @Test fun oldBackupWithoutNewFieldsStillLoads() {
        val json = """{"app":"ExpenseTracker","version":1,"expenses":[{"date":"2026-11-01","amountPaise":100,"note":"x","category":"FOOD","isIncome":true,"createdAt":5}]}"""
        val e = BackupIo.parse(json).expenses.single()
        assertNull(e.refundOf)
        assertFalse(e.isTransfer)
    }

    @Test fun capturePreMarksRefundsAndTransfers() {
        val all = listOf(spend(1, 450, "Zomato", d.minusDays(1)))
        val r = MoneyMoves.guess("Refund of Rs 450.00 credited to your a/c for Zomato order", "Zomato", 450_00, true, d, all)
        assertEquals(1L, r.refundOf?.id)
        assertFalse(r.transfer)
        val rev = MoneyMoves.guess("UPI txn reversed: Rs.450 credited back", "Unknown", 450_00, true, d, all)
        assertEquals(1L, rev.refundOf?.id)
        // An outgoing "refund" word never links.
        assertNull(MoneyMoves.guess("Refund request raised", "Zomato", 450_00, false, d, all).refundOf)
        assertTrue(MoneyMoves.guess("Rs 5000 self transfer to your SBI a/c", "Me", 5000_00, false, d, all).transfer)
        assertTrue(MoneyMoves.guess("Fund transfer to self successful", "x", 100, false, d, all).transfer)
        assertTrue(MoneyMoves.looksLikeSelfTransfer("paid", "Indra K", ownName = "indra k"))
        assertFalse(MoneyMoves.guess("Paid Rs 450 to Zomato", "Zomato", 450_00, false, d, all).transfer)
        assertNull(MoneyMoves.guess("Rs 450 received from Richa", "Richa", 450_00, true, d, all).refundOf)
    }
}
