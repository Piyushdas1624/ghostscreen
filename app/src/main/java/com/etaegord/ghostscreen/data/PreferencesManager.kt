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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val plainPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME_PLAIN, Context.MODE_PRIVATE)

    // Encrypted prefs are initialized lazily on a background thread
    @Volatile
    private var encryptedPrefs: SharedPreferences? = null
    private val initMutex = Mutex()

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
    }

    init {
        // Initialize encrypted prefs on background thread to avoid blocking UI
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val ePrefs = EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME_ENCRYPTED,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                // Migrate plain prefs → encrypted prefs (one time)
                if (!ePrefs.getBoolean(KEY_MIGRATED, false)) {
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
                    Log.d(TAG, "Migrated plain prefs to encrypted prefs")
                }

                encryptedPrefs = ePrefs
                Log.d(TAG, "Encrypted prefs initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init encrypted prefs, staying with plain prefs", e)
            }
        }
    }

    /** Returns the best available prefs (encrypted if ready, plain otherwise) */
    private val prefs: SharedPreferences
        get() = encryptedPrefs ?: plainPrefs

    /** Write to both plain (instant) and encrypted (when ready) */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        plainPrefs.edit().apply { block() }.apply()
        encryptedPrefs?.edit()?.apply { block() }?.apply()
    }

    var isArmed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = edit { putBoolean(KEY_ARMED, value) }

    var isManualOverrideActive: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_OVERRIDE, false)
        set(value) = edit { putBoolean(KEY_MANUAL_OVERRIDE, value) }

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

    fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        plainPrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
