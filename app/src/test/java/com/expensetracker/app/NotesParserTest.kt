package com.expensetracker.app

import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.Categorizer
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.ParsedEntry
import com.expensetracker.app.data.QuickEntry
import com.expensetracker.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NotesParserTest {

    private val history: String =
        javaClass.classLoader!!.getResource("history.txt")!!.readText(Charsets.UTF_8)

    private val parsed = NotesParser.parse(history, 2026)
    private val entries = parsed.entries

    private fun find(date: String, note: String): ParsedEntry =
        entries.single { it.date == LocalDate.parse(date) && it.note == note }

    // ---------------- full history ----------------

    @Test fun noSkippedLines() {
        assertEquals(emptyList<Pair<Int, String>>(), parsed.skippedLines)
    }

    @Test fun entryCountAndTotals() {
        assertEquals(459, entries.size)
        assertEquals(27_563_940L, entries.filter { !it.isIncome }.sumOf { it.amountPaise })
        assertEquals(540_000L, entries.filter { it.isIncome }.sumOf { it.amountPaise })
    }

    @Test fun firstAndLastEntries() {
        val first = entries.first()
        assertEquals(LocalDate.of(2026, 3, 31), first.date)
        assertEquals(500_000L, first.amountPaise)
        assertEquals("Mammi", first.note)
        assertEquals(false, first.isIncome)

        val last = entries.last()
        assertEquals(LocalDate.of(2026, 11, 9), last.date)
        assertEquals(1_120_100L, last.amountPaise)
        assertEquals("Flight to BLR", last.note)
    }

    @Test fun tricky_amounts() {
        assertEquals(2_949_500L, find("2026-04-08", "Rent + Security").amountPaise)
        assertEquals(23_500L, find("2026-10-08", "Lunch + Mojito").amountPaise)
        assertEquals(5_755L, find("2026-04-26", "Domino's").amountPaise)
        assertEquals(55_585L, find("2026-04-30", "Train Ticket (to NDLS)").amountPaise)
        assertEquals(300_000L, find("2026-06-26", "Sent to Mammi").amountPaise)
        assertEquals(100L, find("2026-06-14", "Yippie").amountPaise)
    }

    @Test fun income_detected() {
        val income = entries.filter { it.isIncome }
        assertEquals(2, income.size)
        val mammi = find("2026-04-20", "Mammi")
        assertTrue(mammi.isIncome)
        assertEquals(500_000L, mammi.amountPaise)
        val richa = find("2026-05-02", "Richa")
        assertTrue(richa.isIncome)
        assertEquals(40_000L, richa.amountPaise)
    }

    @Test fun notes_keep_casing_and_emoji() {
        assertEquals(5_000L, find("2026-04-06", "Rapido (Baarish 🫩🫩)").amountPaise)
        find("2026-05-03", "??")
        find("2026-06-28", "Don't remember")
        find("2026-09-05", "Cheese Garlic Bread") // "5 September " header
    }

    @Test fun lineNumbers_point_at_source() {
        val lines = history.lines()
        for (e in entries) {
            assertTrue(lines[e.lineNumber - 1].contains(e.note))
        }
    }

    @Test fun guessFirstYear() {
        assertEquals(2026, NotesParser.guessFirstYear(history, LocalDate.of(2026, 11, 21)))
        // Before 9 Nov 2026 the history must belong to 2025.
        assertEquals(2025, NotesParser.guessFirstYear(history, LocalDate.of(2026, 11, 5)))
        assertEquals(2026, NotesParser.guessFirstYear("", LocalDate.of(2026, 11, 21)))
    }

    // ---------------- headers ----------------

    @Test fun header_variants_and_year_rollover() {
        val text = """
            30 December
            100 - A
            January 2
            200 - B
            3 feb 2030
            300 - C
            1st Mar
            +50 - D
        """.trimIndent()
        val r = NotesParser.parse(text, 2025)
        assertEquals(emptyList<Pair<Int, String>>(), r.skippedLines)
        assertEquals(
            listOf("2025-12-30", "2026-01-02", "2030-02-03", "2030-03-01"),
            r.entries.map { it.date.toString() },
        )
        assertTrue(r.entries.last().isIncome)
    }

    @Test fun lines_before_first_header_and_garbage_are_skipped() {
        val r = NotesParser.parse("EXPENSES\n100 - orphan\n1 April\nhello world\n50 - ok\n31 February\n", 2026)
        assertEquals(listOf(2 to "100 - orphan", 4 to "hello world", 6 to "31 February"), r.skippedLines)
        assertEquals(1, r.entries.size)
        assertEquals(LocalDate.of(2026, 4, 1), r.entries[0].date)
    }

    // ---------------- quick add ----------------

    @Test fun parseLine_cases() {
        assertEquals(QuickEntry(12_000, "Lunch", false), NotesParser.parseLine("120 - Lunch"))
        assertEquals(QuickEntry(12_000, "lunch", false), NotesParser.parseLine("120 lunch"))
        assertEquals(QuickEntry(12_000, "lunch", false), NotesParser.parseLine("lunch 120"))
        assertEquals(QuickEntry(500_000, "Mammi", true), NotesParser.parseLine("+5000 Mammi"))
        assertEquals(QuickEntry(500_000, "Mammi", true), NotesParser.parseLine("+5000 - Mammi"))
        assertEquals(QuickEntry(2_949_500, "Rent", false), NotesParser.parseLine("29,495 - Rent"))
        assertEquals(QuickEntry(23_500, "Lunch + Mojito", false), NotesParser.parseLine("115 + 120 - Lunch + Mojito"))
        assertEquals(QuickEntry(14_000, "21 eggs", false), NotesParser.parseLine("21 eggs 140"))
        assertEquals(QuickEntry(5_755, "Domino's", false), NotesParser.parseLine("Domino's 57.55"))
        assertEquals(QuickEntry(9_900, "", false), NotesParser.parseLine("99"))
        assertNull(NotesParser.parseLine("lunch"))
        assertNull(NotesParser.parseLine("   "))
    }

    // ---------------- categorizer ----------------

    @Test fun categorizer_spot_checks() {
        val expected = mapOf(
            "Lunch, Paneer Roll" to Category.FOOD,
            "Dinner, Paneer bowl" to Category.FOOD,
            "Lunch + Diet coke" to Category.FOOD,
            "Egg Roll" to Category.FOOD,
            "Metro Maggie" to Category.FOOD,
            "Train snacks" to Category.FOOD,
            "Ice cream tub" to Category.FOOD,
            "Domino's" to Category.FOOD,
            "Zepto ( cup noodles, Maggie masala, ice cream )" to Category.GROCERIES,
            "Groceries + cutting board" to Category.GROCERIES,
            "21 Eggs" to Category.GROCERIES,
            "10 Roti" to Category.GROCERIES,
            "Amul Cream, Cheese slices,Yippee Ramen" to Category.GROCERIES,
            "Rapido (Baarish 🫩🫩)" to Category.TRANSPORT,
            "Train Ticket ( to BMR )" to Category.TRANSPORT,
            "Metro ( INA )" to Category.TRANSPORT,
            "auto ( elyssian )" to Category.TRANSPORT,
            "Flight to BLR" to Category.TRANSPORT,
            "Rent + Security" to Category.RENT,
            "Hostel Fee" to Category.RENT,
            "Airtel Recharge" to Category.BILLS,
            "Mullvad" to Category.BILLS,
            "VPN" to Category.BILLS,
            "Claude Pro" to Category.BILLS,
            "CoC Gold Pass" to Category.BILLS,
            "Amul Protein Powder" to Category.HEALTH,
            "Creatine" to Category.HEALTH,
            "Mammi" to Category.PEOPLE,
            "Richa Earbuds" to Category.PEOPLE,
            "Mother's Day" to Category.PEOPLE,
            "BB Gift" to Category.PEOPLE,
            "2x5000 FDs" to Category.SAVINGS,
            "5000x2 RD" to Category.SAVINGS,
            "PoG" to Category.ENTERTAINMENT,
            "Ritish Pickle ball" to Category.ENTERTAINMENT,
            "Egg Boiler" to Category.SHOPPING,
            "Mr.DIY Stuff" to Category.SHOPPING,
            "Prestige Induction" to Category.SHOPPING,
            "T-Shirt" to Category.SHOPPING,
            "Earphones" to Category.SHOPPING,
            "Watch" to Category.SHOPPING,
            "Tip" to Category.OTHER,
            "??" to Category.OTHER,
        )
        val wrong = expected.filter { (note, cat) -> Categorizer.guess(note) != cat }
            .map { (note, cat) -> "$note: expected $cat, got ${Categorizer.guess(note)}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun most_history_gets_a_category() {
        val other = entries.filter { it.category == Category.OTHER }
        // A handful of genuinely vague notes ("Order", "Tip", "??") are fine.
        assertTrue("Too many OTHER: ${other.map { it.note }}", other.size <= 10)
    }

    // ---------------- backup / export ----------------

    private fun toExpenses(list: List<ParsedEntry>) = list.mapIndexed { i, p ->
        Expense(
            id = i + 1L, amountPaise = p.amountPaise, note = p.note, category = p.category,
            date = p.date, isIncome = p.isIncome, createdAt = p.lineNumber.toLong(),
        )
    }

    @Test fun notesText_round_trip() {
        val text = BackupIo.toNotesText(toExpenses(entries))
        assertTrue(text.contains("\n31 MARCH 2026\n5000 - Mammi\n"))
        assertTrue(text.contains("\n+5000 - Mammi\n"))
        assertTrue(text.contains("\n57.55 - Domino's\n"))
        // Year in the text wins over the firstYear argument.
        val again = NotesParser.parse(text, 1999)
        assertEquals(emptyList<Pair<Int, String>>(), again.skippedLines)
        assertEquals(
            entries.map { listOf(it.date, it.amountPaise, it.note, it.isIncome) },
            again.entries.map { listOf(it.date, it.amountPaise, it.note, it.isIncome) },
        )
    }

    @Test fun json_round_trip() {
        val expenses = toExpenses(entries) + Expense(
            amountPaise = 1, note = "quote \" backslash \\ newline \n tab \t ünïcödé 🫩",
            category = Category.OTHER, date = LocalDate.of(2026, 1, 1), createdAt = 5,
        )
        val settings = Settings(monthlyBudgetPaise = 2_500_000, excludeSavingsFromSpend = false,
            reminderEnabled = true, reminderHour = 21, reminderMinute = 30)
        val (back, s) = BackupIo.fromJson(BackupIo.toJson(expenses, settings))
        assertEquals(expenses.map { it.copy(id = 0) }, back)
        assertEquals(settings, s)
    }

    @Test fun json_empty_and_errors() {
        val (back, s) = BackupIo.fromJson(BackupIo.toJson(emptyList(), Settings()))
        assertEquals(emptyList<Expense>(), back)
        assertEquals(Settings(), s)
        val bad = runCatching { BackupIo.fromJson("{\"expenses\": [") }
        assertTrue(bad.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun csv_quoting() {
        val csv = BackupIo.toCsv(
            listOf(
                Expense(amountPaise = 11_000, note = "Lunch, Sandwich + Diet coke", category = Category.FOOD,
                    date = LocalDate.of(2026, 4, 1)),
                Expense(amountPaise = 40_000, note = "say \"hi\"", category = Category.PEOPLE,
                    date = LocalDate.of(2026, 5, 2), isIncome = true),
            ),
        )
        assertEquals(
            "date,amount,type,category,note,place\n" +
                "2026-04-01,110,expense,Food,\"Lunch, Sandwich + Diet coke\",\n" +
                "2026-05-02,400,income,Family & Gifts,\"say \"\"hi\"\"\",\n",
            csv,
        )
    }

    @Test fun formatRupees() {
        assertEquals("5000", BackupIo.formatRupees(500_000))
        assertEquals("57.55", BackupIo.formatRupees(5_755))
        assertEquals("5.50", BackupIo.formatRupees(550))
    }
}
