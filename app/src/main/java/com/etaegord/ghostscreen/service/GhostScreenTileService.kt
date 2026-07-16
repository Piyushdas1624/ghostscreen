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
        preferencesManager = PreferencesManager(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        // Toggle Manual Mode. The accessibility service's
        // OnSharedPreferenceChangeListener reacts to this pref change and
        // shows/hides the overlay — including a proper hide when turning off,
        // even if the current foreground app is protected.
        preferencesManager.isManualOverrideActive = !preferencesManager.isManualOverrideActive

        updateTileState()
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
