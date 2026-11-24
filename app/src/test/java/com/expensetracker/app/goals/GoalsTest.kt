package com.expensetracker.app.goals

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalsTest {
    private val today = LocalDate.of(2026, 11, 25)
    private fun saving(id: Long, rupees: Long, date: LocalDate, goal: String? = "g1", income: Boolean = false) =
        Expense(id = id, amountPaise = rupees * 100, note = "RD", category = Category.SAVINGS, date = date, isIncome = income, goalId = goal)

    private val goa = Goal(
        id = "g1", name = "Goa trip", emoji = "🏖️", targetPaise = 60_000_00,
        deadline = LocalDate.of(2027, 5, 31), createdOn = LocalDate.of(2026, 8, 1),
    )

    @Test fun savedCountsLinkedEntriesStartingBalanceAdjustmentsAndWithdrawals() {
        val g = goa.copy(
            startingPaise = 10_000_00,
            adjustments = listOf(GoalAdjustment(today, 1_000_00, "interest"), GoalAdjustment(today, -500_00)),
        )
        val list = listOf(
            saving(1, 5_000, LocalDate.of(2026, 9, 1)),
            saving(2, 5_000, LocalDate.of(2026, 10, 1)),
            saving(3, 2_000, LocalDate.of(2026, 10, 15), income = true), // withdrawal
            saving(4, 9_999, LocalDate.of(2026, 10, 1), goal = "other"),
            saving(5, 9_999, LocalDate.of(2026, 10, 1), goal = null),
        )
        assertEquals((10_000 + 5_000 + 5_000 - 2_000 + 1_000 - 500) * 100L, GoalMath.saved(g, list))
        val pr = GoalMath.progress(g, list, today)
        assertEquals(60_000_00 - 18_500_00L, pr.remainingPaise)
        assertEquals(18_500f / 60_000f, pr.fraction, 0.001f)
    }

    @Test fun projectionUsesAverageMonthlyRate() {
        // ₹5,000 on the 1st of Sep, Oct, Nov → 15,000 over ~3 months (1 Sep..25 Nov) ≈ ₹5,300/month.
        val list = (9..11).map { m -> saving(m.toLong(), 5_000, LocalDate.of(2026, m, 1)) }
        val pr = GoalMath.progress(goa.copy(createdOn = LocalDate.of(2026, 9, 1)), list, today)
        val rate = pr.monthlyRatePaise!!
        assertTrue("rate $rate", rate in 5_000_00L..5_700_00L)
        assertEquals(false, pr.rateFromPlan)
        // 45,000 to go at ~5.3k/month ≈ 8.5 months → around Aug 2027, after the May deadline.
        val projected = pr.projectedDate!!
        assertTrue("projected $projected", projected.isAfter(LocalDate.of(2027, 7, 1)) && projected.isBefore(LocalDate.of(2027, 10, 1)))
        assertEquals(GoalStatus.BEHIND, pr.status)
        // 45,000 over ~6.2 months to the deadline ≈ ₹7,300/month needed.
        assertTrue("needed ${pr.neededPerMonthPaise}", pr.neededPerMonthPaise!! in 7_000_00L..7_600_00L)
    }

    @Test fun onTrackWhenPaceBeatsDeadline() {
        val list = (9..11).map { m -> saving(m.toLong(), 10_000, LocalDate.of(2026, m, 1)) }
        val pr = GoalMath.progress(goa.copy(createdOn = LocalDate.of(2026, 9, 1)), list, today)
        assertEquals(GoalStatus.ON_TRACK, pr.status)
    }

    @Test fun planIsUsedForANewGoal() {
        val g = goa.copy(createdOn = today.minusDays(5), monthlyPlanPaise = 12_000_00)
        val pr = GoalMath.progress(g, listOf(saving(1, 3_000, today.minusDays(2))), today)
        assertEquals(12_000_00L, pr.monthlyRatePaise)
        assertTrue(pr.rateFromPlan)
        // 57,000 at 12k/month ≈ 4.75 months → mid-April, before the end-May deadline.
        assertEquals(GoalStatus.ON_TRACK, pr.status)
    }

    @Test fun doneAndNotStartedAndOverdue() {
        val done = GoalMath.progress(goa.copy(startingPaise = 70_000_00), emptyList(), today)
        assertEquals(GoalStatus.DONE, done.status)
        assertEquals(1f, done.fraction)
        assertNull(done.projectedDate)
        assertNull(done.neededPerMonthPaise)

        val fresh = GoalMath.progress(goa.copy(deadline = null, createdOn = today), emptyList(), today)
        assertEquals(GoalStatus.NOT_STARTED, fresh.status)
        assertNull(fresh.monthlyRatePaise)

        val late = GoalMath.progress(goa.copy(deadline = today.minusDays(1), startingPaise = 1_000_00), emptyList(), today)
        assertEquals(GoalStatus.OVERDUE, late.status)
    }

    @Test fun withdrawalsBeyondSavingsDoNotProduceANegativeRate() {
        val list = listOf(saving(1, 1_000, LocalDate.of(2026, 9, 1)), saving(2, 3_000, LocalDate.of(2026, 10, 1), income = true))
        val pr = GoalMath.progress(goa.copy(deadline = null), list, today)
        assertEquals(-2_000_00L, pr.savedPaise)
        assertEquals(0f, pr.fraction)
        assertNull(pr.monthlyRatePaise)
    }

    @Test fun codecRoundTrip() {
        val goals = listOf(
            goa.copy(
                name = "Goa\ttrip, with \"quotes\" & 100%\nnewline", monthlyPlanPaise = 5_000_00, startingPaise = 10_000_00,
                adjustments = listOf(GoalAdjustment(today, 1_234_56, "interest\tQ3"), GoalAdjustment(today.minusDays(3), -500_00)),
            ),
            Goal(id = "g2", name = "Emergency fund", emoji = "🛟", targetPaise = 3_00_000_00, color = 0xFF1E88E5, archived = true, createdOn = today),
        )
        val text = GoalCodec.encode(goals)
        assertEquals(goals, GoalCodec.decode(text))
        // Order is preserved and blank/garbage input is harmless.
        assertEquals(listOf("g1", "g2"), GoalCodec.decode(text).map { it.id })
        assertEquals(emptyList<Goal>(), GoalCodec.decode(""))
        assertEquals(1, GoalCodec.decode(text.lines().take(3).joinToString("\n") + "\nG\tbroken\nX\tjunk").size)
    }
}
