package com.etaegord.ghostscreen.service

import android.content.Intent
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
        
        // Toggle Manual Mode
        val isActive = preferencesManager.isManualOverrideActive
        preferencesManager.isManualOverrideActive = !isActive

        // Notify the accessibility service to evaluate the new state
        val intent = Intent("com.etaegord.ghostscreen.EVALUATE_STATE")
        sendBroadcast(intent)
        
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
