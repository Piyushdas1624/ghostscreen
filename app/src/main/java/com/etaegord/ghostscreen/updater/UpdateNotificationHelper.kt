package com.etaegord.ghostscreen.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.etaegord.ghostscreen.R
import com.etaegord.ghostscreen.ui.MainActivity

/**
 * Manages system notification channels and posting non-intrusive update notifications.
 */
object UpdateNotificationHelper {

    private const val TAG = "UpdateNotificationHelper"
    private const val CHANNEL_ID = "ghostscreen_app_updates"
    private const val NOTIFICATION_ID = 202610

    const val EXTRA_SHOW_UPDATE = "extra_show_update"
    const val EXTRA_UPDATE_TAG = "extra_update_tag"
    const val EXTRA_UPDATE_TITLE = "extra_update_title"
    const val EXTRA_UPDATE_BODY = "extra_update_body"
    const val EXTRA_UPDATE_URL = "extra_update_url"
    const val EXTRA_UPDATE_SIZE = "extra_update_size"
    const val EXTRA_UPDATE_DATE = "extra_update_date"
    const val EXTRA_UPDATE_SHA256 = "extra_update_sha256"

    /**
     * Creates the notification channel for app updates (safe on all API levels).
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Updates"
            val descriptionText = "Notifications about new GhostScreen version updates and changelogs"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(true)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Posts a system notification announcing a newly available update.
     * Tapping the notification launches [MainActivity] and opens the Update Dialog.
     */
    fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        // Android 13+ runtime notification permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Log.w(TAG, "Cannot post update notification: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHOW_UPDATE, true)
            putExtra(EXTRA_UPDATE_TAG, updateInfo.tagName)
            putExtra(EXTRA_UPDATE_TITLE, updateInfo.title)
            putExtra(EXTRA_UPDATE_BODY, updateInfo.changelog)
            putExtra(EXTRA_UPDATE_URL, updateInfo.downloadUrl)
            putExtra(EXTRA_UPDATE_SIZE, updateInfo.apkSize)
            putExtra(EXTRA_UPDATE_DATE, updateInfo.publishedAt)
            putExtra(EXTRA_UPDATE_SHA256, updateInfo.sha256 ?: "")
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Update Available: ${updateInfo.tagName}")
            .setContentText("GhostScreen ${updateInfo.tagName} is available. Tap to review changelog & update.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("GhostScreen ${updateInfo.tagName} is available.\n${updateInfo.title}\n\nTap to review changelog & update.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, builder.build())
        Log.d(TAG, "Posted update notification for ${updateInfo.tagName}")
    }

    /**
     * Dismisses any active update notification.
     */
    fun cancelUpdateNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
    }
}
