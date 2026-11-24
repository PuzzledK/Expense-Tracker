package com.expensetracker.app.ml

import android.content.Context
import com.expensetracker.app.data.Categorizer
import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.QuickTemplate
import com.expensetracker.app.data.frequentTemplates
import com.expensetracker.app.data.noteKey
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * Self-improving on-device predictor (no network, no libraries).
 *
 * - **Category**: your correction for this exact note → the category you last used for this exact
 *   note → a Naive Bayes model over note words, character trigrams, what the keyword rules say,
 *   amount, time of day, weekday and place (recent entries weigh more) → keyword rules.
 * - **Learning**: [train] rebuilds from history (lazily, on the next prediction). [learn] compares
 *   the saved category with what the model would have said; a mismatch is a correction, kept in a
 *   small file and fed back into the model with a 4× boost so similar notes shift too.
 * - **Payments**: payee → the note you actually wrote for it, learnt from confirmations.
 * - **Suggestions**: (note, amount) combos ranked by how often you log them at this hour/weekday/place.
 *
 * Thread-safe (one lock; everything is fast). Call [init] once with a Context to persist learning.
 */
object Predictor {

    data class Prediction(val note: String, val category: Category, val confidence: Double)

    private val lock = Any()

    // ---- state (guarded by lock) ----
    private var pending: List<Expense>? = null     // list from the last train(), not built yet
    private var lastTrained: List<Expense>? = null
    private var current: Model? = null
    private var previous: Model? = null              // model before the last rebuild (for learn())
    private val corrections = LinkedHashMap<String, Correction>()          // noteKey → correction
    private val payees = HashMap<String, HashMap<String, PayeeNote>>()     // payeeKey → noteKey → stats
    private val recentPayments = ArrayDeque<RecentPayment>()
    private var file: File? = null
    private var correctionsChanged = false

    internal class PayeeNote(var note: String, var cat: Category, var weight: Double, var ts: Long, var hourSum: Double, var hourN: Int, var logAmtSum: Double, var amtN: Int)
    private class RecentPayment(val payee: String, val amountPaise: Long, val at: LocalDateTime, val ts: Long)

    private const val MAX_CORRECTIONS = 500
    private const val MAX_PAYEES = 500

    // ---------------------------------------------------------------- public API

    /** Loads/persists learnt corrections and payee memory in the app's files dir. Safe to call repeatedly. */
    fun init(ctx: Context) = initFile(File(ctx.applicationContext.filesDir, "ml_predictor.tsv"))

    /** Rebuild from the full history (newest first). Cheap: the model is rebuilt lazily on the next use. */
    fun train(all: List<Expense>) = synchronized(lock) {
        if (all === lastTrained || all === pending) return@synchronized
        pending = all
    }

    /**
     * A manual add/edit was saved — the strongest learning signal. [payee] is the UPI payee when the
     * entry came from an auto-captured payment (capture/ should pass it); otherwise a recent
     * [predictPayment] with the same amount is matched.
     */
    fun learn(saved: Expense, payee: String? = null) = synchronized(lock) {
        val key = noteKey(saved.note)
        if (key.isEmpty()) return@synchronized
        // Judge with a model that hasn't seen this save yet (the not-yet-rebuilt one, or the one before).
        val judge = listOfNotNull(current, previous).firstOrNull { saved.id == 0L || it.byId[saved.id] != saved.category }
            ?: current
        val oldCat = judge?.byId?.get(saved.id)
        val predicted = if (judge != null) decide(judge, saved.note, saved.amountPaise, saved.date.atTime(saved.time ?: java.time.LocalTime.NOON), saved.place, saved.time != null).cat
        else Categorizer.guess(saved.note)
        val changedByUser = oldCat != null && oldCat != saved.category
        val now = System.currentTimeMillis()
        if (changedByUser || predicted != saved.category) {
            val prev = corrections.remove(key)
            corrections[key] = Correction(saved.note, saved.category, now, if (prev?.cat == saved.category) prev.n + 1 else 1)
            while (corrections.size > MAX_CORRECTIONS) corrections.remove(corrections.keys.first())
            correctionsChanged = true
        } else if (corrections[key]?.let { it.cat != saved.category } == true) {
            corrections.remove(key); correctionsChanged = true
        }
        if (correctionsChanged) {
            // Overlay changed: rebuild from the latest history on next use.
            if (pending == null) pending = lastTrained ?: current?.list
        }
        val p = payee ?: matchRecentPayment(saved)
        if (p != null) rememberPayee(p, saved)
        if (correctionsChanged || p != null) persist()
        correctionsChanged = false
    }

