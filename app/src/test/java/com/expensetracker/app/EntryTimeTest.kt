package com.expensetracker.app

import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The optional time-of-day on entries (DB v4 `time_min`, backup field "time"). */
class EntryTimeTest {
    private fun e(time: LocalTime?) = Expense(
        amountPaise = 12_000, note = "Lunch", category = Category.FOOD,
        date = LocalDate.of(2026, 11, 24), time = time,
    )

    private fun roundTrip(list: List<Expense>) = BackupIo.fromJson(BackupIo.toJson(list, Settings())).first

    @Test fun backup_keepsTime_includingEdgesOfDay() {
        val times = listOf(LocalTime.of(0, 0), LocalTime.of(9, 5), LocalTime.of(13, 45), LocalTime.of(23, 59))
        val back = roundTrip(times.map { e(it) })
        assertEquals(times, back.map { it.time })
    }

    @Test fun backup_untimedStaysUntimed() {
        val json = BackupIo.toJson(listOf(e(null)), Settings())
        assertTrue("no time key written for untimed entries", !json.contains("\"time\""))
        assertNull(roundTrip(listOf(e(null))).single().time)
    }

    @Test fun backup_secondsAreDropped() {
        // Times are stored to the minute; a stray seconds value must not leak into the file.
        val json = BackupIo.toJson(listOf(e(LocalTime.of(8, 7, 42))), Settings())
        assertTrue(json, json.contains("\"time\": \"08:07\""))
    }

    @Test fun restore_toleratesOldOrBadTimeValues() {
        fun restore(timeJson: String) = BackupIo.fromJson(
            """{"expenses": [{"date": "2026-11-24", "amountPaise": 100, "note": "x", "category": "FOOD"$timeJson}]}""",
        ).first.single().time
        assertNull(restore(""))                          // backups from before this change
        assertNull(restore(""", "time": "25:99""""))      // garbage → no time, not a crash
        assertNull(restore(""", "time": 930"""))          // wrong type → no time
        assertEquals(LocalTime.of(21, 30), restore(""", "time": "21:30""""))
    }

    @Test fun csvAndNotesExport_unaffected() {
        val csv = BackupIo.toCsv(listOf(e(LocalTime.of(13, 0))))
        assertEquals("date,amount,type,category,note,place", csv.lines().first())
        assertTrue(BackupIo.toNotesText(listOf(e(LocalTime.of(13, 0)))).contains("120 - Lunch"))
    }
}
