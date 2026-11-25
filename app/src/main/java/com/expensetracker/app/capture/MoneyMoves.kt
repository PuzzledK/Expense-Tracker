package com.expensetracker.app.capture

import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Ledger
import java.time.LocalDate

/**
 * Spots captured messages that aren't plain spend/income: refunds of an earlier purchase and
 * money moved between your own accounts. Only suggestions — the review card shows them as chips
 * you can switch off.
 */
object MoneyMoves {
    private val refundRe = Regex("""\brefund(?:ed|s)?\b|\brevers(?:ed|al)\b|\bcash ?back\b.*\b(?:order|purchase)\b|\bchargeback\b""", RegexOption.IGNORE_CASE)
    private val transferRe = Regex(
        """\bself[- ]?transfer\b|\bown (?:a/?c|account)\b|\b(?:to|into) your (?:own )?(?:a/?c|account)\b.*\bfrom your\b|\bfrom your\b.*\bto your (?:a/?c|account)\b|\bbetween your accounts\b|\bmoved to your\b|\bsweep(?:[- ]?in|[- ]?out)?\b|\bfund transfer to self\b""",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeRefund(text: String): Boolean = refundRe.containsMatchIn(text)

    /** [ownName]: your name if known; a payment to/from yourself is a transfer too. */
    fun looksLikeSelfTransfer(text: String, payee: String, ownName: String? = null): Boolean {
        if (transferRe.containsMatchIn(text)) return true
        val me = ownName?.trim()?.lowercase()?.takeIf { it.length >= 3 } ?: return false
        return payee.trim().lowercase() == me
    }

    /** What the review card should start with for a draft. */
    data class Guess(val transfer: Boolean, val refundOf: Expense?)

    fun guess(text: String, payee: String, amountPaise: Long, isIncome: Boolean, date: LocalDate, all: List<Expense>, ownName: String? = null): Guess {
        if (looksLikeSelfTransfer(text, payee, ownName)) return Guess(transfer = true, refundOf = null)
        if (isIncome && looksLikeRefund(text)) {
            return Guess(false, Ledger.bestRefundMatch(all, amountPaise, "$payee $text", date))
        }
        return Guess(false, null)
    }
}
