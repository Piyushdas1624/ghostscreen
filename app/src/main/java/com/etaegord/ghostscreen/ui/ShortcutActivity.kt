package com.etaegord.ghostscreen.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.util.isGhostScreenAccessibilityEnabled

class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        MainActivity.cleanupLegacyAliasesIfNeeded(this)

        val prefs = PreferencesManager.getInstance(this)

        // If the accessibility service isn't enabled (or overlay permission is
        // missing) we can't draw a mask from the background, so route the user
        // into the app to finish setup instead of silently doing nothing.
        if (!isGhostScreenAccessibilityEnabled(this) || !Settings.canDrawOverlays(this)) {
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launch)
            }
            finish()
            return
        }

        // Service is live: toggling the pref fires its OnSharedPreferenceChangeListener,
        // which shows/hides the overlay. No dead broadcast needed.
        prefs.isManualOverrideActive = !prefs.isManualOverrideActive

        finish()
    }

    override fun finish() {
        super.finish()
        disableTransitions()
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
