package com.expensetracker.app.data

import android.content.Context

/** Which entries a rule looks at. */
enum class RuleKind { ANY, EXPENSE, INCOME }

/**
 * One user rule: "if the note contains any of [keywords] (and the amount/kind fit), then set
 * [category] / [countsAs] / rename the note to [rename]". Null actions leave that part alone.
 */
data class AutoRule(
    val id: Long,
    val keywords: List<String>,
    val minPaise: Long? = null,
    val maxPaise: Long? = null,
    val kind: RuleKind = RuleKind.ANY,
    val categoryId: String? = null,
    val countsAs: SpendType? = null,
    val rename: String? = null,
) {
    val hasAction: Boolean get() = categoryId != null || countsAs != null || !rename.isNullOrBlank()

    fun matches(note: String, amountPaise: Long, isIncome: Boolean): Boolean {
        if (kind == RuleKind.EXPENSE && isIncome) return false
        if (kind == RuleKind.INCOME && !isIncome) return false
        if (minPaise != null && amountPaise < minPaise) return false
        if (maxPaise != null && amountPaise > maxPaise) return false
        val text = note.lowercase()
        return keywords.any { k -> k.isNotBlank() && text.contains(k.trim().lowercase()) }
    }
}

/**
 * Your own auto-tagging rules. Ordered: the first matching rule wins. They beat the learned
 * category guess (ml/) because you wrote them on purpose.
 */
object AutoRules {

    fun firstMatch(rules: List<AutoRule>, note: String, amountPaise: Long, isIncome: Boolean): AutoRule? =
        rules.firstOrNull { it.hasAction && it.matches(note, amountPaise, isIncome) }

    /**
     * A new entry with the matching rule applied. [renameNote]/[setCategory] are off when you
     * typed those yourself (the add sheet). "Counts as" only fills in when you didn't pick one.
     */
    fun apply(rules: List<AutoRule>, e: Expense, setCategory: Boolean = true, renameNote: Boolean = true): Expense {
        val r = firstMatch(rules, e.note, e.amountPaise, e.isIncome) ?: return e
        return applyRule(r, e, setCategory, renameNote, overrideCountsAs = false)
    }

    private fun applyRule(r: AutoRule, e: Expense, setCategory: Boolean, renameNote: Boolean, overrideCountsAs: Boolean): Expense {
        var out = e
        if (setCategory && r.categoryId != null && !e.isTransfer) out = out.copy(category = Category.fromId(r.categoryId))
        if (renameNote && !r.rename.isNullOrBlank()) out = out.copy(note = r.rename.trim())
        if (r.countsAs != null && !e.isIncome && !e.isTransfer && (overrideCountsAs || e.spendOverride == null)) out = out.copy(spendOverride = r.countsAs)
        return out
    }

    /** Past entries the rules would change (each paired with its new version). Transfers are left alone. */
    fun changesForPast(rules: List<AutoRule>, all: List<Expense>): List<Pair<Expense, Expense>> =
        all.mapNotNull { e ->
            if (e.isTransfer) return@mapNotNull null
            val r = firstMatch(rules, e.note, e.amountPaise, e.isIncome) ?: return@mapNotNull null
            val n = applyRule(r, e, setCategory = true, renameNote = true, overrideCountsAs = true)
            if (n != e) e to n else null
        }

    // ------------------------------------------------------------------ storage

    /** One rule per line, fields split by TAB, keywords by '|'. Text fields are escaped. */
    fun encode(rules: List<AutoRule>): String = rules.joinToString("\n") { r ->
        listOf(
            r.id.toString(),
            r.keywords.filter { it.isNotBlank() }.joinToString("|") { esc(it.trim()).replace("|", "\\p") },
            r.minPaise?.toString() ?: "",
            r.maxPaise?.toString() ?: "",
            r.kind.name,
            r.categoryId ?: "",
            r.countsAs?.name ?: "",
            esc(r.rename ?: ""),
        ).joinToString("\t")
    }

    fun decode(text: String?): List<AutoRule> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 8) return@mapNotNull null
            val id = p[0].toLongOrNull() ?: return@mapNotNull null
            val keywords = p[1].split('|').map { unesc(it) }.filter { it.isNotBlank() }
            if (keywords.isEmpty()) return@mapNotNull null
            AutoRule(
                id = id,
                keywords = keywords,
                minPaise = p[2].toLongOrNull(),
                maxPaise = p[3].toLongOrNull(),
                kind = runCatching { RuleKind.valueOf(p[4]) }.getOrDefault(RuleKind.ANY),
                categoryId = p[5].ifBlank { null },
                countsAs = p[6].takeIf { it.isNotBlank() }?.let { n -> runCatching { SpendType.valueOf(n) }.getOrNull() },
                rename = unesc(p[7]).ifBlank { null },
            )
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'p' -> sb.append('|'); '\\' -> sb.append('\\')
                    else -> { sb.append(c); sb.append(s[i + 1]) }
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Rules as saved in settings, for code that runs outside the app's ViewModel (widget, notification). */
    fun saved(ctx: Context): List<AutoRule> =
        decode(ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(PREF_KEY, ""))

    const val PREF_KEY = "autoRules"
}
