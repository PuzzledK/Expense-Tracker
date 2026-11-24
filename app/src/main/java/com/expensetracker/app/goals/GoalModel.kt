package com.expensetracker.app.goals

import com.expensetracker.app.data.Expense
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * A savings goal. Pure Kotlin (no Android) so the maths can be unit-tested.
 *
 * Money saved = [startingPaise] + entries linked to this goal (`Expense.goalId == id`, expenses add,
 * income entries count as withdrawals) + manual [adjustments] (positive = added, negative = taken out).
 */
data class Goal(
    val id: String,
    val name: String,
    val emoji: String = "🎯",
    val targetPaise: Long,
    val deadline: LocalDate? = null,
    /** Planned contribution per month; 0 = no plan. */
    val monthlyPlanPaise: Long = 0,
    /** ARGB. */
    val color: Long = GoalColors.first(),
    /** Money you already had for this goal when you created it. Not counted in the monthly rate. */
    val startingPaise: Long = 0,
    val adjustments: List<GoalAdjustment> = emptyList(),
    val archived: Boolean = false,
    val createdOn: LocalDate = LocalDate.now(),
)

/** A manual top-up (amount > 0) or withdrawal (amount < 0) not tied to an entry. */
data class GoalAdjustment(val date: LocalDate, val amountPaise: Long, val note: String = "")

val GoalColors: List<Long> = listOf(
    0xFF00897B, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60, 0xFFEF6C00, 0xFF43A047, 0xFF6D4C41, 0xFF546E7A,
)

enum class GoalStatus(val label: String) {
    DONE("Reached 🎉"),
    ON_TRACK("On track"),
    BEHIND("Behind"),
    OVERDUE("Past deadline"),
    NO_DEADLINE(""),
    NOT_STARTED("Not started"),
}

data class GoalProgress(
    val savedPaise: Long,
    val remainingPaise: Long,
    /** 0..1 (clamped). */
    val fraction: Float,
    /** Average contribution per month so far (excl. starting balance), or the plan when too early to tell. */
    val monthlyRatePaise: Long?,
    /** True when [monthlyRatePaise] came from the plan rather than history. */
    val rateFromPlan: Boolean,
    /** When the goal will be reached at [monthlyRatePaise]; null when done or no rate. */
    val projectedDate: LocalDate?,
    val status: GoalStatus,
    /** Monthly amount needed from now to meet the deadline; null without a deadline or when done. */
    val neededPerMonthPaise: Long?,
)

/** One contribution towards a goal, from an entry or a manual adjustment. */
data class GoalContribution(val date: LocalDate, val amountPaise: Long)

object GoalMath {
    private const val DAYS_PER_MONTH = 30.4375

    /** Signed contributions excluding the starting balance: entries (income = withdrawal) + adjustments. */
    fun contributions(goal: Goal, expenses: List<Expense>): List<GoalContribution> =
        expenses.filter { it.goalId == goal.id }.map {
            GoalContribution(it.date, if (it.isIncome) -it.amountPaise else it.amountPaise)
        } + goal.adjustments.map { GoalContribution(it.date, it.amountPaise) }

    fun saved(goal: Goal, expenses: List<Expense>): Long =
        goal.startingPaise + contributions(goal, expenses).sumOf { it.amountPaise }

