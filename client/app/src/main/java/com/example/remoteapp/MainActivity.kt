package com.example.remoteapp

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import android.content.ContentValues
import android.os.Environment
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink

import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.*
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

data class FsItem(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val ext: String = "",
    val fileType: String = "file",
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val isImage: Boolean = false
)

data class LocalFileItem(
    val id: Long,
    val name: String,
    val size: Long,
    val uri: Uri,
    val category: String, // "video", "audio", "image", "document", "file"
    val duration: Long = 0L,
    val ext: String = ""
) {
    // Compatibility property
    val title: String get() = name
}

typealias LocalVideo = LocalFileItem

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

class MainActivity : FragmentActivity() {
    private val TAG = "RemoteAppDebug"
    private val PREFS_NAME = "RemoteAppPrefs"
    private val KEY_LAST_IP = "last_ip"
    private val KEY_LAST_NAME = "last_name"
    private val KEY_LAST_PASSWORD = "last_password"
    private val KEY_DEVICE_SAVED = "device_saved"
    private val KEY_DARK_MODE = "is_dark_mode"
    private val KEY_AUTO_CONNECT_ENABLED = "auto_connect_enabled"
    private val KEY_AUTO_CONNECT_TARGET = "auto_connect_target"
    private val KEY_AUTO_CONNECT_NAME = "auto_connect_name"

    private val TOUCHPAD_UDP_PORT = 6000
    private val KEYBOARD_UDP_PORT = 7000
    private val SLIDER_UDP_PORT = 8000
    private val PHONE_STREAM_PORT = 5056

    private var udpSocket: DatagramSocket? = null
    private val addressCache = mutableMapOf<String, InetAddress>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var videoServer: VideoStreamServer? = null
    val pendingPairRequestState = mutableStateOf<Pair<String, String>?>(null)

    private fun getUdpSocket(): DatagramSocket {
        var sock = udpSocket
        if (sock == null || sock.isClosed) {
            sock = DatagramSocket()
            udpSocket = sock
        }
        return sock
    }

