package com.etaegord.ghostscreen.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.service.GhostScreenAccessibilityService
import com.etaegord.ghostscreen.util.isGhostScreenAccessibilityEnabled
import com.etaegord.ghostscreen.ui.screens.*
import com.etaegord.ghostscreen.ui.theme.GhostScreenTheme

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Debug-only: set by [com.etaegord.ghostscreen.dev.DeveloperActivity] to
         * force a start destination. Accepted values: home / smart_mask / audit_log
         * / settings / about. Ignored if absent (release builds never set it).
         */
        const val DEV_DEST_EXTRA = "dev.dest"
    }

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // setupRandomIconIfNeeded() removed to prevent app crash on first launch

        preferencesManager = PreferencesManager.getInstance(applicationContext)
        db = AppDatabase.getDatabase(applicationContext)

        enableEdgeToEdge()

        // Dev-menu override (debug-only). DeveloperActivity sets EXTRA_DEST to
        // jump straight to a screen without going through onboarding. In release
        // builds DeveloperActivity does not exist, so this extra is never set.
        val devDest = intent?.getStringExtra(DEV_DEST_EXTRA)
        val startDestination = if (devDest != null) {
            preferencesManager.isOnboardingCompleted = true
            devDest
        } else {
            computeStartDestination(preferencesManager, this)
        }

        setContent {
            GhostScreenTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GhostScreenApp(
                        preferencesManager = preferencesManager,
                        db = db,
                        startDestination = startDestination
                    )
                }
            }
        }
    }

    private fun setupRandomIconIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("GhostScreenPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("icon_randomized", false)) return
        
        val colors = listOf("Blue", "Red", "Green", "Purple")
        val chosen = colors.random()
        
        val pm = context.packageManager
        
        for (c in colors) {
            val state = if (c == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            pm.setComponentEnabledSetting(
                ComponentName(context, "${context.packageName}.ui.MainAlias$c"),
                state,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context, "${context.packageName}.ui.ShortcutAlias$c"),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
        
        prefs.edit().putBoolean("icon_randomized", true).apply()
        
        android.widget.Toast.makeText(context, "Icon randomized! Please reopen the app.", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Determines the initial nav destination by checking prefs + live permission state. */
private fun computeStartDestination(
    preferencesManager: PreferencesManager,
    context: Context
): String {
    val hasOverlay = Settings.canDrawOverlays(context)
    val hasAccessibility = isGhostScreenAccessibilityEnabled(context)

    return when {
        // Never finished onboarding → start from the onboarding flow
        !preferencesManager.isOnboardingCompleted -> "onboarding"
        // Onboarding done but permissions revoked (reinstall, manual revoke) → start onboarding at permission step
        !hasOverlay || !hasAccessibility -> "onboarding"
        // All good → home
        else -> "home"
    }
}



@Composable
fun GhostScreenApp(
    preferencesManager: PreferencesManager,
    db: AppDatabase,
    startDestination: String = "home"
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Onboarding Flow
        composable("onboarding") {
            OnboardingFlow(
                onFinish = {
                    preferencesManager.isOnboardingCompleted = true
                    navController.navigate("home") {
                        popUpTo("onboarding") { inclusive = true }
                    }
                }
            )
        }

        // Main app
        composable("home") {
            HomeScreen(
                preferencesManager = preferencesManager,
                db = db,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToSmartMask = { navController.navigate("smart_mask") },
                onNavigateToAuditLog = { navController.navigate("audit_log") },
                onNavigateToAbout = { navController.navigate("about") }
            )
        }

        composable("settings") {
            SettingsScreen(
                preferencesManager = preferencesManager,
                onNavigateToHome = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
                onNavigateToAbout = { navController.navigate("about") }
            )
        }

        composable("smart_mask") {
            SmartMaskSettingsScreen(
                preferencesManager = preferencesManager,
                db = db,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("audit_log") {
            AuditLogScreen(
                db = db,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("about") {
            AboutScreen(
                onNavigateToHome = { navController.navigate("home") { popUpTo("home") { inclusive = true } } },
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
    }
}
