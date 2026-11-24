package com.expensetracker.app.ml

import com.expensetracker.app.data.Category
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.QuickTemplate
import com.expensetracker.app.data.frequentTemplates
import com.expensetracker.app.data.learnedCategory
import java.time.LocalDateTime

/**
 * STUB — owned by the "ml" session, which replaces the internals with a self-improving model.
 * The public API below is the contract; callers: ExpenseViewModel, QuickLog, capture/.
 * Must be thread-safe (called from the main thread and background workers) and fast:
 * train() runs after every data change on lists of a few thousand entries.
 */
object Predictor {

    data class Prediction(val note: String, val category: Category, val confidence: Double)

    @Volatile private var all: List<Expense> = emptyList()

    /** Rebuild from the full history (newest first). Called after every load/change. */
    fun train(all: List<Expense>) {
        this.all = all
    }

    /** A manual add/edit was saved — strongest learning signal (call after train is fine). */
    fun learn(saved: Expense) = Unit

    /** Category for a typed note, using any context available. */
    fun predictCategory(note: String, amountPaise: Long? = null, at: LocalDateTime? = null, place: String? = null): Category =
        learnedCategory(all, note)

    /** For an auto-captured payment: guess the note you'd write and its category from payee/amount/time/place. */
    fun predictPayment(payee: String, amountPaise: Long, at: LocalDateTime, place: String? = null): Prediction =
        Prediction(payee, learnedCategory(all, payee), 0.0)

    /** One-tap suggestions for right now (e.g. lunch at 1 pm, cab in the evening). */
    fun suggestions(now: LocalDateTime, place: String? = null, limit: Int = 8): List<QuickTemplate> =
        frequentTemplates(all, now.toLocalDate(), limit)
}
