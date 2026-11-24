package com.expensetracker.app.data

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

/**
 * Parses the notes-app expense format:
 *
 * ```
 * EXPENSES
 *
 * 31 MARCH
 * 5000 - Mammi
 * 115 + 120 - Lunch + Mojito
 * +5000 - Mammi        (income)
 * ```
 *
 * and the month-only format (no days; every entry in a month goes on the 1st):
 *
 * ```
 * OCTOBER 2026
 * 186-- Cab
 * ---------------
 * ```
 */
object NotesParser {

    private val MONTHS: Map<String, Int> = buildMap {
        val names = listOf(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december",
        )
        names.forEachIndexed { i, n ->
            put(n, i + 1)
            put(n.take(3), i + 1)
        }
        put("sept", 9)
    }

    private const val NUM = """\d[\d,]*(?:\.\d+)?"""

    /** "31 MARCH", "5 September ", "1 April 2025", "1st April". */
    private val DAY_MONTH = Regex("""^(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\.?(?:,?\s+(\d{4}))?$""")

    /** "April 1", "April 1, 2025". */
    private val MONTH_DAY = Regex("""^([A-Za-z]+)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""")

    /** "OCTOBER 2026", "October", "Oct 2026:" — a whole month with no day. */
    private val MONTH_ONLY = Regex("""^([A-Za-z]+)\.?(?:,?\s+(\d{4}))?\s*:?$""")

    /** Divider lines like "-----------" or "=====". */
    private val SEPARATOR = Regex("""^[-=_*~—–.\s]{3,}$""")

    /** "120 - Lunch", "+5000 - Mammi", "29,495 - Rent", "115 + 120 - Lunch", "3000 -Sent to Mammi", "104-- cab". */
    private val DASH_LINE = Regex("""^(\+)?\s*($NUM(?:\s*\+\s*$NUM)*)\s*[-–—:]+\s*(.*)$""")

    /** "120 Lunch" (amount first, no dash). */
    private val LEADING = Regex("""^(\+)?\s*($NUM)\s+(.*\S)$""")

    /** "Lunch 120" (amount last). */
    private val TRAILING = Regex("""^(.*\S)\s+(\+)?\s*($NUM)$""")

    /** "120" alone. */
    private val AMOUNT_ONLY = Regex("""^(\+)?\s*($NUM)$""")

    private val TITLE = Regex("""^expenses?\s*:?$""", RegexOption.IGNORE_CASE)

