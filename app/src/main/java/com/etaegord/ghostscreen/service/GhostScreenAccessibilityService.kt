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
        if (key == "armed" || key == "manual_override_active") {
            val isManual = preferencesManager.isManualOverrideActive
            val isArmed = preferencesManager.isArmed
            Log.d(TAG, "State changed - Armed: $isArmed, Manual: $isManual")
            if (isManual) {
                // If manual mode is activated, just show the overlay instantly
                if (overlayShownForPackage != "manual") {
                    overlayShownForPackage = "manual"
                    overlay?.show()
                }
            } else if (!isArmed) {
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

    private var lastActiveAppPackage: String? = null

    private fun isSystemUiOrKeyboard(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower == "android" ||
                lower.contains("systemui") ||
                lower.contains("inputmethod") ||
                lower.contains("keyboard") ||
                lower.contains("honeyboard") ||
                lower.contains("swiftkey") ||
                lower.contains("miinput") ||
                lower.contains("miui.miinput") ||
                lower.contains("baidu.input")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""
            
            // Ignore events from our own overlay windows to prevent infinite show/hide loops
            if (packageName == this.packageName && !className.contains("MainActivity")) {
                return
            }

            // Track the last non-system, non-keyboard app package
            if (!isSystemUiOrKeyboard(packageName)) {
                lastActiveAppPackage = packageName
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

    private var hideJob: Job? = null

    private fun checkCurrentApp(packageName: String) {
        if (preferencesManager.isManualOverrideActive) {
            hideJob?.cancel()
            if (overlayShownForPackage != "manual") {
                overlayShownForPackage = "manual"
                overlay?.show()
            }
            return
        }

        if (!preferencesManager.isArmed) {
            hideJob?.cancel()
            overlayShownForPackage = null
            overlay?.hide()
            return
        }

        serviceScope.launch {
            var shouldProtect = false
            var effectivePackage = packageName
            
            if (isSystemUiOrKeyboard(packageName)) {
                val lastPkg = lastActiveAppPackage
                if (lastPkg != null) {
                    shouldProtect = withContext(Dispatchers.IO) {
                        db.appDao().isAppProtected(lastPkg)
                    }
                    effectivePackage = lastPkg
                }
            } else {
                shouldProtect = withContext(Dispatchers.IO) {
                    db.appDao().isAppProtected(packageName)
                }
            }

            withContext(Dispatchers.Main) {
                if (shouldProtect) {
                    hideJob?.cancel()
                    // Only show if not already showing for this effective package
                    if (overlayShownForPackage != effectivePackage) {
                        Log.d(TAG, "App $effectivePackage is protected. Showing overlay.")
                        overlayShownForPackage = effectivePackage
                        overlay?.show()
                    }
                    
                    // Write to audit log in background
                    serviceScope.launch(Dispatchers.IO) {
                        val appName = getAppName(effectivePackage)
                        db.appDao().insertAuditLog(
                            AuditLog(
                                packageName = effectivePackage,
                                appName = appName,
                                action = "Protected with Privacy Overlay"
                            )
                        )
                    }
                } else {
                    if (overlayShownForPackage != null) {
                        hideJob?.cancel()
                        hideJob = serviceScope.launch {
                            delay(350)
                            overlayShownForPackage = null
                            overlay?.hide()
                        }
                    }
                }
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
