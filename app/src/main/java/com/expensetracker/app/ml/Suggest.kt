package com.expensetracker.app.ml

import com.expensetracker.app.data.QuickTemplate
import com.expensetracker.app.data.noteKey
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.pow

/**
 * Context-aware one-tap templates: each (note, amount) combo you've logged at least twice in the
 * last 120 days, scored by Σ recency × (base + hour match) × weekday match × place match.
 * Entries without a clock time use the mean hour of the same note's timed entries, or the hour the
 * words imply ("lunch" ≈ 1 pm), or count as neutral.
 */
internal object Suggest {
    private const val WINDOW_DAYS = 120L
    private const val HALF_LIFE = 30.0

    fun rank(m: Model, now: LocalDateTime, place: String?, limit: Int): List<QuickTemplate> {
        val today = now.toLocalDate()
        val since = today.minusDays(WINDOW_DAYS)
        val hourNow = now.hour + now.minute / 60.0
        val placeNorm = place?.let { Text.norm(it) }?.takeIf { it.isNotEmpty() }

        class Acc(val first: com.expensetracker.app.data.Expense) { var score = 0.0; var count = 0 }
        val groups = LinkedHashMap<Pair<String, Long>, Acc>()   // list is newest first → first = latest
        for (e in m.list) {
            if (e.isIncome || e.date < since || e.date > today) continue
            val key = noteKey(e.note)
            if (key.isEmpty()) continue
            val acc = groups.getOrPut(key to e.amountPaise) { Acc(e) }
            acc.count++
            val age = ChronoUnit.DAYS.between(e.date, today)
            val rec = 0.5.pow(age / HALF_LIFE)
            val h = e.time?.let { it.hour + it.minute / 60.0 }
                ?: m.notes[key]?.meanHour()
                ?: Ctx.impliedHour(Text.feats(e.note).tokens)?.toDouble()
            val hourSim = if (h == null) 0.35 else exp(-Predictor.hourDiff(hourNow, h).let { it * it } / 8.0)
            val wd = when {
                e.date.dayOfWeek == today.dayOfWeek -> 1.3
                Ctx.isWeekend(e.date.dayOfWeek) == Ctx.isWeekend(today.dayOfWeek) -> 1.1
                else -> 0.8
            }
            val pl = when {
                placeNorm == null || e.place.isNullOrBlank() -> 1.0
                Text.norm(e.place) == placeNorm -> 1.5
                else -> 0.6
            }
            acc.score += rec * (0.25 + hourSim) * wd * pl
        }
        return groups.values.asSequence()
            .filter { it.count >= 2 }
            .sortedByDescending { it.score }
            .take(limit)
            .map { QuickTemplate(it.first.note, it.first.amountPaise, it.first.category, it.count) }
            .toList()
    }
}
