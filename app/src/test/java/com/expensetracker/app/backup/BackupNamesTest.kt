package com.expensetracker.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackupNamesTest {

    @Test fun fileNameFormat() {
        assertEquals("expenses-backup-2026-11-05-0907.json", BackupNames.fileName(LocalDateTime.of(2026, 11, 5, 9, 7, 59)))
        assertEquals("expenses-backup-2026-12-31-2359.json", BackupNames.fileName(LocalDateTime.of(2026, 12, 31, 23, 59)))
    }

    @Test fun stampParsesOnlyOurFiles() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 7), BackupNames.stampOf("expenses-backup-2026-11-05-0907.json"))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 7), BackupNames.stampOf("expenses-backup-2026-11-05-0907 (1).json"))
        assertNull(BackupNames.stampOf("expenses-backup-2026-11-05.json"))
        assertNull(BackupNames.stampOf("my-expenses-backup-2026-11-05-0907.json"))
        assertNull(BackupNames.stampOf("expenses-backup-2026-11-05-0907.json.bak"))
        assertNull(BackupNames.stampOf("expenses-backup-2026-13-45-9999.json"))
        assertNull(BackupNames.stampOf("Expenses-2026-11-05.json"))
        assertNull(BackupNames.stampOf("photo.jpg"))
    }

    @Test fun keepsNewestAndIgnoresOtherFiles() {
        val names = listOf(
            "expenses-backup-2026-11-01-2200.json",
            "notes.txt",
            "expenses-backup-2026-11-03-2200.json",
            "expenses-backup-2026-10-30-2200.json",
            "Manual backup 2026-11-02.json",
            "expenses-backup-2026-11-02-2200.json",
        )
        assertEquals(
            listOf("expenses-backup-2026-11-01-2200.json", "expenses-backup-2026-10-30-2200.json"),
            BackupNames.filesToDelete(names, keep = 2),
        )
        assertEquals(emptyList<String>(), BackupNames.filesToDelete(names, keep = 7))
        assertEquals(emptyList<String>(), BackupNames.filesToDelete(listOf("a.json", "b.txt"), keep = 1))
    }

    @Test fun keepIsAtLeastOne() {
        val names = listOf("expenses-backup-2026-11-01-2200.json", "expenses-backup-2026-11-02-2200.json")
        assertEquals(listOf("expenses-backup-2026-11-01-2200.json"), BackupNames.filesToDelete(names, keep = 0))
    }

    @Test fun providerCopiesSortAsNewerWithinSameMinute() {
        val names = listOf(
            "expenses-backup-2026-11-02-2200.json",
            "expenses-backup-2026-11-02-2200 (1).json",
            "expenses-backup-2026-11-01-2200.json",
        )
        assertEquals(
            listOf(
                "expenses-backup-2026-11-02-2200 (1).json",
                "expenses-backup-2026-11-02-2200.json",
                "expenses-backup-2026-11-01-2200.json",
            ),
            BackupNames.newestFirst(names),
        )
    }

    @Test fun dueRules() {
        val day = BackupNames.DAY_MS
        val now = 1_000L * day
        assertTrue(BackupNames.isDue(0, 1, now))
        assertTrue(BackupNames.isDue(now + 5000, 1, now)) // clock went backwards
        assertFalse(BackupNames.isDue(now - 10 * 3_600_000L, 1, now))
        assertTrue(BackupNames.isDue(now - 22 * 3_600_000L, 1, now)) // within slack of a day
        assertFalse(BackupNames.isDue(now - 2 * day, 3, now))
        assertTrue(BackupNames.isDue(now - 3 * day, 3, now))
        assertFalse(BackupNames.isDue(now - 6 * day + 4 * 3_600_000L, 7, now))
        assertTrue(BackupNames.isDue(now - 7 * day, 7, now))
    }

    @Test fun errorTexts() {
        assertEquals("Not enough storage space", BackupNames.describeError(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("No access to the folder — choose it again", BackupNames.describeError(SecurityException("Permission Denial")))
    }
}
