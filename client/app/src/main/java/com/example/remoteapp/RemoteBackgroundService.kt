package com.example.remoteapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL

/**
 * Ultra-low power, battery-optimized background service for Remote PC.
 * Maintains seamless Wi-Fi connectivity without holding permanent 24-hour wake locks,
 * allowing the Android OS to enter deep sleep / Doze mode when idle.
 */
class RemoteBackgroundService : Service() {
    private val TAG = "RemoteBgService"
    private var powerManager: PowerManager? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var currentPcName: String = "PC"
    private var targetIp: String = ""

    companion object {
        const val CHANNEL_ID = "remote_pc_bg_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.remoteapp.ACTION_START"
        const val ACTION_STOP = "com.example.remoteapp.ACTION_STOP"
        const val EXTRA_PC_NAME = "extra_pc_name"
        const val EXTRA_TARGET_IP = "extra_target_ip"

        var isRunning = false
            private set

        var connectedPcName: String = "PC"
            private set

        var activeTargetIp: String = ""
            private set

        fun start(context: Context, pcName: String = "PC", targetIp: String = "") {
            val prefs = context.getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("master_power_enabled", true)
                .apply()

            val intent = Intent(context, RemoteBackgroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PC_NAME, pcName)
                putExtra(EXTRA_TARGET_IP, targetIp)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val prefs = context.getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("master_power_enabled", false)
                .apply()

            val intent = Intent(context, RemoteBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        createNotificationChannel()
        VideoStreamServer.startServer(this)
        FolderSyncManager.start(this)
        isRunning = true
        Log.d(TAG, "RemoteBackgroundService created with battery-optimized sleep profile")
        RemoteControlTileService.requestTileUpdate(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Received ACTION_STOP -> Stopping service cleanly for 0% battery drain")
            stopForegroundService()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)
        val isMasterEnabled = prefs.getBoolean("master_power_enabled", true)
        if (!isMasterEnabled) {
            Log.d(TAG, "Master power is disabled by user -> Not starting service")
            stopForegroundService()
            return START_NOT_STICKY
        }

        currentPcName = intent?.getStringExtra(EXTRA_PC_NAME) ?: currentPcName
        targetIp = intent?.getStringExtra(EXTRA_TARGET_IP) ?: targetIp
        connectedPcName = currentPcName
        activeTargetIp = targetIp

        startForegroundWithNotification()
        startHeartbeat()
        FolderSyncManager.start(this, targetIp, SecureStorage.getPassword(this))
        RemoteControlTileService.requestTileUpdate(this)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote PC Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Remote PC connection status and quick power controls"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RemoteBackgroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Connected to $currentPcName")
            .setContentText("Connected via Wi-Fi • Power Saver Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_menu_view, "Open", pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn OFF", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: app closed/swiped away")
        val prefs = getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE)
        val isMasterEnabled = prefs.getBoolean("master_power_enabled", true)
        if (!isMasterEnabled) {
            stopForegroundService()
        }
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(this, RemoteBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(android.R.drawable.ic_menu_view, "Open", pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn OFF", stopPendingIntent)
                .build()

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private var wasConnected = false

    /**
     * Adaptive, battery-conscious heartbeat loop.
     * Backs off check interval when phone screen is OFF or when PC is unreachable,
     * saving up to 90% background energy.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            var consecutiveFailures = 0

            while (isActive) {
                try {
                    val autoEnabled = SecureStorage.isAutoConnectEnabled(this@RemoteBackgroundService)

                    val secIp = SecureStorage.getServerIp(this@RemoteBackgroundService)
                    val secPort = SecureStorage.getServerPort(this@RemoteBackgroundService)
                    val secName = SecureStorage.getServerName(this@RemoteBackgroundService)
                    val secTarget = if (secIp.isNotEmpty()) "$secIp:$secPort" else ""

                    val token = SecureStorage.getDeviceToken(this@RemoteBackgroundService)
                    val lastPass = SecureStorage.getPassword(this@RemoteBackgroundService)
                    val deviceName = SecureStorage.getDeviceName(this@RemoteBackgroundService)
                    val deviceId = SecureStorage.getDeviceId(this@RemoteBackgroundService)

                    var activeTarget = if (targetIp.isNotEmpty()) targetIp else secTarget
                    var activeName = if (currentPcName.isNotEmpty() && currentPcName != "PC") currentPcName else secName

                    var isAlive = false
                    if (activeTarget.isNotEmpty()) {
                        isAlive = pingPcServer(activeTarget)
                    }

                    if (!isAlive && autoEnabled && consecutiveFailures < 3) {
                        val discovered = discoverPcOnNetwork()
                        if (discovered != null) {
                            activeTarget = discovered.second
                            activeName = discovered.first
                            targetIp = activeTarget
                            currentPcName = activeName
                            connectedPcName = activeName
                            activeTargetIp = activeTarget
                            isAlive = true

                            SecureStorage.saveServerTarget(this@RemoteBackgroundService, activeTarget, activeName)
                        }
                    }

                    if (isAlive && activeTarget.isNotEmpty()) {
                        consecutiveFailures = 0
                        sendPhoneHeartbeat(activeTarget, token, lastPass, deviceName, deviceId)

                        if (!wasConnected) {
                            wasConnected = true
                            connectedPcName = activeName
                            updateNotification("🟢 Connected to $activeName", "Wi-Fi Connected • Power Saver Active")
                            RemoteControlTileService.requestTileUpdate(this@RemoteBackgroundService)
                            Log.d(TAG, "PC $activeName is ONLINE. Auto-connected!")
                        }
                    } else {
                        consecutiveFailures++
                        if (wasConnected) {
                            wasConnected = false
                            connectedPcName = "PC"
                            updateNotification("Remote PC Service Active", "Searching for $activeName on Wi-Fi...")
                            RemoteControlTileService.requestTileUpdate(this@RemoteBackgroundService)
                            Log.d(TAG, "PC $activeName went OFFLINE.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat loop error", e)
                }

                // Battery-optimized delay calculation:
                // - Interactive (screen ON): 15 seconds
                // - Idle (screen OFF): 30 seconds
                // - Offline / unreachable: exponential backoff up to 60 seconds
                val isScreenOn = powerManager?.isInteractive ?: true
                val baseDelay = if (isScreenOn) 15_000L else 30_000L
                val adaptiveDelay = if (consecutiveFailures > 0) {
                    (baseDelay * (1 + consecutiveFailures.coerceAtMost(3))).coerceAtMost(60_000L)
                } else {
                    baseDelay
                }

                delay(adaptiveDelay)
            }
        }
    }

    private fun pingPcServer(hostAndPort: String): Boolean {
        val clean = if (!hostAndPort.contains(":")) "$hostAndPort:5055" else hostAndPort
        return try {
            val url = URL("http://$clean/ping")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            try {
                val url = URL("http://$clean/pair/status")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                code == 200
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun sendPhoneHeartbeat(hostAndPort: String, token: String, password: String, deviceName: String, deviceId: String) {
        val clean = if (!hostAndPort.contains(":")) "$hostAndPort:5055" else hostAndPort
        try {
            val url = URL("http://$clean/phone/heartbeat")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "POST"
            if (token.isNotEmpty()) conn.setRequestProperty("X-Device-Token", token)
            if (password.isNotEmpty()) conn.setRequestProperty("X-Remote-Password", password)
            conn.setRequestProperty("X-Device-Name", deviceName)
            conn.setRequestProperty("X-Device-Id", deviceId)
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (ni.isLoopback || !ni.isUp) continue
                for (ia in ni.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && !list.contains(bcast)) {
                        list.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun discoverPcOnNetwork(): Pair<String, String>? {
        return try {
            val socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1200
            }
            val reqData = "DISCOVER_PC_REMOTE".toByteArray()
            val broadcasts = getBroadcastAddresses()
            for (bcast in broadcasts) {
                try {
                    val packet = DatagramPacket(reqData, reqData.size, bcast, 9999)
                    socket.send(packet)
                } catch (_: Exception) {}
            }

            val buf = ByteArray(1024)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    val respPacket = DatagramPacket(buf, buf.size)
                    socket.receive(respPacket)
                    val senderIp = respPacket.address.hostAddress ?: continue
                    val msg = String(respPacket.data, 0, respPacket.length).trim()

                    if (msg == "DISCOVER_PC_REMOTE") continue

                    var name = "PC"
                    var port = "5055"
                    if (msg.startsWith("REMOTE_SERVER|")) {
                        val parts = msg.split("|")
                        if (parts.size >= 3) {
                            name = parts[1]
                            port = parts[2]
                        }
                    } else if (msg.contains("|")) {
                        val parts = msg.split("|")
                        name = parts[0]
                        if (parts.size > 1) port = parts[1]
                    }

                    socket.close()
                    return Pair(name, "$senderIp:$port")
                } catch (_: Exception) {
                    break
                }
            }
            socket.close()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun stopForegroundService() {
        isRunning = false
        wasConnected = false
        connectedPcName = "PC"
        activeTargetIp = ""
        heartbeatJob?.cancel()
        FolderSyncManager.stop()
        VideoStreamServer.stopServer()
        serviceScope.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        stopForeground(STOP_FOREGROUND_REMOVE)
        RemoteControlTileService.requestTileUpdate(this)
        stopSelf()
    }

    override fun onDestroy() {
        stopForegroundService()
        super.onDestroy()
        Log.d(TAG, "RemoteBackgroundService destroyed -> CPU allowed to sleep normally")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
