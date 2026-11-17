package com.example.remoteapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log

object WifiNetworkHelper {
    private const val TAG = "WifiNetworkHelper"

    /**
     * Attempts to connect to the specified Wi-Fi SSID (and optional passphrase).
     * On Android 10+ (API 29+), uses WifiNetworkSpecifier with ConnectivityManager.
     */
    fun connectToWifi(
        context: Context,
        ssid: String,
        passphrase: String = "",
        onResult: (Boolean, String) -> Unit
    ) {
        if (ssid.isBlank()) {
            onResult(false, "SSID is empty")
            return
        }

        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                onResult(false, "ConnectivityManager unavailable")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val specifierBuilder = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid.trim())

                if (passphrase.isNotBlank()) {
                    specifierBuilder.setWpa2Passphrase(passphrase.trim())
                }

                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifierBuilder.build())
                    .build()

                var responded = false
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        Log.d(TAG, "Connected to network $ssid")
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            Log.w(TAG, "bindProcessToNetwork failed", e)
                        }
                        if (!responded) {
                            responded = true
                            onResult(true, "Connected to $ssid")
                        }
                    }

                    override fun onUnavailable() {
                        super.onUnavailable()
                        Log.w(TAG, "Network $ssid unavailable")
                        if (!responded) {
                            responded = true
                            onResult(false, "Could not connect to $ssid")
                        }
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        try {
                            cm.bindProcessToNetwork(null)
                        } catch (_: Exception) {}
                    }
                }

                cm.requestNetwork(networkRequest, callback)
            } else {
                onResult(false, "Auto-connect requires Android 10 or newer. Please connect to $ssid manually in Wi-Fi settings.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectToWifi error", e)
            onResult(false, "Failed to connect to $ssid: ${e.message}")
        }
    }
}
