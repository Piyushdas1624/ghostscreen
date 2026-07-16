package com.etaegord.ghostscreen.dev

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.etaegord.ghostscreen.data.AppDatabase
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.data.ProtectedApp
import com.etaegord.ghostscreen.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * DEBUG-ONLY developer menu. Physically excluded from release builds because it
 * lives under app/src/debug/.
 *
 * Reach via:
 *   adb shell am start -n com.etaegord.ghostscreen/.dev.DeveloperActivity
 *   adb shell am start -n com.etaegord.ghostscreen/.dev.DeveloperActivity \
 *       --es action skipOnboarding
 *   adb shell am start -n com.etaegord.ghostscreen/.dev.DeveloperActivity \
 *       --es action goto --es dest smart_mask
 *
 * Every action is idempotent and self-verifying (toast confirms state).
 */
class DeveloperActivity : Activity() {

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow being driven headlessly from adb (no UI shown).
        val action = intent?.getStringExtra(EXTRA_ACTION)
        if (action != null) {
            runBlocking { executeHeadless(action) }
            finish()
            return
        }

        setTitle("[D] GhostScreen Developer Menu")
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scroll.addView(root)
        setContentView(scroll)

        TextView(this).apply {
            text = "Developer Menu (debug-only)"
            textSize = 20f
            setPadding(0, 0, 0, 32)
            gravity = Gravity.CENTER
        }.also { root.addView(it) }

