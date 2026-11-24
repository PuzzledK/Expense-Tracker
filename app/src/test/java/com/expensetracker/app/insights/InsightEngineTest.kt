package com.expensetracker.app.insights

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.SpendClassifier
import com.expensetracker.app.data.SpendInfo
import com.expensetracker.app.data.SpendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

class InsightEngineTest {

    private val history: List<Expense> = run {
        val text = javaClass.classLoader!!.getResource("history.txt")!!.readText(Charsets.UTF_8)
        NotesParser.parse(text, 2026).entries.mapIndexed { i, p ->
            Expense(id = i + 1L, amountPaise = p.amountPaise, note = p.note, category = p.category, date = p.date, isIncome = p.isIncome)
        }
    }
    private val info: Map<Long, SpendInfo> = classify(history)

    private fun classify(list: List<Expense>): Map<Long, SpendInfo> {
        val v = SpendClassifier.classify(list)
        return buildMap { list.forEachIndexed { i, e -> v[i]?.let { put(e.id, it) } } }
    }

    private fun build(today: LocalDate, list: List<Expense> = history, inf: Map<Long, SpendInfo> = info) =
        InsightEngine.build(list, inf, today)

    @Test fun printReport() {
        listOf(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 7, 20)).forEach { d ->
            println("=== $d")
            build(d).forEach { println("  [${"%.0f".format(it.score)}] ${it.period} ${it.emoji} ${it.title}\n        ${it.detail}") }
        }
        println(InsightEngine.weekly(history, info, LocalDate.of(2026, 10, 25))?.text())
    }

    @Test fun historyProducesRankedSensibleInsights() {
        val list = build(LocalDate.of(2026, 10, 31))
        assertTrue("expected several insights, got ${list.size}", list.size >= 4)
        assertEquals(list.sortedByDescending { it.score }, list)
        assertTrue(list.all { it.score >= InsightEngine.MIN_SCORE })
        assertEquals(list.size, list.map { it.id }.toSet().size)
        assertTrue(list.all { it.title.isNotBlank() && it.detail.isNotBlank() })
        // The Kijiji cafe / flight-type one-offs and the lunch habit are picked up.
        assertNotNull(list.find { it.type == "weekday_lunch" })
        assertTrue(InsightEngine.headlines(list).size in 1..2)
    }

    @Test fun typicalWeekdayLunchMatchesTheUsualOrder() {
        val lunch = build(LocalDate.of(2026, 10, 31)).first { it.type == "weekday_lunch" }
        // The usual is "142 - Lunch + Diet coke"; the median should land between ₹100 and ₹200.
        val rupees = Regex("₹([\\d,]+)").find(lunch.title)!!.groupValues[1].replace(",", "").toInt()
        assertTrue("lunch ₹$rupees", rupees in 100..200)
    }

    @Test fun categoryChangesRespectThresholds() {
        val today = LocalDate.of(2026, 10, 31)
        val cur = history.filter { it.date.monthValue == 10 }
        val prev = history.filter { it.date.monthValue == 9 }
        fun everyday(l: List<Expense>, c: Category) =
            l.filter { !it.isIncome && it.category == c && info[it.id]?.type == SpendType.EVERYDAY }.sumOf { it.amountPaise }
        build(today).filter { it.type == "category_change" }.forEach { ins ->
            val cat = Category.fromId(ins.id.substringAfter(':'))
            val a = everyday(cur, cat)
            val b = everyday(prev, cat)
            assertTrue("${ins.title}: diff too small", abs(a - b) >= 500_00)
            assertTrue("${ins.title}: % too small", abs(a - b) * 100 / b >= 25)
            assertEquals(if (a > b) InsightTone.BAD else InsightTone.GOOD, ins.tone)
        }
    }

    @Test fun nothingWithUnderTwoWeeksOfHistory() {
        assertEquals(emptyList<Insight>(), build(LocalDate.of(2026, 4, 10)))
        // Comparisons need the previous window to be covered: in mid-April there's no full March.
        val april = build(LocalDate.of(2026, 4, 20))
        assertTrue(april.none { it.type == "category_change" || it.type == "everyday_average" || it.type == "merchant_growth" })
    }

    @Test fun staleHistoryIsIgnored() {
        assertEquals(emptyList<Insight>(), build(LocalDate.of(2027, 3, 1)))
    }

    @Test fun futureEntriesAreIgnored() {
        val today = LocalDate.of(2026, 10, 31)
        val future = history + Expense(id = 99_999, amountPaise = 1_00_000_00, note = "Watch", category = Category.SHOPPING, date = today.plusDays(3))
        assertEquals(build(today).map { it.id to it.title }, build(today, future, info).map { it.id to it.title })
    }

    // ---------------------------------------------------------------- synthetic data

    private var nextId = 1L
    private fun e(date: LocalDate, rupees: Long, note: String, cat: Category = Category.FOOD, time: LocalTime? = null) =
        Expense(id = nextId++, amountPaise = rupees * 100, note = note, category = cat, date = date, time = time)

    /** Two identical months of steady everyday spending: Sep and Oct 2026. */
    private fun steady(months: List<Int> = listOf(9, 10), foodFactor: (Int) -> Double = { 1.0 }): List<Expense> =
        months.flatMap { m ->
            val ym = java.time.YearMonth.of(2026, m)
            (1..ym.lengthOfMonth()).flatMap { d ->
                val day = ym.atDay(d)
                listOf(
                    e(day, (150 * foodFactor(m)).toLong(), "Lunch"),
                    e(day, 60, "Rapido", Category.TRANSPORT),
                )
            }
        }

    private fun everydayInfo(list: List<Expense>) = list.associate { it.id to SpendInfo(SpendType.EVERYDAY, "") }

    @Test fun steadySpendingIsNotNews() {
        val list = steady()
        val ins = build(LocalDate.of(2026, 10, 31), list, everydayInfo(list))
        assertTrue(ins.joinToString { it.id }, ins.none {
            it.type in setOf("category_change", "everyday_average", "merchant_growth", "week_vs_usual", "no_spend_days", "weekend_vs_weekday")
        })
        // A habit and a streak are still worth a line.
        assertNotNull(ins.find { it.type == "streak" })
        assertTrue(ins.first { it.type == "streak" }.title.startsWith("61-day"))
    }

    @Test fun foodDoublingIsFlagged() {
        val list = steady { m -> if (m == 10) 2.0 else 1.0 }
        val ins = build(LocalDate.of(2026, 10, 31), list, everydayInfo(list))
        val food = ins.first { it.id == "category_change:FOOD" }
        assertEquals(InsightTone.BAD, food.tone)
        assertTrue(food.title, food.title.startsWith("Food up "))
        assertNull(ins.find { it.id == "category_change:TRANSPORT" })
        val avg = ins.first { it.type == "everyday_average" }
        assertEquals(InsightTone.BAD, avg.tone)
        // Lunch grew from ₹150 to ₹300 → the merchant insight names it.
        assertTrue(ins.first { it.type == "merchant_growth" }.title.startsWith("Lunch +"))
    }

    @Test fun smallChangesStayBelowThresholds() {
        // +10% food: ₹450 more over the month and below 25% → no category insight.
        val list = steady { m -> if (m == 10) 1.1 else 1.0 }
        val ins = build(LocalDate.of(2026, 10, 31), list, everydayInfo(list))
        assertNull(ins.find { it.type == "category_change" })
    }

    @Test fun lateNightShareNeedsTimes() {
        val base = steady()
        assertNull(build(LocalDate.of(2026, 10, 31), base, everydayInfo(base)).find { it.type == "late_night" })
        val timed = base.map { it.copy(time = if (it.note == "Lunch") LocalTime.of(13, 0) else LocalTime.of(23, 30)) }
        val late = build(LocalDate.of(2026, 10, 31), timed, everydayInfo(timed)).first { it.type == "late_night" }
        // Rapido ₹60 of ₹210 a day ≈ 29%.
        assertTrue(late.title, late.title.startsWith("29%"))
    }

    @Test fun noSpendDays() {
        // Spend only on weekends in Oct → 22 no-spend weekdays (runs of 5, so not a logging break).
        val list = steady(listOf(9)) + steady(listOf(10)).filter { it.date.dayOfWeek.value >= 6 }
        val ins = build(LocalDate.of(2026, 10, 31), list, everydayInfo(list))
        val nsd = ins.first { it.type == "no_spend_days" }
        assertTrue(nsd.title, nsd.title.startsWith("22 no-spend days"))
        // A week-long gap is a break in logging, not a week of no spending.
        val gap = steady().filterNot { it.date in DateSpan(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 16)) }
        assertNull(build(LocalDate.of(2026, 10, 31), gap, everydayInfo(gap)).find { it.type == "no_spend_days" })
    }

    @Test fun weekendsThatCostMore() {
        val list = steady().map { if (it.date.dayOfWeek.value >= 6 && it.note == "Lunch") it.copy(amountPaise = 600_00) else it }
        val w = build(LocalDate.of(2026, 10, 31), list, everydayInfo(list)).first { it.type == "weekend_vs_weekday" }
        assertTrue(w.title, w.title.startsWith("Weekends cost 3.1×"))
        assertEquals(7, w.bars.size)
    }

    @Test fun loggingBreaksAreNotReadAsZeroSpend() {
        // Nothing logged 3–8 Nov and 3 Jul–31 Aug in the real history.
        val nov = build(LocalDate.of(2026, 11, 9))
        assertTrue(nov.joinToString { it.title }, nov.none { it.type == "week_vs_usual" || it.type == "projection" })
        val jul = build(LocalDate.of(2026, 7, 20))
        assertTrue(jul.joinToString { it.title }, jul.none { it.type in setOf("everyday_average", "category_change", "week_vs_usual", "projection") })
        assertTrue(nov.none { "₹0 " in it.title } && jul.none { "₹0 " in it.title })
    }

    @Test fun weeklySummaryText() {
        val list = steady()
        val s = InsightEngine.weekly(list, everydayInfo(list), LocalDate.of(2026, 10, 25))!!
        assertEquals(LocalDate.of(2026, 10, 19), s.week.start)
        assertEquals(7 * 210_00L, s.everydayPaise)
        assertEquals(Category.FOOD, s.topCategory)
        assertEquals(0, s.vsUsualPct)
        assertEquals("Your week: ₹1,470 everyday spend, top: Food ₹1,050", s.text())
        assertNull(InsightEngine.weekly(list, everydayInfo(list), LocalDate.of(2027, 1, 10)))
        assertFalse(InsightEngine.weekly(history, info, LocalDate.of(2026, 10, 25))!!.text().isBlank())
    }

    @Test fun merchantKeys() {
        assertEquals("zepto", InsightEngine.merchantKey("Zepto stuff"))
        assertEquals("rapido", InsightEngine.merchantKey("Rapido to Metro"))
        assertEquals("coffee", InsightEngine.merchantKey("2x Coffee"))
        assertNull(InsightEngine.merchantKey("12"))
    }
}
