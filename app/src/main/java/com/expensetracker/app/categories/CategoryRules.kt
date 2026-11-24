package com.expensetracker.app.categories

import com.expensetracker.app.data.CategoryInfo
import com.expensetracker.app.data.CategoryRegistry

/**
 * Pure list operations behind the Categories screen (no Android, so they're unit-tested).
 * Every function takes the full list as returned by [CategoryRegistry.allInfo] and returns the
 * new full list to pass to [CategoryRegistry.save].
 */
object CategoryRules {

    const val OTHER = "OTHER"
    const val MAX_LABEL = 30
    const val MAX_EMOJI = 8

    fun isBuiltIn(id: String) = id in CategoryRegistry.DEFAULTS

    /** null when hiding is allowed, else the reason it isn't. Unhiding is always allowed. */
    fun hideBlockedReason(list: List<CategoryInfo>, id: String): String? {
        val c = list.firstOrNull { it.id == id } ?: return "Unknown category"
        if (c.hidden) return null
        if (id == OTHER) return "“Other” is the fallback for unknown entries and can't be hidden"
        if (list.count { !it.hidden } <= 1) return "At least one category has to stay visible"
        return null
    }

    fun setHidden(list: List<CategoryInfo>, id: String, hidden: Boolean): List<CategoryInfo> {
        if (hidden && hideBlockedReason(list, id) != null) return list
        return list.map { if (it.id == id) it.copy(hidden = hidden) else it }
    }

    /** True when a built-in differs from its default (so "Reset to default" makes sense). */
    fun isEdited(info: CategoryInfo): Boolean = CategoryRegistry.DEFAULTS[info.id]?.let { it != info } ?: false

    /** Built-ins go back to their default label/emoji/colour. Keeps the hidden flag. */
    fun resetToDefault(list: List<CategoryInfo>, id: String): List<CategoryInfo> {
        val d = CategoryRegistry.DEFAULTS[id] ?: return list
        return list.map { if (it.id == id) d.copy(hidden = it.hidden) else it }
    }

    /** null when the label is fine; [exceptId] is the category being edited. */
    fun labelError(list: List<CategoryInfo>, label: String, exceptId: String?): String? {
        val t = label.trim()
        if (t.isEmpty()) return "Enter a name"
        if (t.length > MAX_LABEL) return "Keep it under $MAX_LABEL characters"
        if (list.any { it.id != exceptId && it.label.trim().equals(t, ignoreCase = true) }) return "“$t” already exists"
        return null
    }

    /** Tabs/newlines would break the stored format; also caps the length. */
    fun cleanLabel(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_LABEL)

    fun cleanEmoji(s: String): String {
        val t = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
        return if (t.isEmpty()) "🏷️" else t.take(MAX_EMOJI)
    }

    /** Edits label/emoji/colour of any category (built-in or custom). */
    fun edit(list: List<CategoryInfo>, id: String, label: String, emoji: String, color: Long): List<CategoryInfo> =
        list.map { if (it.id == id) it.copy(label = cleanLabel(label), emoji = cleanEmoji(emoji), color = color) else it }

    /** Appends a new custom category (customs keep the order they're added in). */
    fun add(list: List<CategoryInfo>, id: String, label: String, emoji: String, color: Long): List<CategoryInfo> =
        list + CategoryInfo(id, cleanLabel(label), cleanEmoji(emoji), color)

    /** Only custom categories can be deleted (move their entries first). */
    fun canDelete(id: String) = !isBuiltIn(id)

    fun delete(list: List<CategoryInfo>, id: String): List<CategoryInfo> =
        if (canDelete(id)) list.filterNot { it.id == id } else list

    /** Categories entries can be moved into from [fromId] (visible ones first, never itself). */
    fun moveTargets(list: List<CategoryInfo>, fromId: String): List<CategoryInfo> =
        list.filter { it.id != fromId }.sortedBy { it.hidden }

    /**
     * After moving all entries of [fromId] into another category: custom ones are deleted,
     * built-ins are hidden (Other is never touched).
     */
    fun afterMerge(list: List<CategoryInfo>, fromId: String): List<CategoryInfo> = when {
        fromId == OTHER -> list
        canDelete(fromId) -> delete(list, fromId)
        else -> setHidden(list, fromId, true)
    }

    /** Moves a custom category one place up (-1) or down (+1) among the custom ones. */
    fun moveCustom(list: List<CategoryInfo>, id: String, delta: Int): List<CategoryInfo> {
        val builtIns = list.filter { isBuiltIn(it.id) }
        val customs = list.filterNot { isBuiltIn(it.id) }.toMutableList()
        val i = customs.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in customs.indices) return list
        customs.add(j, customs.removeAt(i))
        return builtIns + customs
    }

    /** What a backup stores: built-ins that differ from default + all customs, in order. */
    fun forBackup(list: List<CategoryInfo>): List<CategoryInfo> =
        list.filter { CategoryRegistry.DEFAULTS[it.id] != it }

    /**
     * Categories after restoring a backup that carried [backup] (edited built-ins + customs).
     * Replace: the backup's look wins entirely (built-ins it doesn't mention go back to default;
     * custom categories that are only on this phone are kept so none of its entries lose theirs).
     * Merge: this phone's categories win; customs only in the backup are added.
     */
    fun mergeForRestore(current: List<CategoryInfo>, backup: List<CategoryInfo>, replace: Boolean): List<CategoryInfo> {
        val backupById = backup.associateBy { it.id }
        val builtIns = CategoryRegistry.DEFAULTS.values.map { d ->
            if (replace) backupById[d.id] ?: d else current.firstOrNull { it.id == d.id } ?: backupById[d.id] ?: d
        }
        val backupCustoms = backup.filterNot { isBuiltIn(it.id) }
        val currentCustoms = current.filterNot { isBuiltIn(it.id) }
        val customs = if (replace) {
            backupCustoms + currentCustoms.filter { c -> backupCustoms.none { it.id == c.id } }
        } else {
            currentCustoms + backupCustoms.filter { b -> currentCustoms.none { it.id == b.id } }
        }
        val merged = builtIns + customs
        // Never end up with nothing visible.
        return if (merged.none { !it.hidden }) merged.map { if (it.id == OTHER) it.copy(hidden = false) else it } else merged
    }
}
