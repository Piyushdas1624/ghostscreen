package com.etaegord.ghostscreen.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helper utility to handle Android package installer intents and unknown sources permissions.
 */
object PackageInstallerHelper {

    private const val TAG = "PackageInstallerHelper"

    /**
     * Checks whether the app has permission to request package installs.
     * On Android 8.0+ (API 26), requires REQUEST_INSTALL_PACKAGES permission.
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Opens system Settings page to allow installing unknown apps from GhostScreen.
     */
    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open unknown app sources settings", e)
                val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    /**
     * Triggers the Android package installer for the given APK file using FileProvider.
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                val err = "APK file is missing or empty: ${apkFile.absolutePath}"
                Log.e(TAG, err)
                return Result.failure(IllegalArgumentException(err))
            }

            if (!canRequestPackageInstalls(context)) {
                Log.w(TAG, "Install permission not granted; redirecting to Unknown App Sources settings")
                openUnknownSourcesSettings(context)
                return Result.failure(
                    SecurityException("Install permission not granted. Please allow installing unknown apps for GhostScreen in system settings.")
                )
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            Log.d(TAG, "Triggered package installer intent for $apkUri")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger package installer intent", e)
            Result.failure(e)
        }
    }
}
