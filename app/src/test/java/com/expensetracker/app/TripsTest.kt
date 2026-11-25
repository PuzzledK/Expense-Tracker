package com.expensetracker.app

import com.expensetracker.app.data.ActiveTrip
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendType
import com.expensetracker.app.data.Trips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TripsTest {
    private val nov10 = LocalDate.of(2026, 11, 10)
    private fun e(rs: Long, c: Category = Category.FOOD, d: LocalDate = nov10, trip: String? = null, id: Long = 0, income: Boolean = false) =
        Expense(id = id, amountPaise = rs * 100, note = "lunch", category = c, date = d, isIncome = income, createdAt = id + 1, trip = trip)

    @Test fun tripSpendIsOneOffAndOutOfTheBaseline() {
        // 20 ordinary ₹200 lunches plus one ₹250 lunch on a trip.
        val list = (1..20L).map { e(200, id = it, d = nov10.minusDays(it)) } + e(250, id = 99, trip = "Goa")
        val v = SpendClassifier.classify(list)
        assertEquals(SpendType.ONE_OFF, v.last()!!.type)
        assertEquals("Trip: Goa", v.last()!!.reason)
        assertEquals(SpendType.EVERYDAY, v.first()!!.type)
    }

    @Test fun yourOwnChoiceBeatsTheTrip() {
        val v = SpendClassifier.classify(listOf(e(250, trip = "Goa").copy(spendOverride = SpendType.EVERYDAY)))
        assertEquals(SpendType.EVERYDAY, v.single()!!.type)
    }

    @Test fun backupKeepsTheTripAndOldBackupsLoad() {
        val json = BackupIo.toJson(listOf(e(500, trip = "Goa 2026", id = 1), e(100, id = 2)), Settings())
        val back = BackupIo.fromJson(json).first
        assertEquals(listOf("Goa 2026", null), back.map { it.trip })
        val old = json.replace(Regex(""", "trip": "[^"]*""""), "")
        assertNull(BackupIo.fromJson(old).first.first().trip)
    }

    @Test fun totalsNetRefundsAndSkipTransfers() {
        val hotel = e(4000, Category.SHOPPING, trip = "Goa", id = 1)
        val refund = e(1000, income = true, id = 2).copy(refundOf = 1)
        val cab = e(600, Category.TRANSPORT, trip = "Goa", id = 3)
        val move = e(9000, trip = "Goa", id = 4).copy(isTransfer = true)
        val s = Trips.summaries(listOf(hotel, refund, cab, move, e(50, id = 5))).single()
        assertEquals("Goa", s.name)
        assertEquals(3600_00L, s.totalPaise)
        assertEquals(2, s.count)
        assertEquals(Category.SHOPPING to 3000_00L, s.byCategory.first())
    }

    @Test fun tripModeTagsNewSpendsOnly() {
        val goa = ActiveTrip("Goa", start = nov10, end = nov10.plusDays(2))
        assertEquals("Goa", Trips.autoTag(e(100), goa, nov10).trip)
        assertNull(Trips.autoTag(e(100, income = true), goa, nov10).trip)
        assertNull(Trips.autoTag(e(100).copy(isTransfer = true), goa, nov10).trip)
        assertEquals("Work", Trips.autoTag(e(100, trip = "Work"), goa, nov10).trip)
        assertNull(Trips.autoTag(e(100, d = nov10.minusDays(1)), goa, nov10).trip) // before it started
        assertNull(Trips.autoTag(e(100, d = nov10.plusDays(3)), goa, nov10.plusDays(3)).trip) // over
        assertNull(Trips.autoTag(e(100), null, nov10).trip)
    }

    @Test fun recentRenameAndClear() {
        val list = listOf(e(1, trip = "A", d = nov10.minusDays(9)), e(2, trip = "B", d = nov10), e(3, trip = "A", d = nov10.minusDays(5)))
        assertEquals(listOf("B", "A"), Trips.recent(list))
        assertEquals(listOf("B", "B"), Trips.renamed(list, "A", "B").map { it.trip })
        assertEquals(listOf(null), Trips.cleared(list, "B").map { it.trip })
        assertEquals("Goa 2026", Trips.cleanName("  Goa   2026 "))
        assertNull(Trips.cleanName("   "))
    }
}
