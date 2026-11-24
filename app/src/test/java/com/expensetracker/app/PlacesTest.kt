package com.expensetracker.app

import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Places
import com.expensetracker.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PlacesTest {
    private fun e(day: Int, rupees: Long, place: String?, month: Int = 4, income: Boolean = false) = Expense(
        amountPaise = rupees * 100, note = "x", category = Category.FOOD,
        date = LocalDate.of(2026, month, day), place = place, isIncome = income,
    )

    private val data = listOf(
        e(1, 100, "Gurugram"), e(2, 120, "Gurugram"), e(3, 90, "gurugram "), e(4, 80, null),
        e(10, 500, "Patiala"), e(11, 300, "Patiala"), e(12, 200, null), e(13, 150, "Patiala"),   // one trip despite untagged day
        e(20, 1000, "Jaipur"), e(21, 400, "Bikaner"),                                            // multi-city trip
        e(25, 5000, "Delhi", income = true),                                                     // income ignored
    )

    @Test fun homeCity_detectedAndOverridable() {
        assertEquals("Gurugram", Places.homeCity(data, Settings()))
        assertEquals("Bengaluru", Places.homeCity(data, Settings(homeCity = " Bengaluru ")))
        assertNull(Places.homeCity(listOf(e(1, 10, null)), Settings()))
    }

    @Test fun trips_groupAwayDays() {
        val trips = Places.trips(data, "Gurugram")
        assertEquals(2, trips.size)
        val (jaipur, patiala) = trips
        assertEquals(listOf("Jaipur", "Bikaner"), jaipur.cities)
        assertEquals("Jaipur → Bikaner", jaipur.title)
        assertEquals(140000L, jaipur.totalPaise)
        assertEquals(listOf("Patiala"), patiala.cities)
        assertEquals(LocalDate.of(2026, 4, 10), patiala.start)
        assertEquals(LocalDate.of(2026, 4, 13), patiala.end)
        assertEquals(4, patiala.days)
        assertEquals(95000L, patiala.totalPaise)   // the untagged ₹200 day isn't counted as trip spend
    }

    @Test fun totals_andRecent() {
        val t = Places.totals(data)
        assertEquals("Jaipur" to 100000L, t[0])
        assertEquals(95000L, t.first { it.first == "Patiala" }.second)
        assertEquals(31000L, t.first { it.first == "Gurugram" }.second)   // case/space-insensitive merge
        assertEquals(28000L, t.first { it.first == null }.second)
        assertEquals("Gurugram", Places.recent(data).first())
    }

    @Test fun backup_roundTripsPlace() {
        val list = listOf(e(1, 100, "Patiala").copy(lat = 30.34, lon = 76.38))
        val back = BackupIo.fromJson(BackupIo.toJson(list, Settings())).first.single()
        assertEquals("Patiala", back.place)
        assertEquals(30.34, back.lat!!, 1e-9)
        assertEquals(76.38, back.lon!!, 1e-9)
    }
}
