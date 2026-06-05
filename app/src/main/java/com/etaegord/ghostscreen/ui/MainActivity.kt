package com.etaegord.ghostscreen.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
import com.etaegord.ghostscreen.ui.screens.*
import com.etaegord.ghostscreen.ui.theme.GhostScreenTheme

class MainActivity : ComponentActivity() {
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferencesManager = PreferencesManager(applicationContext)
        db = AppDatabase.getDatabase(applicationContext)

        enableEdgeToEdge()

        // Compute start destination here so we have a real Context (this)
        val startDestination = computeStartDestination(preferencesManager, this)

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
}

/** Determines the initial nav destination by checking prefs + live permission state. */
private fun computeStartDestination(
    preferencesManager: PreferencesManager,
    context: Context
): String {
    val hasOverlay = Settings.canDrawOverlays(context)
    val hasAccessibility = isMainAccessibilityEnabled(context)

    return when {
        // Never finished onboarding → start from the welcome screen
        !preferencesManager.isOnboardingCompleted -> "welcome"
        // Onboarding done but permissions revoked (reinstall, manual revoke) → permission screen
        !hasOverlay || !hasAccessibility -> "permission"
        // All good → home
        else -> "home"
    }
}

/** Checks if GhostScreenAccessibilityService is enabled. */
private fun isMainAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any { service ->
        val info = service.resolveInfo.serviceInfo
        info.packageName == context.packageName &&
        info.name == GhostScreenAccessibilityService::class.java.name
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
        // Onboarding
        composable("welcome") {
            WelcomeScreen(onNext = { navController.navigate("permission") })
        }

        composable("permission") {
            PermissionScreen(
                onNext = { navController.navigate("all_set") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("all_set") {
            AllSetScreen(
                onFinish = {
                    preferencesManager.isOnboardingCompleted = true
                    navController.navigate("home") {
                        popUpTo("welcome") { inclusive = true }
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
