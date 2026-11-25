package com.expensetracker.app.data

/**
 * The one rule for how entries count in totals (stats, budget, insights, heatmap, widget):
 *  - own-account transfers count as nothing;
 *  - a refund (income with [Expense.refundOf]) is not income: it lowers the expense it refunds,
 *    in that expense's month and category. A fully refunded expense drops out.
 *  - a refund whose expense is gone (deleted) stays plain income, so the money isn't lost.
 *
 * Screens listing entries keep the raw list; anything that adds amounts up should use [counted].
 */
object Ledger {

    /** True when this entry is ignored by every total. */
    fun isNeutral(e: Expense): Boolean = e.isTransfer

    /** The entries as they count: transfers removed, refunds folded into their expenses. */
    fun counted(all: List<Expense>): List<Expense> {
        if (all.none { it.isTransfer || it.refundOf != null }) return all
        val expenseIds = all.asSequence().filter { !it.isIncome && !it.isTransfer }.map { it.id }.toHashSet()
        val refunded = HashMap<Long, Long>()
        all.forEach { e ->
            val target = e.refundOf
            if (e.isIncome && !e.isTransfer && target != null && target in expenseIds) refunded.merge(target, e.amountPaise, Long::plus)
        }
        return all.mapNotNull { e ->
            when {
                e.isTransfer -> null
                e.isIncome && e.refundOf != null && e.refundOf in expenseIds -> null
                !e.isIncome && e.id in refunded -> {
                    val left = e.amountPaise - (refunded[e.id] ?: 0)
                    if (left <= 0) null else e.copy(amountPaise = left)
                }
                else -> e
            }
        }
    }

    /** Total refunded so far against expense [id]. */
    fun refundedFor(all: List<Expense>, id: Long): Long =
        all.filter { it.isIncome && !it.isTransfer && it.refundOf == id }.sumOf { it.amountPaise }

    /**
     * Expenses a refund of [amountPaise] with text [hint] most likely belongs to, best first:
     * same amount and a shared word with the note score highest, then recency. Only expenses on
     * or before [date] (a refund follows the purchase), within the last [days] days.
     */
    fun refundCandidates(
        all: List<Expense>,
        amountPaise: Long,
        hint: String,
        date: java.time.LocalDate,
        excludeId: Long = 0,
        days: Long = 120,
        limit: Int = 30,
    ): List<Expense> {
        val words = tokens(hint)
        val from = date.minusDays(days)
        return all.asSequence()
            .filter { !it.isIncome && !it.isTransfer && it.id != excludeId && !it.date.isAfter(date) && !it.date.isBefore(from) }
            .map { e ->
                var score = 0
                if (amountPaise > 0 && e.amountPaise == amountPaise) score += 4
                else if (amountPaise in 1..e.amountPaise) score += 1 // partial refund
                if (words.isNotEmpty() && tokens(e.note).any { it in words }) score += 3
                e to score
            }
            .sortedWith(compareByDescending<Pair<Expense, Int>> { it.second }.thenByDescending { it.first.date }.thenByDescending { it.first.createdAt })
            .map { it.first }
            .take(limit)
            .toList()
    }

    /** The best match only when it's convincing (same amount or same merchant), else null. */
    fun bestRefundMatch(all: List<Expense>, amountPaise: Long, hint: String, date: java.time.LocalDate): Expense? {
        val words = tokens(hint)
        return refundCandidates(all, amountPaise, hint, date, limit = 1).firstOrNull()?.takeIf { e ->
            e.amountPaise == amountPaise || (words.isNotEmpty() && tokens(e.note).any { it in words })
        }
    }

    private val stop = setOf("refund", "refunded", "reversal", "reversed", "credited", "received", "from", "for", "your", "the", "and", "via", "upi", "rs", "inr", "to", "of", "a/c", "account")

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stop && it.any(Char::isLetter) }.toSet()
}
