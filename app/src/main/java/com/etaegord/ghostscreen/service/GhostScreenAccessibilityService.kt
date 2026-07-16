package com.etaegord.ghostscreen.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.AuditLog
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.util.OverlayTransition
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
                // Manual mode on -> show instantly, regardless of armed/current app.
                if (overlayShownForPackage != "manual") {
                    overlayShownForPackage = "manual"
                    overlay?.show()
                }
            } else if (!isArmed) {
                // Fully disarmed -> hide.
                overlayShownForPackage = null
                lastProtectedPackage = null
                overlay?.hide()
            } else {
                // Manual just turned OFF (or armed just turned ON) while armed.
                // Force the overlay down first so a protected current app doesn't
                // keep the manual mask stuck on (tile "off" did nothing bug),
                // then re-evaluate the real current app.
                overlayShownForPackage = null
                overlay?.hide()
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
    // The last *protected* real app we were masking, if any. Transient surfaces
    // (systemui/keyboard) inherit this state so the overlay doesn't flicker.
    private var lastProtectedPackage: String? = null

    private fun isSystemUiOrKeyboard(packageName: String): Boolean =
        OverlayTransition.isTransient(packageName)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""

            // Ignore events from our own overlay windows to prevent infinite show/hide loops
            if (packageName == this.packageName && !className.contains("MainActivity")) {
                return
            }

            // Track the last non-system, non-keyboard app package
            if (!OverlayTransition.isTransient(packageName)) {
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
        serviceScope.launch {
            val isTransient = OverlayTransition.isTransient(packageName)
            // Resolve the "effective" real app: for transient surfaces use the
            // last real app the user was in; otherwise use the current package.
            val effectivePackage = if (isTransient) lastActiveAppPackage else packageName

            val shouldProtect = if (effectivePackage != null) {
                withContext(Dispatchers.IO) {
                    db.appDao().isAppProtected(effectivePackage)
                }
            } else false

            val decision = OverlayTransition.decide(
                currentPackage = packageName,
                isTransient = isTransient,
                isProtected = shouldProtect,
                lastEffectiveProtected = lastProtectedPackage,
                armed = preferencesManager.isArmed,
                manualOverride = preferencesManager.isManualOverrideActive
            )

            withContext(Dispatchers.Main) {
                when (decision) {
                    OverlayTransition.Decision.SHOW -> {
                        hideJob?.cancel()
                        val tag = if (preferencesManager.isManualOverrideActive) "manual" else effectivePackage
                        if (tag != null && overlayShownForPackage != tag) {
                            Log.d(TAG, "Showing overlay for $tag")
                            overlayShownForPackage = tag
                            // Remember the protected real app so transient surfaces can inherit it.
                            if (!preferencesManager.isManualOverrideActive && effectivePackage != null) {
                                lastProtectedPackage = effectivePackage
                            }
                            overlay?.show()

                            // Write to audit log in background
                            effectivePackage?.let { pkg ->
                                serviceScope.launch(Dispatchers.IO) {
                                    val appName = getAppName(pkg)
                                    db.appDao().insertAuditLog(
                                        AuditLog(
                                            packageName = pkg,
                                            appName = appName,
                                            action = "Protected with Privacy Overlay"
                                        )
                                    )
                                }
                            }
                        }
                    }
                    OverlayTransition.Decision.HIDE -> {
                        if (overlayShownForPackage != null) {
                            hideJob?.cancel()
                            hideJob = serviceScope.launch {
                                delay(350)
                                overlayShownForPackage = null
                                overlay?.hide()
                            }
                        }
                        // Clearing manual override must actually take the mask down.
                        lastProtectedPackage = null
                    }
                    OverlayTransition.Decision.STAY -> {
                        // Transient surface over a protected app: keep the mask
                        // exactly as-is. Cancel any pending hide so it doesn't drop.
                        hideJob?.cancel()
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
