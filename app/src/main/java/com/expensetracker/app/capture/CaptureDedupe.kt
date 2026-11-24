package com.expensetracker.app.capture

import com.expensetracker.app.data.Expense
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** The fields dedupe needs from a stored draft (any status). Pure data — unit-tested. */
data class DraftKey(
    val id: Long,
    val amountPaise: Long,
    val atMillis: Long,
    val isIncome: Boolean,
    val payee: String,
    val sourcePackage: String,
    val fingerprint: String,
)

/**
 * One payment usually fires 2–3 notifications (UPI app + bank SMS + bank app) within a minute
 * or two. They're merged when: same amount, same direction, within ±[WINDOW_MS], and either from
 * different apps or (same app) with compatible payee names. A notification whose exact text we've
 * already seen (re-posted/updated notification, [fingerprint]) is always a duplicate.
 */
object CaptureDedupe {
    const val WINDOW_MS = 3 * 60_000L
    const val MANUAL_WINDOW_MS = 10 * 60_000L

    /** Stable hash of package + normalised text; lets us recognise re-posts without keeping the text. */
    fun fingerprint(pkg: String, title: String?, text: String?): String {
        val norm = (pkg + "|" + title.orEmpty() + "|" + text.orEmpty()).lowercase().replace(Regex("\\s+"), " ").trim()
        val d = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray(Charsets.UTF_8))
        return d.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in noise }

    private val noise = setOf("ltd", "pvt", "private", "limited", "the", "and", "india", "technologies", "services", "store", "stores", "payments")

    /** "Zomato" ~ "ZOMATO LTD" ~ "zomato@icici" → compatible; "Unknown" is compatible with anything. */
    fun payeesCompatible(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank() || a == "Unknown" || b == "Unknown") return true
        val ca = a.lowercase().filter(Char::isLetterOrDigit)
        val cb = b.lowercase().filter(Char::isLetterOrDigit)
        if (ca.isEmpty() || cb.isEmpty()) return true
        if (ca.contains(cb) || cb.contains(ca)) return true
        if (ca.length >= 4 && cb.length >= 4 && ca.take(4) == cb.take(4)) return true
        val wa = words(a).toSet()
        return words(b).any { it in wa }
    }

    /** The stored draft [c] duplicates, or null. Prefers same-payee matches, then the closest in time. */
    fun findDuplicate(existing: List<DraftKey>, c: DraftKey): DraftKey? {
        existing.firstOrNull { it.fingerprint == c.fingerprint && it.id != c.id }?.let { return it }
        val near = existing.filter {
            it.id != c.id && it.amountPaise == c.amountPaise && it.isIncome == c.isIncome &&
                abs(it.atMillis - c.atMillis) <= WINDOW_MS
        }
        val compatible = near.filter { payeesCompatible(it.payee, c.payee) }
        compatible.minByOrNull { abs(it.atMillis - c.atMillis) }?.let { return it }
        // Different apps often name the same merchant differently (GPay "Rapido" vs bank "ROPPEN TRANSPORTATION").
        return near.filter { it.sourcePackage != c.sourcePackage }.minByOrNull { abs(it.atMillis - c.atMillis) }
    }

    /**
     * True if you already logged this by hand: same day, same amount and direction, and either
     * logged (createdAt) or timed (time) within ±10 min of the payment.
     */
    fun matchesManual(expenses: List<Expense>, amountPaise: Long, isIncome: Boolean, atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val day = at.toLocalDate()
        val secOfDay = at.toLocalTime().toSecondOfDay()
        return expenses.any { e ->
            e.date == day && e.amountPaise == amountPaise && e.isIncome == isIncome && (
                abs(e.createdAt - atMillis) <= MANUAL_WINDOW_MS ||
                    (e.time != null && abs(e.time.toSecondOfDay() - secOfDay) <= MANUAL_WINDOW_MS / 1000)
                )
        }
    }
}
