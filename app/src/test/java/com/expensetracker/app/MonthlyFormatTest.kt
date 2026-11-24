package com.expensetracker.app

import com.expensetracker.app.data.NotesParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The month-only "104-- cab" format with dashed separators and an untitled block on top. */
class MonthlyFormatTest {
    private val text = javaClass.classLoader!!.getResource("monthly_format.txt")!!.readText(Charsets.UTF_8)

    @Test fun withoutUndatedMonth_topBlockIsSkippedButCounted() {
        val r = NotesParser.parse(text, 2026)
        assertEquals(45, r.undatedEntries)
        assertEquals(YearMonth.of(2026, 9), r.suggestedUndatedMonth)
        assertEquals(45, r.skippedLines.size)          // only the undated lines; separators ignored
        assertEquals(69 + 22, r.entries.size)          // October + November
        assertTrue(r.entries.all { it.date.dayOfMonth == 1 })
    }

    @Test fun withUndatedMonth_everythingImports() {
        val r = NotesParser.parse(text, 2026, YearMonth.of(2026, 9))
        assertEquals(0, r.skippedLines.size)
        assertEquals(45 + 69 + 22, r.entries.size)
        val byMonth = r.entries.groupBy { it.date }
        assertEquals(setOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)), byMonth.keys)
        val first = r.entries.first()
        assertEquals(10400L, first.amountPaise)
        assertEquals("cab to ptp", first.note)          // no leftover "-" from "--"
        val oct = byMonth.getValue(LocalDate.of(2026, 10, 1))
        assertEquals("Cab", oct.first().note)
        assertTrue(oct.any { it.amountPaise == 1_800_000L && it.note == "pg rent" })
        assertTrue(oct.any { it.amountPaise == 100L && it.note == "hoppr" })
        assertEquals(LocalDate.of(2026, 11, 1), r.entries.last().date)
        assertEquals("gynec consultation fee", r.entries.last().note)
    }

    @Test fun guessYearAndQuickLine() {
        assertEquals(2026, NotesParser.guessFirstYear(text, LocalDate.of(2026, 11, 21)))
        assertEquals("cab", NotesParser.parseLine("104-- cab")!!.note)
        assertEquals("Sent to Mammi", NotesParser.parseLine("3000 -Sent to Mammi")!!.note)
    }
}
