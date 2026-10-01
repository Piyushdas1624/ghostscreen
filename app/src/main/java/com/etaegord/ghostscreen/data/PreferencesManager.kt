package com.etaegord.ghostscreen.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * PreferencesManager with lazy-initialized EncryptedSharedPreferences.
 *
 * Tink crypto verification can take 3-9 seconds on first access, which
 * was blocking the main thread and causing 587 dropped frames.
 *
 * Fix: Use standard SharedPreferences immediately (for instant reads on launch),
 * then async-migrate to encrypted prefs once Tink is ready. Writes go to both.
 */
class PreferencesManager(context: Context) {
    private val appContext: Context = context.applicationContext
    private val plainPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME_PLAIN, Context.MODE_PRIVATE)

    // Encrypted prefs are initialized lazily on a background thread
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null
    private val syncLock = Any()

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_NAME_PLAIN = "ghostscreen_prefs"
        private const val PREFS_NAME_ENCRYPTED = "ghostscreen_secure_prefs"
        private const val KEY_MIGRATED = "encrypted_prefs_migrated"

        private const val KEY_ARMED = "armed"
        private const val KEY_MANUAL_OVERRIDE = "manual_override_active"
        private const val KEY_TRANSPARENCY = "transparency"
        private const val KEY_COLOR = "color"
        private const val KEY_LAUNCH_ON_BOOT = "launch_on_boot"
        private const val KEY_VIBRATE_ON_ACTIVATION = "vibrate_on_activation"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        // Window Cutout parameters (percentage of screen height)
        private const val KEY_CUTOUT_Y_PCT = "cutout_y_pct"
        private const val KEY_CUTOUT_HEIGHT_PCT = "cutout_height_pct"

        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_BLUR_RADIUS = "blur_radius"
        private const val KEY_DISCLAIMER_UNDERSTOOD = "disclaimer_understood"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        // Next-Gen Privacy Suite Keys
        private const val KEY_OVERLAY_MODE = "overlay_mode"
        private const val KEY_SPOTLIGHT_RADIUS_DP = "spotlight_radius_dp"
        private const val KEY_TILT_SHIELD_ENABLED = "tilt_shield_enabled"
        private const val KEY_TILT_ANGLE_THRESHOLD = "tilt_angle_threshold"
        private const val KEY_PANIC_SHAKE_ENABLED = "panic_shake_enabled"
        private const val KEY_AI_SENSITIVE_SHIELDING_ENABLED = "ai_sensitive_shielding_enabled"
        private const val KEY_AI_SHIELD_PASSWORDS = "ai_shield_passwords"
        private const val KEY_AI_SHIELD_CARDS = "ai_shield_cards"
        private const val KEY_AI_SHIELD_OTPS = "ai_shield_otps"
        private const val KEY_AI_SHIELD_BALANCES = "ai_shield_balances"
        private const val KEY_NOTIFICATION_SHIELD_ENABLED = "notification_shield_enabled"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager =
            instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
    }

    init {
        // Initialize encrypted prefs on background thread to avoid blocking UI.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val ePrefs = try {
                createEncryptedPrefs(appContext)
            } catch (e: Exception) {
                // Corrupt keyset/ciphertext (observed on-device as
                // AEADBadTagException): the stored encrypted prefs reference a
                // Keystore master key that no longer decrypts them — typically
                // caused by reinstalls, key rotation, or a clock-was-wrong first
                // boot. Tink cannot recover; the only sane fix is to drop the
                // orphaned store + its "have I installed?" sentinel and rebuild
                // from the (always-trusted) plain prefs. Verified recoverable
                // on a real device.
                Log.e(TAG, "Encrypted prefs corrupt, rebuilding from plain prefs", e)
                deleteEncryptedStore(appContext)
                try {
                    createEncryptedPrefs(appContext)
                } catch (e2: Exception) {
                    Log.e(TAG, "Encrypted prefs still failing after wipe; using plain prefs", e2)
                    return@launch
                }
            }

            // Always sync plain -> encrypted on init (not just once via
            // KEY_MIGRATED). This closes the startup desync window: any setting
            // written to plainPrefs BEFORE encryptedPrefs was ready (or while it
            // was broken) is replayed here, so reads can safely flip to the
            // encrypted store without reverting recent user changes.
            synchronized(syncLock) {
                val editor = ePrefs.edit()
                plainPrefs.all.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        is Long -> editor.putLong(key, value)
                    }
                }
                editor.putBoolean(KEY_MIGRATED, true)
                editor.apply()

                encryptedPrefs = ePrefs
            }
            Log.d(TAG, "Encrypted prefs initialized successfully")
        }
    }

    private fun createEncryptedPrefs(appContext: android.content.Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME_ENCRYPTED,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Drop the orphaned encrypted store and Tink's install sentinel so a fresh
     *  keyset can be created on the next attempt. */
    private fun deleteEncryptedStore(appContext: android.content.Context) {
        runCatching {
            appContext.deleteSharedPreferences(PREFS_NAME_ENCRYPTED)
        }
        // Tink's AndroidKeysetManager records "have I installed?" here; if left
        // behind it short-circuits keyset creation and re-poisons the next init.
        runCatching { java.io.File(appContext.filesDir, "profileInstalled").delete() }
    }

    /** Returns the best available prefs (encrypted if ready, plain otherwise) */
    private val prefs: SharedPreferences
        get() = encryptedPrefs ?: plainPrefs

    /** Write to both plain (instant) and encrypted (when ready) */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        synchronized(syncLock) {
            plainPrefs.edit().apply { block() }.apply()
            encryptedPrefs?.edit()?.apply { block() }?.apply()
        }
    }

    var isArmed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = edit { putBoolean(KEY_ARMED, value) }

    var isManualOverrideActive: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
        set(value) {
            edit { putBoolean(KEY_MANUAL_OVERRIDE, value) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    android.service.quicksettings.TileService.requestListeningState(
                        appContext,
                        android.content.ComponentName(appContext, "com.etaegord.ghostscreen.service.GhostScreenTileService")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to request tile listening state", e)
                }
            }
        }

    var transparency: Int
        get() = prefs.getInt(KEY_TRANSPARENCY, 75)
        set(value) = edit { putInt(KEY_TRANSPARENCY, value.coerceIn(0, 100)) }

    var color: String
        get() = prefs.getString(KEY_COLOR, "#000000") ?: "#000000"
        set(value) = edit { putString(KEY_COLOR, value) }

    var isLaunchOnBootEnabled: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_ON_BOOT, false)
        set(value) = edit { putBoolean(KEY_LAUNCH_ON_BOOT, value) }

    var isVibrateOnActivationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE_ON_ACTIVATION, true)
        set(value) = edit { putBoolean(KEY_VIBRATE_ON_ACTIVATION, value) }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    var cutoutYPct: Float
        get() = prefs.getFloat(KEY_CUTOUT_Y_PCT, 0.5f)
        set(value) = edit { putFloat(KEY_CUTOUT_Y_PCT, value.coerceIn(0.0f, 1.0f)) }

    var cutoutHeightPct: Float
        get() = prefs.getFloat(KEY_CUTOUT_HEIGHT_PCT, 0.25f)
        set(value) = edit { putFloat(KEY_CUTOUT_HEIGHT_PCT, value.coerceIn(0.05f, 0.9f)) }

    var isBlurEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLUR_ENABLED, false)
        set(value) = edit { putBoolean(KEY_BLUR_ENABLED, value) }

    var blurRadius: Int
        get() = prefs.getInt(KEY_BLUR_RADIUS, 10)
        set(value) = edit { putInt(KEY_BLUR_RADIUS, value.coerceIn(0, 25)) }

    var isSmartMaskDisclaimerUnderstood: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER_UNDERSTOOD, false)
        set(value) = edit { putBoolean(KEY_DISCLAIMER_UNDERSTOOD, value) }

    var isDynamicColorEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) = edit { putBoolean(KEY_DYNAMIC_COLOR, value) }

    var overlayModeName: String
        get() = prefs.getString(KEY_OVERLAY_MODE, "SPLIT") ?: "SPLIT"
        set(value) = edit { putString(KEY_OVERLAY_MODE, value) }

    var spotlightRadiusDp: Int
        get() = prefs.getInt(KEY_SPOTLIGHT_RADIUS_DP, 95)
        set(value) = edit { putInt(KEY_SPOTLIGHT_RADIUS_DP, value.coerceIn(50, 200)) }

    var isTiltShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_TILT_SHIELD_ENABLED, false)
        set(value) = edit { putBoolean(KEY_TILT_SHIELD_ENABLED, value) }

    var tiltAngleThreshold: Int
        get() = prefs.getInt(KEY_TILT_ANGLE_THRESHOLD, 40)
        set(value) = edit { putInt(KEY_TILT_ANGLE_THRESHOLD, value.coerceIn(20, 70)) }

    var isPanicShakeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PANIC_SHAKE_ENABLED, false)
        set(value) = edit { putBoolean(KEY_PANIC_SHAKE_ENABLED, value) }

    var isAiSensitiveShieldingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SENSITIVE_SHIELDING_ENABLED, false)
        set(value) = edit { putBoolean(KEY_AI_SENSITIVE_SHIELDING_ENABLED, value) }

    var isAiShieldPasswordsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SHIELD_PASSWORDS, true)
        set(value) = edit { putBoolean(KEY_AI_SHIELD_PASSWORDS, value) }

    var isAiShieldCardsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SHIELD_CARDS, true)
        set(value) = edit { putBoolean(KEY_AI_SHIELD_CARDS, value) }

    var isAiShieldOtpsEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SHIELD_OTPS, true)
        set(value) = edit { putBoolean(KEY_AI_SHIELD_OTPS, value) }

    var isAiShieldBalancesEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_SHIELD_BALANCES, true)
        set(value) = edit { putBoolean(KEY_AI_SHIELD_BALANCES, value) }

    var isNotificationShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SHIELD_ENABLED, true)
        set(value) = edit { putBoolean(KEY_NOTIFICATION_SHIELD_ENABLED, value) }

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
