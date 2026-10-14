package org.schabi.newpipe.backup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules automatic backups: periodically in the background, and also when the app is in use
 * and the last backup is older than the backup interval.
 */
object RemoteBackupScheduler {
    const val PERIODIC_WORK_NAME = "remote_backup"
    const val CATCH_UP_WORK_NAME = "remote_backup_catch_up"
    private const val RETRY_DELAY_MINUTES = 10L

    /** How often opening the app checks whether a backup is due. */
    private val CATCH_UP_CHECK_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5)

    @Volatile
    private var lastCatchUpCheck = 0L

    /**
     * Schedules or cancels the periodic backup to match the settings.
     *
     * @param settingsChanged whether to apply changed settings to an existing schedule
     */
    @JvmStatic
    fun schedule(context: Context, settingsChanged: Boolean) {
        val config = RemoteBackupConfig.load(context)
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!config.enabled || !config.isComplete) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            workManager.cancelUniqueWork(CATCH_UP_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<RemoteBackupWorker>(
            config.intervalHours.toLong(), TimeUnit.HOURS
        )
            .setConstraints(constraints(config))
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            if (settingsChanged) {
                ExistingPeriodicWorkPolicy.UPDATE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            },
            request
        )
    }

    /** Starts a backup if the last attempt is older than the backup interval. */
    @JvmStatic
    fun backUpIfDue(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCatchUpCheck < CATCH_UP_CHECK_INTERVAL_MILLIS) {
            return
        }
        lastCatchUpCheck = now
        val config = RemoteBackupConfig.load(context)
        if (!config.enabled || !config.isComplete) {
            return
        }
        val lastAttempt = RemoteBackupStatus.lastAttemptTime(context)
        if (now - lastAttempt < TimeUnit.HOURS.toMillis(config.intervalHours.toLong())) {
            return
        }
        val request = OneTimeWorkRequestBuilder<RemoteBackupWorker>()
            .setConstraints(constraints(config))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(CATCH_UP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Keeps the schedule up to date and backs up when the app is opened and a backup is due. */
    @JvmStatic
    fun initialize(application: Application) {
        schedule(application, false)
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = backUpIfDue(application)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun constraints(config: RemoteBackupConfig) = Constraints.Builder()
        .setRequiredNetworkType(
            if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()
}
