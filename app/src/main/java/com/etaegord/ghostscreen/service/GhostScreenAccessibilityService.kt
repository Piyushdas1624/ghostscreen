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
                val isFullyShowing = overlay?.isFullyShowing() == true
                if (overlayShownForPackage != "manual" || !isFullyShowing) {
                    overlayShownForPackage = "manual"
                    if (!isFullyShowing) {
                        overlay?.show()
                    }
                }
            } else if (!isArmed) {
                // Fully disarmed -> hide.
                pendingCheckJob?.cancel()
                hideJob?.cancel()
                overlayShownForPackage = null
                lastProtectedPackage = null
                overlay?.hide()
            } else {
                // Manual just turned OFF (or armed just turned ON) while armed.
                // Instead of prematurely tearing down the overlay (which causes black flicker),
                // evaluate the real current app. If protected, it seamlessly stays up;
                // if not protected, checkCurrentApp will cleanly fade it out.
                val pkg = currentPackageName ?: rootInActiveWindow?.packageName?.toString()
                if (pkg != null) {
                    currentPackageName = pkg
                    pendingCheckJob?.cancel()
                    pendingCheckJob = serviceScope.launch {
                        checkCurrentApp(pkg)
                    }
                } else {
                    overlayShownForPackage = null
                    lastProtectedPackage = null
                    overlay?.hide()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        preferencesManager = PreferencesManager.getInstance(applicationContext)
        db = AppDatabase.getDatabase(applicationContext)
        overlay = GhostScreenOverlay(this, preferencesManager)
        
        preferencesManager.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service onServiceConnected")
        // Initial state evaluation on connect (Issue #5)
        if (preferencesManager.isManualOverrideActive) {
            overlayShownForPackage = "manual"
            overlay?.show()
        } else if (preferencesManager.isArmed) {
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg != null) {
                currentPackageName = pkg
                pendingCheckJob?.cancel()
                pendingCheckJob = serviceScope.launch {
                    checkCurrentApp(pkg)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        overlay?.onConfigurationChanged()
    }

    private var lastActiveAppPackage: String? = null
    // The last *protected* real app we were masking, if any. Transient surfaces
    // (systemui/keyboard) inherit this state so the overlay doesn't flicker.
    private var lastProtectedPackage: String? = null

    private fun isSystemUiOrKeyboard(packageName: String): Boolean =
        OverlayTransition.isTransient(packageName)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 1. Notification Privacy Shield: Detect heads-up banners and obscure
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (preferencesManager.isNotificationShieldEnabled) {
                val pkg = event.packageName?.toString() ?: ""
                if (pkg != this.packageName) {
                    overlay?.showNotificationShield()
                }
            }
            return
        }

        // 2. AI Sensitive Field Auto-Shielding: scan visible fields on content change or focus
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (preferencesManager.isAiSensitiveShieldingEnabled) {
                scheduleSensitiveFieldScan()
            }
            return
        }

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

            // Trigger AI sensitive field scan on app window change if enabled
            if (preferencesManager.isAiSensitiveShieldingEnabled) {
                scheduleSensitiveFieldScan()
            }

            // Debounce: if we already know about this package, skip duplicate events
            if (packageName == currentPackageName && packageName == overlayShownForPackage) {
                return
            }

            currentPackageName = packageName

            // Cancel any pending check to debounce rapid events.
            // Cancelling pendingCheckJob cancels the active DB query as well because
            // checkCurrentApp is suspend (Issue #2 race condition fix).
            pendingCheckJob?.cancel()
            pendingCheckJob = serviceScope.launch {
                // Small delay to debounce rapid window-state-change bursts
                delay(150)
                checkCurrentApp(packageName)
            }
        }
    }

    private var sensitiveScanJob: Job? = null
    private val cardRegex = Regex("""\b(?:\d[ -]*?){13,19}\b""")
    private val otpRegex = Regex("""(?i)\b(?:otp|code|verification|pin)\b.{0,15}\b\d{4,8}\b""")
    private val balanceRegex = Regex("""(?i)(?:[$€£₹]|rs\.?|inr|usd)\s*[\d,]+(?:\.\d{1,2})?""")

    private fun scheduleSensitiveFieldScan() {
        sensitiveScanJob?.cancel()
        sensitiveScanJob = serviceScope.launch(Dispatchers.Default) {
            delay(280) // 280ms debounce so typing/scrolling doesn't hammer CPU
            val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return@launch
            val foundBounds = mutableListOf<android.graphics.RectF>()
            val nodeCount = intArrayOf(0)
            try {
                scanNodeHierarchy(root, 0, foundBounds, nodeCount)
            } catch (e: Exception) {
                Log.w(TAG, "Error in scanNodeHierarchy", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
            if (foundBounds.isNotEmpty()) {
                Log.d(TAG, "AI Sensitive Field Shielding detected ${foundBounds.size} fields")
            }
            withContext(Dispatchers.Main) {
                overlay?.setSensitiveFieldRects(foundBounds)
            }
        }
    }

    private fun scanNodeHierarchy(
        node: android.view.accessibility.AccessibilityNodeInfo?,
        depth: Int,
        outBounds: MutableList<android.graphics.RectF>,
        count: IntArray
    ) {
        if (node == null || depth > 24 || count[0] > 300) return
        count[0]++

        var isSensitive = false
        if (preferencesManager.isAiShieldPasswordsEnabled && node.isPassword) {
            isSensitive = true
        }

        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!isSensitive && !text.isNullOrBlank()) {
            if (preferencesManager.isAiShieldCardsEnabled && cardRegex.containsMatchIn(text)) {
                isSensitive = true
            } else if (preferencesManager.isAiShieldOtpsEnabled && otpRegex.containsMatchIn(text)) {
                isSensitive = true
            } else if (preferencesManager.isAiShieldBalancesEnabled && balanceRegex.containsMatchIn(text)) {
                isSensitive = true
            }
        }

        if (isSensitive) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty && r.width() > 0 && r.height() > 0) {
                val pad = 4
                outBounds.add(
                    android.graphics.RectF(
                        (r.left - pad).toFloat(),
                        (r.top - pad).toFloat(),
                        (r.right + pad).toFloat(),
                        (r.bottom + pad).toFloat()
                    )
                )
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                scanNodeHierarchy(child, depth + 1, outBounds, count)
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }

    private var hideJob: Job? = null

    private suspend fun checkCurrentApp(packageName: String) {
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
                    val isFullyShowing = overlay?.isFullyShowing() == true
                    if (tag != null && (overlayShownForPackage != tag || !isFullyShowing)) {
                        Log.d(TAG, "Showing overlay for $tag")
                        overlayShownForPackage = tag
                        // Remember the protected real app so transient surfaces can inherit it.
                        if (!preferencesManager.isManualOverrideActive && effectivePackage != null) {
                            lastProtectedPackage = effectivePackage
                        }
                        if (!isFullyShowing) {
                            overlay?.show()
                        }

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
                    hideJob?.cancel()
                    if (overlayShownForPackage != null || overlay?.isShowing() == true) {
                        val isLeavingManual = !preferencesManager.isManualOverrideActive && overlayShownForPackage == "manual"
                        hideJob = serviceScope.launch {
                            if (!isLeavingManual) {
                                delay(200)
                            }
                            overlayShownForPackage = null
                            overlay?.hide()
                        }
                    } else {
                        overlayShownForPackage = null
                    }
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
        sensitiveScanJob?.cancel()
        overlayShownForPackage = null
        overlay?.hide()
        overlay = null
        serviceJob.cancel()
    }

    companion object {
        private const val TAG = "GhostScreenService"
    }
}
