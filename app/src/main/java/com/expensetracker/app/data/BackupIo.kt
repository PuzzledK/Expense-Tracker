package com.expensetracker.app.data

import java.time.LocalDate

/**
 * Backup / export formats. JSON is hand-rolled (no org.json) so it runs in plain JVM unit tests.
 */
object BackupIo {

    private const val VERSION = 1

    // ---------------- JSON ----------------

    fun toJson(expenses: List<Expense>, settings: Settings): String = buildString {
        append("{\n  \"app\": \"ExpenseTracker\",\n  \"version\": ").append(VERSION).append(",\n")
        append("  \"settings\": {")
        append("\"monthlyBudgetPaise\": ").append(settings.monthlyBudgetPaise)
        append(", \"excludeSavingsFromSpend\": ").append(settings.excludeSavingsFromSpend)
        append(", \"reminderEnabled\": ").append(settings.reminderEnabled)
        append(", \"reminderHour\": ").append(settings.reminderHour)
        append(", \"reminderMinute\": ").append(settings.reminderMinute)
        append("},\n  \"expenses\": [")
        expenses.forEachIndexed { i, e ->
            append(if (i == 0) "\n    " else ",\n    ")
            append("{\"date\": ").append(quote(e.date.toString()))
            append(", \"amountPaise\": ").append(e.amountPaise)
            append(", \"note\": ").append(quote(e.note))
            append(", \"category\": ").append(quote(e.category.name))
            append(", \"isIncome\": ").append(e.isIncome)
            append(", \"createdAt\": ").append(e.createdAt)
            e.spendOverride?.let { append(", \"spendOverride\": ").append(quote(it.name)) }
            e.place?.let { append(", \"place\": ").append(quote(it)) }
            e.time?.let { append(", \"time\": ").append(quote("%02d:%02d".format(it.hour, it.minute))) }
            if (e.lat != null && e.lon != null) append(", \"lat\": ").append(e.lat).append(", \"lon\": ").append(e.lon)
            append("}")
        }
        if (expenses.isNotEmpty()) append("\n  ")
        append("]\n}\n")
    }

    /** Ids are reset to 0. Throws IllegalArgumentException on malformed input. */
    fun fromJson(json: String): Pair<List<Expense>, Settings?> {
        val root = JsonReader(json).readRoot() as? Map<*, *>
            ?: throw IllegalArgumentException("Backup file is not a JSON object")
        val list = root["expenses"] as? List<*>
            ?: throw IllegalArgumentException("Backup file has no \"expenses\" list")
        val expenses = list.mapIndexed { i, item ->
            val o = item as? Map<*, *> ?: throw IllegalArgumentException("Expense #${i + 1} is not an object")
            Expense(
                id = 0,
                amountPaise = (o["amountPaise"] as? Number)?.toLong()
                    ?: throw IllegalArgumentException("Expense #${i + 1} has no amount"),
                note = o["note"] as? String ?: "",
                category = (o["category"] as? String)
                    ?.let { name -> Category.entries.firstOrNull { it.name == name } }
                    ?: Category.OTHER,
                date = (o["date"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: throw IllegalArgumentException("Expense #${i + 1} has no valid date"),
                isIncome = o["isIncome"] as? Boolean ?: false,
                createdAt = (o["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                spendOverride = (o["spendOverride"] as? String)
                    ?.let { name -> SpendType.entries.firstOrNull { it.name == name } },
                place = (o["place"] as? String)?.takeIf { it.isNotBlank() },
                lat = (o["lat"] as? Number)?.toDouble(),
                lon = (o["lon"] as? Number)?.toDouble(),
                time = (o["time"] as? String)?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() },
            )
        }
        val settings = (root["settings"] as? Map<*, *>)?.let { s ->
            val d = Settings()
            Settings(
                monthlyBudgetPaise = (s["monthlyBudgetPaise"] as? Number)?.toLong() ?: d.monthlyBudgetPaise,
                excludeSavingsFromSpend = s["excludeSavingsFromSpend"] as? Boolean ?: d.excludeSavingsFromSpend,
                reminderEnabled = s["reminderEnabled"] as? Boolean ?: d.reminderEnabled,
                reminderHour = (s["reminderHour"] as? Number)?.toInt() ?: d.reminderHour,
                reminderMinute = (s["reminderMinute"] as? Number)?.toInt() ?: d.reminderMinute,
            )
        }
        return expenses to settings
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    /** Minimal JSON reader: objects → Map, arrays → List, numbers → Long/Double. */
    private class JsonReader(private val s: String) {
        private var i = 0

        fun readRoot(): Any? {
            val v = value()
            ws()
            if (i != s.length) fail("Unexpected trailing data")
            return v
        }

        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at position $i")

        private fun ws() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            ws()
            if (i >= s.length) fail("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("Unexpected character '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("Expected $word")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (s.getOrNull(i) == '}') { i++; return m }
            while (true) {
                ws()
                if (s.getOrNull(i) != '"') fail("Expected key")
                val k = str()
                ws()
                if (s.getOrNull(i) != ':') fail("Expected ':'")
                i++
                m[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            ws()
            if (s.getOrNull(i) == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // opening quote
            while (true) {
                if (i >= s.length) fail("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("Bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("Bad number '$t'")
        }
    }

    // ---------------- CSV ----------------

    fun toCsv(expenses: List<Expense>): String = buildString {
        append("date,amount,type,category,note,place\n")
        for (e in sortedOldestFirst(expenses)) {
            append(e.date).append(',')
            append(formatRupees(e.amountPaise)).append(',')
            append(if (e.isIncome) "income" else "expense").append(',')
            append(csvField(e.category.label)).append(',')
            append(csvField(e.note)).append(',')
            append(csvField(e.place.orEmpty())).append('\n')
        }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\""
        else s

    // ---------------- Notes text ----------------

    /**
     * Back to the notes-app format, oldest first. The year is written on the first header and
     * whenever it changes ("31 MARCH 2026"), so re-importing is unambiguous.
     */
    fun toNotesText(expenses: List<Expense>): String = buildString {
        append("EXPENSES\n")
        var lastDate: LocalDate? = null
        for (e in sortedOldestFirst(expenses)) {
            if (e.date != lastDate) {
                append('\n')
                append(e.date.dayOfMonth).append(' ').append(e.date.month.name)
                if (lastDate == null || lastDate.year != e.date.year) append(' ').append(e.date.year)
                append('\n')
                lastDate = e.date
            }
            if (e.isIncome) append('+')
            append(formatRupees(e.amountPaise)).append(" - ").append(e.note.replace('\n', ' ')).append('\n')
        }
    }

    /** 500000 → "5000", 5755 → "57.55", 550 → "5.50". */
    fun formatRupees(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val p = kotlin.math.abs(paise)
        return if (p % 100 == 0L) "$sign${p / 100}" else "$sign${p / 100}.${String.format(java.util.Locale.ROOT, "%02d", p % 100)}"
    }

    private fun sortedOldestFirst(list: List<Expense>): List<Expense> =
        list.sortedWith(compareBy<Expense>({ it.date }, { it.createdAt }, { it.id }))
}
