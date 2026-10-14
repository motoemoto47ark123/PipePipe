package org.schabi.newpipe.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import org.schabi.newpipe.NewPipeDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Decides where the database file is stored.
 *
 * By default the database lives in the app's private storage, which Android deletes when the app
 * is uninstalled. The user can move it into a folder on the phone's storage instead, so that it
 * survives reinstalling the app. Such a folder needs storage access and does not support SQLite's
 * write-ahead log reliably, so a database there is kept in rollback journal mode.
 *
 * The chosen folder is stored in its own preferences file so that it is neither part of exported
 * settings nor overwritten when settings are imported.
 */
object DatabaseLocation {
    private const val TAG = "DatabaseLocation"
    private const val PREFERENCES_NAME = "database_location"
    private const val KEY_FOLDER = "folder"
    private const val WRITE_TEST_FILE = ".pipepipe-write-test"
    private const val STAGING_FOLDER = "database-staging"

    /** Name of the folder suggested on the phone's storage. */
    const val SHARED_FOLDER_NAME = "PipePipe"

    data class Resolved(
        val file: File,
        /** Whether the file is in a folder chosen by the user. */
        val isCustom: Boolean,
        /** Whether a folder was chosen but cannot be written, so private storage is used. */
        val isFallback: Boolean
    )

    /** Facts about a database file found in a folder. */
    data class DatabaseInfo(val size: Long, val lastModified: Long, val version: Int)

    @Volatile
    private var lastResolved: Resolved? = null

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** The folder chosen by the user, or null when the database is in private storage. */
    @JvmStatic
    fun getFolder(context: Context): String? =
        preferences(context).getString(KEY_FOLDER, null)?.takeIf { it.isNotBlank() }

    private fun saveFolder(context: Context, folder: String?) {
        // commit() because the app is restarted right after a change
        preferences(context).edit(commit = true) {
            if (folder == null) remove(KEY_FOLDER) else putString(KEY_FOLDER, folder)
        }
    }

    @JvmStatic
    fun defaultDatabaseFile(context: Context): File =
        context.applicationContext.getDatabasePath(AppDatabase.DATABASE_NAME)

    /** The suggested folder on the phone's storage, which is kept when the app is uninstalled. */
    @JvmStatic
    fun suggestedSharedFolder(): String =
        File(Environment.getExternalStorageDirectory(), SHARED_FOLDER_NAME).absolutePath

    @JvmStatic
    fun databaseFileIn(folder: String): File = File(folder, AppDatabase.DATABASE_NAME)

    /**
     * Finds the database file to open. Falls back to private storage when the chosen folder
     * cannot be written, for example because storage access was revoked.
     */
    @JvmStatic
    fun resolve(context: Context): Resolved {
        val folder = getFolder(context)
        val resolved = when {
            folder == null -> Resolved(defaultDatabaseFile(context), false, false)
            isWritableFolder(File(folder)) -> Resolved(databaseFileIn(folder), true, false)
            else -> {
                Log.w(TAG, "Cannot write to $folder, using private storage")
                Resolved(defaultDatabaseFile(context), false, true)
            }
        }
        lastResolved = resolved
        return resolved
    }

    /** Whether the database had to be opened from private storage instead of the chosen folder. */
    @JvmStatic
    fun isUsingFallback(): Boolean = lastResolved?.isFallback == true

