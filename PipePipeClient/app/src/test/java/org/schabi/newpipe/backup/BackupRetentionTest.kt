package org.schabi.newpipe.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class BackupRetentionTest {
    private fun at(time: String) =
        BackupRetention.nameFor(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(time)!!)

    @Test
    fun namesSortByTime() {
        assertEquals("PipePipeBackup-20261013_160502.zip", BackupRetention.nameFor(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-10-13 16:05:02")!!
        ))
        assertTrue(BackupRetention.isBackupName("PipePipeBackup-20261013_160502.zip"))
        assertFalse(BackupRetention.isBackupName("PipePipeBackup-20261013_160502.zip.part"))
        assertFalse(BackupRetention.isBackupName("PipePipeData-20261013_160502.zip"))
        assertEquals(
            listOf(at("2026-10-14 01:00"), at("2026-10-13 23:00")),
            BackupRetention.newestFirst(
                listOf(at("2026-10-13 23:00"), "notes.txt", at("2026-10-14 01:00"))
            )
        )
    }

    @Test
    fun keepsRecentBackups() {
        val names = (0..9).map { at("2026-10-13 1$it:00") }
        val deleted = BackupRetention.backupsToDelete(names, keepRecent = 3, keepDaily = 0)
        assertEquals(names.take(7).toSet(), deleted.toSet())
    }

    @Test
    fun keepsTheLastBackupOfEachDay() {
        // Hourly backups over five days
        val names = (10..14).flatMap { day -> (0..23).map { at("2026-10-$day %02d:00".format(it)) } }
        val kept = names - BackupRetention.backupsToDelete(names, keepRecent = 5, keepDaily = 3)
            .toSet()
        val expected = (19..23).map { at("2026-10-14 $it:00") } +
            at("2026-10-13 23:00") + at("2026-10-12 23:00")
        assertEquals(expected.toSet(), kept.toSet())
    }

    @Test
    fun neverDeletesOtherFilesOrTheNewestBackup() {
        val names = listOf("notes.txt", "PipePipeData-20261013_160502.zip", at("2026-10-13 10:00"))
        assertEquals(emptyList<String>(), BackupRetention.backupsToDelete(names, 0, 0))
    }
}