    /** Category for a typed note, using any context available. */
    fun predictCategory(note: String, amountPaise: Long? = null, at: LocalDateTime? = null, place: String? = null): Category =
        synchronized(lock) {
            val m = model() ?: return@synchronized correctionFor(noteKey(note), null) ?: Categorizer.guess(note)
            decide(m, note, amountPaise, at, place, at != null).cat
        }

    /** For an auto-captured payment: guess the note you'd write and its category from payee/amount/time/place. */
    fun predictPayment(payee: String, amountPaise: Long, at: LocalDateTime, place: String? = null): Prediction =
        synchronized(lock) {
            recentPayments.addLast(RecentPayment(payee, amountPaise, at, System.currentTimeMillis()))
            while (recentPayments.size > 50) recentPayments.removeFirst()
            paymentGuess(payee, amountPaise, at, place)
        }

    /** One-tap suggestions for right now (e.g. lunch at 1 pm, cab in the evening). */
    fun suggestions(now: LocalDateTime, place: String? = null, limit: Int = 8): List<QuickTemplate> =
        synchronized(lock) {
            val m = model() ?: return@synchronized emptyList()
            Suggest.rank(m, now, place, limit).ifEmpty { frequentTemplates(m.list, now.toLocalDate(), limit) }
        }

    // ---------------------------------------------------------------- category decision

    internal enum class Source { CORRECTION, EXACT_NOTE, MODEL, RULES }
    internal class Decision(val cat: Category, val confidence: Double, val source: Source)

    private fun model(): Model? {
        pending?.let { list ->
            previous = current
            current = Model.build(list, corrections.values)
            lastTrained = list
            pending = null
        }
        return current
    }

    private fun valid(m: Model?, c: Category) = m?.has(c) == true || com.expensetracker.app.data.CategoryRegistry.exists(c.id)

    /** Your correction for this note, unless a newer entry with that note says otherwise. */
    private fun correctionFor(key: String, m: Model?): Category? {
        val k = corrections[key] ?: return null
        if (!valid(m, k.cat)) return null
        val agg = m?.notes?.get(key)
        if (agg != null && agg.lastTsByCat.any { (c, ts) -> c != k.cat && ts > k.ts }) return null
        return k.cat
    }

    internal fun decide(m: Model, note: String, amountPaise: Long?, at: LocalDateTime?, place: String?, timeKnown: Boolean): Decision {
        val key = noteKey(note)
        correctionFor(key, m)?.let { return Decision(it, 0.95, Source.CORRECTION) }
        m.notes[key]?.top()?.let { top ->
            val agg = m.notes.getValue(key)
            val share = (agg.weights[top] ?: 0.0) / agg.total
            return Decision(top, min(0.95, 0.6 + 0.35 * share * agg.count / (agg.count + 1.0)), Source.EXACT_NOTE)
        }
        val text = Text.feats(note)
        val s = m.score(text, Ctx.feats(amountPaise, at?.toLocalDate(), if (timeKnown) at?.hour else null, place))
        val b = s.best()
        if (b >= 0 && s.textEvidence) {
            val p = s.probs()[b]
            // Model vs keyword rules: when they disagree, go with whichever has the better record —
            // the model's confidence here, or how often the rule for this category matched your past choices.
            if (s.cats[b] != text.kw && text.kw != Category.OTHER && valid(m, text.kw)) {
                val trust = m.ruleTrust(text.kw)
                if (trust > p) return Decision(text.kw, trust, Source.RULES)
            }
            return Decision(s.cats[b], p, Source.MODEL)
        }
        return Decision(text.kw, if (text.kw == Category.OTHER) 0.1 else 0.4, Source.RULES)
    }

    // ---------------------------------------------------------------- payments

