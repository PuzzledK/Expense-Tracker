package com.expensetracker.app.backup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure naming / rotation / scheduling rules for automatic backups (unit-tested). */
object BackupNames {

    const val PREFIX = "expenses-backup-"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm", Locale.ROOT)

    /** Also accepts the " (1)" suffix some providers add when a name already exists. */
    private val PATTERN = Regex("""^expenses-backup-(\d{4}-\d{2}-\d{2}-\d{4})(?: ?\((\d+)\))?\.json$""")

    const val DAY_MS = 24L * 60 * 60 * 1000
    /** A run counts as due this much early, so a daily alarm that fires a bit early isn't skipped. */
    const val SLACK_MS = 3L * 60 * 60 * 1000

    fun fileName(at: LocalDateTime): String = PREFIX + at.format(STAMP) + ".json"

    /** Time encoded in an automatic backup's name; null for any other file (never touched). */
    fun stampOf(name: String): LocalDateTime? {
        val m = PATTERN.matchEntire(name) ?: return null
        return runCatching { LocalDateTime.parse(m.groupValues[1], STAMP) }.getOrNull()
    }

    fun isBackupName(name: String) = stampOf(name) != null

    /** Our backup files, newest first (same minute: the provider's "(n)" copies count as newer). */
    fun newestFirst(names: List<String>): List<String> =
        names.filter { isBackupName(it) }.sortedWith(
            compareByDescending<String> { stampOf(it) }
                .thenByDescending { PATTERN.matchEntire(it)?.groupValues?.get(2)?.toIntOrNull() ?: 0 },
        )

    /** Which files to delete so only the newest [keep] backups remain. Other files are ignored. */
    fun filesToDelete(names: List<String>, keep: Int): List<String> =
        newestFirst(names).drop(keep.coerceAtLeast(1))

    /** Whether a scheduled run should back up now. Never backed up (0) = due. */
    fun isDue(lastSuccessMs: Long, everyDays: Int, nowMs: Long): Boolean {
        if (lastSuccessMs <= 0L || lastSuccessMs > nowMs) return true
        return nowMs - lastSuccessMs >= everyDays.coerceAtLeast(1) * DAY_MS - SLACK_MS
    }

    /** "Every day" / "Every 3 days" / "Every week". */
    fun frequencyLabel(days: Int) = when (days) {
        1 -> "Every day"
        7 -> "Every week"
        else -> "Every $days days"
    }

    /** Friendly text for storage/provider errors. */
    fun describeError(t: Throwable): String {
        val msg = (t.message ?: t.javaClass.simpleName)
        return when {
            t is SecurityException -> "No access to the folder — choose it again"
            msg.contains("ENOSPC", true) || msg.contains("No space", true) -> "Not enough storage space"
            msg.contains("ENOENT", true) || msg.contains("not found", true) || msg.contains("FileNotFound", true) ->
                "The backup folder was deleted or moved — choose it again"
            else -> msg
        }
    }
}
