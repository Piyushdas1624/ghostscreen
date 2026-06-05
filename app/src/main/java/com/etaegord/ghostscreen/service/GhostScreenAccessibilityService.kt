package com.etaegord.ghostscreen.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.AuditLog
import com.etaegord.ghostscreen.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GhostScreenAccessibilityService : AccessibilityService() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var db: AppDatabase
    private var overlay: GhostScreenOverlay? = null
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var currentPackageName: String? = null
    private var overlayShownForPackage: String? = null
    private var pendingCheckJob: Job? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "armed") {
            val isArmed = preferencesManager.isArmed
            Log.d(TAG, "Armed state changed: $isArmed")
            if (!isArmed) {
                overlayShownForPackage = null
                overlay?.hide()
            } else {
                currentPackageName?.let { checkCurrentApp(it) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        preferencesManager = PreferencesManager(applicationContext)
        db = AppDatabase.getDatabase(applicationContext)
        overlay = GhostScreenOverlay(this, preferencesManager)
        
        preferencesManager.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""
            
            // Skip system UI and launcher package changes to prevent false switches
            if (packageName == "com.android.systemui" || packageName == "android") {
                return
            }
            
            // Ignore events from our own overlay windows to prevent infinite show/hide loops
            if (packageName == this.packageName && !className.contains("MainActivity")) {
                return
            }
            
            // Debounce: if we already know about this package, skip duplicate events
            if (packageName == currentPackageName && packageName == overlayShownForPackage) {
                return
            }
            
            currentPackageName = packageName
            
            // Cancel any pending check to debounce rapid events
            pendingCheckJob?.cancel()
            pendingCheckJob = serviceScope.launch {
                // Small delay to debounce rapid window-state-change bursts
                delay(150)
                checkCurrentApp(packageName)
            }
        }
    }

    private fun checkCurrentApp(packageName: String) {
        if (!preferencesManager.isArmed) {
            overlayShownForPackage = null
            overlay?.hide()
            return
        }

        // Avoid masking our own app settings screen
        if (packageName == this.packageName) {
            overlayShownForPackage = null
            overlay?.hide()
            return
        }

        serviceScope.launch {
            val isProtected = withContext(Dispatchers.IO) {
                db.appDao().isAppProtected(packageName)
            }

            if (isProtected) {
                // Only show if not already showing for this package
                if (overlayShownForPackage != packageName) {
                    Log.d(TAG, "App $packageName is protected. Showing overlay.")
                    overlayShownForPackage = packageName
                    overlay?.show()
                }
                
                // Write to audit log in background
                withContext(Dispatchers.IO) {
                    val appName = getAppName(packageName)
                    db.appDao().insertAuditLog(
                        AuditLog(
                            packageName = packageName,
                            appName = appName,
                            action = "Protected with Privacy Overlay"
                        )
                    )
                }
            } else {
                overlayShownForPackage = null
                overlay?.hide()
            }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        preferencesManager.unregisterOnSharedPreferenceChangeListener(prefsListener)
        pendingCheckJob?.cancel()
        overlayShownForPackage = null
        overlay?.hide()
        overlay = null
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "GhostScreenService"
    }
}
