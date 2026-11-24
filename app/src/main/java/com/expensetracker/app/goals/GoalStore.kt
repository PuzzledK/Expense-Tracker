package com.expensetracker.app.goals

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Savings goals, kept in SharedPreferences "goals" (key "data", [GoalCodec] text). */
object GoalStore {
    private const val PREFS = "goals"
    private const val KEY = "data"

    private val _goals = MutableStateFlow<List<Goal>>(emptyList())
    /** All goals incl. archived, in your order. Call [ensureLoaded] first. */
    val goals: StateFlow<List<Goal>> = _goals.asStateFlow()
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (!loaded) load(ctx)
    }

    fun load(ctx: Context) {
        _goals.value = GoalCodec.decode(prefs(ctx).getString(KEY, "") ?: "")
        loaded = true
    }

    fun save(ctx: Context, list: List<Goal>) {
        _goals.value = list
        loaded = true
        prefs(ctx).edit().putString(KEY, GoalCodec.encode(list)).apply()
    }

    fun upsert(ctx: Context, goal: Goal) {
        ensureLoaded(ctx)
        val list = _goals.value
        save(ctx, if (list.any { it.id == goal.id }) list.map { if (it.id == goal.id) goal else it } else list + goal)
    }

    fun remove(ctx: Context, id: String) {
        ensureLoaded(ctx)
        save(ctx, _goals.value.filterNot { it.id == id })
    }

    /** Moves a goal one place up (-1) or down (+1) in the list. */
    fun move(ctx: Context, id: String, delta: Int) {
        ensureLoaded(ctx)
        val list = _goals.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        save(ctx, list)
    }

    fun newId(): String = "g_" + System.currentTimeMillis().toString(36)

    /** All goals as text, for JSON backups (backup session). */
    fun exportText(ctx: Context): String {
        ensureLoaded(ctx)
        return GoalCodec.encode(_goals.value)
    }

    /**
     * Replaces all goals with those in [text] (from [exportText]). Blank text or text with no
     * readable goals leaves your goals untouched. Returns the number of goals restored.
     */
    fun importText(ctx: Context, text: String): Int {
        val list = GoalCodec.decode(text)
        if (list.isEmpty()) return 0
        save(ctx, list)
        return list.size
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
