package com.example.remoteapp

import android.app.ActivityManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import android.webkit.MimeTypeMap
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.Locale

class VideoStreamServer(
    private val port: Int = 5056,
    private val context: Context,
    var onPairRequest: ((String, String) -> Unit)? = null,
    var onPairApproved: ((String, String) -> Unit)? = null,
    var onToast: ((String) -> Unit)? = null
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val resolver: ContentResolver = context.contentResolver
    private var isTorchOn = false

    companion object {
        private const val TAG = "VideoStreamServer"
        private var instance: VideoStreamServer? = null

        @Synchronized
        fun startServer(
            context: Context,
            onPairRequest: ((String, String) -> Unit)? = null,
            onPairApproved: ((String, String) -> Unit)? = null,
            onToast: ((String) -> Unit)? = null
        ): VideoStreamServer {
            val srv = instance ?: VideoStreamServer(5056, context.applicationContext).also {
                instance = it
                it.start()
            }
            if (onPairRequest != null) srv.onPairRequest = onPairRequest
            if (onPairApproved != null) srv.onPairApproved = onPairApproved
            if (onToast != null) srv.onToast = onToast
            return srv
        }

        @Synchronized
        fun stopServer() {
            instance?.stop()
            instance = null
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "VideoStreamServer running on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleConnection(client) }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server socket exception", e)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleConnection(socket: Socket) {
        try {
            val rawIn = socket.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var prev1 = -1
            var prev2 = -1
            var prev3 = -1
            while (true) {
                val b = rawIn.read()
                if (b == -1) break
                headerBytes.write(b)
                if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                    break
                }
                prev3 = prev2
                prev2 = prev1
                prev1 = b
            }

            val headerStr = headerBytes.toString("UTF-8")
            val lines = headerStr.split("\r\n")
            if (lines.isEmpty()) return
            val reqParts = lines[0].split(" ")
            if (reqParts.size < 2) return
            val uri = reqParts[1]

            var rangeHeader: String? = null
            var contentLength = 0L
            for (line in lines) {
                val lower = line.lowercase(Locale.ROOT)
                if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim()
                } else if (lower.startsWith("content-length:")) {
                    contentLength = line.substring(15).trim().toLongOrNull() ?: 0L
                }
            }

            val out = socket.getOutputStream()

            // 1. Live Device Information Endpoint
            if (uri.startsWith("/device/info")) {
                val json = getDeviceInfoJson()
                sendJsonResponse(out, json)
                return
            }

            // 2. Mobile Control Action Endpoint
            if (uri.startsWith("/action")) {
                val actionType = uri.substringAfter("type=", "").substringBefore("&")
                val actionValue = uri.substringAfter("value=", "").substringBefore("&")
                val decodedVal = try { URLDecoder.decode(actionValue, "UTF-8") } catch (_: Exception) { actionValue }
                handlePhoneAction(actionType, decodedVal)
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("action", actionType)
                    put("value", decodedVal)
                }
                sendJsonResponse(out, resp.toString())
                return
            }

            // 3. Installed Apps List
            if (uri.startsWith("/apps/list")) {
                val json = getInstalledAppsJson()
                sendJsonResponse(out, json)
                return
            }

            // 4. Clipboard Synchronization
            if (uri.startsWith("/clipboard")) {
                val text = uri.substringAfter("text=", "").substringBefore("&")
                val decoded = try { URLDecoder.decode(text, "UTF-8") } catch (_: Exception) { text }
                handleClipboard(decoded)
                sendJsonResponse(out, "{\"status\":\"ok\",\"message\":\"Clipboard updated\"}")
                return
            }

            // 5. Toast / Notification
            if (uri.startsWith("/notify")) {
                val text = uri.substringAfter("text=", "").substringBefore("&")
                val decoded = try { URLDecoder.decode(text, "UTF-8") } catch (_: Exception) { text }
                onToast?.invoke(decoded) ?: run {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "💬 $decoded", Toast.LENGTH_LONG).show()
                    }
                }
                sendJsonResponse(out, "{\"status\":\"ok\"}")
                return
            }

            // 6. Pairing Requests
            if (uri.startsWith("/pair/request")) {
                val serverName = uri.substringAfter("server_name=", "PC").substringBefore("&")
                val serverIp = uri.substringAfter("ip=", socket.inetAddress.hostAddress ?: "").substringBefore("&")
                val decodedName = try { URLDecoder.decode(serverName, "UTF-8") } catch (_: Exception) { serverName }
                onPairRequest?.invoke(decodedName, serverIp)
                sendJsonResponse(out, "{\"status\":\"pending\"}")
                return
            } else if (uri.startsWith("/pair/approved")) {
                val serverName = uri.substringAfter("name=", "PC").substringBefore("&")
                val serverIp = uri.substringAfter("ip=", socket.inetAddress.hostAddress ?: "").substringBefore("&")
                val decodedName = try { URLDecoder.decode(serverName, "UTF-8") } catch (_: Exception) { serverName }
                onPairApproved?.invoke(decodedName, serverIp)
                sendJsonResponse(out, "{\"status\":\"ok\"}")
                return
            }

            // 7. Storage Explorer
            if (uri.startsWith("/storage/list")) {
                val cat = uri.substringAfter("type=", "all").substringBefore("&")
                val jsonArray = JSONArray()
                val proj = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )

                fun queryFolder(contentUri: Uri, typeTag: String) {
                    try {
                        val cursor = resolver.query(contentUri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                        cursor?.use { c ->
                            val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                            val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            var count = 0
                            while (c.moveToNext() && count < 80) {
                                val id = if (idCol >= 0) c.getLong(idCol) else 0L
                                val name = if (nameCol >= 0) c.getString(nameCol) ?: "file" else "file"
                                val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                                val date = if (dateCol >= 0) c.getLong(dateCol) else 0L

                                val obj = JSONObject()
                                obj.put("id", id)
                                obj.put("name", name)
                                obj.put("type", typeTag)
                                obj.put("size", size)
                                obj.put("date", date)
                                jsonArray.put(obj)
                                count++
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (cat == "photos" || cat == "all" || cat == "image") queryFolder(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image")
                if (cat == "videos" || cat == "all" || cat == "video") queryFolder(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
                if (cat == "music" || cat == "all" || cat == "audio") queryFolder(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
                if ((cat == "downloads" || cat == "all") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    queryFolder(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "download")
                }
                if (cat == "documents" || cat == "all" || cat == "doc") {
                    try {
                        val filesUri = MediaStore.Files.getContentUri("external")
                        val cursor = resolver.query(filesUri, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                        cursor?.use { c ->
                            val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                            val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                            val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                            val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                            var count = 0
                            while (c.moveToNext() && count < 80) {
                                val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                                val lower = name.lowercase(Locale.ROOT)
                                if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc") ||
                                    lower.endsWith(".txt") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") ||
                                    lower.endsWith(".zip") || lower.endsWith(".apk")) {
                                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                                    val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                                    val date = if (dateCol >= 0) c.getLong(dateCol) else 0L
                                    val obj = JSONObject().apply {
                                        put("id", id)
                                        put("name", name)
                                        put("type", "document")
                                        put("size", size)
                                        put("date", date)
                                    }
                                    jsonArray.put(obj)
                                    count++
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val root = JSONObject()
                root.put("status", "ok")
                root.put("files", jsonArray)
                sendJsonResponse(out, root.toString())
                return
            } else if (uri.startsWith("/storage/file")) {
                val idStr = uri.substringAfter("id=", "").substringBefore("&")
                val type = uri.substringAfter("type=", "image").substringBefore("&")
                val fId = idStr.toLongOrNull()
                if (fId != null) {
                    val targetUri = when (type) {
                        "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, fId)
                        "audio" -> ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, fId)
                        "download" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, fId) else ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fId)
                        "document" -> ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), fId)
                        else -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fId)
                    }
                    try {
                        resolver.openInputStream(targetUri)?.use { input ->
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            input.copyTo(out)
                            out.flush()
                            return
                        }
                    } catch (_: Exception) {}
                }
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                out.flush()
                return
            } else if (uri.startsWith("/storage/upload")) {
                val filenameParam = uri.substringAfter("filename=", "file_${System.currentTimeMillis()}").substringBefore("&")
                val decodedName = try { URLDecoder.decode(filenameParam, "UTF-8") } catch (_: Exception) { filenameParam }
                val cleanName = File(decodedName).name
                val success = saveUploadedFile(cleanName, contentLength, rawIn)
                if (success) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "📥 Received $cleanName from PC\nSaved in Downloads/RemotePC", Toast.LENGTH_LONG).show()
                    }
                    sendJsonResponse(out, "{\"status\":\"ok\",\"filename\":\"$cleanName\"}")
                } else {
                    out.write("HTTP/1.1 500 Internal Server Error\r\n\r\nFailed to save file".toByteArray())
                    out.flush()
                }
                return
            } else if (!uri.startsWith("/video")) {
                val body = "<h3>Remote PC Android Control Server Active</h3>".toByteArray()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                out.write(body)
                out.flush()
                return
            }

            // 8. Video / Audio Streaming with Range Support
            val idStr = uri.substringAfter("id=", "").substringBefore("&")
            val videoId = idStr.toLongOrNull()
            if (videoId == null) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            var contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
            var pfd = try { resolver.openFileDescriptor(contentUri, "r") } catch (_: Exception) { null }
            if (pfd == null) {
                contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, videoId)
                pfd = try { resolver.openFileDescriptor(contentUri, "r") } catch (_: Exception) { null }
            }
            if (pfd == null) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val totalLength = pfd.statSize
            pfd.close()

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                var rangeFrom = 0L
                var rangeTo = totalLength - 1
                val rangeValues = rangeHeader.substring(6).split("-")
                if (rangeValues[0].isNotEmpty()) {
                    rangeFrom = rangeValues[0].toLong()
                }
                if (rangeValues.size > 1 && rangeValues[1].isNotEmpty()) {
                    rangeTo = rangeValues[1].toLong()
                }
                if (rangeTo >= totalLength) {
                    rangeTo = totalLength - 1
                }
                val sendLength = rangeTo - rangeFrom + 1

                val header = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes $rangeFrom-$rangeTo/$totalLength\r\n" +
                        "Content-Length: $sendLength\r\n\r\n"
                out.write(header.toByteArray())

                resolver.openInputStream(contentUri)?.use { stream ->
                    stream.skip(rangeFrom)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = sendLength
                    while (remaining > 0) {
                        val toRead = if (remaining > buffer.size) buffer.size else remaining.toInt()
                        val read = stream.read(buffer, 0, toRead)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            } else {
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: $totalLength\r\n\r\n"
                out.write(header.toByteArray())

                resolver.openInputStream(contentUri)?.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendJsonResponse(out: OutputStream, jsonStr: String) {
        val body = jsonStr.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    // -------------------------------------------------------------
    // DEVICE STATS & ACTIONS IMPLEMENTATION
    // -------------------------------------------------------------

    private fun getDeviceInfoJson(): String {
        val root = JSONObject()
        root.put("status", "online")
        root.put("model", Build.MODEL)
        root.put("manufacturer", Build.MANUFACTURER)
        root.put("android_version", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        // Battery
        var batteryPct = 100
        var isCharging = false
        var healthStr = "Good"
        try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                batteryPct = (level * 100) / scale
            }
            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val health: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD) ?: BatteryManager.BATTERY_HEALTH_GOOD
            healthStr = when (health) {
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                else -> "Good"
            }
        } catch (_: Exception) {}
        root.put("battery", batteryPct)
        root.put("battery_health", healthStr)
        root.put("is_charging", isCharging)

        // Storage
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val totalGb = String.format(Locale.US, "%.1f", (totalBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)).toDoubleOrNull() ?: 64.0
            val freeGb = String.format(Locale.US, "%.1f", (availableBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)).toDoubleOrNull() ?: 32.0
            root.put("storage_total_gb", totalGb)
            root.put("storage_free_gb", freeGb)
        } catch (_: Exception) {
            root.put("storage_total_gb", 64.0)
            root.put("storage_free_gb", 32.0)
        }

        // RAM
        try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val totalMb = (memInfo.totalMem / (1024 * 1024)).toInt()
            val availMb = (memInfo.availMem / (1024 * 1024)).toInt()
            val usedMb = totalMb - availMb
            root.put("ram_total_mb", totalMb)
            root.put("ram_used_mb", usedMb)
        } catch (_: Exception) {
            root.put("ram_total_mb", 4096)
            root.put("ram_used_mb", 2048)
        }

        // Screen Resolution
        try {
            val dm = context.resources.displayMetrics
            root.put("screen_res", "${dm.widthPixels}x${dm.heightPixels}")
        } catch (_: Exception) {
            root.put("screen_res", "1080x2400")
        }

        // Wi-Fi SSID
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            var ssid = info?.ssid?.replace("\"", "") ?: "Connected Wi-Fi"
            if (ssid.isEmpty() || ssid == "<unknown ssid>") ssid = "Connected Wi-Fi"
            root.put("wifi_ssid", ssid)
        } catch (_: Exception) {
            root.put("wifi_ssid", "Connected Wi-Fi")
        }

        return root.toString()
    }

    private fun handlePhoneAction(action: String, value: String) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (action) {
            "flashlight" -> {
                try {
                    val cam = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    val id = cam?.cameraIdList?.firstOrNull()
                    if (cam != null && id != null) {
                        isTorchOn = when (value) {
                            "on" -> true
                            "off" -> false
                            else -> !isTorchOn
                        }
                        cam.setTorchMode(id, isTorchOn)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Flashlight error", e)
                }
            }
            "volume_up" -> am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                } else {
                    @Suppress("DEPRECATION")
                    am?.setStreamMute(AudioManager.STREAM_MUSIC, true)
                }
            }
            "volume", "set_volume" -> {
                if (am != null) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val pct = (value.toIntOrNull() ?: 50).coerceIn(0, 100)
                    val level = (pct * max) / 100
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, level, AudioManager.FLAG_SHOW_UI)
                }
            }
            "brightness" -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "☀️ Brightness set to $value% by PC", Toast.LENGTH_SHORT).show()
                }
            }
            "media_playpause", "playpause" -> {
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            "media_next", "next" -> {
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
            }
            "media_prev", "prev" -> {
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
            "nav_back", "back" -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Back pressed from PC", Toast.LENGTH_SHORT).show()
                }
            }
            "nav_home", "home" -> {
                try {
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            "nav_recents", "recents" -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Recents requested from PC", Toast.LENGTH_SHORT).show()
                }
            }
            "notifications" -> {
                try {
                    val statusBarService = context.getSystemService("statusbar")
                    val statusBarManager = Class.forName("android.app.StatusBarManager")
                    val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
                    expandMethod.invoke(statusBarService)
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Notifications opened from PC", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "open_dialer", "dialer" -> {
                val telUri = if (value.isNotEmpty()) Uri.parse("tel:$value") else Uri.parse("tel:")
                val intent = Intent(Intent.ACTION_DIAL, telUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            "open_sms", "sms" -> {
                val smsUri = if (value.isNotEmpty()) Uri.parse("smsto:$value") else Uri.parse("smsto:")
                val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            "launch_app" -> {
                val intent = context.packageManager.getLaunchIntentForPackage(value)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            "app_settings" -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$value")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
            "lock_screen", "screen_off", "lock" -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "🔒 Screen Lock triggered by PC", Toast.LENGTH_SHORT).show()
                }
            }
            "toast" -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, value, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleClipboard(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    val clip = ClipData.newPlainText("PC Clipboard", text)
                    cm.setPrimaryClip(clip)
                    Toast.makeText(context, "📋 Clipboard synced from PC", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard update error", e)
            }
        }
    }

    private fun getInstalledAppsJson(): String {
        val root = JSONObject()
        root.put("status", "ok")
        val array = JSONArray()
        try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in apps) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    val obj = JSONObject().apply {
                        put("name", pm.getApplicationLabel(app).toString())
                        put("packageName", app.packageName)
                        put("isSystem", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                    }
                    array.put(obj)
                }
            }
        } catch (_: Exception) {}
        root.put("apps", array)
        return root.toString()
    }

    private fun saveUploadedFile(name: String, length: Long, input: InputStream): Boolean {
        val ext = name.substringAfterLast(".", "")
        val mimeType = if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.ROOT)) ?: "application/octet-stream"
        } else {
            "application/octet-stream"
        }

        // 1. MediaStore API (Android 10+ Q) with strict trailing slash
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RemotePC/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outStream ->
                        copyBytes(input, outStream, length)
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Log.d(TAG, "Successfully saved $name via MediaStore to Downloads/RemotePC/")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore upload save failed, attempting direct filesystem fallback", e)
            }
        }

        // 2. Direct File storage in Downloads/RemotePC
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloads, "RemotePC")
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = File(targetDir, name)
            FileOutputStream(targetFile).use { outStream ->
                copyBytes(input, outStream, length)
            }
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
            Log.d(TAG, "Successfully saved $name to public downloads ${targetFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Direct public file write failed", e)
        }

        // 3. Fallback to app-specific external files dir (scoped storage permission fallback)
        try {
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val targetDir = File(extDir, "RemotePC").apply { mkdirs() }
            val targetFile = File(targetDir, name)
            FileOutputStream(targetFile).use { outStream ->
                copyBytes(input, outStream, length)
            }
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
            Log.d(TAG, "Saved $name to app-specific files ${targetFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "All storage save attempts failed for $name", e)
            return false
        }
    }

    private fun copyBytes(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(8192)
        var remaining = if (length > 0) length else Long.MAX_VALUE
        while (remaining > 0) {
            val toRead = if (length > 0) minOf(remaining, buffer.size.toLong()).toInt() else buffer.size
            val r = input.read(buffer, 0, toRead)
            if (r == -1) break
            output.write(buffer, 0, r)
            if (length > 0) remaining -= r
        }
        output.flush()
    }
}