    private fun paymentGuess(payee: String, amountPaise: Long, at: LocalDateTime, place: String?): Prediction {
        val m = model()
        val pk = Payee.key(payee)
        val canon = Payee.canonical(pk)
        fun cat(note: String) = if (m != null) decide(m, note, amountPaise, at, place, true).cat else (correctionFor(noteKey(note), null) ?: Categorizer.guess(note))

        // 1. You've confirmed payments to this payee before.
        val memo = payees[pk] ?: canon?.let { payees[Payee.key(it)] }
        if (!memo.isNullOrEmpty()) {
            val hour = at.hour + at.minute / 60.0
            val scored = memo.values.map { pn ->
                val hs = if (pn.hourN > 0) 0.3 + exp(-sq(hourDiff(hour, pn.hourSum / pn.hourN)) / 8.0) else 1.0
                val asim = if (pn.amtN > 0) 0.3 + exp(-sq(ln(amountPaise.coerceAtLeast(100).toDouble()) - pn.logAmtSum / pn.amtN) / 0.5) else 1.0
                pn to pn.weight * hs * asim
            }
            val (best, sc) = scored.maxBy { it.second }
            val share = sc / scored.sumOf { it.second }
            val n = memo.values.sumOf { it.weight }
            val conf = min(0.95, share * n / (n + 1.0))
            return Prediction(best.note, cat(best.note), conf)
        }

        // 2. A note in your history mentions this payee/merchant ("Rapido", "Zepto", "Richa").
        if (m != null) {
            val words = (Text.tokens(pk) + (canon?.let { Text.tokens(it) } ?: emptyList())).distinct()
            val hits = words.flatMap { m.tokenIndex[it].orEmpty() }.distinct().mapNotNull { m.notes[it] }
            if (hits.isNotEmpty()) {
                val best = hits.maxBy { it.total }
                val share = best.total / hits.sumOf { it.total }
                return Prediction(best.note, cat(best.note), min(0.6, 0.3 + 0.3 * share))
            }
        }

        // 3. Nothing learnt: the merchant's brand name, or the person's name.
        val note = canon ?: Text.titleCase(pk.ifEmpty { payee })
        val c = cat(note)
        val conf = when {
            canon != null && c != Category.OTHER -> 0.35
            canon != null -> 0.2
            else -> 0.1
        }
        return Prediction(note, c, conf)
    }

    private fun matchRecentPayment(saved: Expense): String? {
        val now = System.currentTimeMillis()
        val hit = recentPayments.lastOrNull {
            it.amountPaise == saved.amountPaise &&
                abs(ChronoUnit.DAYS.between(it.at.toLocalDate(), saved.date)) <= 2 &&
                now - it.ts < 7L * 24 * 3600 * 1000
        } ?: return null
        recentPayments.remove(hit)
        return hit.payee
    }

    private fun rememberPayee(payee: String, saved: Expense) {
        val pk = Payee.key(payee)
        if (pk.isEmpty()) return
        val notes = payees.getOrPut(pk) { HashMap() }
        notes.values.forEach { it.weight *= 0.95 }   // older confirmations fade a little
        val nk = noteKey(saved.note)
        val pn = notes.getOrPut(nk) { PayeeNote(saved.note, saved.category, 0.0, 0, 0.0, 0, 0.0, 0) }
        pn.note = saved.note; pn.cat = saved.category; pn.weight += 1.0; pn.ts = System.currentTimeMillis()
        saved.time?.let { pn.hourSum += it.hour + it.minute / 60.0; pn.hourN++ }
        if (saved.amountPaise > 0) { pn.logAmtSum += ln(saved.amountPaise.toDouble()); pn.amtN++ }
        if (payees.size > MAX_PAYEES) payees.minByOrNull { (_, v) -> v.values.maxOf { it.ts } }?.let { payees.remove(it.key) }
    }

    private fun sq(x: Double) = x * x
    internal fun hourDiff(a: Double, b: Double): Double { val d = abs(a - b) % 24; return min(d, 24 - d) }

    // ---------------------------------------------------------------- persistence

    internal fun initFile(f: File?) = synchronized(lock) {
        if (f == file) return@synchronized
        file = f
        corrections.clear(); payees.clear()
        if (f == null || !f.exists()) return@synchronized
        runCatching { Store.decode(f.readText(), corrections, payees) }
        if (pending == null) pending = lastTrained ?: current?.list
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(Store.encode(corrections, payees))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Clears all learnt state (tests). */
    internal fun resetForTesting() = synchronized(lock) {
        pending = null; lastTrained = null; current = null; previous = null
        corrections.clear(); payees.clear(); recentPayments.clear(); file = null
    }

    internal fun correctionCount() = synchronized(lock) { corrections.size }
    internal fun decideForTesting(note: String, amountPaise: Long? = null, at: LocalDateTime? = null): Decision? =
        synchronized(lock) { model()?.let { decide(it, note, amountPaise, at, null, at != null) } }
}
