package com.example.remoteapp

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android Control Center / Quick Settings Tile for Remote PC.
 * Allows the user to toggle the Remote PC connection & background service
 * ON or OFF instantly from their notification / control center shade with 0% idle battery drain.
 */
@RequiresApi(Build.VERSION_CODES.N)
class RemoteControlTileService : TileService() {

    companion object {
        private const val TAG = "RemoteTileService"

        /**
         * Requests the system to refresh the Quick Settings tile state.
         */
        fun requestTileUpdate(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestListeningState(
                        context.applicationContext,
                        ComponentName(context.applicationContext, RemoteControlTileService::class.java)
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Tile update request ignored: ${e.message}")
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = RemoteBackgroundService.isRunning
        val prefs = getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)

        if (isRunning) {
            // Turn OFF: completely stop background service to preserve 100% battery
            prefs.edit().putBoolean("master_power_enabled", false).apply()
            RemoteBackgroundService.stop(this)
            Log.d(TAG, "Quick Settings: Remote PC service turned OFF by user")
        } else {
            // Turn ON: restore connection to last configured PC
            prefs.edit().putBoolean("master_power_enabled", true).apply()
            val ip = prefs.getString("last_ip", "") ?: ""
            val name = prefs.getString("last_name", "PC") ?: "PC"
            RemoteBackgroundService.start(this, name, ip)
            Log.d(TAG, "Quick Settings: Remote PC service turned ON for $name ($ip)")
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = RemoteBackgroundService.isRunning
        val pcName = RemoteBackgroundService.connectedPcName

        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_remote_pc)
        tile.label = "Remote PC"

        if (isRunning) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (pcName.isNotEmpty() && pcName != "PC") "Connected • $pcName" else "Running • ON"
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Service OFF"
            }
        }

        tile.updateTile()
    }
}
