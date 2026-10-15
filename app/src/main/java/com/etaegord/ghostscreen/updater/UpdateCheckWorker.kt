package com.etaegord.ghostscreen.updater

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.etaegord.ghostscreen.BuildConfig
import com.etaegord.ghostscreen.data.PreferencesManager
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker that periodically checks for new GitHub releases
 * and alerts the user via a system notification.
 */
class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "UpdateCheckWorker"
        const val UNIQUE_WORK_NAME = "ghostscreen_periodic_update_check"

        /**
         * Enqueues a periodic background check (default: every 24 hours when connected to network).
         */
        fun schedulePeriodicWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Scheduled periodic update check work")
        }

        /**
         * Cancels periodic background update check work.
         */
        fun cancelPeriodicWork(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Cancelled periodic update check work")
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager.getInstance(applicationContext)

        if (!prefs.isAutoCheckUpdatesEnabled) {
            Log.d(TAG, "Auto-check updates is disabled in preferences; skipping.")
            return Result.success()
        }

        Log.d(TAG, "Executing background update check against GitHub API...")

        val checkResult = GitHubReleaseChecker.fetchLatestRelease(context = applicationContext)
        checkResult.onSuccess { updateInfo ->
            prefs.lastUpdateCheckTime = System.currentTimeMillis()
            val currentVersion = BuildConfig.VERSION_NAME
            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

            if (VersionComparator.isNewer(
                    remoteVersion = updateInfo.tagName,
                    currentVersion = currentVersion,
                    remoteVersionCode = updateInfo.versionCode,
                    currentVersionCode = currentVersionCode
                )
            ) {
                if (updateInfo.tagName == prefs.skippedUpdateVersion) {
                    Log.d(TAG, "Update ${updateInfo.tagName} is available but skipped by user.")
                } else {
                    Log.i(TAG, "New update available: ${updateInfo.tagName} (current: $currentVersion, code: $currentVersionCode)")
                    UpdateNotificationHelper.showUpdateNotification(applicationContext, updateInfo)
                }
            } else {
                Log.d(TAG, "App is up to date (current: $currentVersion, remote: ${updateInfo.tagName})")
            }
        }.onFailure { error ->
            Log.w(TAG, "Background update check failed: ${error.message}")
        }

        return Result.success()
    }
}
