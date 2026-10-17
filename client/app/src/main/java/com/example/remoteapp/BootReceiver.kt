package com.example.remoteapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val TAG = "RemoteBootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.d(TAG, "Device boot completed / package updated. Checking startup settings...")
            val prefs = context.getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)
            val autostartEnabled = prefs.getBoolean("autostart_on_boot", true)

            if (autostartEnabled) {
                val ip = prefs.getString("last_ip", "") ?: ""
                val name = prefs.getString("last_name", "PC") ?: "PC"
                Log.d(TAG, "Starting RemoteBackgroundService for PC: $name ($ip)")
                RemoteBackgroundService.start(context, name, ip)
            } else {
                Log.d(TAG, "Auto-start on boot is disabled in settings.")
            }
        }
    }
}