    /**
     * [undatedMonth]: where to put expense lines that come before any header
     * (dated the 1st of that month). null = report them as skipped.
     */
    fun parse(text: String, firstYear: Int, undatedMonth: YearMonth? = null): ParseResult {
        val entries = mutableListOf<ParsedEntry>()
        val skipped = mutableListOf<Pair<Int, String>>()
        val undated = mutableListOf<Pair<Int, QuickEntry>>()
        var firstHeader: LocalDate? = null
        var year = firstYear
        var prevMonth = 0
        var current: LocalDate? = null

        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim()
            if (line.isEmpty() || TITLE.matches(line) || SEPARATOR.matches(line)) return@forEachIndexed

            val header = parseHeader(line)
            if (header != null) {
                val (day, month, explicitYear) = header
                if (explicitYear != null) {
                    year = explicitYear
                } else if (prevMonth != 0 && month < prevMonth) {
                    year++
                }
                val date = try {
                    LocalDate.of(year, month, day)
                } catch (_: DateTimeException) {
                    null
                }
                if (date == null) {
                    skipped += lineNo to line
                } else {
                    current = date
                    prevMonth = month
                    if (firstHeader == null) firstHeader = date
                }
                return@forEachIndexed
            }

            val entry = parseDashLine(line) ?: parseLooseLine(line)
            val date = current
            if (entry != null && date == null) {
                undated += lineNo to entry
            } else if (entry == null || date == null) {
                skipped += lineNo to line
            } else {
                entries += ParsedEntry(
                    date = date,
                    amountPaise = entry.amountPaise,
                    note = entry.note,
                    isIncome = entry.isIncome,
                    category = Categorizer.guess(entry.note),
                    lineNumber = lineNo,
                )
            }
        }
        val suggested = firstHeader?.let { YearMonth.from(it).minusMonths(1) }
        if (undatedMonth != null) {
            val day = undatedMonth.atDay(1)
            entries.addAll(0, undated.map { (lineNo, e) ->
                ParsedEntry(day, e.amountPaise, e.note, e.isIncome, Categorizer.guess(e.note), lineNo)
            })
        } else {
            val lines = text.lines()
            undated.forEach { (lineNo, _) -> skipped += lineNo to lines[lineNo - 1].trim() }
            skipped.sortBy { it.first }
        }
        return ParseResult(entries, skipped, undated.size, suggested)
    }

    /** Picks the year of the first date header so that the last entry is not in the future. */
    fun guessFirstYear(text: String, today: LocalDate): Int {
        for (y in today.year downTo today.year - 10) {
            val last = parse(text, y).entries.maxOfOrNull { it.date } ?: return today.year
            if (!last.isAfter(today)) return y
        }
        return today.year
    }

    /** Parses one quick-add line. Returns null when no amount can be found. */
    fun parseLine(line: String): QuickEntry? {
        val t = line.trim()
        if (t.isEmpty()) return null
        return parseDashLine(t) ?: parseLooseLine(t)
    }

    // --- internals ---

    private fun parseHeader(line: String): Triple<Int, Int, Int?>? {
        DAY_MONTH.matchEntire(line)?.let { m ->
            val month = MONTHS[m.groupValues[2].lowercase()] ?: return null
            return Triple(m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull())
        }
        MONTH_DAY.matchEntire(line)?.let { m ->
            val month = MONTHS[m.groupValues[1].lowercase()] ?: return null
            return Triple(m.groupValues[2].toInt(), month, m.groupValues[3].toIntOrNull())
        }
        MONTH_ONLY.matchEntire(line)?.let { m ->
            val month = MONTHS[m.groupValues[1].lowercase()] ?: return null
            return Triple(1, month, m.groupValues[2].toIntOrNull())
        }
        return null
    }

    private fun parseDashLine(line: String): QuickEntry? {
        val m = DASH_LINE.matchEntire(line) ?: return null
        val total = m.groupValues[2].split('+').sumOf { toPaise(it) ?: return null }
        return QuickEntry(total, m.groupValues[3].trim(), m.groupValues[1].isNotEmpty())
    }

    private fun parseLooseLine(line: String): QuickEntry? {
        // Prefer a trailing amount ("21 eggs 140" → ₹140 "21 eggs"), else a leading one.
        TRAILING.matchEntire(line)?.let { m ->
            val note = m.groupValues[1].trim()
            if (note.any { it.isLetter() }) {
                val paise = toPaise(m.groupValues[3]) ?: return@let
                val plus = m.groupValues[2].isNotEmpty() || note.startsWith("+")
                return QuickEntry(paise, note.removePrefix("+").trim(), plus)
            }
        }
        LEADING.matchEntire(line)?.let { m ->
            val paise = toPaise(m.groupValues[2]) ?: return@let
            return QuickEntry(paise, m.groupValues[3].trim(), m.groupValues[1].isNotEmpty())
        }
        AMOUNT_ONLY.matchEntire(line)?.let { m ->
            val paise = toPaise(m.groupValues[2]) ?: return null
            return QuickEntry(paise, "", m.groupValues[1].isNotEmpty())
        }
        return null
    }

    /** "29,495" → 2949500, "57.55" → 5755, "5.5" → 550. */
    internal fun toPaise(s: String): Long? {
        val clean = s.trim().replace(",", "")
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        if (parts.size > 2) return null
        val rupees = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = if (parts.size == 2) {
            val f = parts[1]
            if (f.isEmpty() || !f.all { it.isDigit() }) return null
            val two = (f + "00").take(2).toLong()
            // Round on the third decimal.
            if (f.length > 2 && f[2] >= '5') two + 1 else two
        } else 0L
        return rupees * 100 + frac
    }
}
