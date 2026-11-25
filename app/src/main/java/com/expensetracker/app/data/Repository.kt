package com.expensetracker.app.data

import android.content.Context
import androidx.core.content.edit

/** Settings live in SharedPreferences "settings" (Reminders reads the same keys on boot). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = Settings(
        monthlyBudgetPaise = prefs.getLong("monthlyBudgetPaise", 0),
        categoryBudgets = CategoryBudgets.decode(prefs.getString("categoryBudgets", "")),
        excludeSavingsFromSpend = prefs.getBoolean("excludeSavingsFromSpend", true),
        reminderEnabled = prefs.getBoolean("reminderEnabled", false),
        reminderHour = prefs.getInt("reminderHour", 22),
        reminderMinute = prefs.getInt("reminderMinute", 0),
        quickLogNotification = prefs.getBoolean("quickLogNotification", false),
        locationTagging = prefs.getBoolean("locationTagging", false),
        homeCity = prefs.getString("homeCity", "") ?: "",
        appLock = prefs.getBoolean("appLock", false),
        lockTimeoutSec = prefs.getInt("lockTimeoutSec", 30),
        captureEnabled = prefs.getBoolean("captureEnabled", false),
        autoBackupEnabled = prefs.getBoolean("autoBackupEnabled", false),
        autoBackupTreeUri = prefs.getString("autoBackupTreeUri", "") ?: "",
        autoBackupEveryDays = prefs.getInt("autoBackupEveryDays", 1),
        autoBackupKeep = prefs.getInt("autoBackupKeep", 14),
        insightsNotification = prefs.getBoolean("insightsNotification", false),
        themeId = prefs.getString("themeId", "classic") ?: "classic",
        darkMode = prefs.getInt("darkMode", 0),
        pureBlack = prefs.getBoolean("pureBlack", false),
    )

    fun save(s: Settings) = prefs.edit {
        putLong("monthlyBudgetPaise", s.monthlyBudgetPaise)
        putString("categoryBudgets", CategoryBudgets.encode(s.categoryBudgets))
        putBoolean("excludeSavingsFromSpend", s.excludeSavingsFromSpend)
        putBoolean("reminderEnabled", s.reminderEnabled)
        putInt("reminderHour", s.reminderHour)
        putInt("reminderMinute", s.reminderMinute)
        putBoolean("quickLogNotification", s.quickLogNotification)
        putBoolean("locationTagging", s.locationTagging)
        putString("homeCity", s.homeCity)
        putBoolean("appLock", s.appLock)
        putInt("lockTimeoutSec", s.lockTimeoutSec)
        putBoolean("captureEnabled", s.captureEnabled)
        putBoolean("autoBackupEnabled", s.autoBackupEnabled)
        putString("autoBackupTreeUri", s.autoBackupTreeUri)
        putInt("autoBackupEveryDays", s.autoBackupEveryDays)
        putInt("autoBackupKeep", s.autoBackupKeep)
        putBoolean("insightsNotification", s.insightsNotification)
        putString("themeId", s.themeId)
        putInt("darkMode", s.darkMode)
        putBoolean("pureBlack", s.pureBlack)
    }
}

/** Category you last gave the same note (list is newest first); otherwise the keyword guess. */
fun learnedCategory(all: List<Expense>, note: String): Category {
    val key = noteKey(note)
    if (key.isNotEmpty()) all.firstOrNull { noteKey(it.note) == key }?.let { return it.category }
    return Categorizer.guess(note)
}

/** Entries repeated at least twice in the last 90 days, most frequent first. */
fun frequentTemplates(list: List<Expense>, today: java.time.LocalDate = java.time.LocalDate.now(), limit: Int = 8): List<QuickTemplate> {
    val since = today.minusDays(90)
    return list.asSequence()
        .filter { !it.isIncome && it.date >= since }
        .groupBy { noteKey(it.note) to it.amountPaise }
        .filter { it.key.first.isNotEmpty() && it.value.size >= 2 }
        .map { (_, items) ->
            val latest = items.first()
            QuickTemplate(latest.note, latest.amountPaise, latest.category, items.size)
        }
        .sortedByDescending { it.count }
        .take(limit)
        .toList()
}

/** Normalized key used for duplicate detection and learning categories from history. */
internal fun noteKey(note: String) = note.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