    fun progress(goal: Goal, expenses: List<Expense>, today: LocalDate = LocalDate.now()): GoalProgress {
        val contribs = contributions(goal, expenses)
        val saved = goal.startingPaise + contribs.sumOf { it.amountPaise }
        val remaining = max(0L, goal.targetPaise - saved)
        val fraction = if (goal.targetPaise <= 0) 1f else (saved.toDouble() / goal.targetPaise).coerceIn(0.0, 1.0).toFloat()

        // Average monthly rate since the first contribution (or since the goal was created).
        val start = (contribs.map { it.date } + goal.createdOn).min()
        val days = ChronoUnit.DAYS.between(start, today).coerceAtLeast(0) + 1
        val months = days / DAYS_PER_MONTH
        val net = contribs.sumOf { it.amountPaise }
        val historyRate = if (net > 0) (net / max(1.0, months)).toLong() else 0L
        val plan = goal.monthlyPlanPaise.takeIf { it > 0 }
        // Under a month of history says little; trust the plan then.
        val (rate, fromPlan) = when {
            plan != null && months < 1.0 -> plan to true
            historyRate > 0 -> historyRate to false
            plan != null -> plan to true
            else -> null to false
        }

        val projected = if (remaining == 0L || rate == null || rate <= 0) null
        else today.plusDays(ceil(remaining.toDouble() / rate * DAYS_PER_MONTH).toLong())

        val needed = goal.deadline?.takeIf { remaining > 0 }?.let { dl ->
            val monthsLeft = ChronoUnit.DAYS.between(today, dl) / DAYS_PER_MONTH
            if (monthsLeft <= 0) remaining else ceil(remaining / max(1.0, monthsLeft)).toLong()
        }

        val status = when {
            remaining == 0L -> GoalStatus.DONE
            goal.deadline != null && today.isAfter(goal.deadline) -> GoalStatus.OVERDUE
            goal.deadline == null -> if (saved <= 0 && rate == null) GoalStatus.NOT_STARTED else GoalStatus.NO_DEADLINE
            projected == null -> if (saved <= 0) GoalStatus.NOT_STARTED else GoalStatus.BEHIND
            projected.isAfter(goal.deadline) -> GoalStatus.BEHIND
            else -> GoalStatus.ON_TRACK
        }
        return GoalProgress(saved, remaining, fraction, rate, fromPlan, projected, status, needed)
    }
}

/**
 * Plain-text format for SharedPreferences and backups (no org.json so it works in JVM tests).
 * ```
 * goals-v1
 * G <id> <name> <emoji> <target> <deadline|-> <plan> <colorHex> <starting> <archived 0/1> <createdOn>
 * A <goalId> <date> <amount> <note>
 * ```
 * Fields are tab-separated and URL-encoded; goal order = line order.
 */
object GoalCodec {
    private const val HEADER = "goals-v1"

    fun encode(goals: List<Goal>): String = buildString {
        append(HEADER)
        goals.forEach { g ->
            append('\n')
            append(
                listOf(
                    "G", g.id, g.name, g.emoji, g.targetPaise.toString(), g.deadline?.toString() ?: "-",
                    g.monthlyPlanPaise.toString(), java.lang.Long.toHexString(g.color), g.startingPaise.toString(),
                    if (g.archived) "1" else "0", g.createdOn.toString(),
                ).joinToString("\t") { enc(it) },
            )
            g.adjustments.forEach { a ->
                append('\n')
                append(listOf("A", g.id, a.date.toString(), a.amountPaise.toString(), a.note).joinToString("\t") { enc(it) })
            }
        }
    }

    /** Lenient: unknown or broken lines are skipped. Blank text = no goals. */
    fun decode(text: String): List<Goal> {
        val goals = LinkedHashMap<String, Goal>()
        val adjustments = HashMap<String, MutableList<GoalAdjustment>>()
        text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() && it != HEADER }.forEach { line ->
            val p = line.split('\t').map { dec(it) }
            runCatching {
                when (p[0]) {
                    "G" -> if (p.size >= 11 && p[1].isNotBlank()) goals[p[1]] = Goal(
                        id = p[1], name = p[2], emoji = p[3].ifBlank { "🎯" }, targetPaise = p[4].toLong(),
                        deadline = p[5].takeIf { it != "-" && it.isNotBlank() }?.let(LocalDate::parse),
                        monthlyPlanPaise = p[6].toLong(), color = p[7].toLong(16), startingPaise = p[8].toLong(),
                        archived = p[9] == "1", createdOn = LocalDate.parse(p[10]),
                    )
                    "A" -> if (p.size >= 5) adjustments.getOrPut(p[1]) { mutableListOf() }
                        .add(GoalAdjustment(LocalDate.parse(p[2]), p[3].toLong(), p[4]))
                }
            }
        }
        return goals.values.map { g -> g.copy(adjustments = adjustments[g.id].orEmpty()) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}
