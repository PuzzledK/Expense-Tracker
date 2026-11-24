package com.expensetracker.app.backup

import android.content.Context
import com.expensetracker.app.categories.CategoryRules
import com.expensetracker.app.data.BackupIo
import com.expensetracker.app.data.CategoryRegistry
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Settings
import com.expensetracker.app.goals.GoalCodec
import com.expensetracker.app.goals.GoalStore

/** Builds and applies the complete JSON backup (entries + settings + categories + goals). */
object BackupData {

    fun toJson(ctx: Context, expenses: List<Expense>, settings: Settings): String {
        CategoryRegistry.ensureLoaded(ctx)
        val goals = runCatching { GoalStore.exportText(ctx) }.getOrNull()
        return BackupIo.toJson(expenses, settings, CategoryRules.forBackup(CategoryRegistry.allInfo()), goals)
    }

    /**
     * Applies the backup's categories and goals. Call before inserting its entries.
     * Runs on a background thread (SharedPreferences/file writes only).
     */
    fun applyExtras(ctx: Context, content: BackupIo.Content, replace: Boolean) {
        CategoryRegistry.ensureLoaded(ctx)
        content.categories?.let { backup ->
            CategoryRegistry.save(ctx, CategoryRules.mergeForRestore(CategoryRegistry.allInfo(), backup, replace))
        }
        content.goalsText?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching {
                if (replace) {
                    GoalStore.importText(ctx, text)
                } else {
                    // Merge: keep this phone's goals, add the backup's other goals.
                    GoalStore.ensureLoaded(ctx)
                    val current = GoalStore.goals.value
                    val extra = GoalCodec.decode(text).filter { b -> current.none { it.id == b.id } }
                    if (extra.isNotEmpty()) GoalStore.save(ctx, current + extra)
                }
            }
        }
    }
}