        section(root, "Preferences") {
            btn(root, "Skip onboarding (set flag true)") { exec(PREFS_SKIP_ONBOARDING); toast("onboarding_completed = true") }
            btn(root, "Reset all preferences (clear)") { exec(PREFS_RESET); toast("prefs cleared") }
            btn(root, "Show current prefs") { toast(readPrefs()) }
        }
        section(root, "Protection state") {
            btn(root, "ARM Smart (isArmed=true, manual=false)") { exec(STATE_ARM_SMART); toast("armed") }
            btn(root, "Instant/Manual on") { exec(STATE_MANUAL_ON); toast("manual on") }
            btn(root, "Disarm everything") { exec(STATE_DISARM); toast("disarmed") }
        }
        section(root, "Database") {
            btn(root, "Insert 3 fake protected apps") { exec(DB_SEED); toast("seeded 3 apps") }
            btn(root, "Clear protected_apps") { exec(DB_CLEAR_APPS); toast("apps cleared") }
            btn(root, "Clear audit_logs") { exec(DB_CLEAR_LOGS); toast("logs cleared") }
            btn(root, "Reset entire DB (destructive)") { exec(DB_RESET); toast("db reset") }
        }
        section(root, "Navigation (deterministic)") {
            btn(root, "Launch Home") { launchMain(DEST_HOME) }
            btn(root, "Launch Smart Mask") { launchMain(DEST_SMART_MASK) }
            btn(root, "Launch Audit Log") { launchMain(DEST_AUDIT) }
            btn(root, "Launch Settings") { launchMain(DEST_SETTINGS) }
            btn(root, "Launch About") { launchMain(DEST_ABOUT) }
        }
        section(root, "Permissions") {
            btn(root, "Nothing here — use adb (see DevMenu README)") {
                toast("overlay: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow\naccessibility: adb shell settings put secure enabled_accessibility_services ...")
            }
        }
    }

    // ── Headless (adb-driven) path ─────────────────────────────────────────
    private suspend fun executeHeadless(action: String) {
        when (action) {
            "skipOnboarding", PREFS_SKIP_ONBOARDING -> exec(PREFS_SKIP_ONBOARDING)
            "resetPrefs", PREFS_RESET -> exec(PREFS_RESET)
            "armSmart", STATE_ARM_SMART -> exec(STATE_ARM_SMART)
            "manualOn", STATE_MANUAL_ON -> exec(STATE_MANUAL_ON)
            "disarm", STATE_DISARM -> exec(STATE_DISARM)
            "seedApps", DB_SEED -> exec(DB_SEED)
            "clearApps", DB_CLEAR_APPS -> exec(DB_CLEAR_APPS)
            "clearLogs", DB_CLEAR_LOGS -> exec(DB_CLEAR_LOGS)
            "resetDb", DB_RESET -> exec(DB_RESET)
            "goto" -> {
                // chained state prep then navigate
                val dest = intent?.getStringExtra(EXTRA_DEST) ?: DEST_HOME
                exec(PREFS_SKIP_ONBOARDING)
                launchMain(dest)
            }
            else -> { /* unknown: no-op */ }
        }
    }

    // ── Actions ────────────────────────────────────────────────────────────
    private fun exec(action: String) {
        val pm = PreferencesManager(applicationContext)
        val db = AppDatabase.getDatabase(applicationContext)
        when (action) {
            PREFS_SKIP_ONBOARDING -> {
                // Write BOTH stores to avoid the known startup-desync window.
                pm.isOnboardingCompleted = true
                forcePlainPref("onboarding_completed", true)
            }
            PREFS_RESET -> {
                // Wipe plain + encrypted so the app starts truly fresh.
                deleteSharedPreferences("ghostscreen_prefs")
                runCatching { deleteSharedPreferences("ghostscreen_secure_prefs") }
            }
            STATE_ARM_SMART -> { pm.isArmed = true; pm.isManualOverrideActive = false }
            STATE_MANUAL_ON -> { pm.isManualOverrideActive = true }
            STATE_DISARM -> { pm.isArmed = false; pm.isManualOverrideActive = false }
            DB_SEED -> ioLaunch {
                val dao = db.appDao()
                dao.insertProtectedApp(ProtectedApp("com.whatsapp", "WhatsApp"))
                dao.insertProtectedApp(ProtectedApp("com.android.chrome", "Chrome"))
                dao.insertProtectedApp(ProtectedApp("com.linkedin.android", "LinkedIn"))
            }
            DB_CLEAR_APPS -> ioLaunch { db.appDao().clearApps() }
            DB_CLEAR_LOGS -> ioLaunch { db.appDao().clearAuditLogs() }
            DB_RESET -> ioLaunch {
                db.clearAllTables()
            }
        }
    }

    /** Force a value into the plain store so the prefs getter reads it even
     *  before the encrypted store finishes initializing (desync workaround). */
    private fun forcePlainPref(key: String, value: Boolean) {
        getSharedPreferences("ghostscreen_prefs", MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
    }

    private fun launchMain(dest: String) {
        exec(PREFS_SKIP_ONBOARDING) // deterministic: never land in onboarding during a test
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(EXTRA_DEST, dest)
        }
        startActivity(intent)
    }

    private fun ioLaunch(block: suspend () -> Unit) = ioScope.launch { block() }

    // ── Helpers / read-outs ────────────────────────────────────────────────
    private fun readPrefs(): String {
        val pm = PreferencesManager(applicationContext)
        return buildString {
            append("armed="); append(pm.isArmed); append(" | ")
            append("manual="); append(pm.isManualOverrideActive); append(" | ")
            append("onboarded="); append(pm.isOnboardingCompleted); append(" | ")
            append("color="); append(pm.color)
        }
    }

    // ── Tiny view builders (framework-only, no Compose) ────────────────────
    private fun section(root: LinearLayout, title: String, body: () -> Unit) {
        TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.parseColor("#FF6750A4"))
            setPadding(0, 40, 0, 16)
        }.also { root.addView(it) }
        body()
    }

    private fun btn(parent: ViewGroup, label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        parent.addView(b)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_DEST = "dest"
        // action keys
        const val PREFS_SKIP_ONBOARDING = "prefs_skip_onboarding"
        const val PREFS_RESET = "prefs_reset"
        const val STATE_ARM_SMART = "state_arm_smart"
        const val STATE_MANUAL_ON = "state_manual_on"
        const val STATE_DISARM = "state_disarm"
        const val DB_SEED = "db_seed"
        const val DB_CLEAR_APPS = "db_clear_apps"
        const val DB_CLEAR_LOGS = "db_clear_logs"
        const val DB_RESET = "db_reset"
        // dest keys (MainActivity consumes these)
        const val DEST_HOME = "home"
        const val DEST_SMART_MASK = "smart_mask"
        const val DEST_AUDIT = "audit_log"
        const val DEST_SETTINGS = "settings"
        const val DEST_ABOUT = "about"
    }
}
