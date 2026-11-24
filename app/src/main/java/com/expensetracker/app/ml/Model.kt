package com.expensetracker.app.ml

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.noteKey
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Everything known about one exact note (by [noteKey]) in the history. */
internal class NoteAgg(var note: String, val tokens: List<String>) {
    val weights = HashMap<Category, Double>()      // decayed count per category
    val lastTsByCat = HashMap<Category, Long>()    // newest createdAt per category
    var lastCat: Category? = null
    var lastTs = Long.MIN_VALUE
    var total = 0.0
    var count = 0
    var hourSum = 0.0
    var hourN = 0

    fun top(): Category? {
        // Recency matters most: the category you used last gets a bonus worth half the mass.
        val bonus = 0.5 * total + 1.0
        return weights.maxByOrNull { (c, w) -> w + if (c == lastCat) bonus else 0.0 }?.key
    }

    /** Mean clock hour of timed entries with this note, or null. */
    fun meanHour(): Double? = if (hourN > 0) hourSum / hourN else null
}

/** A correction you made: this note is [cat], said at [ts] (epoch ms), [n] times. */
internal data class Correction(val note: String, val cat: Category, val ts: Long, val n: Int)

/**
 * Immutable snapshot built from the history (+ correction overlay): a multinomial Naive Bayes
 * over [Feat] groups, an exact-note memory, and a word index for matching payees to notes.
 */
