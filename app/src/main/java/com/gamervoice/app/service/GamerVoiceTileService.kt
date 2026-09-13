package com.gamervoice.app.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.model.RoomPersistenceManager

@RequiresApi(Build.VERSION_CODES.N)
class GamerVoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        val cachedRooms = RoomPersistenceManager.getCachedRooms()
        val lastRoomCode = cachedRooms.firstOrNull()?.roomCode

        val launchIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!lastRoomCode.isNullOrEmpty()) {
                putExtra("auto_join_room", lastRoomCode)
            }
        }

        if (isLocked) {
            unlockAndRun {
                startActivityAndCollapse(launchIntent)
            }
        } else {
            startActivityAndCollapse(launchIntent)
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val cachedRooms = RoomPersistenceManager.getCachedRooms()
        val lastRoomCode = cachedRooms.firstOrNull()?.roomCode

        if (!lastRoomCode.isNullOrEmpty()) {
            tile.label = "Squad: $lastRoomCode"
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Tap to Rejoin"
            }
        } else {
            tile.label = "GamerVoice"
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Ready to Squad"
            }
        }
        tile.updateTile()
    }
}
