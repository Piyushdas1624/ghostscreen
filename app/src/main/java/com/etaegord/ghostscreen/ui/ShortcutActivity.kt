package com.etaegord.ghostscreen.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.etaegord.ghostscreen.data.PreferencesManager

class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = PreferencesManager(this)
        
        // Toggle Manual Mode
        val isActive = prefs.isManualOverrideActive
        prefs.isManualOverrideActive = !isActive
        
        // Notify AccessibilityService
        val intent = Intent("com.etaegord.ghostscreen.EVALUATE_STATE")
        sendBroadcast(intent)
        
        // Finish immediately so the UI doesn't linger
        finish()
    }
}
