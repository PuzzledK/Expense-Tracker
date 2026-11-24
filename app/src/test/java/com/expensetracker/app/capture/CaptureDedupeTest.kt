package com.expensetracker.app.capture

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class CaptureDedupeTest {

    private val zone = ZoneOffset.ofHoursMinutes(5, 30)
    private val base = LocalDateTime.of(2026, 11, 24, 13, 5).atZone(zone).toInstant().toEpochMilli()

    private fun key(id: Long, amount: Long = 14200, dtMin: Double = 0.0, payee: String = "Zomato", pkg: String = "gpay", fp: String = "fp$id", income: Boolean = false) =
        DraftKey(id, amount, base + (dtMin * 60_000).toLong(), income, payee, pkg, fp)

    @Test fun mergesUpiAppAndBankSmsWithinWindow() {
        val gpay = key(1, pkg = "gpay", payee = "Zomato")
        val sms = key(2, dtMin = 1.5, pkg = "sms", payee = "Zomato Ltd")
        assertEquals(1L, CaptureDedupe.findDuplicate(listOf(gpay), sms)?.id)
    }

    @Test fun mergesAcrossAppsEvenWithDifferentNames() {
        val gpay = key(1, pkg = "gpay", payee = "Rapido")
        val sms = key(2, dtMin = 2.0, pkg = "sms", payee = "Roppen Transportation")
        assertEquals(1L, CaptureDedupe.findDuplicate(listOf(gpay), sms)?.id)
    }

    @Test fun doesNotMergeOutsideWindow() {
        assertNull(CaptureDedupe.findDuplicate(listOf(key(1)), key(2, dtMin = 3.5, pkg = "sms")))
    }

    @Test fun doesNotMergeDifferentAmountOrDirection() {
        assertNull(CaptureDedupe.findDuplicate(listOf(key(1)), key(2, amount = 14300, pkg = "sms")))
        assertNull(CaptureDedupe.findDuplicate(listOf(key(1)), key(2, income = true, pkg = "sms")))
    }

    @Test fun sameAppDifferentPayeesAreTwoPayments() {
        val a = key(1, amount = 2000, payee = "Chai Point")
        val b = key(2, amount = 2000, dtMin = 1.0, payee = "Anu Sharma")
        assertNull(CaptureDedupe.findDuplicate(listOf(a), b))
    }

    @Test fun repostedNotificationMatchesByFingerprintAnyTime() {
        val old = key(1, fp = "same")
        val repost = key(2, dtMin = 600.0, fp = "same")
        assertEquals(1L, CaptureDedupe.findDuplicate(listOf(old), repost)?.id)
    }

    @Test fun prefersCompatiblePayeeOverCloserTime() {
        val other = key(1, pkg = "gpay", payee = "Anu Sharma", dtMin = 0.5)
        val zomato = key(3, pkg = "gpay", payee = "Zomato", dtMin = -1.0)
        val sms = key(2, pkg = "sms", payee = "ZOMATO")
        assertEquals(3L, CaptureDedupe.findDuplicate(listOf(other, zomato), sms)?.id)
    }

    @Test fun payeeCompatibility() {
        assertTrue(CaptureDedupe.payeesCompatible("Zomato", "ZOMATO LTD"))
        assertTrue(CaptureDedupe.payeesCompatible("Unknown", "Swiggy"))
        assertTrue(CaptureDedupe.payeesCompatible("Swiggy Stores", "swiggy"))
        assertTrue(CaptureDedupe.payeesCompatible("Anu Sharma", "Anu"))
        assertFalse(CaptureDedupe.payeesCompatible("Zomato", "Swiggy"))
    }

    @Test fun fingerprintIsStableAndWhitespaceInsensitive() {
        val a = CaptureDedupe.fingerprint("pkg", "Title", "You paid  ₹66 to Rapido")
        val b = CaptureDedupe.fingerprint("pkg", "title", "You paid ₹66 to Rapido")
        assertEquals(a, b)
        assertNotEquals(a, CaptureDedupe.fingerprint("pkg", "Title", "You paid ₹67 to Rapido"))
    }

    private fun expense(amount: Long = 14200, createdAt: Long = base, time: LocalTime? = null, date: LocalDate = LocalDate.of(2026, 11, 24), income: Boolean = false) =
        Expense(amountPaise = amount, note = "Lunch", category = Category.FOOD, date = date, isIncome = income, createdAt = createdAt, time = time)

    @Test fun manualEntryLoggedWithinTenMinutesMatches() {
        val logged = expense(createdAt = base + 8 * 60_000)
        assertTrue(CaptureDedupe.matchesManual(listOf(logged), 14200, false, base, zone))
    }

    @Test fun manualEntryWithMatchingClockTimeMatches() {
        val loggedLater = expense(createdAt = base + 5 * 3_600_000L, time = LocalTime.of(13, 0))
        assertTrue(CaptureDedupe.matchesManual(listOf(loggedLater), 14200, false, base, zone))
    }

    @Test fun manualEntryOtherwiseDoesNotMatch() {
        val tooLate = expense(createdAt = base + 30 * 60_000)
        val otherDay = expense(date = LocalDate.of(2026, 11, 23))
        val otherAmount = expense(amount = 14300)
        val income = expense(income = true)
        assertFalse(CaptureDedupe.matchesManual(listOf(tooLate, otherDay, otherAmount, income), 14200, false, base, zone))
    }
}
