package com.etaegord.ghostscreen.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.etaegord.ghostscreen.data.PreferencesManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val preferencesManager = PreferencesManager(context)
            Log.d("BootReceiver", "Boot completed. Launch on boot enabled: ${preferencesManager.isLaunchOnBootEnabled}")
            
            if (preferencesManager.isLaunchOnBootEnabled) {
                // Set armed state so the accessibility service will activate on the next app switch
                preferencesManager.isArmed = true
                Log.d("BootReceiver", "Armed state set to true on boot")
            }
        }
    }
}