    private fun sendUdpMove(dx: Float, dy: Float, targetIpAndPort: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ip = targetIpAndPort.split(":")[0]
                val addr = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val data = "$dx,$dy".toByteArray()
                val packet = DatagramPacket(data, data.size, addr, TOUCHPAD_UDP_PORT)
                getUdpSocket().send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "sendUdpMove error", e)
            }
        }
    }

    private fun sendUdpKey(text: String, targetIpAndPort: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ip = targetIpAndPort.split(":")[0]
                val addr = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val data = text.toByteArray()
                val packet = DatagramPacket(data, data.size, addr, KEYBOARD_UDP_PORT)
                getUdpSocket().send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "sendUdpKey error", e)
            }
        }
    }

    private var lastSentVol = -1
    private var lastSentBright = -1

    private fun sendUdpSlider(cmd: String, value: Int, targetIpAndPort: String, force: Boolean = false) {
        if (cmd == "vol" || cmd == "volume") {
            if (!force && lastSentVol == value) return
            lastSentVol = value
        } else if (cmd == "bright" || cmd == "brightness") {
            if (!force && lastSentBright == value) return
            lastSentBright = value
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ip = targetIpAndPort.split(":")[0]
                val addr = addressCache.getOrPut(ip) { InetAddress.getByName(ip) }
                val message = "$cmd:$value"
                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, addr, SLIDER_UDP_PORT)
                getUdpSocket().send(packet)
            } catch (e: Exception) {
                Log.e(TAG, "sendUdpSlider error", e)
            }
        }
    }

    private fun saveCredentials(name: String, ip: String, pass: String, savedDevice: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_IP, ip)
            .putString(KEY_LAST_NAME, name)
            .putString(KEY_LAST_PASSWORD, pass)
            .putBoolean(KEY_DEVICE_SAVED, savedDevice)
            .putBoolean(KEY_AUTO_CONNECT_ENABLED, true)
            .putString(KEY_AUTO_CONNECT_TARGET, ip)
            .putString(KEY_AUTO_CONNECT_NAME, name)
            .apply()
    }

    private fun getSavedCredentials(): Triple<String, String, String>? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString(KEY_LAST_IP, null) ?: return null
        val name = prefs.getString(KEY_LAST_NAME, "") ?: ""
        val pass = prefs.getString(KEY_LAST_PASSWORD, "") ?: ""
        return Triple(name, ip, pass)
    }

    private fun isDeviceSaved(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEVICE_SAVED, false)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            } else {
                Toast.makeText(this, "Battery is set to Unrestricted for long background use", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun promptPhoneLock(title: String, onSuccess: () -> Unit, onFallback: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    Toast.makeText(this@MainActivity, "Authentication cancelled", Toast.LENGTH_SHORT).show()
                } else {
                    onFallback()
                }
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Remote PC")
            .setSubtitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Biometric authenticate error, fallback", e)
            onFallback()
        }
    }

    private fun sendCommand(
        cmd: String,
        targetIpAndPort: String,
        password: String,
        onUnauthorized: (() -> Unit)? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanTarget = getCleanTarget(targetIpAndPort)
                val url = "http://$cleanTarget/$cmd"
                val effectivePass = if (password.isNotEmpty()) password else SecureStorage.getPassword(this@MainActivity)
                val token = SecureStorage.getDeviceToken(this@MainActivity)
                val request = Request.Builder()
                    .url(url)
                    .post(RequestBody.create(null, ByteArray(0)))
                    .addHeader("X-Remote-Password", effectivePass)
                    .apply {
                        if (token.isNotEmpty()) addHeader("X-Device-Token", token)
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        withContext(Dispatchers.Main) {
                            onUnauthorized?.invoke()
                        }
                    } else if (response.code == 405) {
                        val getReq = Request.Builder()
                            .url(url)
                            .get()
                            .addHeader("X-Remote-Password", effectivePass)
                            .apply {
                                if (token.isNotEmpty()) addHeader("X-Device-Token", token)
                            }
                            .build()
                        client.newCall(getReq).execute().close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendCommand error: $cmd", e)
            }
        }
    }

    private fun sendMouseAction(
        path: String,
        queryParams: Map<String, String>,
        targetIpAndPort: String,
        password: String,
        onUnauthorized: (() -> Unit)? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cleanTarget = getCleanTarget(targetIpAndPort)
                val urlBuilder = ("http://$cleanTarget$path").toHttpUrlOrNull()?.newBuilder() ?: return@launch
                for ((k, v) in queryParams) {
                    urlBuilder.addQueryParameter(k, v)
                }
                val token = SecureStorage.getDeviceToken(this@MainActivity)
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .post(RequestBody.create(null, ByteArray(0)))
                    .addHeader("X-Remote-Password", password)
                    .apply {
                        if (token.isNotEmpty()) addHeader("X-Device-Token", token)
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        withContext(Dispatchers.Main) {
                            onUnauthorized?.invoke()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendMouseAction error", e)
            }
        }
    }

    private fun getCleanTarget(target: String): String {
        return if (!target.contains(":")) "$target:5055" else target
    }

    fun getAllLocalIps(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "0.0.0.0")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllLocalIps error", e)
        }
        return ips
    }

    fun getLocalIpTowards(targetHost: String): String {
        try {
            val hostOnly = if (targetHost.contains(":")) targetHost.substringBefore(":") else targetHost
            val targetAddr = InetAddress.getByName(hostOnly)
            DatagramSocket().use { socket ->
                socket.connect(targetAddr, 5055)
                val ip = socket.localAddress?.hostAddress
                if (!ip.isNullOrEmpty() && ip != "0.0.0.0" && ip != "127.0.0.1") {
                    return ip
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIpTowards probe error", e)
        }

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrEmpty() && ip != "127.0.0.1" && ip != "0.0.0.0") {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIpTowards fallback error", e)
        }

        return "127.0.0.1"
    }

    private fun isServerAlive(target: String): Boolean {
        return try {
            val cleanTarget = getCleanTarget(target)
            val req = Request.Builder()
                .url("http://$cleanTarget/")
                .get()
                .build()
            val shortClient = client.newBuilder()
                .connectTimeout(700, TimeUnit.MILLISECONDS)
                .readTimeout(700, TimeUnit.MILLISECONDS)
                .build()
            shortClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code == 401 || resp.code == 403
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startVideoServer() {
        if (videoServer == null) {
            try {
                videoServer = VideoStreamServer.startServer(
                    this,
                    onPairRequest = { name, ip ->
                        runOnUiThread {
                            pendingPairRequestState.value = Pair(name, ip)
                        }
                    },
                    onPairApproved = { name, ip ->
                        runOnUiThread {
                            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putBoolean(KEY_DEVICE_SAVED, true)
                                .putBoolean(KEY_AUTO_CONNECT_ENABLED, true)
                                .putString(KEY_AUTO_CONNECT_TARGET, ip)
                                .putString(KEY_AUTO_CONNECT_NAME, name)
                                .putString(KEY_LAST_IP, ip)
                                .putString(KEY_LAST_NAME, name)
                                .apply()
                            Toast.makeText(this@MainActivity, "✓ Auto-Connect Authorized by PC ($name)!", Toast.LENGTH_LONG).show()
                        }
                    },
                    onToast = { text ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
                        }
                    }
                )
                Log.d(TAG, "Started VideoStreamServer on port $PHONE_STREAM_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VideoStreamServer", e)
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netIf = interfaces.nextElement()
                if (netIf.isLoopback || !netIf.isUp) continue
                for (addr in netIf.interfaceAddresses) {
                    addr.broadcast?.let { list.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBroadcastAddresses error", e)
        }
        if (list.isEmpty()) {
            list.add(InetAddress.getByName("255.255.255.255"))
        }
        return list
    }

    private fun discoverAllServers(onUpdate: (List<Pair<String, String>>) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localIps = getAllLocalIps()
            val found = Collections.synchronizedMap(LinkedHashMap<String, Pair<String, String>>()) // Key: normalized IP

            fun emitUnique() {
                val uniqueList = found.values
                    .distinctBy { it.first.trim().lowercase() } // Deduplicate by Hostname
                    .toList()
                lifecycleScope.launch(Dispatchers.Main) {
                    onUpdate(uniqueList)
                }
            }

            withContext(Dispatchers.Main) {
                onUpdate(emptyList())
            }

            // Quick probe saved PC credentials if online and not self
            getSavedCredentials()?.let { saved ->
                val savedTarget = saved.second
                val savedIp = if (savedTarget.contains(":")) savedTarget.substringBefore(":") else savedTarget
                val fullTarget = if (savedTarget.contains(":")) savedTarget else "$savedTarget:5055"
                if (!localIps.contains(savedIp) && isServerAlive(fullTarget)) {
                    found[savedIp] = Pair(saved.first, fullTarget)
                    emitUnique()
                }
            }

            var listenSocket: DatagramSocket? = null
            try {
                listenSocket = DatagramSocket(9999).apply {
                    broadcast = true
                    soTimeout = 800
                }
            } catch (e: Exception) {
                try {
                    listenSocket = DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 800
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Socket init error", e2)
                }
            }

            val discoveryMsg = "DISCOVER_PC_REMOTE".toByteArray()
            val broadcasts = getBroadcastAddresses()
            val sendSocket = DatagramSocket()
            for (bcast in broadcasts) {
                try {
                    val p = DatagramPacket(discoveryMsg, discoveryMsg.size, bcast, 9999)
                    sendSocket.send(p)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast send error", e)
                }
            }
            sendSocket.close()

            val startTime = System.currentTimeMillis()
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() - startTime < 3500) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    listenSocket?.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue

                    // IGNORE the phone's own IP and loopback
                    if (localIps.contains(senderIp)) continue

                    val text = String(packet.data, 0, packet.length).trim()

                    // IGNORE own discovery request packets that looped back
                    if (text == "DISCOVER_PC_REMOTE") continue

                    var name: String? = null
                    var port = "5055"
                    if (text.startsWith("REMOTE_SERVER|")) {
                        val parts = text.split("|")
                        if (parts.size >= 3) {
                            name = parts[1]
                            port = parts[2]
                        }
                    } else if (text.contains("|")) {
                        val parts = text.split("|")
                        name = parts[0]
                        if (parts.size > 1) port = parts[1]
                    }

                    if (name.isNullOrBlank()) continue

                    val target = "$senderIp:$port"
                    // Check if the server is actually alive before adding
                    if (isServerAlive(target)) {
                        found[senderIp] = Pair(name, target)
                        emitUnique()
                    }
                } catch (e: SocketTimeoutException) {
                    // continue listening
                } catch (e: Exception) {
                    break
                }
            }
            listenSocket?.close()
            emitUnique()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAutoTheme = initPrefs.getBoolean("auto_theme", true)
        val isSystemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        ThemeState.isAutoTheme = isAutoTheme
        ThemeState.isDark = if (isAutoTheme) isSystemDark else initPrefs.getBoolean(KEY_DARK_MODE, isSystemDark)
        startVideoServer()

        val saved = getSavedCredentials()
        if (saved != null) {
            RemoteBackgroundService.start(this, saved.first, saved.second)
        }

        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            LaunchedEffect(systemDark, ThemeState.isAutoTheme) {
                if (ThemeState.isAutoTheme) {
                    ThemeState.isDark = systemDark
                }
            }

            val bgDark = AppTheme.BackgroundDark
            val cardDark = AppTheme.CardDarkElevated
            val accentCyan = AppTheme.AccentCyan
            val accentOrange = AppTheme.AccentAmber

            MaterialTheme(
                colorScheme = AppTheme.colorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.BackgroundDark
                ) {
                    var currentScreen by remember { mutableStateOf("splash") }
                    var selectedDevice by remember { mutableStateOf<Pair<String, String>?>(null) }
                    var sessionPassword by remember { mutableStateOf("") }
                    var discoveredDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                    var currentVolume by remember { mutableFloatStateOf(50f) }
                    var currentBrightness by remember { mutableFloatStateOf(50f) }

                    var showQrScanner by remember { mutableStateOf(false) }
                    var showPasswordDialog by remember { mutableStateOf(false) }
                    var showDisconnectConfirm by remember { mutableStateOf(false) }
                    var passwordInput by remember { mutableStateOf("") }
                    var passwordVisible by remember { mutableStateOf(false) }
                    var saveDeviceChecked by remember { mutableStateOf(true) }
                    var deviceToConnect by remember { mutableStateOf<Pair<String, String>?>(null) }
                    var pendingUpdate by remember { mutableStateOf<GithubReleaseInfo?>(null) }
                    var showStartupUpdateDialog by remember { mutableStateOf(false) }
                    var isDownloadingStartupUpdate by remember { mutableStateOf(false) }
                    var startupUpdateProgress by remember { mutableFloatStateOf(0f) }
                    var startupUpdateSpeed by remember { mutableStateOf("") }
                    var startupUpdateStreams by remember { mutableIntStateOf(1) }
                    var startupUpdateTransferred by remember { mutableLongStateOf(0L) }
                    var startupUpdateTotal by remember { mutableLongStateOf(0L) }

                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val scope = rememberCoroutineScope()

                    LaunchedEffect(Unit) {
                        scope.launch(Dispatchers.IO) {
                            val appVer = AppUpdater.getAppVersion(this@MainActivity)
                            val update = AppUpdater.checkUpdate(appVer)
                            if (update != null && update.hasUpdate) {
                                withContext(Dispatchers.Main) {
                                    pendingUpdate = update
                                    showStartupUpdateDialog = true
                                }
                            }
                        }
                    }

                    fun fetchStatus(target: String, pass: String) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val cleanTarget = getCleanTarget(target)
                                val effectivePass = if (pass.isNotEmpty()) pass else SecureStorage.getPassword(this@MainActivity)
                                val token = SecureStorage.getDeviceToken(this@MainActivity)
                                val reqBuilder = Request.Builder()
                                    .url("http://$cleanTarget/status")
                                    .get()
                                    .addHeader("X-Remote-Password", effectivePass)
                                if (token.isNotEmpty()) {
                                    reqBuilder.addHeader("X-Device-Token", token)
                                }
                                val request = reqBuilder.build()
                                client.newCall(request).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        try {
                                            val json = JSONObject(body)
                                            val v = if (json.has("volume") && !json.isNull("volume")) json.optDouble("volume", -1.0).toFloat() else -1f
                                            val b = if (json.has("brightness") && !json.isNull("brightness")) json.optDouble("brightness", -1.0).toFloat() else -1f
                                            withContext(Dispatchers.Main) {
                                                if (v >= 0f) currentVolume = v
                                                if (b >= 0f) currentBrightness = b
                                            }
                                        } catch (je: Exception) {
                                            Log.e(TAG, "Error parsing status json", je)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "fetchStatus error", e)
                            }
                        }
                    }

                    fun handleSelectDevice(device: Pair<String, String>) {
                        val saved = getSavedCredentials()
                        val isSaved = isDeviceSaved()
                        val secIp = SecureStorage.getServerIp(this@MainActivity)
                        val secPort = SecureStorage.getServerPort(this@MainActivity)
                        val secPass = SecureStorage.getPassword(this@MainActivity)
                        val devClean = getCleanTarget(device.second)
                        val secClean = if (secIp.isNotEmpty()) "$secIp:$secPort" else ""

                        val isMatchingSaved = (saved != null && isSaved && getCleanTarget(saved.second) == devClean && saved.third.isNotEmpty()) ||
                                              (secClean.isNotEmpty() && secClean == devClean && secPass.isNotEmpty())
                        val resolvedPass = if (secPass.isNotEmpty()) secPass else (saved?.third ?: "")

                        if (isMatchingSaved && resolvedPass.isNotEmpty()) {
                            // Prompt Phone Lock (GPay style)
                            promptPhoneLock(
                                title = "Access ${device.first}",
                                onSuccess = {
                                    sessionPassword = resolvedPass
                                    selectedDevice = device
                                    fetchStatus(device.second, resolvedPass)
                                    currentScreen = "remote"
                                    RemoteBackgroundService.start(this@MainActivity, device.first, device.second)
                                },
                                onFallback = {
                                    deviceToConnect = device
                                    passwordInput = resolvedPass
                                    saveDeviceChecked = true
                                    showPasswordDialog = true
                                }
                            )
                        } else {
                            deviceToConnect = device
                            passwordInput = ""
                            saveDeviceChecked = true
                            showPasswordDialog = true
                        }
                    }

                    fun verifyAndConnect(device: Pair<String, String>, pass: String, saveDevice: Boolean) {
                        scope.launch(Dispatchers.IO) {
                            val cleanTarget = getCleanTarget(device.second)
                            val devName = SecureStorage.getDeviceName(this@MainActivity)
                            val devId = SecureStorage.getDeviceId(this@MainActivity)
                            val encName = URLEncoder.encode(devName, "UTF-8")
                            val encId = URLEncoder.encode(devId, "UTF-8")

                            var tokenObtained = ""
                            try {
                                val regUrl = "http://$cleanTarget/pair/register-device?name=$encName&device_id=$encId"
                                val regReq = Request.Builder()
                                    .url(regUrl)
                                    .post(RequestBody.create(null, ByteArray(0)))
                                    .addHeader("X-Remote-Password", pass)
                                    .build()
                                client.newCall(regReq).execute().use { regResp ->
                                    if (regResp.isSuccessful) {
                                        val regJson = JSONObject(regResp.body?.string() ?: "{}")
                                        tokenObtained = regJson.optString("token", "")
                                    }
                                }
                            } catch (_: Exception) {}

                            val statusReqBuilder = Request.Builder()
                                .url("http://$cleanTarget/status")
                                .get()
                                .addHeader("X-Remote-Password", pass)
                            if (tokenObtained.isNotEmpty()) {
                                statusReqBuilder.addHeader("X-Device-Token", tokenObtained)
                            }
                            try {
                                client.newCall(statusReqBuilder.build()).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: ""
                                        var initVol: Float? = null
                                        var initBright: Float? = null
                                        try {
                                            val json = JSONObject(body)
                                            if (json.has("volume") && !json.isNull("volume")) {
                                                initVol = json.optDouble("volume", -1.0).toFloat()
                                            }
                                            if (json.has("brightness") && !json.isNull("brightness")) {
                                                initBright = json.optDouble("brightness", -1.0).toFloat()
                                            }
                                        } catch (je: Exception) {
                                            Log.e(TAG, "verifyAndConnect parse status error", je)
                                        }
                                        withContext(Dispatchers.Main) {
                                            if (initVol != null && initVol >= 0f) currentVolume = initVol
                                            if (initBright != null && initBright >= 0f) currentBrightness = initBright

                                            if (tokenObtained.isNotEmpty()) {
                                                SecureStorage.saveDeviceToken(this@MainActivity, tokenObtained)
                                            }
                                            SecureStorage.saveServerTarget(this@MainActivity, device.second, device.first)
                                            SecureStorage.savePassword(this@MainActivity, pass)
                                            SecureStorage.setConfigured(this@MainActivity, true)
                                            saveCredentials(device.first, device.second, pass, saveDevice)

                                            sessionPassword = pass
                                            selectedDevice = device
                                            showPasswordDialog = false
                                            currentScreen = "remote"
                                            RemoteBackgroundService.start(this@MainActivity, device.first, device.second)
                                            Toast.makeText(this@MainActivity, "Connected to ${device.first}", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "Wrong password", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Failed to connect: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }

                    // Navigation Drawer wrapper
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerContainerColor = AppTheme.CardDark,
                                drawerContentColor = AppTheme.TextPrimary
                            ) {
                                Spacer(Modifier.height(16.dp))
                                // Header Hero Card
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(44.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        Brush.linearGradient(
                                                            listOf(AppTheme.AccentCyan.copy(alpha = 0.25f), AppTheme.AccentSky.copy(alpha = 0.1f))
                                                        )
                                                    )
                                                    .border(1.dp, AppTheme.AccentCyan.copy(alpha = 0.5f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Rounded.Computer, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(26.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column {
                                                Text("Remote PC", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = AppTheme.TextPrimary)
                                                Text("Wireless Control Suite", fontSize = 11.sp, color = AppTheme.TextSecondary)
                                            }
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        if (selectedDevice != null) {
                                            LiveStatusBadge(text = "CONNECTED: ${selectedDevice!!.first.uppercase()}", color = AppTheme.AccentEmerald)
                                        } else {
                                            LiveStatusBadge(text = "STANDBY • NO PC CONNECTED", color = AppTheme.AccentAmber)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                if (selectedDevice == null) {
                                    // DISCONNECTED MODE: Only show Server Finding and Settings
                                    NavigationDrawerItem(
                                        label = { Text("Server Finding", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Search, null, tint = if (currentScreen == "discovery") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "discovery",
                                        onClick = { currentScreen = "discovery"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )

                                    NavigationDrawerItem(
                                        label = { Text("Settings", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Settings, null, tint = if (currentScreen == "settings") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "settings",
                                        onClick = { currentScreen = "settings"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                } else {
                                    // CONNECTED MODE
                                    NavigationDrawerItem(
                                        label = { Text("Server Finding", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Search, null, tint = if (currentScreen == "discovery") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "discovery",
                                        onClick = { currentScreen = "discovery"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "CONTROLS",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.TextMuted,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                    )

                                    NavigationDrawerItem(
                                        label = { Text("Remote Control", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Home, null, tint = if (currentScreen == "remote") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "remote",
                                        onClick = { currentScreen = "remote"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Media Player", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.PlayCircle, null, tint = if (currentScreen == "media") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "media",
                                        onClick = { currentScreen = "media"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("🖥️ Remote Desktop", fontWeight = FontWeight.Bold) },
                                        icon = { Icon(Icons.Rounded.Monitor, null, tint = if (currentScreen == "remote_desktop") Color(0xFF818CF8) else AppTheme.AccentCyan) },
                                        selected = currentScreen == "remote_desktop",
                                        onClick = { currentScreen = "remote_desktop"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = Color(0xFF6366F1).copy(alpha = 0.2f),
                                            selectedTextColor = Color(0xFF818CF8)
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Touchpad Trackpad", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.TouchApp, null, tint = if (currentScreen == "touchpad") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "touchpad",
                                        onClick = { currentScreen = "touchpad"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("PC Keyboard", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Keyboard, null, tint = if (currentScreen == "keyboard") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "keyboard",
                                        onClick = { currentScreen = "keyboard"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "FILE TRANSFERS",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.TextMuted,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                    )

                                    NavigationDrawerItem(
                                        label = { Text("PC File Explorer", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.FolderCopy, null, tint = AppTheme.AccentCyan) },
                                        selected = currentScreen == "pc_files" || currentScreen == "pc_videos",
                                        onClick = { currentScreen = "pc_files"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Phone Files (Send to PC)", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.DriveFolderUpload, null, tint = AppTheme.AccentAmber) },
                                        selected = currentScreen == "phone_files" || currentScreen == "phone_videos",
                                        onClick = { currentScreen = "phone_files"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentAmber.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentAmber
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "PREFERENCES",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.TextMuted,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                                    )

                                    NavigationDrawerItem(
                                        label = { Text("Settings", fontWeight = FontWeight.Medium) },
                                        icon = { Icon(Icons.Rounded.Settings, null, tint = if (currentScreen == "settings") AppTheme.AccentCyan else AppTheme.TextSecondary) },
                                        selected = currentScreen == "settings",
                                        onClick = { currentScreen = "settings"; scope.launch { drawerState.close() } },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = AppTheme.AccentCyan.copy(alpha = 0.15f),
                                            selectedTextColor = AppTheme.AccentCyan
                                        ),
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )

                                    Spacer(Modifier.weight(1f))
                                    NavigationDrawerItem(
                                        label = { Text("Disconnect Session", color = AppTheme.AccentRose, fontWeight = FontWeight.SemiBold) },
                                        icon = { Icon(Icons.Rounded.PowerSettingsNew, null, tint = AppTheme.AccentRose) },
                                        selected = false,
                                        onClick = {
                                            showDisconnectConfirm = true
                                            scope.launch { drawerState.close() }
                                        },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    ) {
                        BackHandler(enabled = drawerState.isOpen) {
                            scope.launch { drawerState.close() }
                        }

                        BackHandler(enabled = !drawerState.isOpen && currentScreen == "settings") {
                            currentScreen = if (selectedDevice != null) "remote" else "discovery"
                        }

                        BackHandler(enabled = !drawerState.isOpen && currentScreen in listOf("touchpad", "keyboard", "media", "pc_files", "phone_files", "remote_desktop")) {
                            currentScreen = "remote"
                        }

                        BackHandler(enabled = !drawerState.isOpen && currentScreen == "remote") {
                            currentScreen = "discovery"
                        }

                        // Enforce disconnected view restriction
                        if (selectedDevice == null && currentScreen !in listOf("discovery", "settings", "setup", "splash")) {
                            currentScreen = "discovery"
                        }

                        Crossfade(targetState = currentScreen, label = "ScreenFade") { screen ->
                            when (screen) {
                                "splash" -> {
                                    SplashScreen(onFinish = {
                                        var isConfig = SecureStorage.isConfigured(this@MainActivity)
                                        if (!isConfig) {
                                            val saved = getSavedCredentials()
                                            if (saved != null && saved.second.isNotEmpty()) {
                                                SecureStorage.saveServerTarget(this@MainActivity, saved.second, saved.first)
                                                SecureStorage.savePassword(this@MainActivity, saved.third)
                                                SecureStorage.setConfigured(this@MainActivity, true)
                                                isConfig = true
                                            }
                                        }

                                        if (!isConfig) {
                                            currentScreen = "setup"
                                            return@SplashScreen
                                        }

                                        val autoConnectEnabled = SecureStorage.isAutoConnectEnabled(this@MainActivity)
                                        val secIp = SecureStorage.getServerIp(this@MainActivity)
                                        val secPort = SecureStorage.getServerPort(this@MainActivity)
                                        val secName = SecureStorage.getServerName(this@MainActivity)
                                        val secPass = SecureStorage.getPassword(this@MainActivity)
                                        val autoConnectTarget = if (secIp.isNotEmpty()) "$secIp:$secPort" else ""

                                        if (autoConnectEnabled && autoConnectTarget.isNotEmpty()) {
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                val isAlive = isServerAlive(autoConnectTarget)
                                                withContext(Dispatchers.Main) {
                                                    if (isAlive) {
                                                        sessionPassword = secPass
                                                        selectedDevice = Pair(secName, autoConnectTarget)
                                                        fetchStatus(autoConnectTarget, secPass)
                                                        currentScreen = "remote"
                                                        RemoteBackgroundService.start(this@MainActivity, secName, autoConnectTarget)
                                                        Toast.makeText(this@MainActivity, "⚡ Auto-connected to $secName", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(this@MainActivity, "Auto-connect target '$secName' is offline. Select a device.", Toast.LENGTH_SHORT).show()
                                                        currentScreen = "discovery"
                                                    }
                                                }
                                            }
                                        } else {
                                            currentScreen = "discovery"
                                        }
                                    })
                                }
                                "setup" -> {
                                    SetupScreen(
                                        onSetupComplete = { devName, pass ->
                                            currentScreen = "discovery"
                                        }
                                    )
                                }
                                "settings" -> {
                                    SettingsScreen(
                                        onBack = {
                                            currentScreen = if (selectedDevice != null) "remote" else "discovery"
                                        },
                                        onNavigateDiscovery = {
                                            currentScreen = "discovery"
                                        },
                                        onRequestBatteryOptimization = {
                                            requestIgnoreBatteryOptimization()
                                        },
                                        onResetSetup = {
                                            selectedDevice = null
                                            currentScreen = "setup"
                                        }
                                    )
                                }
                                "discovery" -> {
                                    LaunchedEffect(Unit) {
                                        discoverAllServers { discoveredDevices = it }
                                    }
                                    DiscoveryScreen(
                                        devices = discoveredDevices,
                                        onSelect = { handleSelectDevice(it) },
                                        onRefresh = {
                                            discoveredDevices = emptyList()
                                            discoverAllServers { discoveredDevices = it }
                                        },
                                        onScanQrClick = { showQrScanner = true },
                                        onMenuClick = { scope.launch { drawerState.open() } },
                                        onSettingsClick = { currentScreen = "settings" }
                                    )
                                }
                                "remote" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                    val autoTarget = prefs.getString(KEY_AUTO_CONNECT_TARGET, "") ?: ""
                                    val devHost = dev.second.split(":")[0]
                                    val autoHost = if (autoTarget.contains(":")) autoTarget.split(":")[0] else autoTarget
                                    val isAutoConnected = prefs.getBoolean(KEY_AUTO_CONNECT_ENABLED, false) && (autoHost.isNotEmpty() && autoHost == devHost)
                                    RemoteControlScreen(
                                        deviceName = dev.first,
                                        ipAddress = dev.second,
                                        password = sessionPassword,
                                        volume = currentVolume,
                                        brightness = currentBrightness,
                                        isAutoConnected = isAutoConnected,
                                        onSetupAutoConnect = {
                                            if (isAutoConnected) {
                                                prefs.edit().putBoolean(KEY_AUTO_CONNECT_ENABLED, false).apply()
                                                Toast.makeText(this@MainActivity, "Auto-Connect disabled for this device", Toast.LENGTH_SHORT).show()
                                            } else {
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val hostOnly = if (dev.second.contains(":")) dev.second.substringBefore(":") else dev.second
                                                        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: Build.MODEL
                                                        val encodedName = URLEncoder.encode(Build.MODEL, "UTF-8")
                                                        val encodedId = URLEncoder.encode(deviceId, "UTF-8")
                                                        val url = "http://$hostOnly:5055/pair/request?name=$encodedName&device_id=$encodedId"
                                                        val req = Request.Builder().url(url).post(RequestBody.create(null, ByteArray(0))).build()
                                                        client.newCall(req).execute().close()
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(this@MainActivity, "Auto-Connect request sent to PC! Please tap Accept on PC.", Toast.LENGTH_LONG).show()
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        onVolumeChange = { v ->
                                            currentVolume = v
                                            sendUdpSlider("vol", v.toInt(), dev.second)
                                        },
                                        onBrightnessChange = { b ->
                                            currentBrightness = b
                                            sendUdpSlider("bright", b.toInt(), dev.second)
                                        },
                                        onVolumeChangeFinished = {
                                            sendUdpSlider("vol", currentVolume.toInt(), dev.second, force = true)
                                        },
                                        onBrightnessChangeFinished = {
                                            sendUdpSlider("bright", currentBrightness.toInt(), dev.second, force = true)
                                        },
                                        onRefreshStatus = { fetchStatus(dev.second, sessionPassword) },
                                        onCommand = { cmd -> sendCommand(cmd, dev.second, sessionPassword) },
                                        onNavigateRemoteDesktop = { currentScreen = "remote_desktop" },
                                        onBack = { currentScreen = "discovery" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "media" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    MediaControlScreen(
                                        deviceName = dev.first,
                                        ipAddress = dev.second,
                                        password = sessionPassword,
                                        volume = currentVolume,
                                        onVolumeChange = { v ->
                                            currentVolume = v
                                            sendUdpSlider("vol", v.toInt(), dev.second)
                                        },
                                        onVolumeChangeFinished = {
                                            sendUdpSlider("vol", currentVolume.toInt(), dev.second, force = true)
                                        },
                                        onRefreshStatus = { fetchStatus(dev.second, sessionPassword) },
                                        onCommand = { cmd -> sendCommand(cmd, dev.second, sessionPassword) },
                                        onBack = { currentScreen = "remote" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "touchpad" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    TouchpadScreen(
                                        deviceName = dev.first,
                                        ipAddress = dev.second,
                                        password = sessionPassword,
                                        onUdpMove = { dx, dy -> sendUdpMove(dx, dy, dev.second) },
                                        onMouseAction = { path, q -> sendMouseAction(path, q, dev.second, sessionPassword) },
                                        onBack = { currentScreen = "remote" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "keyboard" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    KeyboardScreen(
                                        deviceName = dev.first,
                                        onKey = { key -> sendUdpKey(key, dev.second) },
                                        onBack = { currentScreen = "remote" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "pc_videos", "pc_files" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    PcFileManagerScreen(
                                        targetIp = dev.second,
                                        password = sessionPassword,
                                        onBack = { currentScreen = "remote" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "phone_videos", "phone_files" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    PhoneFileManagerScreen(
                                        targetIp = dev.second,
                                        password = sessionPassword,
                                        onBack = { currentScreen = "remote" },
                                        onMenuClick = { scope.launch { drawerState.open() } }
                                    )
                                }
                                "remote_desktop" -> {
                                    val dev = selectedDevice ?: Pair("PC", "127.0.0.1:5055")
                                    val host = if (dev.second.contains(":")) dev.second.substringBefore(":") else dev.second
                                    RemoteDesktopScreen(
                                        targetIp = host,
                                        password = sessionPassword,
                                        onBack = { currentScreen = "remote" }
                                    )
                                }
                            }
                        }

                        if (showDisconnectConfirm) {
                            LiquidConfirmDialog(
                                title = "Disconnect from PC",
                                message = "Are you sure you want to end your remote session with ${selectedDevice?.first ?: "PC"}?",
                                icon = Icons.Rounded.PowerSettingsNew,
                                iconColor = Color(0xFFEF4444),
                                confirmText = "Disconnect",
                                confirmColor = Color(0xFFEF4444),
                                onConfirm = {
                                    RemoteBackgroundService.stop(this@MainActivity)
                                    selectedDevice = null
                                    currentScreen = "discovery"
                                    showDisconnectConfirm = false
                                },
                                onDismiss = { showDisconnectConfirm = false }
                            )
                        }

                        if (showPasswordDialog && deviceToConnect != null) {
                            LiquidPasswordDialog(
                                deviceName = deviceToConnect!!.first,
                                password = passwordInput,
                                onPasswordChange = { passwordInput = it },
                                isVisible = passwordVisible,
                                onVisibilityToggle = { passwordVisible = !passwordVisible },
                                saveDevice = saveDeviceChecked,
                                onSaveDeviceChange = { saveDeviceChecked = it },
                                onDismiss = { showPasswordDialog = false },
                                onConfirm = {
                                    verifyAndConnect(deviceToConnect!!, passwordInput, saveDeviceChecked)
                                }
                            )
                        }

                        if (showStartupUpdateDialog && pendingUpdate != null) {
                            val info = pendingUpdate!!
                            AlertDialog(
                                onDismissRequest = { if (!isDownloadingStartupUpdate) showStartupUpdateDialog = false },
                                containerColor = AppTheme.CardDarkElevated,
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.SystemUpdate, null, tint = AppTheme.AccentCyan, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("New Update Available", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                    }
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Version ${info.latestVersion} is available!", fontWeight = FontWeight.SemiBold, color = AppTheme.AccentCyan, fontSize = 13.sp)
                                        Text(info.notes, color = AppTheme.TextSecondary, fontSize = 12.sp, maxLines = 6)
                                        if (isDownloadingStartupUpdate) {
                                            Spacer(Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { startupUpdateProgress },
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                                color = AppTheme.AccentCyan,
                                                trackColor = AppTheme.CardDark
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "Downloading: ${(startupUpdateProgress * 100).toInt()}%${if (startupUpdateSpeed.isNotEmpty()) " · $startupUpdateSpeed" else ""}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = AppTheme.AccentCyan
                                                )
                                                if (startupUpdateStreams > 1) {
                                                    Text(
                                                        "⚡ ${startupUpdateStreams} Multi-Streams",
                                                        fontSize = 10.sp,
                                                        color = AppTheme.TextSecondary
                                                    )
                                                }
                                            }
                                            if (startupUpdateTotal > 0L) {
                                                Text(
                                                    "${formatFileSize(startupUpdateTransferred)} / ${formatFileSize(startupUpdateTotal)}",
                                                    fontSize = 10.sp,
                                                    color = AppTheme.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            isDownloadingStartupUpdate = true
                                            scope.launch {
                                                AppUpdater.downloadAndInstallApk(
                                                    context = this@MainActivity,
                                                    apkUrl = info.apkUrl,
                                                    onProgress = { p, speed, streams, transferred, total ->
                                                        startupUpdateProgress = p
                                                        startupUpdateSpeed = speed
                                                        startupUpdateStreams = streams
                                                        startupUpdateTransferred = transferred
                                                        startupUpdateTotal = total
                                                    },
                                                    onDownloaded = {
                                                        isDownloadingStartupUpdate = false
                                                        showStartupUpdateDialog = false
                                                        Toast.makeText(this@MainActivity, "Installing update...", Toast.LENGTH_SHORT).show()
                                                    },
                                                    onError = { err ->
                                                        isDownloadingStartupUpdate = false
                                                        Toast.makeText(this@MainActivity, "Update error: $err", Toast.LENGTH_LONG).show()
                                                    }
                                                )
                                            }
                                        },
                                        enabled = !isDownloadingStartupUpdate,
                                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan)
                                    ) {
                                        Text(if (isDownloadingStartupUpdate) "Downloading..." else "Download & Install", color = AppTheme.BackgroundDark, fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    if (!isDownloadingStartupUpdate) {
                                        TextButton(onClick = { showStartupUpdateDialog = false }) {
                                            Text("Later", color = AppTheme.TextSecondary)
                                        }
                                    }
                                }
                            )
                        }

                        val incomingPair = pendingPairRequestState.value
                        if (incomingPair != null) {
                            AlertDialog(
                                onDismissRequest = { pendingPairRequestState.value = null },
                                containerColor = AppTheme.CardDarkElevated,
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(26.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Setup Auto-Connect", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                },
                                text = {
                                    Text(
                                        "PC \"${incomingPair.first}\" (${incomingPair.second}) requested to enable permanent Auto-Connect with this phone.\n\nAllow this PC to automatically connect without confirmation next time?",
                                        color = AppTheme.TextSecondary,
                                        fontSize = 14.sp
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            val sName = incomingPair.first
                                            val sIp = incomingPair.second
                                            pendingPairRequestState.value = null
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val myPhoneIp = getLocalIpTowards(sIp)
                                                    val hostOnly = if (sIp.contains(":")) sIp.substringBefore(":") else sIp
                                                    val url = "http://$hostOnly:5055/pair/approve?ip=$myPhoneIp&auto_connect=true"
                                                    val req = Request.Builder().url(url).post(RequestBody.create(null, ByteArray(0))).build()
                                                    client.newCall(req).execute().close()
                                                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                                    prefs.edit()
                                                        .putBoolean(KEY_DEVICE_SAVED, true)
                                                        .putBoolean(KEY_AUTO_CONNECT_ENABLED, true)
                                                        .putString(KEY_AUTO_CONNECT_TARGET, sIp)
                                                        .putString(KEY_AUTO_CONNECT_NAME, sName)
                                                        .putString(KEY_LAST_IP, sIp)
                                                        .putString(KEY_LAST_NAME, sName)
                                                        .apply()
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "✓ Auto-Connect Enabled with $sName!", Toast.LENGTH_LONG).show()
                                                    }
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@MainActivity, "Approval failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentEmerald)
                                    ) {
                                        Text("Allow & Auto-Connect", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { pendingPairRequestState.value = null }) {
                                        Text("Decline", color = AppTheme.TextMuted)
                                    }
                                }
                            )
                        }

                        if (showQrScanner) {
                            QrScannerDialog(
                                onDismiss = { showQrScanner = false },
                                onQrScanned = { pcName, pcTarget, pin ->
                                    showQrScanner = false
                                    val device = Pair(pcName, pcTarget)
                                    if (pin.isNotEmpty()) {
                                        verifyAndConnect(device, pin, true)
                                    } else {
                                        val savedPass = SecureStorage.getPassword(this@MainActivity)
                                        if (savedPass.isNotEmpty()) {
                                            verifyAndConnect(device, savedPass, true)
                                        } else {
                                            deviceToConnect = device
                                            passwordInput = ""
                                            saveDeviceChecked = true
                                            showPasswordDialog = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        udpSocket?.close()
        if (!RemoteBackgroundService.isRunning) {
            videoServer?.stop()
        }
    }
}

// Note: VideoStreamServer is implemented in VideoStreamServer.kt for full system/device control support

// -------------------------------------------------------------
// COMPOSE UI COMPONENTS & SCREENS
// -------------------------------------------------------------

@Composable
fun SplashScreen(onFinish: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1300)
        onFinish()
    }
    val infiniteTransition = rememberInfiniteTransition(label = "splashPulse")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF132238),
                        AppTheme.BackgroundDark
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Outer glowing ambient ring
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(glowScale)
                        .alpha(glowAlpha)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(AppTheme.AccentCyan, Color.Transparent)
                            )
                        )
                )
                // Center icon emblem
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                            )
                        )
                        .border(1.5.dp, AppTheme.CardBorderGlow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = AppTheme.AccentCyan,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "REMOTE PC",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = AppTheme.TextPrimary,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Ultra-Low Latency Wireless Suite",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppTheme.TextSecondary,
                letterSpacing = 0.5.sp
            )

            Spacer(Modifier.height(36.dp))

            LiveStatusBadge(text = "INITIALIZING", color = AppTheme.AccentCyan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    devices: List<Pair<String, String>>,
    onSelect: (Pair<String, String>) -> Unit,
    onRefresh: () -> Unit,
    onScanQrClick: () -> Unit,
    onMenuClick: () -> Unit,
    onSettingsClick: (() -> Unit)? = null
) {
    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Discovered PCs", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTheme.TextPrimary)
                        Text("Local Wi-Fi Network", fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onScanQrClick) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan QR Code", tint = AppTheme.AccentCyan)
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Scan", tint = AppTheme.AccentSky)
                    }
                    if (onSettingsClick != null) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = AppTheme.TextSecondary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LiquidScanner(isScanning = devices.isEmpty())

            Spacer(Modifier.height(16.dp))

            if (devices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            "Searching for PC on local network...",
                            color = AppTheme.TextSecondary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Make sure Remote PC is open on your computer or scan its QR code",
                            color = AppTheme.TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = onRefresh,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentCyan)
                            ) {
                                Icon(Icons.Rounded.WifiFind, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan Wi-Fi", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = onScanQrClick,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan)
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = AppTheme.BackgroundDark, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Scan QR Code", color = AppTheme.BackgroundDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "AVAILABLE PCS (${devices.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.TextSecondary,
                        letterSpacing = 0.5.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onScanQrClick,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan QR", fontSize = 12.sp, color = AppTheme.AccentCyan, fontWeight = FontWeight.Bold)
                        }
                        TextButton(
                            onClick = onRefresh,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = AppTheme.AccentSky, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan Again", fontSize = 12.sp, color = AppTheme.AccentSky)
                        }
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(devices) { dev ->
                        LiquidDeviceCard(device = dev, onClick = { onSelect(dev) })
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LiquidScanner(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerScale1"
    )
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerAlpha1"
    )
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerScale2"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scannerAlpha2"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(130.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isScanning) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(scale1)
                    .alpha(alpha1)
                    .clip(CircleShape)
                    .border(1.5.dp, AppTheme.AccentCyan, CircleShape)
                    .background(AppTheme.AccentCyan.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(scale2)
                    .alpha(alpha2)
                    .clip(CircleShape)
                    .border(1.5.dp, AppTheme.AccentSky, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                    )
                )
                .border(1.5.dp, AppTheme.CardBorderGlow, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = AppTheme.AccentCyan,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun LiquidDeviceCard(device: Pair<String, String>, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
        border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(AppTheme.AccentCyan.copy(alpha = 0.25f), AppTheme.AccentSky.copy(alpha = 0.08f))
                        )
                    )
                    .border(1.dp, AppTheme.AccentCyan.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Computer,
                    contentDescription = null,
                    tint = AppTheme.AccentCyan,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = device.first,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.TextPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    LiveStatusBadge(text = "READY", color = AppTheme.AccentEmerald)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Available on Local Wi-Fi",
                    fontSize = 12.sp,
                    color = AppTheme.TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = AppTheme.AccentCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    deviceName: String,
    ipAddress: String,
    password: String,
    volume: Float,
    brightness: Float,
    isAutoConnected: Boolean = false,
    onSetupAutoConnect: () -> Unit = {},
    onVolumeChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChangeFinished: (() -> Unit)? = null,
    onBrightnessChangeFinished: (() -> Unit)? = null,
    onRefreshStatus: () -> Unit,
    onCommand: (String) -> Unit,
    onNavigateRemoteDesktop: () -> Unit = {},
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    var pendingSensitiveAction by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    var pendingActionColor by remember { mutableStateOf(AppTheme.AccentRose) }
    var pendingActionIcon by remember { mutableStateOf(Icons.Rounded.WarningAmber) }

    LaunchedEffect(ipAddress) {
        onRefreshStatus()
    }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.TextPrimary)
                        Text("Connected via Wi-Fi", fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshStatus) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Sync", tint = AppTheme.AccentCyan)
                    }
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Hero PC Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderGlow)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(AppTheme.AccentCyan.copy(alpha = 0.15f))
                            .border(1.dp, AppTheme.AccentCyan.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.DesktopWindows, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppTheme.TextPrimary)
                        Spacer(Modifier.height(2.dp))
                        Text("Local Wi-Fi Network", fontSize = 11.sp, color = AppTheme.TextSecondary)
                    }
                    LiveStatusBadge(text = "CONNECTED", color = AppTheme.AccentEmerald)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Hero AnyDesk Remote Desktop Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateRemoteDesktop() },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF6366F1).copy(alpha = 0.18f)
                ),
                border = BorderStroke(1.5.dp, Color(0xFF818CF8).copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF6366F1).copy(alpha = 0.3f))
                            .border(1.dp, Color(0xFF818CF8), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Monitor,
                            contentDescription = null,
                            tint = Color(0xFFA5B4FC),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🖥️ Remote Desktop",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF10B981)
                            ) {
                                Text(
                                    "ANYDESK",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Live Screen Mirror • Direct Touch & Trackpad • 60 FPS",
                            fontSize = 11.sp,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFFA5B4FC),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Two-Way Auto Connect Setup Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSetupAutoConnect() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAutoConnected) AppTheme.AccentEmerald.copy(alpha = 0.15f) else AppTheme.AccentCyan.copy(alpha = 0.12f)
                ),
                border = BorderStroke(1.dp, if (isAutoConnected) AppTheme.AccentEmerald.copy(alpha = 0.5f) else AppTheme.AccentCyan.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isAutoConnected) Icons.Rounded.CheckCircle else Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = if (isAutoConnected) AppTheme.AccentEmerald else AppTheme.AccentCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (isAutoConnected) "✓ Auto-Connect Active" else "⚡ Setup Auto-Connect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = AppTheme.TextPrimary
                        )
                        Text(
                            if (isAutoConnected) "Authorized for seamless connection without prompt" else "Tap to request permanent auto-connect handshake with PC",
                            fontSize = 11.sp,
                            color = AppTheme.TextSecondary
                        )
                    }
                    if (!isAutoConnected) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.AccentCyan
                        ) {
                            Text(
                                "Setup",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.BackgroundDark
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "SYSTEM & WINDOWS")
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidActionTile("Start Menu", Icons.Rounded.Window, AppTheme.AccentCyan, Modifier.weight(1f)) { onCommand("startmenu") }
                LiquidActionTile("Task View", Icons.Rounded.Dashboard, AppTheme.AccentSky, Modifier.weight(1f)) { onCommand("shortcut/taskview") }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidActionTile("Switch Apps", Icons.Rounded.Tab, AppTheme.AccentPurple, Modifier.weight(1f)) { onCommand("shortcut/switch") }
                LiquidActionTile("Show Desktop", Icons.Rounded.DesktopWindows, AppTheme.AccentCyan, Modifier.weight(1f)) { onCommand("desktop") }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "AUDIO & DISPLAY")
            Spacer(Modifier.height(12.dp))

            LiquidSliderCard(
                title = "Volume",
                value = volume,
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                accentColor = AppTheme.AccentCyan,
                onMinusClick = { onVolumeChange((volume - 10f).coerceAtLeast(0f)) },
                onPlusClick = { onVolumeChange((volume + 10f).coerceAtMost(100f)) },
                onValueChange = onVolumeChange,
                onValueChangeFinished = onVolumeChangeFinished
            )

            Spacer(Modifier.height(12.dp))

            LiquidSliderCard(
                title = "Brightness",
                value = brightness,
                icon = Icons.Rounded.LightMode,
                accentColor = AppTheme.AccentAmber,
                onMinusClick = { onBrightnessChange((brightness - 10f).coerceAtLeast(0f)) },
                onPlusClick = { onBrightnessChange((brightness + 10f).coerceAtMost(100f)) },
                onValueChange = onBrightnessChange,
                onValueChangeFinished = onBrightnessChangeFinished
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "SESSION & POWER", accentColor = AppTheme.AccentAmber)
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidActionTile("Turn Off Screen", Icons.Rounded.VisibilityOff, AppTheme.AccentAmber, Modifier.weight(1f)) {
                    pendingSensitiveAction = Triple(
                        "Turn Off Monitor",
                        "Turn off the monitor display for '$deviceName'?",
                        { onCommand("screenoff") }
                    )
                    pendingActionColor = AppTheme.AccentAmber
                    pendingActionIcon = Icons.Rounded.VisibilityOff
                }
                LiquidActionTile("Lock PC", Icons.Rounded.Lock, AppTheme.AccentSky, Modifier.weight(1f)) {
                    pendingSensitiveAction = Triple(
                        "Lock PC",
                        "Lock the Windows user session for '$deviceName'?",
                        { onCommand("lock") }
                    )
                    pendingActionColor = AppTheme.AccentSky
                    pendingActionIcon = Icons.Rounded.Lock
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiquidActionTile("Sleep Mode", Icons.Rounded.Bedtime, AppTheme.AccentAmber, Modifier.weight(1f)) {
                    pendingSensitiveAction = Triple(
                        "Sleep Mode",
                        "Are you sure you want to put '$deviceName' to sleep?",
                        { onCommand("sleep") }
                    )
                    pendingActionColor = AppTheme.AccentAmber
                    pendingActionIcon = Icons.Rounded.Bedtime
                }
                LiquidActionTile("Restart PC", Icons.Rounded.RestartAlt, AppTheme.AccentRose, Modifier.weight(1f)) {
                    pendingSensitiveAction = Triple(
                        "Restart PC",
                        "Are you sure you want to restart '$deviceName'? Your connection will temporarily close.",
                        { onCommand("restart") }
                    )
                    pendingActionColor = AppTheme.AccentRose
                    pendingActionIcon = Icons.Rounded.RestartAlt
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    pendingSensitiveAction = Triple(
                        "Shutdown PC",
                        "Are you sure you want to shut down '$deviceName'? All running programs on the PC will close and unsaved data will be lost.",
                        { onCommand("shutdown") }
                    )
                    pendingActionColor = AppTheme.AccentRose
                    pendingActionIcon = Icons.Rounded.PowerSettingsNew
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentRose),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(10.dp))
                Text("Shutdown PC", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            if (pendingSensitiveAction != null) {
                val (actionTitle, actionMsg, actionFn) = pendingSensitiveAction!!
                LiquidConfirmDialog(
                    title = actionTitle,
                    message = actionMsg,
                    icon = pendingActionIcon,
                    iconColor = pendingActionColor,
                    confirmText = if (actionTitle.contains("Shutdown")) "Shutdown" else if (actionTitle.contains("Restart")) "Restart" else "Confirm",
                    confirmColor = pendingActionColor,
                    onConfirm = {
                        actionFn()
                        pendingSensitiveAction = null
                    },
                    onDismiss = { pendingSensitiveAction = null }
                )
            }
        }
    }
}

@Composable
fun LiquidActionTile(title: String, icon: ImageVector, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .height(86.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
        border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
        }
    }
}

@Composable
fun LiquidSliderCard(
    title: String,
    value: Float,
    icon: ImageVector,
    accentColor: Color,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    displayValue: String? = null,
    onMinusClick: (() -> Unit)? = null,
    onPlusClick: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var localValue by remember { mutableFloatStateOf(value) }

    LaunchedEffect(value) {
        if (!isDragging) {
            localValue = value
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
        border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppTheme.TextPrimary)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    val label = displayValue ?: "${localValue.toInt()}%"
                    Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = accentColor)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onMinusClick != null) {
                    IconButton(
                        onClick = onMinusClick,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(AppTheme.BackgroundSurface)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease", tint = AppTheme.TextPrimary, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Slider(
                    value = localValue.coerceIn(valueRange.start, valueRange.endInclusive),
                    onValueChange = { newVal ->
                        isDragging = true
                        localValue = newVal
                        onValueChange(newVal)
                    },
                    onValueChangeFinished = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    valueRange = valueRange,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = Color(0xFF24334C)
                    )
                )
                if (onPlusClick != null) {
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = onPlusClick,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Increase", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaControlScreen(
    deviceName: String,
    ipAddress: String,
    password: String,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onVolumeChangeFinished: (() -> Unit)? = null,
    onRefreshStatus: () -> Unit,
    onCommand: (String) -> Unit,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    LaunchedEffect(ipAddress) {
        onRefreshStatus()
    }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Media Player", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.TextPrimary)
                        Text(deviceName, fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Visualizer Deck
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(AppTheme.CardDarkElevated, AppTheme.CardDark)
                            )
                        )
                        .border(2.dp, AppTheme.CardBorderGlow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = AppTheme.AccentCyan,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        // Simulated Animated Sound Wave Bars
                        val infiniteTransition = rememberInfiniteTransition(label = "audioBars")
                        val bar1 by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "b1")
                        val bar2 by infiniteTransition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "b2")
                        val bar3 by infiniteTransition.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "b3")
                        val bar4 by infiniteTransition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(480), RepeatMode.Reverse), label = "b4")
                        val bar5 by infiniteTransition.animateFloat(0.3f, 0.8f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b5")

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(bar1, bar2, bar3, bar4, bar5).forEach { scale ->
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(18.dp * scale)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(AppTheme.AccentCyan)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                LiveStatusBadge(text = "WIRELESS PLAYBACK ACTIVE", color = AppTheme.AccentCyan)
            }

            // Main Playback Cluster (Prev, -10s, Play/Pause, +10s, Next)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onCommand("media_prev") },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AppTheme.CardDark)
                        .border(1.dp, AppTheme.CardBorderSubtle, CircleShape)
                ) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Prev", tint = AppTheme.TextPrimary, modifier = Modifier.size(24.dp))
                }

                IconButton(
                    onClick = { onCommand("backward10") },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(AppTheme.CardDarkElevated)
                        .border(1.dp, AppTheme.CardBorderGlow, CircleShape)
                ) {
                    Icon(Icons.Rounded.Replay10, contentDescription = "Back 10s", tint = AppTheme.AccentAmber, modifier = Modifier.size(28.dp))
                }

                // Glowing Play/Pause FAB
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(AppTheme.CyanGradient)
                        .clickable { onCommand("media_play_pause") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = AppTheme.BackgroundDark,
                        modifier = Modifier.size(40.dp)
                    )
                }

                IconButton(
                    onClick = { onCommand("forward10") },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(AppTheme.CardDarkElevated)
                        .border(1.dp, AppTheme.CardBorderGlow, CircleShape)
                ) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10s", tint = AppTheme.AccentCyan, modifier = Modifier.size(28.dp))
                }

                IconButton(
                    onClick = { onCommand("media_next") },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(AppTheme.CardDark)
                        .border(1.dp, AppTheme.CardBorderSubtle, CircleShape)
                ) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = AppTheme.TextPrimary, modifier = Modifier.size(24.dp))
                }
            }

            // Secondary Quick Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onCommand("mute") },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null, tint = AppTheme.AccentAmber)
                    Spacer(Modifier.width(8.dp))
                    Text("Mute", color = AppTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { onCommand("fullscreen") },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = null, tint = AppTheme.AccentCyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Fullscreen", color = AppTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            // Volume Slider Card
            LiquidSliderCard(
                title = "Volume",
                value = volume,
                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                accentColor = AppTheme.AccentCyan,
                onMinusClick = { onVolumeChange((volume - 10f).coerceAtLeast(0f)) },
                onPlusClick = { onVolumeChange((volume + 10f).coerceAtMost(100f)) },
                onValueChange = onVolumeChange,
                onValueChangeFinished = onVolumeChangeFinished
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(
    deviceName: String,
    ipAddress: String,
    password: String,
    onUdpMove: (Float, Float) -> Unit,
    onMouseAction: (String, Map<String, String>) -> Unit,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE) }
    var sensitivity by remember { mutableFloatStateOf(prefs.getFloat("touchpad_sensitivity", 1.8f)) }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Touchpad & Mouse", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.TextPrimary)
                        Text(deviceName, fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
        ) {
            // Top Quick Action Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onMouseAction("/mouse/click", mapOf("button" to "left")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Left Click", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onMouseAction("/mouse/double_click", emptyMap()) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Double Click", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onMouseAction("/mouse/click", mapOf("button" to "right")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Right Click", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Main Trackpad Container with Integrated Scroll Strip
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppTheme.CardDark)
                    .border(1.dp, AppTheme.CardBorderGlow, RoundedCornerShape(20.dp))
            ) {
                // Main Touch Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(sensitivity) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onUdpMove(dragAmount.x * sensitivity, dragAmount.y * sensitivity)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    onMouseAction("/mouse/click", mapOf("button" to "left"))
                                },
                                onDoubleTap = {
                                    onMouseAction("/mouse/double_click", emptyMap())
                                },
                                onLongPress = {
                                    onMouseAction("/mouse/click", mapOf("button" to "right"))
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = AppTheme.TextMuted,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Ultra-Responsive Trackpad Area",
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.TextSecondary,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "• Slide to move mouse\n• Tap for Left Click\n• Long-press for Right Click",
                            color = AppTheme.TextMuted,
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                    }
                }

                // Vertical Scroll Lane Strip
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF10192A))
                        .border(
                            width = 1.dp,
                            color = AppTheme.CardBorderSubtle,
                            shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val scrollDelta = -dragAmount.y / 20f
                                onMouseAction("/mouse/scroll", mapOf("delta" to scrollDelta.toString()))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(20.dp))
                        Text("SCROLL", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentCyan.copy(alpha = 0.7f), letterSpacing = 2.sp)
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Sensitivity Bar
            LiquidSliderCard(
                title = "Sensitivity",
                value = sensitivity,
                icon = Icons.Rounded.Speed,
                accentColor = AppTheme.AccentCyan,
                valueRange = 0.5f..4.0f,
                displayValue = String.format(Locale.US, "%.1fx", sensitivity),
                onMinusClick = {
                    val s = (sensitivity - 0.2f).coerceAtLeast(0.5f)
                    sensitivity = s
                    prefs.edit().putFloat("touchpad_sensitivity", s).apply()
                },
                onPlusClick = {
                    val s = (sensitivity + 0.2f).coerceAtMost(4.0f)
                    sensitivity = s
                    prefs.edit().putFloat("touchpad_sensitivity", s).apply()
                },
                onValueChange = {
                    sensitivity = it
                    prefs.edit().putFloat("touchpad_sensitivity", it).apply()
                }
            )

            Spacer(Modifier.height(10.dp))

            // Bottom Tactile Physical Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onMouseAction("/mouse/click", mapOf("button" to "left")) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                    border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text("LEFT CLICK", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppTheme.TextPrimary)
                }

                Button(
                    onClick = { onMouseAction("/mouse/click", mapOf("button" to "right")) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                    border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Text("RIGHT CLICK", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppTheme.TextPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardScreen(
    deviceName: String,
    onKey: (String) -> Unit,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC Keyboard", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.TextPrimary)
                        Text(deviceName, fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Quick Text Input & Paste Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type text/URL to send to PC...", fontSize = 12.sp, color = AppTheme.TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentCyan,
                        unfocusedBorderColor = AppTheme.CardBorderSubtle,
                        focusedContainerColor = AppTheme.CardDark,
                        unfocusedContainerColor = AppTheme.CardDark,
                        focusedTextColor = AppTheme.TextPrimary,
                        unfocusedTextColor = AppTheme.TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        if (textInput.isNotEmpty()) {
                            onKey("text:$textInput")
                            textInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = "Send", tint = AppTheme.BackgroundDark, modifier = Modifier.size(18.dp))
                }
            }

            // Top Function Keys
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    KeyBtn("ESC", Modifier.weight(1f), accent = true) { onKey("key:esc") }
                    KeyBtn("TAB", Modifier.weight(1f), accent = true) { onKey("key:tab") }
                    KeyBtn("WIN", Modifier.weight(1f), accent = true) { onKey("key:win") }
                    KeyBtn("ALT", Modifier.weight(1f), accent = true) { onKey("key:alt") }
                    KeyBtn("CTRL", Modifier.weight(1f), accent = true) { onKey("key:ctrl") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    KeyBtn("F5", Modifier.weight(1f)) { onKey("key:f5") }
                    KeyBtn("F11", Modifier.weight(1f)) { onKey("key:f11") }
                    KeyBtn("HOME", Modifier.weight(1f)) { onKey("key:home") }
                    KeyBtn("END", Modifier.weight(1f)) { onKey("key:end") }
                    KeyBtn("DEL", Modifier.weight(1f), accent = true) { onKey("key:delete") }
                }
            }

            // QWERTY & Navigation Deck
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val r1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in r1) {
                        KeyBtn(k, Modifier.weight(1f)) { onKey("text:${k.lowercase()}") }
                    }
                }

                val r2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in r2) {
                        KeyBtn(k, Modifier.weight(1f)) { onKey("text:${k.lowercase()}") }
                    }
                }

                val r3 = listOf("Z", "X", "C", "V", "B", "N", "M")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyBtn("SHIFT", Modifier.weight(1.4f), accent = true) { onKey("key:shift") }
                    for (k in r3) {
                        KeyBtn(k, Modifier.weight(1f)) { onKey("text:${k.lowercase()}") }
                    }
                    KeyBtn("⌫", Modifier.weight(1.4f), accent = true) { onKey("key:backspace") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyBtn("SPACE", Modifier.weight(3f)) { onKey("key:space") }
                    KeyBtn("ENTER", Modifier.weight(2f), accent = true) { onKey("key:enter") }
                }

                // Arrow Key Pad
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    KeyBtn("▲", Modifier.width(68.dp), accent = true) { onKey("key:up") }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    KeyBtn("◀", Modifier.width(68.dp), accent = true) { onKey("key:left") }
                    KeyBtn("▼", Modifier.width(68.dp), accent = true) { onKey("key:down") }
                    KeyBtn("▶", Modifier.width(68.dp), accent = true) { onKey("key:right") }
                }
            }
        }
    }
}

@Composable
fun KeyBtn(label: String, modifier: Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) AppTheme.KeyBackgroundAccent else AppTheme.KeyBackground
        ),
        border = BorderStroke(1.dp, if (accent) AppTheme.AccentCyan.copy(alpha = 0.5f) else AppTheme.KeyBorder),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = modifier.height(46.dp)
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (accent) AppTheme.AccentCyan else AppTheme.TextPrimary
        )
    }
}

