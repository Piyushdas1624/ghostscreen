package com.etaegord.ghostscreen.service

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.etaegord.ghostscreen.data.PreferencesManager
import com.etaegord.ghostscreen.ui.MainActivity
import com.etaegord.ghostscreen.util.isGhostScreenAccessibilityEnabled

@RequiresApi(Build.VERSION_CODES.N)
class GhostScreenTileService : TileService() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager.getInstance(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // Guard: If accessibility service or overlay permission is missing,
        // we cannot draw a mask, so route user into MainActivity instead of
        // falsely activating the tile.
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y = isGhostScreenAccessibilityEnabled(this)
        if (!hasOverlay || !hasA11y) {
            qsTile?.let { tile ->
                tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.etaegord.ghostscreen.R.drawable.ic_tile)
                tile.state = Tile.STATE_INACTIVE
                tile.label = "GhostScreen (Off)"
                tile.updateTile()
            }
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        // Toggle Manual Mode.
        val newState = !preferencesManager.isManualOverrideActive

        // Instant visual feedback: update QS tile immediately before pref write
        qsTile?.let { tile ->
            tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.etaegord.ghostscreen.R.drawable.ic_tile)
            tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (newState) "GhostScreen (On)" else "GhostScreen"
            tile.updateTile()
        }

        // Pref change triggers accessibility service's OnSharedPreferenceChangeListener
        preferencesManager.isManualOverrideActive = newState
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        tile.icon = android.graphics.drawable.Icon.createWithResource(this, com.etaegord.ghostscreen.R.drawable.ic_tile)

        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y = isGhostScreenAccessibilityEnabled(this)

        if (hasOverlay && hasA11y && preferencesManager.isManualOverrideActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "GhostScreen (On)"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "GhostScreen"
        }
        tile.updateTile()
    }
}
