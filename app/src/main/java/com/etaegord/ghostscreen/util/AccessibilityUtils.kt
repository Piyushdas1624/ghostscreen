package com.etaegord.ghostscreen.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import com.etaegord.ghostscreen.service.GhostScreenAccessibilityService

/** Single source of truth for "is our accessibility service running or enabled?" */
fun isGhostScreenAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    val isRunning = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        ?.any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == context.packageName &&
                (si.name == GhostScreenAccessibilityService::class.java.name ||
                 si.name == ".service.GhostScreenAccessibilityService")
        } ?: false

    if (isRunning) return true

    // Fallback to Settings.Secure to survive cold starts and transient binder connect delays
    return try {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (accessibilityEnabled != 1) return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedName = "${context.packageName}/${GhostScreenAccessibilityService::class.java.name}"
        val shortExpectedName = "${context.packageName}/.service.GhostScreenAccessibilityService"

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = splitter.next()
            if (componentName.equals(expectedName, ignoreCase = true) ||
                componentName.equals(shortExpectedName, ignoreCase = true)) {
                return true
            }
        }
        false
    } catch (_: Exception) {
        false
    }
}
