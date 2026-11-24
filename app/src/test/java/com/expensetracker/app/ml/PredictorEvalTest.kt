package com.expensetracker.app.ml

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.NotesParser
import com.expensetracker.app.data.learnedCategory
import com.expensetracker.app.data.noteKey
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/**
 * Walk-forward evaluation on the real notes history: for each entry in time order, train on
 * everything before it, predict it, then reveal it (train + learn, as a manual save would).
 *
 * Caveat: the history's labels are what the importer's keyword rules assigned (weak labels), so
 * the old baseline — which falls back to those same rules — is near-perfect by construction on
 * them. The second scenario relabels a few habits the way a user with custom categories would,
 * which is where learning actually matters.
 */
class PredictorEvalTest {

    @Before fun setUp() = Predictor.resetForTesting()
    @After fun tearDown() = Predictor.resetForTesting()

    private fun history(): List<Expense> {
        val text = javaClass.classLoader!!.getResource("history.txt")!!.readText(Charsets.UTF_8)
        return NotesParser.parse(text, 2026).entries
            .sortedWith(compareBy({ it.date }, { it.lineNumber }))
            .map {
                Expense(
                    id = it.lineNumber.toLong(), amountPaise = it.amountPaise, note = it.note, category = it.category,
                    date = it.date, isIncome = it.isIncome,
                    createdAt = it.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + it.lineNumber,
                )
            }
    }

    private class Result(val baseline: Double, val model: Double, val n: Int)

    private fun walkForward(entries: List<Expense>, useLearn: Boolean = true): Result {
        Predictor.resetForTesting()
        var base = 0; var mine = 0
        val seen = ArrayList<Expense>()   // newest first
        for (e in entries) {
            if (learnedCategory(seen, e.note) == e.category) base++
            if (Predictor.predictCategory(e.note, e.amountPaise, null) == e.category) mine++
            seen.add(0, e)
            Predictor.train(ArrayList(seen))
            if (useLearn) Predictor.learn(e)
        }
        return Result(base.toDouble() / entries.size, mine.toDouble() / entries.size, entries.size)
    }

    private fun pct(x: Double) = "%.1f%%".format(x * 100)

    @Test fun walkForwardOnImporterLabels() {
        val r = walkForward(history())
        println("[ml-eval] importer (weak) labels, n=${r.n}: baseline learnedCategory=${pct(r.baseline)}  model=${pct(r.model)}")
        assertTrue("model ${r.model} < baseline ${r.baseline}", r.model >= r.baseline)
    }

    /**
     * A user whose categories don't match the keyword rules: lunches go to a custom
     * "Office food" category, cabs/autos to custom "Commute", protein/creatine to custom
     * "Supplements", and groceries bought at Zepto count as Food. The rules can't know any of this.
     */
    @Test fun walkForwardOnPersonalisedLabels() {
        val office = Category.create("c_office_food")
        val commute = Category.create("c_commute")
        val supplements = Category.create("c_supplements")
        val relabeled = history().map { e ->
            val k = " ${noteKey(e.note)} "
            val c = when {
                e.isIncome -> e.category
                " lunch " in k -> office
                listOf(" rapido ", " auto ", " cab ", " uber ", " ola ", " metro ").any { it in k } -> commute
                " protein " in k || " creatine " in k -> supplements
                " zepto " in k -> Category.FOOD
                else -> e.category
            }
            e.copy(category = c)
        }
        val changed = relabeled.zip(history()).count { (a, b) -> a.category != b.category }
        val noLearn = walkForward(relabeled, useLearn = false)
        val r = walkForward(relabeled)
        println("[ml-eval] personalised labels ($changed of ${r.n} differ from the rules): baseline=${pct(r.baseline)}  model=${pct(r.model)}  (train only, no learn(): ${pct(noLearn.model)})")
        assertTrue("model ${r.model} < baseline ${r.baseline}", r.model >= r.baseline)
    }

    @Test fun trainIsFastFor3000Entries() {
        val base = history().filter { !it.isIncome }
        val big = (0 until 3000).map { i ->
            val e = base[i % base.size]
            e.copy(id = 10_000L + i, date = e.date.minusDays((i / base.size) * 200L))
        }.sortedByDescending { it.date }
        repeat(3) { Predictor.train(ArrayList(big)); Predictor.predictCategory("warm up") }   // JIT warm-up
        val runs = 5
        val t0 = System.nanoTime()
        repeat(runs) { Predictor.train(ArrayList(big)); Predictor.predictCategory("Lunch, Rajma") }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println("[ml-eval] train+first predict on 3000 entries: %.1f ms (JVM)".format(ms))
        assertTrue("too slow: $ms ms", ms < 150)
    }
}
