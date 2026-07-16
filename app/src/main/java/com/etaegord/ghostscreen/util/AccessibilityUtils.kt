package com.etaegord.ghostscreen.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.etaegord.ghostscreen.service.GhostScreenAccessibilityService

/** Single source of truth for "is our accessibility service running?" */
fun isGhostScreenAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        .any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == context.packageName &&
                si.name == GhostScreenAccessibilityService::class.java.name
        }
}
