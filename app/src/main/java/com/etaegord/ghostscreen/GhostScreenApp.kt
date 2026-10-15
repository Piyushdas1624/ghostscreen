package com.etaegord.ghostscreen

import android.app.Application
import com.etaegord.ghostscreen.util.CrashLogger

/**
 * GhostScreenApp — Custom Application class for GhostScreen.
 * Ensures crash logging and diagnostic pipelines are armed on process start.
 */
class GhostScreenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.init(this)
        com.etaegord.ghostscreen.updater.UpdateNotificationHelper.createNotificationChannel(this)
        com.etaegord.ghostscreen.updater.UpdateManager.getInstance(this).syncPeriodicWork()
    }
}
