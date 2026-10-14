package org.schabi.newpipe.settings

import android.content.SharedPreferences
import android.util.Log
import org.schabi.newpipe.streams.io.SharpOutputStream
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.OutputStream
import java.util.zip.ZipOutputStream

class ContentSettingsManager(private val fileLocator: NewPipeFileLocator) {
    companion object {
        const val TAG = "ContentSetManager"

        /**
         * Writes [db] and, unless [preferences] is null, the settings into [output] as a zip file
         * in the format that can be imported.
         *
         * @param settingsFile a temporary file for the serialized settings
         */
        @JvmStatic
        @Throws(Exception::class)
        fun writeExport(
            output: OutputStream,
            db: File,
            preferences: SharedPreferences?,
            settingsFile: File
        ) {
            ZipOutputStream(BufferedOutputStream(output)).use { outZip ->
                ZipHelper.addFileToZip(outZip, db.path, "newpipe.db")
                if (preferences == null) {
                    return
                }

                try {
                    settingsFile.parentFile?.mkdirs()
                    ObjectOutputStream(FileOutputStream(settingsFile)).use { settingsOutput ->
                        settingsOutput.writeObject(preferences.all)
                        settingsOutput.flush()
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to exportDatabase", e)
                }

                ZipHelper.addFileToZip(outZip, settingsFile.path, "newpipe.settings")
            }
        }
    }

    /**
     * Exports given [SharedPreferences] to the file in given outputPath.
     * It also creates the file.
     */
    @Throws(Exception::class)
    fun exportDatabase(preferences: SharedPreferences, file: StoredFileHelper) {
        file.create()
        writeExport(SharpOutputStream(file.stream), fileLocator.db, preferences, fileLocator.settings)
    }

    fun deleteSettingsFile() {
        fileLocator.settings.delete()
    }

    /**
     * Tries to create database directory if it does not exist.
     *
     * @return Whether the directory exists afterwards.
     */
    fun ensureDbDirectoryExists(): Boolean {
        return fileLocator.dbDir.exists() || fileLocator.dbDir.mkdir()
    }

    fun extractDb(file: StoredFileHelper): Boolean {
        val success = ZipHelper.extractFileFromZip(file, fileLocator.db.path, "newpipe.db")
        if (success) {
            fileLocator.dbJournal.delete()
            fileLocator.dbWal.delete()
            fileLocator.dbShm.delete()
        }

        return success
    }

    fun extractSettings(file: StoredFileHelper): Boolean {
        return ZipHelper.extractFileFromZip(file, fileLocator.settings.path, "newpipe.settings")
    }

    /** Same as [extractDb] for a zip file that is accessible with `java.io`. */
    fun extractDb(file: File): Boolean {
        val success = ZipHelper.extractFileFromZip(file, fileLocator.db.path, "newpipe.db")
        if (success) {
            fileLocator.dbJournal.delete()
            fileLocator.dbWal.delete()
            fileLocator.dbShm.delete()
        }

        return success
    }

    /** Same as [extractSettings] for a zip file that is accessible with `java.io`. */
    fun extractSettings(file: File): Boolean {
        return ZipHelper.extractFileFromZip(file, fileLocator.settings.path, "newpipe.settings")
    }

    fun loadSharedPreferences(preferences: SharedPreferences) {
        try {
            val preferenceEditor = preferences.edit()

            ObjectInputStream(FileInputStream(fileLocator.settings)).use { input ->
                preferenceEditor.clear()
                @Suppress("UNCHECKED_CAST")
                val entries = input.readObject() as Map<String, *>
                for ((key, value) in entries) {
                    when (value) {
                        is Boolean -> {
                            preferenceEditor.putBoolean(key, value)
                        }
                        is Float -> {
                            preferenceEditor.putFloat(key, value)
                        }
                        is Int -> {
                            preferenceEditor.putInt(key, value)
                        }
                        is Long -> {
                            preferenceEditor.putLong(key, value)
                        }
                        is String -> {
                            preferenceEditor.putString(key, value)
                        }
                        is Set<*> -> {
                            // There are currently only Sets with type String possible
                            @Suppress("UNCHECKED_CAST")
                            preferenceEditor.putStringSet(key, value as Set<String>?)
                        }
                    }
                }
                preferenceEditor.commit()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Unable to loadSharedPreferences", e)
        }
    }
}
