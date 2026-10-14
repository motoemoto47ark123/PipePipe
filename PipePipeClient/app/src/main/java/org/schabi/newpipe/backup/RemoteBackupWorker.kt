package org.schabi.newpipe.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.schabi.newpipe.R
import org.schabi.newpipe.backup.transport.AuthenticationException
import org.schabi.newpipe.backup.transport.HostKeyChangedException
import org.schabi.newpipe.settings.SettingsActivity

/** Uploads a backup in the background, see [RemoteBackupScheduler]. */
class RemoteBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val config = RemoteBackupConfig.load(applicationContext)
        if (!config.enabled || !config.isComplete) {
            return Result.success()
        }
        return try {
            RemoteBackupRunner.backUp(applicationContext, false)
            cancelFailureNotification(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Backup failed", e)
            val retrying = runAttemptCount < MAX_RETRIES &&
                e !is AuthenticationException && e !is HostKeyChangedException
            if (retrying) {
                Result.retry()
            } else {
                showFailureNotification(
                    applicationContext,
                    RemoteBackupRunner.describeError(applicationContext, e)
                )
                // Try again at the next scheduled time
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "RemoteBackupWorker"
        private const val MAX_RETRIES = 2
        private const val NOTIFICATION_ID = 2100

        private fun showFailureNotification(context: Context, message: String) {
            val intent = Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context, NOTIFICATION_ID, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            )
            val notification = NotificationCompat.Builder(
                context, context.getString(R.string.remote_backup_notification_channel_id)
            )
                .setSmallIcon(R.drawable.ic_backup)
                .setContentTitle(context.getString(R.string.remote_backup_failed_notification))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // Notifications are not allowed
            }
        }

        private fun cancelFailureNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