    @JvmStatic
    fun isWritableFolder(folder: File): Boolean {
        if (!folder.isDirectory && !folder.mkdirs()) {
            return false
        }
        return try {
            val probe = File(folder, WRITE_TEST_FILE)
            FileOutputStream(probe).use { it.write(0) }
            probe.delete()
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Converts a folder picked with the system folder picker into a file system path. Only folders
     * on the phone's storage or an SD card have one.
     */
    @JvmStatic
    fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") {
            return null
        }
        val documentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")
        val root = when {
            volume.equals("primary", ignoreCase = true) ->
                Environment.getExternalStorageDirectory().absolutePath
            volume.equals("home", ignoreCase = true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    .absolutePath
            else -> "/storage/$volume"
        }
        return if (relativePath.isEmpty()) root else "$root/$relativePath"
    }

    /** Reads facts about the database file in [folder], or returns null when there is none. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun inspectDatabaseIn(context: Context, folder: String): DatabaseInfo? {
        val file = databaseFileIn(folder)
        if (!file.isFile) {
            return null
        }
        val staged = stage(context, file)
        try {
            return DatabaseInfo(file.length(), file.lastModified(), checkDatabase(staged))
        } finally {
            deleteDatabaseFiles(staged)
        }
    }

    /**
     * Copies the current database into [folder] (null for private storage), switches to the copy
     * and deletes the old files. The app has to be restarted afterwards.
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun moveDatabase(context: Context, folder: String?) {
        val target = if (folder == null) defaultDatabaseFile(context) else databaseFileIn(folder)
        val source = NewPipeDatabase.getDatabaseFile(context)
        if (source.canonicalPath == target.canonicalPath) {
            saveFolder(context, folder)
            return
        }

        // Opening makes sure that the file exists and is up to date. Closing usually writes the
        // write-ahead log into the database file; a log that is left is copied along with it.
        NewPipeDatabase.getInstance(context).openHelper.writableDatabase
        NewPipeDatabase.close()

        copyDatabase(context, source, target, folder != null)
        saveFolder(context, folder)
        deleteDatabaseFiles(source)
    }

    /**
     * Switches to the database that already exists in [folder]. The current database is left
     * where it is. The app has to be restarted afterwards.
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun useDatabaseIn(context: Context, folder: String) {
        val file = databaseFileIn(folder)
        val info = inspectDatabaseIn(context, folder) ?: throw IOException("$file does not exist")
        if (info.version > AppDatabase.DATABASE_VERSION) {
            throw IOException("The database in $folder is from a newer version of PipePipe")
        }
        NewPipeDatabase.close()
        prepareForSharedStorage(context, file)
        saveFolder(context, folder)
    }

    /** Stops using the chosen folder without moving any data. */
    @JvmStatic
    fun forgetFolder(context: Context) {
        NewPipeDatabase.close()
        saveFolder(context, null)
    }

    /**
     * Makes sure that a database in a chosen folder is in rollback journal mode, which, unlike the
     * write-ahead log, does not need shared memory. Databases from exports or other copies are
     * often still in write-ahead log mode.
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun prepareForSharedStorage(context: Context, file: File) {
        val wal = File(file.path + "-wal")
        if (!file.isFile || (!isInWriteAheadLogMode(file) && !wal.exists())) {
            return
        }
        val staged = stage(context, file)
        try {
            checkDatabase(staged)
            replaceWith(staged, file)
        } finally {
            deleteDatabaseFiles(staged)
        }
    }

    /**
     * Copies [source] to [target], checking the copy before it replaces anything. A copy meant
     * for a chosen folder is converted to rollback journal mode first.
     */
    @Throws(IOException::class)
    private fun copyDatabase(context: Context, source: File, target: File, forSharedStorage: Boolean) {
        val staged = stage(context, source)
        try {
            checkDatabase(staged, forSharedStorage)
            replaceWith(staged, target)
        } finally {
            deleteDatabaseFiles(staged)
        }
    }

    /** Copies a database and its write-ahead log into private storage, where SQLite always works. */
    @Throws(IOException::class)
    private fun stage(context: Context, source: File): File {
        val folder = File(context.applicationContext.cacheDir, STAGING_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("Could not create $folder")
        }
        val staged = File(folder, AppDatabase.DATABASE_NAME)
        deleteDatabaseFiles(staged)
        copyFile(source, staged)
        val wal = File(source.path + "-wal")
        if (wal.length() > 0) {
            copyFile(wal, File(staged.path + "-wal"))
        }
        return staged
    }

    /**
     * Opens a staged copy, which applies its write-ahead log, and checks it.
     *
     * @param toRollbackJournal whether to also leave the copy in rollback journal mode
     * @return the schema version of the database
     */
    @Throws(IOException::class)
    private fun checkDatabase(staged: File, toRollbackJournal: Boolean = true): Int {
        try {
            SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                        throw IOException("The database file is damaged")
                    }
                }
                if (toRollbackJournal) {
                    // The result row has to be read for the change to happen
                    db.rawQuery("PRAGMA journal_mode=TRUNCATE", null).use { it.moveToFirst() }
                }
                return db.version
            }
        } catch (e: android.database.SQLException) {
            throw IOException("Not a usable database: ${e.message}", e)
        }
    }

    /** Replaces [target] with [source] so that [target] is either the old or the new file. */
    @Throws(IOException::class)
    private fun replaceWith(source: File, target: File) {
        val folder = target.absoluteFile.parentFile
            ?: throw IOException("$target has no parent folder")
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("Could not create $folder")
        }
        val temporary = File(folder, target.name + ".tmp")
        copyFile(source, temporary)
        deleteDatabaseFiles(target)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not rename $temporary to $target")
        }
    }

    @Throws(IOException::class)
    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    /** Whether the database file header says that the write-ahead log is used. */
    @JvmStatic
    fun isInWriteAheadLogMode(file: File): Boolean {
        val header = ByteArray(20)
        return try {
            FileInputStream(file).use { input ->
                input.read(header) == header.size && header[18].toInt() == 2 &&
                    header[19].toInt() == 2
            }
        } catch (e: IOException) {
            false
        }
    }

    @JvmStatic
    fun deleteDatabaseFiles(file: File) {
        file.delete()
        for (suffix in arrayOf("-journal", "-wal", "-shm")) {
            File(file.path + suffix).delete()
        }
    }

    /** Whether storage access has been granted, as far as it is needed for chosen folders. */
    @JvmStatic
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
