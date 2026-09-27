package com.etaegord.ghostscreen.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.etaegord.ghostscreen.data.PreferencesManager

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

        // Toggle Manual Mode.
        val newState = !preferencesManager.isManualOverrideActive

        // Instant visual feedback: update QS tile immediately before pref write
        qsTile?.let { tile ->
            tile.state = if (newState) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = if (newState) "GhostScreen (On)" else "GhostScreen"
            tile.updateTile()
        }

        // Pref change triggers accessibility service's OnSharedPreferenceChangeListener
        preferencesManager.isManualOverrideActive = newState
    }

    private fun updateTileState() {
        val tile = qsTile ?: return

        if (preferencesManager.isManualOverrideActive) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "GhostScreen (On)"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "GhostScreen"
        }
        tile.updateTile()
    }
}
