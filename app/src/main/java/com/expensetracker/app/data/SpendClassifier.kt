package com.expensetracker.app.data

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * On-device model that decides whether an expense is everyday spending, a one-off
 * (flights, gadgets, big outings), fixed/recurring (rent, monthly subscriptions) or savings.
 *
 * It learns from your own history every time it runs, in this order:
 *  1. Your manual choice always wins.
 *  2. Savings → Savings; Rent & Bills and Recharge & Subs → Fixed.
 *  3. Recurring: the same note seen in 3+ different months, about once a month, at a
 *     steady amount (e.g. a gym fee) → Fixed. Food/groceries/transport never count,
 *     since "Zepto" or "Lunch" repeating is just everyday life.
 *  4. Travel bookings (flight, train ticket…) of ₹500+ → One-off.
 *  5. Outlier test on a log scale (median + robust spread via MAD). Food, groceries and
 *     transport are compared with their own category (a ₹1,700 restaurant bill vs your
 *     usual ₹140 meal); everything else (shopping, gifts…) with your overall typical
 *     spend, so a ₹2,000 gadget stands out. Far above normal (≥ 4× typical, above the
 *     robust cut-off, at least ₹800) → One-off.
 *  6. Anything ≥ ₹5,000 that isn't fixed → One-off.
 * Income entries get no verdict.
 */
object SpendClassifier {

    private const val MIN_ONE_OFF = 800_00L          // ₹800
    private const val ALWAYS_ONE_OFF = 5_000_00L     // ₹5,000
    private const val MIN_CATEGORY_SAMPLES = 12
    private const val Z_CUTOFF = 2.5
    private const val MIN_SPREAD = 0.35              // log-scale floor so tight categories don't over-flag
    private const val MIN_RATIO = 4.0

    /** Frequent, consumable categories judged against themselves; the rest against overall spend. */
    private val CONSUMABLES = setOf(Category.FOOD, Category.GROCERIES, Category.TRANSPORT)

    private val travel = Regex("""\b(flight|flights|airfare|ticket|tickets|train|bus booking|irctc|indigo|vistara|air india|hotel|airbnb|oyo)\b""", RegexOption.IGNORE_CASE)

    private data class Baseline(val medianLog: Double, val spread: Double, val n: Int)

    /** Returns one verdict per input expense, in the same order (null for income). */
    fun classify(all: List<Expense>): List<SpendInfo?> {
        val spends = all.filter { !it.isIncome }
        val recurring = recurringKeys(spends)

        // Baseline pool: ordinary spending only (no savings / rent / recurring / manual non-everyday).
        val pool = spends.filter { e ->
            e.amountPaise > 0 &&
                e.category != Category.SAVINGS && e.category != Category.RENT && e.category != Category.BILLS &&
                noteKey(e.note) !in recurring &&
                (e.trip == null || e.spendOverride == SpendType.EVERYDAY) &&
                (e.spendOverride == null || e.spendOverride == SpendType.EVERYDAY)
        }
        val global = baseline(pool.map { it.amountPaise })
        val perCategory = pool.groupBy { it.category }
            .mapValues { (_, v) -> baseline(v.map { it.amountPaise }) }
            .filterKeys { it in CONSUMABLES }
            .filterValues { it != null && it.n >= MIN_CATEGORY_SAMPLES }
            .mapValues { it.value!! }

        return all.map { e ->
            if (e.isIncome) null else classifyOne(e, recurring, perCategory[e.category] ?: global, perCategory.containsKey(e.category))
        }
    }

    private fun classifyOne(e: Expense, recurring: Map<String, Long>, base: Baseline?, ownCategory: Boolean): SpendInfo {
        e.spendOverride?.let { return SpendInfo(it, "Set by you", auto = false) }
        e.trip?.let { return SpendInfo(SpendType.ONE_OFF, "Trip: $it") }
        if (e.category == Category.SAVINGS) return SpendInfo(SpendType.SAVINGS, "Savings category")
        if (e.category == Category.RENT) return SpendInfo(SpendType.FIXED, "Rent & bills")
        if (e.category == Category.BILLS) return SpendInfo(SpendType.FIXED, "Recharge / subscription")

        recurring[noteKey(e.note)]?.let { median ->
            val r = e.amountPaise.toDouble() / median
            if (r in 0.6..1.6) return SpendInfo(SpendType.FIXED, "Repeats about once a month")
        }

        if (e.amountPaise >= 500_00L && travel.containsMatchIn(e.note)) {
            return SpendInfo(SpendType.ONE_OFF, "Travel booking")
        }

        if (base != null && e.amountPaise >= MIN_ONE_OFF) {
            val typical = exp(base.medianLog)
            val cutoff = exp(base.medianLog + Z_CUTOFF * base.spread)
            val ratio = e.amountPaise / typical
            if (e.amountPaise >= cutoff && ratio >= MIN_RATIO) {
                val where = if (ownCategory) "your typical ${e.category.label}" else "your typical"
                return SpendInfo(
                    SpendType.ONE_OFF,
                    "${fmtRatio(ratio)}× $where spend (${rupees(typical.roundToLong())})",
                )
            }
        }

        if (e.amountPaise >= ALWAYS_ONE_OFF) return SpendInfo(SpendType.ONE_OFF, "Large amount")
        return SpendInfo(SpendType.EVERYDAY, "Typical spending")
    }

    /** noteKey → median amount, for notes that recur roughly monthly across 3+ months. */
    private fun recurringKeys(spends: List<Expense>): Map<String, Long> =
        SubscriptionDetector.recurringGroups(spends).associate { it.key to it.medianPaise }

    private fun baseline(amounts: List<Long>): Baseline? {
        if (amounts.size < 5) return null
        val logs = amounts.map { ln(it.toDouble()) }.sorted()
        val med = median(logs)
        val mad = median(logs.map { abs(it - med) }.sorted())
        return Baseline(med, max(1.4826 * mad, MIN_SPREAD), amounts.size)
    }

    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    private fun fmtRatio(r: Double) = if (r >= 10) r.roundToLong().toString() else "%.1f".format(r)

    private fun rupees(paise: Long) = "₹" + BackupIo.formatRupees(paise)
}