// -------------------------------------------------------------
// -------------------------------------------------------------
// -------------------------------------------------------------
// PC FILE MANAGER SCREEN (Browse, Search, View, Save to Phone, Stream)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcFileManagerScreen(
    targetIp: String,
    password: String,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var currentPath by remember { mutableStateOf("") }
    var parentPath by remember { mutableStateOf<String?>(null) }
    var drives by remember { mutableStateOf<List<String>>(emptyList()) }
    var shortcuts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var items by remember { mutableStateOf<List<FsItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var downloadingFile by remember { mutableStateOf<String?>(null) }
    var pendingPcOpenItem by remember { mutableStateOf<FsItem?>(null) }

    // Previews & In-App Viewers
    var previewImageItem by remember { mutableStateOf<FsItem?>(null) }
    var previewImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isImageLoading by remember { mutableStateOf(false) }

    var previewTextItem by remember { mutableStateOf<FsItem?>(null) }
    var previewTextContent by remember { mutableStateOf("") }
    var isTextLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val okHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val effectivePass = if (password.isNotEmpty()) password else SecureStorage.getPassword(context)
    val token = SecureStorage.getDeviceToken(context)
    val tokenParam = if (token.isNotEmpty()) "&token=${URLEncoder.encode(token, "UTF-8")}" else ""

    fun Request.Builder.withAuthHeaders(): Request.Builder {
        addHeader("X-Remote-Password", effectivePass)
        if (token.isNotEmpty()) {
            addHeader("X-Device-Token", token)
        }
        return this
    }

    val pathCache = remember { mutableMapOf<String, Pair<String?, List<FsItem>>>() }

    fun loadDrives() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val req = Request.Builder()
                    .url("http://$clean/fs/drives")
                    .get()
                    .withAuthHeaders()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val drivesArr = json.getJSONArray("drives")
                        val dList = mutableListOf<String>()
                        for (i in 0 until drivesArr.length()) {
                            dList.add(drivesArr.getString(i))
                        }
                        val scArr = json.getJSONArray("shortcuts")
                        val scList = mutableListOf<Pair<String, String>>()
                        for (i in 0 until scArr.length()) {
                            val obj = scArr.getJSONObject(i)
                            scList.add(Pair(obj.getString("name"), obj.getString("path")))
                        }
                        withContext(Dispatchers.Main) {
                            drives = dList
                            shortcuts = scList
                            currentPath = ""
                            parentPath = null
                            items = emptyList()
                            isLoading = false
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Error fetching drives: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadPath(path: String, forceRefresh: Boolean = false) {
        val cached = pathCache[path]
        if (cached != null) {
            currentPath = path
            parentPath = cached.first
            items = cached.second
            isLoading = false
            if (!forceRefresh) return
        } else {
            isLoading = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedPath = URLEncoder.encode(path, "UTF-8")
                val req = Request.Builder()
                    .url("http://$clean/fs/list?path=$encodedPath")
                    .get()
                    .withAuthHeaders()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val itemsArr = json.getJSONArray("items")
                        val fList = ArrayList<FsItem>(itemsArr.length())
                        for (i in 0 until itemsArr.length()) {
                            val obj = itemsArr.getJSONObject(i)
                            fList.add(
                                FsItem(
                                    name = obj.getString("name"),
                                    path = obj.getString("path"),
                                    isDir = obj.getBoolean("is_dir"),
                                    size = obj.optLong("size", 0L),
                                    ext = obj.optString("ext", ""),
                                    fileType = obj.optString("file_type", "file"),
                                    isVideo = obj.optBoolean("is_video", false),
                                    isAudio = obj.optBoolean("is_audio", false),
                                    isImage = obj.optBoolean("is_image", false)
                                )
                            )
                        }
                        val newParent = json.optString("parent_path", null).takeIf { it != "null" && it.isNotEmpty() }
                        pathCache[path] = Pair(newParent, fList)
                        withContext(Dispatchers.Main) {
                            currentPath = path
                            parentPath = newParent
                            items = fList
                            isLoading = false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isLoading = false
                            Toast.makeText(context, "Cannot open folder", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Error loading path: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Intercept back button and edge gestures
    BackHandler {
        if (previewImageItem != null) {
            previewImageItem = null
            previewImageBitmap = null
        } else if (previewTextItem != null) {
            previewTextItem = null
            previewTextContent = ""
        } else if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else if (currentPath.isNotEmpty()) {
            if (parentPath != null && parentPath!!.isNotEmpty()) {
                loadPath(parentPath!!)
            } else {
                loadDrives()
            }
        } else {
            onBack()
        }
    }

    fun downloadToPhone(item: FsItem) {
        downloadingFile = item.name
        Toast.makeText(context, "Saving '${item.name}' to Phone Downloads...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                val encodedPass = URLEncoder.encode(password, "UTF-8")
                val req = Request.Builder()
                    .url("http://$clean/fs/download?path=$encodedPath&password=$encodedPass")
                    .addHeader("X-Remote-Password", password)
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty response")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                body.byteStream().copyTo(out)
                            }
                        } else {
                            throw IOException("Could not create MediaStore entry")
                        }
                    } else {
                        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        dir.mkdirs()
                        val target = File(dir, item.name)
                        body.byteStream().use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        downloadingFile = null
                        Toast.makeText(context, "Saved to Downloads: ${item.name}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadingFile = null
                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openWithExternalApp(item: FsItem) {
        Toast.makeText(context, "Opening '${item.name}' on mobile...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                val encodedPass = URLEncoder.encode(effectivePass, "UTF-8")
                val req = Request.Builder()
                    .url("http://$clean/fs/download?path=$encodedPath&password=$encodedPass$tokenParam")
                    .withAuthHeaders()
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body")

                    val cacheDir = File(context.cacheDir, "pc_files")
                    cacheDir.mkdirs()
                    val targetFile = File(cacheDir, item.name)
                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        targetFile
                    )
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext.lowercase()) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    withContext(Dispatchers.Main) {
                        try {
                            context.startActivity(Intent.createChooser(intent, "Open with"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openOnPc(item: FsItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                val req = Request.Builder()
                    .url("http://$clean/fs/open-on-pc?path=$encodedPath")
                    .post(RequestBody.create(null, ByteArray(0)))
                    .withAuthHeaders()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            Toast.makeText(context, "Opened on PC: ${item.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Could not open on PC", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun streamMedia(item: FsItem) {
        val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
        val encodedPath = URLEncoder.encode(item.path, "UTF-8")
        val encodedPass = URLEncoder.encode(effectivePass, "UTF-8")
        val streamUrl = "http://$clean/fs/stream?path=$encodedPath&password=$encodedPass$tokenParam"

        val mime = if (item.isVideo) "video/*" else if (item.isAudio) "audio/*" else "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), mime)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Stream to Phone"))
        } catch (e: Exception) {
            Toast.makeText(context, "No media player found", Toast.LENGTH_SHORT).show()
        }
    }

    fun viewFileOnMobile(item: FsItem) {
        val isVideo = item.isVideo || item.fileType == "video"
        val isAudio = item.isAudio || item.fileType == "audio"
        val isImage = item.isImage || item.fileType == "image"

        val textExtensions = setOf(
            "txt", "log", "md", "py", "java", "kt", "json", "xml", "html",
            "css", "js", "ts", "jsx", "tsx", "csv", "sh", "bat", "cmd", "ps1",
            "sql", "c", "cpp", "h", "cs", "dart", "go", "rs", "yaml", "yml",
            "ini", "cfg", "toml", "env", "properties", "gradle"
        )

        when {
            item.isDir -> loadPath(item.path)
            isVideo || isAudio -> streamMedia(item)
            isImage -> {
                previewImageItem = item
                previewImageBitmap = null
                isImageLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                        val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                        val encodedPass = URLEncoder.encode(effectivePass, "UTF-8")
                        val req = Request.Builder()
                            .url("http://$clean/fs/download?path=$encodedPath&password=$encodedPass$tokenParam")
                            .withAuthHeaders()
                            .get()
                            .build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                resp.body?.byteStream()?.use { input ->
                                    val bmp = BitmapFactory.decodeStream(input)
                                    withContext(Dispatchers.Main) {
                                        previewImageBitmap = bmp?.asImageBitmap()
                                        isImageLoading = false
                                    }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isImageLoading = false
                                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isImageLoading = false
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            item.ext.lowercase() in textExtensions -> {
                previewTextItem = item
                previewTextContent = ""
                isTextLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                        val encodedPath = URLEncoder.encode(item.path, "UTF-8")
                        val req = Request.Builder()
                            .url("http://$clean/fs/read-text?path=$encodedPath")
                            .withAuthHeaders()
                            .get()
                            .build()
                        okHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bodyStr = resp.body?.string() ?: ""
                                val json = JSONObject(bodyStr)
                                val text = json.optString("content", "")
                                withContext(Dispatchers.Main) {
                                    previewTextContent = text
                                    isTextLoading = false
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isTextLoading = false
                                    Toast.makeText(context, "Failed to read text", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isTextLoading = false
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            else -> {
                // PDF or other documents: open with native/external viewer app via FileProvider
                openWithExternalApp(item)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDrives()
    }

    val filteredItems = remember(items, searchQuery, selectedCategory) {
        items.filter { item ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                item.name.contains(searchQuery, ignoreCase = true) || item.ext.contains(searchQuery, ignoreCase = true)
            }
            val matchesCat = when (selectedCategory) {
                "all" -> true
                "folder" -> item.isDir
                "video" -> item.isVideo || item.fileType == "video"
                "audio" -> item.isAudio || item.fileType == "audio"
                "image" -> item.isImage || item.fileType == "image"
                "document" -> item.fileType == "document"
                else -> true
            }
            matchesSearch && matchesCat
        }
    }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC File Manager", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.TextPrimary)
                        Text(
                            if (currentPath.isEmpty()) "Browse PC Drives & Folders" else currentPath,
                            fontSize = 11.sp,
                            color = AppTheme.AccentSky,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    if (currentPath.isNotEmpty()) {
                        IconButton(onClick = { loadDrives() }) {
                            Icon(Icons.Rounded.Home, contentDescription = "Drives", tint = AppTheme.AccentCyan)
                        }
                    }
                    IconButton(onClick = {
                        if (previewImageItem != null) {
                            previewImageItem = null
                            previewImageBitmap = null
                        } else if (previewTextItem != null) {
                            previewTextItem = null
                            previewTextContent = ""
                        } else if (searchQuery.isNotEmpty()) {
                            searchQuery = ""
                        } else if (currentPath.isNotEmpty()) {
                            if (parentPath != null && parentPath!!.isNotEmpty()) loadPath(parentPath!!) else loadDrives()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search files or extensions...", color = AppTheme.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = AppTheme.AccentCyan)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = AppTheme.TextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppTheme.AccentCyan,
                    unfocusedBorderColor = AppTheme.CardBorderSubtle,
                    focusedContainerColor = AppTheme.CardDarkElevated,
                    unfocusedContainerColor = AppTheme.CardDarkElevated,
                    focusedTextColor = AppTheme.TextPrimary,
                    unfocusedTextColor = AppTheme.TextPrimary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Category Filter Row (when inside a folder)
            if (currentPath.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val categories = listOf(
                        "all" to "All",
                        "folder" to "Folders",
                        "video" to "Videos",
                        "audio" to "Music",
                        "image" to "Photos",
                        "document" to "Docs"
                    )
                    categories.forEach { (catId, label) ->
                        val isSelected = selectedCategory == catId
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = catId },
                            label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppTheme.AccentCyan,
                                selectedLabelColor = AppTheme.BackgroundDark,
                                containerColor = AppTheme.CardDarkElevated,
                                labelColor = AppTheme.TextSecondary
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentCyan)
                }
            } else if (currentPath.isEmpty()) {
                // Drives & Shortcuts View
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text("QUICK ACCESS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextMuted, letterSpacing = 1.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(shortcuts, key = { "sc_${it.second}" }) { sc ->
                        val icon = when (sc.first.lowercase()) {
                            "videos" -> Icons.Rounded.VideoLibrary
                            "downloads" -> Icons.Rounded.Download
                            "desktop" -> Icons.Rounded.Computer
                            "documents" -> Icons.Rounded.Description
                            "pictures" -> Icons.Rounded.Image
                            "music" -> Icons.Rounded.LibraryMusic
                            else -> Icons.Rounded.FolderSpecial
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { loadPath(sc.second) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
                            border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.AccentAmber.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(icon, contentDescription = null, tint = AppTheme.AccentAmber, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sc.first, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary, fontSize = 14.sp)
                                    Text(sc.second, fontSize = 11.sp, color = AppTheme.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppTheme.TextMuted)
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("PC STORAGE DRIVES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextMuted, letterSpacing = 1.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    items(drives, key = { "drv_$it" }) { drv ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { loadPath(drv) },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
                            border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(AppTheme.AccentCyan.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Storage, contentDescription = null, tint = AppTheme.AccentCyan, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Drive $drv", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 14.sp)
                                    Text("Local Hard Disk Partition", fontSize = 11.sp, color = AppTheme.TextMuted)
                                }
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = AppTheme.TextMuted)
                            }
                        }
                    }
                }
            } else {
                // Folder Items List View
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (filteredItems.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (searchQuery.isNotEmpty()) "No items matching '$searchQuery'" else "Folder is empty",
                                    color = AppTheme.TextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    items(filteredItems, key = { it.path }) { item ->
                        val isVideo = item.isVideo || item.fileType == "video"
                        val isAudio = item.isAudio || item.fileType == "audio"
                        val isImage = item.isImage || item.fileType == "image"
                        val isDoc = item.fileType == "document"
                        val isArchive = item.fileType == "archive"

                        val (tintColor, iconVector) = when {
                            item.isDir -> Pair(AppTheme.AccentAmber, Icons.Rounded.Folder)
                            isVideo -> Pair(AppTheme.AccentCyan, Icons.Rounded.VideoFile)
                            isAudio -> Pair(AppTheme.AccentEmerald, Icons.Rounded.AudioFile)
                            isImage -> Pair(Color(0xFFEC4899), Icons.Rounded.Image)
                            isDoc -> Pair(AppTheme.AccentSky, Icons.Rounded.Description)
                            isArchive -> Pair(Color(0xFFFBBF24), Icons.Rounded.Archive)
                            else -> Pair(AppTheme.TextSecondary, Icons.Rounded.InsertDriveFile)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    viewFileOnMobile(item)
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
                            border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(iconVector, contentDescription = null, tint = tintColor, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.isDir) {
                                        Text(
                                            "${formatFileSize(item.size)} • ${if (item.ext.isNotEmpty()) item.ext.uppercase() else "FILE"}",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    } else {
                                        Text("Folder", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                }

                                if (!item.isDir) {
                                    // View on Mobile Button
                                    IconButton(
                                        onClick = { viewFileOnMobile(item) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Visibility,
                                            contentDescription = "View on Mobile",
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    // Save to phone (download)
                                    IconButton(
                                        onClick = { downloadToPhone(item) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Download,
                                            contentDescription = "Save to Phone",
                                            tint = if (downloadingFile == item.name) Color(0xFFF59E0B) else Color(0xFF10B981),
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }

                                    // Open on PC button (with confirmation)
                                    IconButton(
                                        onClick = { pendingPcOpenItem = item },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Computer,
                                            contentDescription = "Open on PC",
                                            tint = Color(0xFF94A3B8),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------
    // IN-APP IMAGE PREVIEW DIALOG
    // ---------------------------------------------------------
    if (previewImageItem != null) {
        val item = previewImageItem!!
        AlertDialog(
            onDismissRequest = {
                previewImageItem = null
                previewImageBitmap = null
            },
            containerColor = Color(0xFF0F172A),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatFileSize(item.size), fontSize = 11.sp, color = Color(0xFF38BDF8))
                    }
                    IconButton(onClick = {
                        previewImageItem = null
                        previewImageBitmap = null
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(Color(0xFF1E293B), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isImageLoading) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    } else if (previewImageBitmap != null) {
                        Image(
                            bitmap = previewImageBitmap!!,
                            contentDescription = item.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text("Could not load image preview", color = Color(0xFF64748B), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadToPhone(item) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save to Phone", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { openWithExternalApp(item) }) {
                    Text("Open in Gallery", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        )
    }

    // Confirm sensitive file launch on PC desktop
    if (pendingPcOpenItem != null) {
        val target = pendingPcOpenItem!!
        LiquidConfirmDialog(
            title = "Open on PC Desktop?",
            message = "Do you want to launch '${target.name}' directly on your PC screen?",
            icon = Icons.Rounded.Computer,
            iconColor = Color(0xFF38BDF8),
            confirmText = "Open on PC",
            confirmColor = Color(0xFF38BDF8),
            onConfirm = {
                openOnPc(target)
                pendingPcOpenItem = null
            },
            onDismiss = { pendingPcOpenItem = null }
        )
    }

    // ---------------------------------------------------------
    // IN-APP TEXT / CODE PREVIEW DIALOG
    // ---------------------------------------------------------
    if (previewTextItem != null) {
        val item = previewTextItem!!
        AlertDialog(
            onDismissRequest = {
                previewTextItem = null
                previewTextContent = ""
            },
            containerColor = Color(0xFF0F172A),
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatFileSize(item.size)} • ${item.ext.uppercase()}", fontSize = 11.sp, color = Color(0xFF38BDF8))
                    }
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("PC File", previewTextContent)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = {
                        previewTextItem = null
                        previewTextContent = ""
                    }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(Color(0xFF090D16), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    if (isTextLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = previewTextContent.ifEmpty { "(File is empty)" },
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadToPhone(item) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save to Phone", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { openWithExternalApp(item) }) {
                    Text("Open with App", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// PHONE FILE MANAGER SCREEN (All Permissions, Browse, Search, Save to PC, Play)
// -------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneFileManagerScreen(
    targetIp: String,
    password: String,
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var items by remember { mutableStateOf<List<LocalFileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var uploadingFile by remember { mutableStateOf<String?>(null) }
    var showAllFilesConfirm by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val okHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Intercept back button and edge gestures
    BackHandler {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
        } else {
            onBack()
        }
    }

    // Check all required permissions
    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    fun isPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            return true
        }
        return mediaPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    var hasPermission by remember { mutableStateOf(isPermissionGranted()) }

    fun loadLocalFiles() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val list = mutableListOf<LocalFileItem>()
            try {
                // 1. Query Videos
                val videoProjection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.TITLE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DISPLAY_NAME
                )
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    videoProjection,
                    null,
                    null,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Video_$id"
                        val dur = cursor.getLong(durCol)
                        val size = cursor.getLong(sizeCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                        val ext = name.substringAfterLast(".", "")
                        list.add(LocalFileItem(id, name, size, uri, "video", dur, ext))
                    }
                }

                // 2. Query Audio
                val audioProjection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DISPLAY_NAME
                )
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    audioProjection,
                    null,
                    null,
                    "${MediaStore.Audio.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Audio_$id"
                        val dur = cursor.getLong(durCol)
                        val size = cursor.getLong(sizeCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val ext = name.substringAfterLast(".", "")
                        list.add(LocalFileItem(id, name, size, uri, "audio", dur, ext))
                    }
                }

                // 3. Query Images
                val imageProjection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.SIZE
                )
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imageProjection,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    var count = 0
                    while (cursor.moveToNext() && count < 250) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Image_$id"
                        val size = cursor.getLong(sizeCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        val ext = name.substringAfterLast(".", "")
                        list.add(LocalFileItem(id, name, size, uri, "image", 0L, ext))
                        count++
                    }
                }

                // 4. Query Documents & Files from MediaStore.Files
                val fileProjection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE
                )
                val docMimes = "mime_type LIKE 'application/%' OR mime_type LIKE 'text/%'"
                context.contentResolver.query(
                    MediaStore.Files.getContentUri("external"),
                    fileProjection,
                    docMimes,
                    null,
                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    var count = 0
                    while (cursor.moveToNext() && count < 250) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "Doc_$id"
                        val size = cursor.getLong(sizeCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                        val ext = name.substringAfterLast(".", "")
                        list.add(LocalFileItem(id, name, size, uri, "document", 0L, ext))
                        count++
                    }
                }

                withContext(Dispatchers.Main) {
                    items = list
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    Toast.makeText(context, "Error fetching phone files: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val multiplePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it } || isPermissionGranted()
        hasPermission = granted
        if (granted) {
            loadLocalFiles()
        } else {
            Toast.makeText(context, "Permissions are required to browse phone files", Toast.LENGTH_SHORT).show()
        }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (isPermissionGranted()) {
            hasPermission = true
            loadLocalFiles()
        }
    }

    fun uploadToPc(item: LocalFileItem) {
        uploadingFile = item.name
        Toast.makeText(context, "Saving '${item.name}' to PC...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val clean = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedName = URLEncoder.encode(item.name, "UTF-8")
                val reqBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = item.size
                    override fun writeTo(sink: BufferedSink) {
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            val buf = ByteArray(16384)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                sink.write(buf, 0, read)
                            }
                        }
                    }
                }
                val effectivePass = if (password.isNotEmpty()) password else SecureStorage.getPassword(context)
                val token = SecureStorage.getDeviceToken(context)
                val req = Request.Builder()
                    .url("http://$clean/fs/upload?filename=$encodedName")
                    .post(reqBody)
                    .addHeader("X-Remote-Password", effectivePass)
                    .apply {
                        if (token.isNotEmpty()) addHeader("X-Device-Token", token)
                    }
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        uploadingFile = null
                        if (resp.isSuccessful) {
                            Toast.makeText(context, "Saved to PC Downloads: ${item.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Upload failed: ${resp.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    uploadingFile = null
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun sendToPc(item: LocalFileItem) {
        Toast.makeText(context, "Opening '${item.name}' on PC...", Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                val phoneIp = (context as MainActivity).getLocalIpTowards(targetIp)
                val streamUrl = "http://$phoneIp:5056/video?id=${item.id}"
                val cleanTarget = if (!targetIp.contains(":")) "$targetIp:5055" else targetIp
                val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
                val req = Request.Builder()
                    .url("http://$cleanTarget/player/open?url=$encodedUrl")
                    .post(RequestBody.create(null, ByteArray(0)))
                    .addHeader("X-Remote-Password", password)
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    withContext(Dispatchers.Main) {
                        if (resp.isSuccessful) {
                            Toast.makeText(context, "Playing '${item.name}' on PC!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Failed to launch on PC", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasPermission) {
            loadLocalFiles()
        } else {
            multiplePermissionsLauncher.launch(mediaPermissions)
        }
    }

    val filteredItems = remember(items, searchQuery, selectedCategory) {
        items.filter { item ->
            val matchesSearch = if (searchQuery.isBlank()) true else {
                item.name.contains(searchQuery, ignoreCase = true) || item.ext.contains(searchQuery, ignoreCase = true)
            }
            val matchesCat = when (selectedCategory) {
                "all" -> true
                "video" -> item.category == "video"
                "audio" -> item.category == "audio"
                "image" -> item.category == "image"
                "document" -> item.category == "document"
                else -> true
            }
            matchesSearch && matchesCat
        }
    }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            if (showAllFilesConfirm) {
                LiquidConfirmDialog(
                    title = "Grant All Files Access?",
                    message = "This allows Remote-PC to browse all documents, downloads, and archives on your phone to transfer or stream them to your PC. Continue to system settings?",
                    icon = Icons.Rounded.FolderShared,
                    iconColor = AppTheme.AccentSky,
                    confirmText = "Open Settings",
                    confirmColor = AppTheme.AccentSky,
                    onConfirm = {
                        showAllFilesConfirm = false
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            allFilesLauncher.launch(intent)
                        } catch (_: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            allFilesLauncher.launch(intent)
                        }
                    },
                    onDismiss = { showAllFilesConfirm = false }
                )
            }
            TopAppBar(
                title = { Text("Phone File Manager", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (hasPermission) loadLocalFiles() else multiplePermissionsLauncher.launch(mediaPermissions)
                    }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = AppTheme.AccentAmber)
                    }
                    IconButton(onClick = {
                        if (searchQuery.isNotEmpty()) searchQuery = "" else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.CardDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search phone files...", color = AppTheme.TextMuted, fontSize = 13.sp) },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = AppTheme.AccentAmber)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = AppTheme.TextSecondary)
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppTheme.AccentAmber,
                    unfocusedBorderColor = AppTheme.CardBorderSubtle,
                    focusedContainerColor = AppTheme.CardDarkElevated,
                    unfocusedContainerColor = AppTheme.CardDarkElevated,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            // Category Filter Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val categories = listOf(
                    "all" to "All",
                    "video" to "Videos",
                    "audio" to "Music",
                    "image" to "Photos",
                    "document" to "Documents"
                )
                categories.forEach { (catId, label) ->
                    val isSelected = selectedCategory == catId
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = catId },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppTheme.AccentAmber,
                            selectedLabelColor = AppTheme.BackgroundDark,
                            containerColor = AppTheme.CardDarkElevated,
                            labelColor = AppTheme.TextSecondary
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!hasPermission) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                        border = BorderStroke(1.dp, AppTheme.CardBorderSubtle),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.FolderSpecial,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Storage Permissions Required",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.TextPrimary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Permissions are required to browse your photos, videos, music, and documents to view or save them to your PC.",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = { multiplePermissionsLauncher.launch(mediaPermissions) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = Color.Black)
                                Spacer(Modifier.width(8.dp))
                                Text("Grant Media Permissions", fontWeight = FontWeight.Bold, color = Color.Black)
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Spacer(Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { showAllFilesConfirm = true },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) {
                                    Icon(Icons.Rounded.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Grant All Files Access (Docs/Downloads)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFF59E0B))
                }
            } else if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isNotEmpty()) "No files match '$searchQuery'" else "No files found on phone",
                        color = Color(0xFF64748B)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredItems, key = { it.uri.toString() }) { item ->
                        val (tintColor, iconVector) = when (item.category) {
                            "video" -> Pair(Color(0xFF38BDF8), Icons.Rounded.VideoFile)
                            "audio" -> Pair(Color(0xFF10B981), Icons.Rounded.AudioFile)
                            "image" -> Pair(Color(0xFFEC4899), Icons.Rounded.Image)
                            "document" -> Pair(Color(0xFF60A5FA), Icons.Rounded.Description)
                            else -> Pair(Color(0xFF94A3B8), Icons.Rounded.InsertDriveFile)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(iconVector, contentDescription = null, tint = tintColor, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val sizeStr = formatFileSize(item.size)
                                    val durStr = if (item.duration > 0) {
                                        val min = TimeUnit.MILLISECONDS.toMinutes(item.duration)
                                        val sec = TimeUnit.MILLISECONDS.toSeconds(item.duration) % 60
                                        " • ${String.format(Locale.US, "%02d:%02d", min, sec)}"
                                    } else ""
                                    Text(
                                        "$sizeStr$durStr • ${item.category.uppercase()}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }

                                // Save to PC (Upload)
                                Button(
                                    onClick = { uploadToPc(item) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Rounded.Upload, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("SAVE TO PC", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }

                                // Play on PC if video or audio
                                if (item.category == "video" || item.category == "audio") {
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { sendToPc(item) },
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(Icons.Rounded.Tv, contentDescription = "Play on PC", tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Compatibility forwards
@Composable
fun PcVideosScreen(targetIp: String, password: String, onBack: () -> Unit, onMenuClick: () -> Unit) {
    PcFileManagerScreen(targetIp, password, onBack, onMenuClick)
}

@Composable
fun PhoneVideosScreen(targetIp: String, password: String, onBack: () -> Unit, onMenuClick: () -> Unit) {
    PhoneFileManagerScreen(targetIp, password, onBack, onMenuClick)
}


// -------------------------------------------------------------
// LIQUID PASSWORD DIALOG WITH SAVE DEVICE (PHONE LOCK) TOGGLE
// -------------------------------------------------------------
@Composable
fun LiquidPasswordDialog(
    deviceName: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    isVisible: Boolean,
    onVisibilityToggle: () -> Unit,
    saveDevice: Boolean,
    onSaveDeviceChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.CardDarkElevated,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AppTheme.AccentCyan.copy(alpha = 0.15f))
                        .border(1.dp, AppTheme.AccentCyan.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = AppTheme.AccentCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Connect to PC", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 18.sp)
                    Text(deviceName, fontSize = 12.sp, color = AppTheme.AccentSky)
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Server Password", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onVisibilityToggle) {
                            Icon(
                                if (isVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = AppTheme.TextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentCyan,
                        unfocusedBorderColor = AppTheme.CardBorderSubtle,
                        focusedLabelColor = AppTheme.AccentCyan,
                        unfocusedLabelColor = AppTheme.TextSecondary,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSaveDeviceChange(!saveDevice) }
                        .padding(4.dp)
                ) {
                    Checkbox(
                        checked = saveDevice,
                        onCheckedChange = { onSaveDeviceChange(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AppTheme.AccentCyan,
                            uncheckedColor = AppTheme.TextMuted
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            "Save Device (Biometric / Screen Lock)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.TextPrimary
                        )
                        Text(
                            "Instant access next time via fingerprint or PIN",
                            fontSize = 11.sp,
                            color = AppTheme.TextSecondary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Connect", color = AppTheme.BackgroundDark, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.TextSecondary)
            }
        }
    )
}

// -------------------------------------------------------------
// SENSITIVE ACCESS CONFIRMATION DIALOG
// -------------------------------------------------------------
@Composable
fun LiquidConfirmDialog(
    title: String,
    message: String,
    icon: ImageVector = Icons.Rounded.WarningAmber,
    iconColor: Color = AppTheme.AccentRose,
    confirmText: String = "Confirm",
    confirmColor: Color = AppTheme.AccentRose,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppTheme.CardDarkElevated,
        shape = RoundedCornerShape(20.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f))
                    .border(1.dp, iconColor.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(28.dp))
            }
        },
        title = {
            Text(title, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        text = {
            Text(message, color = AppTheme.TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(confirmText, color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel", color = AppTheme.TextSecondary)
            }
        }
    )
}
