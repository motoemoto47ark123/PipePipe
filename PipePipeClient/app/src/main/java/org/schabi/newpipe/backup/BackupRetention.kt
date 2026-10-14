package org.schabi.newpipe.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Names backup files and decides which old backups are deleted. */
object BackupRetention {
    private const val PREFIX = "PipePipeBackup-"
    private const val SUFFIX = ".zip"
    private val NAME_PATTERN = Regex("""PipePipeBackup-(\d{8})_(\d{6})\.zip""")

    /** The name of a backup made at [date], for example PipePipeBackup-20261013_160000.zip. */
    @JvmStatic
    fun nameFor(date: Date): String =
        PREFIX + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date) + SUFFIX

    @JvmStatic
    fun isBackupName(name: String): Boolean = NAME_PATTERN.matches(name)

    /** Backup names, newest first. Other files are ignored. */
    @JvmStatic
    fun newestFirst(names: Collection<String>): List<String> =
        names.filter(::isBackupName).sortedDescending()

    /**
     * Picks the backups to delete. The [keepRecent] newest backups are kept, and so is the newest
     * backup of each of the [keepDaily] most recent days, so that a few bad backups can never
     * push out every good one. Files that are not backups are never deleted.
     */
    @JvmStatic
    fun backupsToDelete(names: Collection<String>, keepRecent: Int, keepDaily: Int): List<String> {
        val backups = newestFirst(names)
        val keep = HashSet(backups.take(keepRecent.coerceAtLeast(1)))
        val days = HashSet<String>()
        for (name in backups) {
            val day = NAME_PATTERN.matchEntire(name)!!.groupValues[1]
            if (day !in days && days.size < keepDaily) {
                days.add(day)
                keep.add(name)
            }
        }
        return backups.filter { it !in keep }
    }
}
