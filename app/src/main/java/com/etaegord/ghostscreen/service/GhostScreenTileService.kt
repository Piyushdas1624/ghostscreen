package com.etaegord.ghostscreen.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.etaegord.ghostscreen.data.PreferencesManager

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
        preferencesManager.isArmed = !preferencesManager.isArmed
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isArmed = preferencesManager.isArmed
        
        tile.state = if (isArmed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        
        // Subtitle is available in Android 10 (API 29) and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isArmed) "Armed" else "Disarmed"
        }
        
        tile.updateTile()
    }
}