internal class Model private constructor(
    val list: List<Expense>,
    val cats: Array<Category>,
    private val catIndex: Map<Category, Int>,
    private val prior: DoubleArray,
    private val counts: HashMap<String, DoubleArray>,
    private val logDenom: Array<DoubleArray>,   // [group][cat] = ln(total_gc + α·V_g)
    private val vocabByGroup: IntArray,
    val notes: HashMap<String, NoteAgg>,
    val tokenIndex: HashMap<String, MutableList<String>>,   // word → note keys containing it
    val byId: HashMap<Long, Category>,
    val newestDate: LocalDate?,
    private val ruleAgree: HashMap<Category, Double>,
    private val ruleTotal: HashMap<Category, Double>,
) {
    fun has(c: Category) = c in catIndex

    /** How often (recency-weighted, smoothed) your category matched what the keyword rules said [kw]. */
    fun ruleTrust(kw: Category): Double = ((ruleAgree[kw] ?: 0.0) + 1.0) / ((ruleTotal[kw] ?: 0.0) + 2.0)

    class Scores(val cats: Array<Category>, val logp: DoubleArray, val textEvidence: Boolean) {
        fun best(): Int = logp.indices.maxByOrNull { logp[it] } ?: -1
        /** Softmax posterior (NB is over-confident; [temp] > 1 flattens it). */
        fun probs(temp: Double = 3.0): DoubleArray {
            if (logp.isEmpty()) return logp
            val m = logp.max()
            val e = DoubleArray(logp.size) { exp((logp[it] - m) / temp) }
            val s = e.sum()
            return DoubleArray(e.size) { e[it] / s }
        }
    }

    /** NB log-scores per category. Unknown features (never seen in training) are ignored. */
    fun score(text: TextFeats, ctx: List<Feat>): Scores {
        val c = cats.size
        val out = DoubleArray(c)
        if (c == 0) return Scores(cats, out, false)
        val pSum = prior.sum()
        for (i in 0 until c) out[i] = PRIOR_WEIGHT * ln((prior[i] + 0.5) / (pSum + 0.5 * c))

        var knownTok = false
        var bestTriCover = 0.0
        fun add(f: Feat) {
            val arr = counts[f.id] ?: return
            val a = G.ALPHA[f.group]
            val den = logDenom[f.group]
            for (i in 0 until c) out[i] += f.w * (ln(arr[i] + a) - den[i])
        }
        for (f in text.feats) {
            val known = counts.containsKey(f.id)
            if (f.group == G.TOK && known) knownTok = true
            add(f)
        }
        // Trigram coverage per word: "rapdio" still shares most trigrams with "rapido".
        for (t in text.tokens) {
            val tri = Text.trigrams(t)
            if (tri.size < 3) continue
            val k = tri.count { counts.containsKey("g:$it") }
            bestTriCover = max(bestTriCover, k.toDouble() / tri.size)
        }
        for (f in ctx) {
            // Time-of-day only once there is enough timed data to mean anything.
            if (f.group == G.HR && vocabByGroup[G.HR] == 0) continue
            add(f)
        }
        return Scores(cats, out, knownTok || bestTriCover >= 0.6)
    }

    companion object {
        const val HALF_LIFE_DAYS = 180.0
        const val NOTE_HALF_LIFE_DAYS = 60.0
        const val CORRECTION_BOOST = 4.0
        const val PRIOR_WEIGHT = 0.7

        fun decay(age: Long, halfLife: Double): Double = max(0.05, 0.5.pow(age.coerceAtLeast(0) / halfLife))

        fun build(list: List<Expense>, corrections: Collection<Correction>): Model {
            val newest = list.maxOfOrNull { it.date }
            val catIndex = LinkedHashMap<Category, Int>()
            for (e in list) catIndex.getOrPut(e.category) { catIndex.size }
            for (k in corrections) catIndex.getOrPut(k.cat) { catIndex.size }
            val c = catIndex.size
            val cats = Array(c) { Category.OTHER }
            catIndex.forEach { (cat, i) -> cats[i] = cat }

            val prior = DoubleArray(c)
            val counts = HashMap<String, DoubleArray>(4096)
            val totals = Array(G.COUNT) { DoubleArray(c) }
            val notes = HashMap<String, NoteAgg>()
            val byId = HashMap<Long, Category>(list.size * 2)
            val ruleAgree = HashMap<Category, Double>()
            val ruleTotal = HashMap<Category, Double>()
            fun rule(kw: Category, cat: Category, w: Double) {
                ruleTotal.merge(kw, w, Double::plus)
                if (kw == cat) ruleAgree.merge(kw, w, Double::plus)
            }

            fun addFeats(feats: List<Feat>, ci: Int, w: Double) {
                for (f in feats) {
                    val arr = counts.getOrPut(f.id) { DoubleArray(c) }
                    val x = f.w * w
                    arr[ci] += x
                    totals[f.group][ci] += x
                }
            }

            for (e in list) {
                if (e.id != 0L) byId[e.id] = e.category
                val ci = catIndex.getValue(e.category)
                val age = if (newest == null) 0 else ChronoUnit.DAYS.between(e.date, newest)
                val w = decay(age, HALF_LIFE_DAYS)
                prior[ci] += w
                val tf = Text.feats(e.note)
                addFeats(tf.feats, ci, w)
                rule(tf.kw, e.category, w)
                addFeats(Ctx.feats(e.amountPaise, e.date, e.time?.hour, e.place), ci, w)

                val key = noteKey(e.note)
                if (key.isEmpty()) continue
                val agg = notes.getOrPut(key) { NoteAgg(e.note, tf.tokens) }
                val nw = decay(age, NOTE_HALF_LIFE_DAYS)
                agg.weights.merge(e.category, nw, Double::plus)
                agg.total += nw
                agg.count++
                if (e.createdAt >= (agg.lastTsByCat[e.category] ?: Long.MIN_VALUE)) agg.lastTsByCat[e.category] = e.createdAt
                // Newest by date, then by createdAt (imports share a date but keep line order).
                if (agg.lastCat == null || e.createdAt > agg.lastTs) {
                    agg.lastCat = e.category; agg.lastTs = e.createdAt; agg.note = e.note
                }
                e.time?.let { agg.hourSum += it.hour + it.minute / 60.0; agg.hourN++ }
            }

            // Corrections: pseudo-entries with a boost, so similar notes shift too (not just the exact one).
            for (k in corrections) {
                val ci = catIndex.getValue(k.cat)
                val tf = Text.feats(k.note)
                addFeats(tf.feats, ci, CORRECTION_BOOST * k.n.coerceAtMost(3))
                rule(tf.kw, k.cat, CORRECTION_BOOST)
            }

            val vocab = IntArray(G.COUNT)
            for (id in counts.keys) vocab[groupOf(id)]++
            val logDenom = Array(G.COUNT) { g -> DoubleArray(c) { i -> ln(totals[g][i] + G.ALPHA[g] * max(1, vocab[g])) } }

            val tokenIndex = HashMap<String, MutableList<String>>()
            for ((key, agg) in notes) for (t in agg.tokens) tokenIndex.getOrPut(t) { ArrayList(2) } += key

            return Model(list, cats, catIndex, prior, counts, logDenom, vocab, notes, tokenIndex, byId, newest, ruleAgree, ruleTotal)
        }

        private fun groupOf(id: String): Int = when (id[0]) {
            't' -> G.TOK; 'g' -> G.TRI; 'k' -> G.KW; 'a' -> G.AMT; 'h' -> G.HR; 'd' -> G.WD; else -> G.PL
        }
    }
}
