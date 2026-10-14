package org.schabi.newpipe.backup

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.preference.PreferenceManager
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.DatabaseLocation
import org.schabi.newpipe.settings.ContentSettingsManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Creates backup files in the same format as "Export database", so they can be imported. */
object BackupArchiver {
    private const val TAG = "BackupArchiver"

    /**
     * Writes a consistent copy of the database and, optionally, the settings into [target].
     *
     * @param workFolder a private folder for temporary files
     * @return a hash of what was backed up, to recognize when nothing changed
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun createArchive(
        context: Context,
        target: File,
        includeSettings: Boolean,
        workFolder: File
    ): String {
        val database = NewPipeDatabase.getInstance(context)
        // Opening the database finds out whether the chosen folder is accessible
        database.openHelper.writableDatabase
        if (DatabaseLocation.isUsingFallback()) {
            // The database in use is not the user's real one: never let it replace good backups
            throw IOException(context.getString(R.string.remote_backup_error_database_unavailable))
        }

        if (!workFolder.isDirectory && !workFolder.mkdirs()) {
            throw IOException("Could not create $workFolder")
        }
        val snapshot = File(workFolder, "newpipe.db")
        val settingsFile = File(workFolder, "newpipe.settings")
        try {
            try {
                NewPipeDatabase.checkpoint()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not write the write-ahead log into the database", e)
            }
            val source = NewPipeDatabase.getDatabaseFile(context)
            // A transaction keeps others from writing while the file is copied
            database.runInTransaction(Runnable { source.copyTo(snapshot, overwrite = true) })

            val preferences = if (includeSettings) {
                PreferenceManager.getDefaultSharedPreferences(context)
            } else {
                null
            }
            try {
                FileOutputStream(target).use { output ->
                    ContentSettingsManager.writeExport(output, snapshot, preferences, settingsFile)
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Could not create the backup file", e)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(snapshot).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            preferences?.all?.toSortedMap()?.forEach { (key, value) ->
                digest.update("$key=$value\n".toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            snapshot.delete()
            settingsFile.delete()
        }
    }
}
