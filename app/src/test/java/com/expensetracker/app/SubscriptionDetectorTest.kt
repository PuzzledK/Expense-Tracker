package com.expensetracker.app

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.SubscriptionDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionDetectorTest {
    private var nextId = 1L
    private fun e(date: String, rupees: Long, note: String, cat: Category = Category.BILLS, income: Boolean = false) =
        Expense(id = nextId++, amountPaise = rupees * 100, note = note, category = cat, date = LocalDate.parse(date), isIncome = income)
    private fun d(s: String) = LocalDate.parse(s)

    private val netflix = listOf(
        e("2026-08-05", 199, "Netflix"), e("2026-09-05", 199, "Netflix"),
        e("2026-10-05", 199, "Netflix"), e("2026-11-05", 249, "netflix"),
    )

    @Test fun findsMonthlyPayments() {
        val subs = SubscriptionDetector.detect(netflix, d("2026-11-20"))
        assertEquals(1, subs.size)
        val s = subs[0]
        assertEquals("netflix", s.key)
        assertEquals("netflix", s.name)
        assertEquals(249_00L, s.amountPaise)
        assertEquals(d("2026-11-05"), s.lastPaid)
        assertEquals(d("2026-12-05"), s.nextDue)
        assertEquals(4, s.months)
    }

    @Test fun flagsPriceChange() {
        val s = SubscriptionDetector.detect(netflix, d("2026-11-20")).single()
        assertTrue(s.priceChanged)
        assertEquals(199_00L, s.previousAmountPaise)
    }

    @Test fun steadyPriceIsNotAChange() {
        val list = listOf(e("2026-09-01", 499, "Gym", Category.SHOPPING), e("2026-10-01", 499, "Gym", Category.SHOPPING), e("2026-11-01", 499, "Gym", Category.SHOPPING))
        assertNull(SubscriptionDetector.detect(list, d("2026-11-10")).single().previousAmountPaise)
        assertFalse(SubscriptionDetector.priceChanged(499_00, 499_50)) // 50 paise: rounding, not a change
        assertTrue(SubscriptionDetector.priceChanged(199_00, 249_00))
    }

    @Test fun needsThreeMonths() {
        assertTrue(SubscriptionDetector.detect(netflix.take(2), d("2026-09-20")).isEmpty())
    }

    @Test fun consumablesIncomeAndTinyAmountsAreIgnored() {
        val list = (8..11).flatMap { m ->
            listOf(
                e("2026-%02d-03".format(m), 300, "Zepto", Category.GROCERIES),
                e("2026-%02d-04".format(m), 50000, "Salary", Category.BILLS, income = true),
                e("2026-%02d-05".format(m), 49, "Cloud storage"),
            )
        }
        assertTrue(SubscriptionDetector.detect(list, d("2026-11-20")).isEmpty())
    }

    @Test fun staleOnesAreTreatedAsCancelled() {
        assertTrue(SubscriptionDetector.detect(netflix, d("2027-02-01")).isEmpty())
    }

    @Test fun hiddenOnesAreLeftOut() {
        assertTrue(SubscriptionDetector.detect(netflix, d("2026-11-20"), hidden = setOf("netflix")).isEmpty())
    }

    @Test fun sortedBySoonestDue() {
        val rent = listOf(e("2026-09-01", 15000, "Rent", Category.RENT), e("2026-10-01", 15000, "Rent", Category.RENT), e("2026-11-01", 15000, "Rent", Category.RENT))
        val subs = SubscriptionDetector.detect(netflix + rent, d("2026-11-20"))
        assertEquals(listOf("rent", "netflix"), subs.map { it.key })
        assertEquals(15000_00L + 249_00L, SubscriptionDetector.monthlyTotal(subs))
    }

    // ── Next due date ──

    @Test fun monthEndClampsToShortMonths() {
        // Paid on the 31st → 30 Nov, then back to 31 Dec.
        assertEquals(d("2026-11-30"), SubscriptionDetector.nextDue(listOf(d("2026-08-31"), d("2026-10-31")), d("2026-11-02")))
        assertEquals(d("2026-12-31"), SubscriptionDetector.nextDue(listOf(d("2026-10-31"), d("2026-11-30")), d("2026-12-02")))
        assertEquals(d("2027-02-28"), SubscriptionDetector.nextDue(listOf(d("2026-12-31"), d("2027-01-31")), d("2027-02-02")))
    }

    @Test fun thirtiethStaysOnThirtieth() {
        // 30th of a 31-day month is not month-end: stay on the 30th, clamped in Feb.
        assertEquals(d("2027-02-28"), SubscriptionDetector.nextDue(listOf(d("2026-12-30"), d("2027-01-30")), d("2027-02-01")))
    }

    @Test fun usesTheUsualDayNotALatePayment() {
        // Usually the 5th; paid late on the 9th this month → next is still the 5th.
        assertEquals(d("2026-12-05"), SubscriptionDetector.nextDue(listOf(d("2026-09-05"), d("2026-10-05"), d("2026-11-09")), d("2026-11-20")))
        assertEquals(5, SubscriptionDetector.anchorDay(listOf(d("2026-09-05"), d("2026-10-05"), d("2026-11-09"))))
    }

    @Test fun overdueShowsForAWeekThenRollsOn() {
        val dates = listOf(d("2026-09-05"), d("2026-10-05"))
        assertEquals(d("2026-11-05"), SubscriptionDetector.nextDue(dates, d("2026-11-10"))) // 5 days overdue
        assertEquals(d("2026-12-05"), SubscriptionDetector.nextDue(dates, d("2026-11-20"))) // skipped → next month
    }

    @Test fun dueTomorrowForReminder() {
        val subs = SubscriptionDetector.detect(netflix, d("2026-12-04"))
        assertEquals(1, SubscriptionDetector.dueTomorrow(subs, d("2026-12-04")).size)
        assertTrue(SubscriptionDetector.dueTomorrow(subs, d("2026-12-03")).isEmpty())
    }
}
