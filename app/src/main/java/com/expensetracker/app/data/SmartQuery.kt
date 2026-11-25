package com.expensetracker.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * Free-text search turned into filters: "food over 500 last month", "swiggy this year",
 * "income in march", "refunds", "500-1000 in goa", "trip:goa". Words it doesn't recognise
 * fall back to matching the note / category / place text, so plain searches work as before.
 * Pure Kotlin (no Android) so it's unit-tested.
 */
data class SmartQuery(
    val categories: Set<String> = emptySet(),
    val minPaise: Long? = null,
    val maxPaise: Long? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kind: Kind? = null,
    val place: String? = null,
    /** `trip:<name>`; matched against the entry's trip once entries carry one (see [matches]). */
    val trip: String? = null,
    /** Leftover words; every one must appear in the note, category or place. */
    val words: List<String> = emptyList(),
    /** What was understood, in query order, each with the text it came from (for removable chips). */
    val parts: List<Part> = emptyList(),
) {
    enum class Kind(val label: String) {
        SPEND("Spent"), INCOME("Received"), REFUND("Refunds"), TRANSFER("Transfers"),
        EVERYDAY("Everyday"), ONE_OFF("One-off"), FIXED("Fixed"), SAVINGS("Savings"),
    }

    /** One recognised filter: chip [label] and the [start, end) span of the query it consumed. */
    data class Part(val label: String, val start: Int, val end: Int, val isText: Boolean = false)

    val isEmpty: Boolean get() = parts.isEmpty()

    fun matches(e: Expense, info: Map<Long, SpendInfo> = emptyMap()): Boolean {
        if (categories.isNotEmpty() && e.category.id !in categories) return false
        if (minPaise != null && e.amountPaise < minPaise) return false
        if (maxPaise != null && e.amountPaise > maxPaise) return false
        if (from != null && e.date < from) return false
        if (to != null && e.date > to) return false
        if (place != null && !e.place.orEmpty().equals(place, ignoreCase = true)) return false
        // TODO(trips): once Expense has `trip`, match `e.trip.equals(trip, ignoreCase = true)` here.
        //  Until then a trip filter falls back to matching the name in the note/place text.
        if (trip != null && !textHas(e, trip)) return false
        when (kind) {
            null -> {}
            Kind.SPEND -> if (e.isIncome || e.isTransfer) return false
            Kind.INCOME -> if (!e.isIncome || e.isTransfer) return false
            Kind.REFUND -> if (e.refundOf == null) return false
            Kind.TRANSFER -> if (!e.isTransfer) return false
            else -> {
                if (e.isIncome || e.isTransfer) return false
                val t = e.spendOverride ?: info[e.id]?.type ?: SpendType.EVERYDAY
                if (t.name != kind.name) return false
            }
        }
        return words.all { textHas(e, it) }
    }

    private fun textHas(e: Expense, w: String) =
        e.note.contains(w, ignoreCase = true) || e.category.label.contains(w, ignoreCase = true) ||
            e.place?.contains(w, ignoreCase = true) == true

    companion object {
        /** The query with one understood part cut out (what tapping a chip's ✕ does). */
        fun without(query: String, part: Part): String {
            if (part.start < 0 || part.end > query.length || part.start >= part.end) return query
            return (query.substring(0, part.start) + " " + query.substring(part.end)).trim().replace(Regex("\\s+"), " ")
        }

        private val MONTHS = Month.entries.associateBy { it.name.lowercase() } +
            Month.entries.associateBy { it.name.lowercase().take(3) } + mapOf("sept" to Month.SEPTEMBER)

        private val KINDS = mapOf(
            "income" to Kind.INCOME, "received" to Kind.INCOME, "earned" to Kind.INCOME, "credit" to Kind.INCOME, "credits" to Kind.INCOME,
            "spent" to Kind.SPEND, "spend" to Kind.SPEND, "spends" to Kind.SPEND, "expense" to Kind.SPEND, "expenses" to Kind.SPEND,
            "refund" to Kind.REFUND, "refunds" to Kind.REFUND, "refunded" to Kind.REFUND,
            "transfer" to Kind.TRANSFER, "transfers" to Kind.TRANSFER, "self-transfer" to Kind.TRANSFER,
            "everyday" to Kind.EVERYDAY, "daily" to Kind.EVERYDAY,
            "one-off" to Kind.ONE_OFF, "oneoff" to Kind.ONE_OFF, "one-offs" to Kind.ONE_OFF,
            "fixed" to Kind.FIXED, "recurring" to Kind.FIXED,
        )

        private val ALIASES = mapOf(
            "grocery" to "GROCERIES", "travel" to "TRANSPORT", "cab" to "TRANSPORT", "cabs" to "TRANSPORT",
            "bill" to "RENT", "subscription" to "BILLS", "subscriptions" to "BILLS", "recharges" to "BILLS",
            "gift" to "PEOPLE", "entertainment" to "ENTERTAINMENT", "health" to "HEALTH", "medical" to "HEALTH",
            "saving" to "SAVINGS", "eating" to "FOOD",
        )

        private val FILLER = setOf("on", "for", "at", "the", "of", "with", "all", "my", "and", "from", "show", "me", "₹", "rs", "rs.", "inr")
        private val OVER = setOf("over", "above", "more", "min", "minimum", ">", ">=", "atleast")
        private val UNDER = setOf("under", "below", "less", "max", "maximum", "<", "<=", "upto", "atmost")
        private val amountRe = Regex("""^(?:₹|rs\.?|inr)?(\d+(?:\.\d+)?)(k|l|lakh)?$""")
        private val rangeRe = Regex("""^(?:₹|rs\.?)?(\d+(?:\.\d+)?)(k)?\s*(?:-|–|to)\s*(?:₹|rs\.?)?(\d+(?:\.\d+)?)(k)?$""")

        private data class Tok(val text: String, val start: Int, val end: Int)

        /**
         * Parse [query] as of [today]. [categories] are the known categories (label + emoji),
         * [places] the cities your entries are tagged with.
         */
        fun parse(query: String, today: LocalDate, categories: List<CategoryInfo>, places: Collection<String> = emptyList()): SmartQuery {
            val toks = Regex("""\S+""").findAll(query).map { Tok(it.value.lowercase().trimEnd(',', '.', '?', '!'), it.range.first, it.range.last + 1) }
                .flatMap { t -> splitOperator(t) }.filter { it.text.isNotEmpty() }.toList()
            val catByWord = HashMap<String, String>()
            categories.forEach { c ->
                catByWord[c.id.lowercase()] = c.id
                catByWord[c.label.lowercase()] = c.id
                if (c.emoji.isNotBlank()) catByWord[c.emoji.trim()] = c.id
                c.label.lowercase().split(Regex("[^\\p{L}]+")).filter { it.length >= 3 && it != "and" }.forEach { catByWord.putIfAbsent(it, c.id) }
            }
            ALIASES.forEach { (w, id) -> if (categories.any { it.id == id }) catByWord.putIfAbsent(w, id) }
            val placeByKey = places.associateBy { it.trim().lowercase() }

            var q = SmartQuery()
            val parts = ArrayList<Part>()
            val words = ArrayList<String>()
            val cats = LinkedHashSet<String>()
            var i = 0
            fun span(a: Int, b: Int) = toks[a].start to toks[b].end
            fun add(label: String, a: Int, b: Int) { val (s, e) = span(a, b); parts += Part(label, s, e) }
            fun setDates(from: LocalDate, to: LocalDate, label: String, a: Int, b: Int) {
                q = q.copy(from = from, to = to); add(label, a, b)
            }

            while (i < toks.size) {
                val t = toks[i].text
                val next = toks.getOrNull(i + 1)?.text
                val next2 = toks.getOrNull(i + 2)?.text

                // trip:<name>
                if (t.startsWith("trip:") && t.length > 5) {
                    val name = query.substring(toks[i].start + 5, toks[i].end).trim()
                    q = q.copy(trip = name); add("Trip: $name", i, i); i++; continue
                }

                // Dates
                when {
                    t == "today" -> { setDates(today, today, "Today", i, i); i++; continue }
                    t == "yesterday" -> { val d = today.minusDays(1); setDates(d, d, "Yesterday", i, i); i++; continue }
                    (t == "this" || t == "last" || t == "previous") && next in setOf("week", "month", "year") -> {
                        val last = t != "this"
                        val (f, to, label) = when (next) {
                            "week" -> {
                                val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { if (last) it.minusWeeks(1) else it }
                                Triple(mon, if (last) mon.plusDays(6) else today, if (last) "Last week" else "This week")
                            }
                            "month" -> {
                                val m = YearMonth.from(today).let { if (last) it.minusMonths(1) else it }
                                Triple(m.atDay(1), if (last) m.atEndOfMonth() else today, if (last) "Last month" else "This month")
                            }
                            else -> {
                                val y = if (last) today.year - 1 else today.year
                                Triple(LocalDate.of(y, 1, 1), if (last) LocalDate.of(y, 12, 31) else today, if (last) "Last year" else "This year")
                            }
                        }
                        setDates(f, to, label, i, i + 1); i += 2; continue
                    }
                    (t == "last" || t == "past") && next?.toIntOrNull() != null && next2 != null && unitDays(next2) != null -> {
                        val n = next.toInt()
                        val f = when (unitDays(next2)) {
                            1 -> today.minusDays(n.toLong() - 1)
                            7 -> today.minusWeeks(n.toLong()).plusDays(1)
                            else -> today.minusMonths(n.toLong()).plusDays(1)
                        }
                        setDates(f, today, "Last $n ${next2.trimEnd('s')}${if (n == 1) "" else "s"}", i, i + 2); i += 3; continue
                    }
                }
                val monthAt = { k: Int -> toks.getOrNull(k)?.text?.let { MONTHS[it] } }
                val skipIn = if ((t == "in" || t == "during") && (monthAt(i + 1) != null || next?.let(::yearOf) != null)) 1 else 0
                val mi = i + skipIn
                val mon = monthAt(mi)
                if (mon != null) {
                    val y = toks.getOrNull(mi + 1)?.text?.let(::yearOf)
                    val year = y ?: YearMonth.of(today.year, mon).let { if (it.isAfter(YearMonth.from(today))) today.year - 1 else today.year }
                    val ym = YearMonth.of(year, mon)
                    val end = if (y != null) mi + 1 else mi
                    setDates(ym.atDay(1), ym.atEndOfMonth(), ym.month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + year, i, end)
                    i = end + 1; continue
                }
                if (skipIn == 1) {
                    val y = yearOf(next!!)!!
                    setDates(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "$y", i, i + 1); i += 2; continue
                }

                // Amounts
                val op = when {
                    t in OVER || (t == "at" && next == "least") -> 1
                    t in UNDER || (t == "at" && next == "most") -> -1
                    else -> 0
                }
                if (op != 0) {
                    var k = i + 1
                    if (t == "at" || (t == "more" || t == "less") && toks.getOrNull(k)?.text == "than") k++
                    if (t == "at") k = i + 2
                    val amt = toks.getOrNull(k)?.text?.let(::amountOf)
                    if (amt != null) {
                        q = if (op > 0) q.copy(minPaise = amt) else q.copy(maxPaise = amt)
                        add((if (op > 0) "Over " else "Under ") + rupees(amt), i, k); i = k + 1; continue
                    }
                }
                if (t == "between") {
                    val a = next?.let(::amountOf); val b = if (next2 == "and") toks.getOrNull(i + 3)?.text?.let(::amountOf) else null
                    if (a != null && b != null) {
                        q = q.copy(minPaise = minOf(a, b), maxPaise = maxOf(a, b)); add("${rupees(minOf(a, b))}–${rupees(maxOf(a, b))}", i, i + 3); i += 4; continue
                    }
                }
                val range = rangeRe.matchEntire(t)?.let { m ->
                    val x = paise(m.groupValues[1], m.groupValues[2]); val y = paise(m.groupValues[3], m.groupValues[4])
                    if (x != null && y != null) minOf(x, y) to maxOf(x, y) else null
                }
                if (range != null) {
                    q = q.copy(minPaise = range.first, maxPaise = range.second)
                    add("${rupees(range.first)}–${rupees(range.second)}", i, i); i++; continue
                }
                // Bare amount ("₹450", "450", "1.2k"): that exact amount. A bare 4-digit year isn't one.
                val amt = amountOf(t)
                if (amt != null && (yearOf(t) == null || !t[0].isDigit())) {
                    q = q.copy(minPaise = amt, maxPaise = amt); add(rupees(amt), i, i); i++; continue
                }
                val kind = KINDS[t]
                val cat = catByWord[t] ?: catByWord[t.trimEnd('s')]
                val placeHere = if (t == "in" && next != null) placeByKey[next] else null
                when {
                    kind != null -> { q = q.copy(kind = kind); add(kind.label, i, i); i++ }
                    cat != null -> {
                        cats += cat
                        add(categories.first { it.id == cat }.let { "${it.emoji} ${it.label}".trim() }, i, i); i++
                    }
                    placeHere != null -> { q = q.copy(place = placeHere); add("📍 $placeHere", i, i + 1); i += 2 }
                    placeByKey[t] != null -> { val pl = placeByKey.getValue(t); q = q.copy(place = pl); add("📍 $pl", i, i); i++ }
                    t in FILLER || t == "in" || t == "than" -> i++
                    else -> {
                        words += t
                        val (st, en) = span(i, i); parts += Part("“$t”", st, en, isText = true); i++
                    }
                }
            }
            return q.copy(categories = cats, words = words, parts = parts)
        }

        /** "<500" / ">2k" arrive as one token: split the operator off so the rules above see two. */
        private fun splitOperator(t: Tok): List<Tok> {
            val op = listOf(">=", "<=", ">", "<").firstOrNull { t.text.startsWith(it) && t.text.length > it.length } ?: return listOf(t)
            return listOf(Tok(op, t.start, t.start + op.length), Tok(t.text.substring(op.length), t.start + op.length, t.end))
        }

        private fun unitDays(w: String): Int? = when (w.trimEnd('s')) { "day" -> 1; "week" -> 7; "month" -> 30; else -> null }

        private fun yearOf(w: String): Int? = w.toIntOrNull()?.takeIf { w.length == 4 && it in 2000..2100 }

        internal fun amountOf(w: String): Long? = amountRe.matchEntire(w)?.let { paise(it.groupValues[1], it.groupValues[2]) }

        private fun paise(num: String, suffix: String): Long? {
            val v = num.toDoubleOrNull() ?: return null
            val mult = when (suffix) { "k" -> 1_000.0; "l", "lakh" -> 1_00_000.0; else -> 1.0 }
            return Math.round(v * mult * 100)
        }

        private fun rupees(p: Long): String {
            val r = p / 100
            return "₹" + if (p % 100 == 0L) java.text.NumberFormat.getIntegerInstance(java.util.Locale("en", "IN")).format(r) else "%.2f".format(p / 100.0)
        }
    }
}
