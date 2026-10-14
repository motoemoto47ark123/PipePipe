package org.schabi.newpipe.backup

import android.content.Context
import android.text.format.Formatter
import androidx.core.content.edit
import org.schabi.newpipe.R
import java.text.DateFormat
import java.util.Date

/** Remembers how the last backups went, in the preferences file of [RemoteBackupConfig]. */
object RemoteBackupStatus {
    private const val KEY_LAST_SUCCESS = "status_last_success"
    private const val KEY_LAST_NAME = "status_last_name"
    private const val KEY_LAST_SIZE = "status_last_size"
    private const val KEY_LAST_HASH = "status_last_hash"
    private const val KEY_LAST_ATTEMPT = "status_last_attempt"
    private const val KEY_LAST_ERROR = "status_last_error"

    /** Keys of the values in this object, to recognize their changes. */
    @JvmField
    val KEYS = setOf(
        KEY_LAST_SUCCESS, KEY_LAST_NAME, KEY_LAST_SIZE, KEY_LAST_HASH, KEY_LAST_ATTEMPT,
        KEY_LAST_ERROR
    )

    private fun preferences(context: Context) = RemoteBackupConfig.preferences(context)

    fun recordSuccess(context: Context, time: Long, name: String, size: Long, hash: String) {
        preferences(context).edit {
            putLong(KEY_LAST_SUCCESS, time)
            putLong(KEY_LAST_ATTEMPT, time)
            putString(KEY_LAST_NAME, name)
            putLong(KEY_LAST_SIZE, size)
            putString(KEY_LAST_HASH, hash)
            remove(KEY_LAST_ERROR)
        }
    }

    /** Records an attempt that found nothing new to upload. */
    fun recordUnchanged(context: Context, time: Long) {
        preferences(context).edit {
            putLong(KEY_LAST_ATTEMPT, time)
            remove(KEY_LAST_ERROR)
        }
    }

    fun recordFailure(context: Context, time: Long, message: String) {
        preferences(context).edit {
            putLong(KEY_LAST_ATTEMPT, time)
            putString(KEY_LAST_ERROR, message)
        }
    }

    /** Forgets what was uploaded, so that the next backup is uploaded even without changes. */
    @JvmStatic
    fun forgetLastUpload(context: Context) {
        preferences(context).edit { remove(KEY_LAST_HASH) }
    }

    fun lastHash(context: Context): String? = preferences(context).getString(KEY_LAST_HASH, null)

    @JvmStatic
    fun lastAttemptTime(context: Context): Long = preferences(context).getLong(KEY_LAST_ATTEMPT, 0)

    @JvmStatic
    fun lastError(context: Context): String? = preferences(context).getString(KEY_LAST_ERROR, null)

    /** A one-line description of the last backup and, if it failed since, of the failure. */
    @JvmStatic
    fun describe(context: Context): String {
        val prefs = preferences(context)
        val lastSuccess = prefs.getLong(KEY_LAST_SUCCESS, 0)
        val error = prefs.getString(KEY_LAST_ERROR, null)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val success = if (lastSuccess > 0) {
            context.getString(
                R.string.remote_backup_status_last,
                format.format(Date(lastSuccess)),
                Formatter.formatShortFileSize(context, prefs.getLong(KEY_LAST_SIZE, 0))
            )
        } else {
            context.getString(R.string.remote_backup_status_none)
        }
        if (error == null) {
            return success
        }
        val failedAt = format.format(Date(prefs.getLong(KEY_LAST_ATTEMPT, 0)))
        return context.getString(R.string.remote_backup_status_failed, failedAt, error) +
            "\n" + success
    }

    /** A short summary for the entry that opens the backup settings. */
    @JvmStatic
    fun summary(context: Context): String {
        val config = RemoteBackupConfig.load(context)
        if (!config.enabled) {
            return context.getString(R.string.remote_backup_summary_off)
        }
        if (!config.isComplete) {
            return context.getString(R.string.remote_backup_error_no_server)
        }
        val interval = context.resources.getQuantityString(
            R.plurals.remote_backup_every_hours, config.intervalHours, config.intervalHours
        )
        val error = lastError(context)
        return if (error != null) {
            context.getString(R.string.remote_backup_summary_failing, config.server.host, error)
        } else {
            context.getString(R.string.remote_backup_summary_on, interval, config.server.host)
        }
    }
}
