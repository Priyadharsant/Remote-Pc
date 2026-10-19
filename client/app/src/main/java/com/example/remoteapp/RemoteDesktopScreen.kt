package com.example.remoteapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MonitorInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isPrimary: Boolean
)

data class WindowInfo(
    val hwnd: Long,
    val title: String,
    val width: Int,
    val height: Int
)

enum class TouchInteractionStyle {
    MOBILE_SCROLL, // Single finger swipe scrolls like a phone, tap clicks, long press right-clicks
    DRAG_SELECT    // Single finger drag holds left click (drags windows, highlights text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDesktopScreen(
    targetIp: String,
    password: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val handleBack: () -> Unit = {
        (context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onBack()
    }

    // 1. Force Landscape & Fullscreen on Enter, Explicitly Restore Portrait on Exit
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Intercept hardware/gesture Back button
    BackHandler {
        handleBack()
    }

    // Connection & Frame State
    var isConnected by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var reconnectTrigger by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var monitors by remember { mutableStateOf<List<MonitorInfo>>(emptyList()) }
    var selectedMonitorId by remember { mutableIntStateOf(1) }
    var activeWindows by remember { mutableStateOf<List<WindowInfo>>(emptyList()) }
    var targetMode by remember { mutableStateOf("desktop") } // "desktop" | "window"
    var targetHwnd by remember { mutableLongStateOf(0L) }
    var targetWindowTitle by remember { mutableStateOf("Full Desktop") }
    var showWindowPickerSheet by remember { mutableStateOf(false) }

    // Stream Presets (Crisp 45 FPS, 60 quality, 0.9 scale)
    var selectedFps by remember { mutableIntStateOf(45) }
    var selectedQuality by remember { mutableIntStateOf(60) }
    var selectedScale by remember { mutableFloatStateOf(0.9f) }

    // Touch interaction mode (Default: Direct Touch / Drag Select for sliding sliders and desktop controls)
    var touchStyle by remember { mutableStateOf(TouchInteractionStyle.DRAG_SELECT) }

    // Live Metrics
    var fpsCounter by remember { mutableIntStateOf(0) }
    var currentFps by remember { mutableIntStateOf(0) }
    var frameResolution by remember { mutableStateOf("1920x1080") }

    // Side Navigation & Tool Drawers
    var showQuickNav by remember { mutableStateOf(true) }
    var showFullDrawer by remember { mutableStateOf(false) }
    var showKeyboardSheet by remember { mutableStateOf(false) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }

    // Real PC Keyboard Modifier States (Sticky/Latchable)
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isWinActive by remember { mutableStateOf(false) }

    // Header & Sidebar Visibility Policies
    // Header hides permanently after 5s; Sidebar auto-closes after 5s and slides open on tap
    var isHeaderVisible by remember { mutableStateOf(true) }
    var isSidebarVisible by remember { mutableStateOf(true) }
    var lastSidebarInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Canvas & Destination Fit Geometry
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dstLeft by remember { mutableFloatStateOf(0f) }
    var dstTop by remember { mutableFloatStateOf(0f) }
    var dstWidth by remember { mutableFloatStateOf(0f) }
    var dstHeight by remember { mutableFloatStateOf(0f) }

    // Zoom & Pan
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // WebSocket Reference & AudioTrack Reference
    var webSocketRef by remember { mutableStateOf<WebSocket?>(null) }
    var audioJob by remember { mutableStateOf<Job?>(null) }
    var audioTrackRef by remember { mutableStateOf<AudioTrack?>(null) }

    // Haptic feedback helper
    val triggerHaptic: () -> Unit = {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {}
    }

    // Audio stream helper using low-latency AudioTrack
    val stopAudio: () -> Unit = {
        audioJob?.cancel()
        audioJob = null
        try {
            audioTrackRef?.stop()
            audioTrackRef?.release()
        } catch (_: Exception) {}
        audioTrackRef = null
        isAudioPlaying = false
    }

    val toggleAudio: () -> Unit = {
        if (isAudioPlaying) {
            stopAudio()
        } else {
            stopAudio()
            isAudioPlaying = true
            val host = if (targetIp.contains(":")) targetIp.substringBefore(":") else targetIp
            audioJob = coroutineScope.launch(Dispatchers.IO) {
                var connection: HttpURLConnection? = null
                var track: AudioTrack? = null
                try {
                    val url = URL("http://$host:5055/audio/stream")
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 10000
                        doInput = true
                        useCaches = false
                    }
                    connection.connect()

                    val inputStream = connection.inputStream
                    // Parse 44-byte WAV header safely
                    val headerBytes = ByteArray(44)
                    var headerRead = 0
                    while (headerRead < 44) {
                        val r = inputStream.read(headerBytes, headerRead, 44 - headerRead)
                        if (r == -1) break
                        headerRead += r
                    }

                    var sampleRate = 48000
                    var channelMask = AudioFormat.CHANNEL_OUT_STEREO
                    if (headerRead >= 28) {
                        val numChannels = (headerBytes[22].toInt() and 0xFF) or ((headerBytes[23].toInt() and 0xFF) shl 8)
                        val sr = (headerBytes[24].toInt() and 0xFF) or
                                ((headerBytes[25].toInt() and 0xFF) shl 8) or
                                ((headerBytes[26].toInt() and 0xFF) shl 16) or
                                ((headerBytes[27].toInt() and 0xFF) shl 24)
                        if (sr in 8000..192000) {
                            sampleRate = sr
                        }
                        if (numChannels == 1) {
                            channelMask = AudioFormat.CHANNEL_OUT_MONO
                        }
                    }

                    val minBufSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    // ~250ms buffer capacity (approx 48KB) to absorb network jitter without underruns
                    val bufferCapacity = maxOf(minBufSize * 4, 48000)

                    track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferCapacity)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    audioTrackRef = track

                    val bufferedStream = java.io.BufferedInputStream(inputStream, 16384)

                    // Frame size in bytes (16-bit stereo PCM = 4 bytes per frame)
                    val frameSize = if (channelMask == AudioFormat.CHANNEL_OUT_MONO) 2 else 4
                    var carryoverLen = 0
                    val carryoverBuf = ByteArray(frameSize)

                    // Jitter Pre-buffering: accumulate ~16KB (~85ms of audio) before starting playback
                    // to guarantee the AudioTrack never starves from Wi-Fi jitter!
                    val prebufferTarget = 16384
                    var prebufferedBytes = 0
                    val prebuffer = ByteArray(prebufferTarget)
                    while (isActive && prebufferedBytes < prebufferTarget) {
                        val r = bufferedStream.read(prebuffer, prebufferedBytes, prebufferTarget - prebufferedBytes)
                        if (r == -1) break
                        prebufferedBytes += r
                    }

                    if (prebufferedBytes > 0) {
                        val alignedPrebuffer = (prebufferedBytes / frameSize) * frameSize
                        val preRemainder = prebufferedBytes - alignedPrebuffer
                        if (alignedPrebuffer > 0) {
                            track.write(prebuffer, 0, alignedPrebuffer)
                        }
                        if (preRemainder > 0) {
                            System.arraycopy(prebuffer, alignedPrebuffer, carryoverBuf, 0, preRemainder)
                            carryoverLen = preRemainder
                        }
                    }
                    track.play()

                    val readBuf = ByteArray(4096)
                    val writeBuf = ByteArray(4096 + frameSize)
                    while (isActive) {
                        val bytesRead = bufferedStream.read(readBuf, 0, readBuf.size)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            if (carryoverLen > 0) {
                                System.arraycopy(carryoverBuf, 0, writeBuf, 0, carryoverLen)
                            }
                            System.arraycopy(readBuf, 0, writeBuf, carryoverLen, bytesRead)
                            val totalBytes = carryoverLen + bytesRead
                            val alignedBytes = (totalBytes / frameSize) * frameSize
                            val remainder = totalBytes - alignedBytes

                            if (alignedBytes > 0) {
                                track.write(writeBuf, 0, alignedBytes)
                            }

                            if (remainder > 0) {
                                System.arraycopy(writeBuf, alignedBytes, carryoverBuf, 0, remainder)
                                carryoverLen = remainder
                            } else {
                                carryoverLen = 0
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RemoteAudio", "Audio stream error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isAudioPlaying = false
                    }
                } finally {
                    try {
                        track?.stop()
                        track?.release()
                    } catch (_: Exception) {}
                    try {
                        connection?.disconnect()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Send action helper over WebSocket
    val sendAction: (JSONObject) -> Unit = { json ->
        webSocketRef?.let { ws ->
            try {
                if (!json.has("mode")) {
                    json.put("mode", targetMode)
                }
                if (targetMode == "window" && !json.has("hwnd")) {
                    json.put("hwnd", targetHwnd)
                }
                ws.send(json.toString())
            } catch (e: Exception) {
                Log.e("RemoteDesktop", "Failed to send action: ${e.message}")
            }
        }
    }

    // Real PC Keyboard: executes key action combining any currently held modifiers (Shift, Ctrl, Alt, Win)
    val sendKeyWithModifiers: (String) -> Unit = { key ->
        triggerHaptic()
        val activeMods = mutableListOf<String>()
        if (isCtrlActive) activeMods.add("ctrl")
        if (isAltActive) activeMods.add("alt")
        if (isShiftActive) activeMods.add("shift")
        if (isWinActive) activeMods.add("win")

        if (activeMods.isNotEmpty()) {
            val combo = JSONArray().apply {
                activeMods.forEach { put(it) }
                put(key)
            }
            sendAction(JSONObject().apply {
                put("type", "hotkey")
                put("keys", combo)
            })
            // Standard PC keyboard behavior: auto-unlatch Shift after single key execution
            if (isShiftActive) isShiftActive = false
        } else {
            sendAction(JSONObject().apply {
                put("type", "key_press")
                put("key", key)
            })
        }
    }

    // Real PC Keyboard: Character typing with dynamic Shift casing & modifier combo support
    val sendCharInput: (Char) -> Unit = { ch ->
        triggerHaptic()
        val activeMods = mutableListOf<String>()
        if (isCtrlActive) activeMods.add("ctrl")
        if (isAltActive) activeMods.add("alt")
        if (isWinActive) activeMods.add("win")

        if (activeMods.isNotEmpty()) {
            val combo = JSONArray().apply {
                activeMods.forEach { put(it) }
                if (isShiftActive) put("shift")
                put(ch.lowercaseChar().toString())
            }
            sendAction(JSONObject().apply {
                put("type", "hotkey")
                put("keys", combo)
            })
            if (isShiftActive) isShiftActive = false
        } else {
            val finalChar = if (isShiftActive) {
                when (ch) {
                    '1' -> '!'
                    '2' -> '@'
                    '3' -> '#'
                    '4' -> '$'
                    '5' -> '%'
                    '6' -> '^'
                    '7' -> '&'
                    '8' -> '*'
                    '9' -> '('
                    '0' -> ')'
                    '-' -> '_'
                    '=' -> '+'
                    ';' -> ':'
                    '\'' -> '"'
                    ',' -> '<'
                    '.' -> '>'
                    '/' -> '?'
                    '[' -> '{'
                    ']' -> '}'
                    '\\' -> '|'
                    '`' -> '~'
                    else -> ch.uppercaseChar()
                }
            } else {
                ch
            }
            sendAction(JSONObject().apply {
                put("type", "type_text")
                put("text", finalChar.toString())
            })
            if (isShiftActive) isShiftActive = false
        }
    }

    // Auto-fetch fresh window list whenever window picker sheet opens
    LaunchedEffect(showWindowPickerSheet) {
        if (showWindowPickerSheet) {
            sendAction(JSONObject().apply { put("type", "get_windows") })
        }
    }

    // Hide top header permanently after 5 seconds of entering Remote Desktop
    LaunchedEffect(Unit) {
        delay(5000L)
        isHeaderVisible = false
    }

    // Auto-hide/close floating sidebar after 5 seconds of inactivity
    LaunchedEffect(isSidebarVisible, lastSidebarInteractionTime, showFullDrawer, showKeyboardSheet, showWindowPickerSheet) {
        if (isSidebarVisible && !showFullDrawer && !showKeyboardSheet && !showWindowPickerSheet) {
            delay(5000L)
            isSidebarVisible = false
        }
    }

    // Measure FPS
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            currentFps = fpsCounter
            fpsCounter = 0
        }
    }

    // Connect WebSocket
    DisposableEffect(targetIp, password, reconnectTrigger) {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val encodedPass = try { URLEncoder.encode(password, "UTF-8") } catch (_: Exception) { password }
        val host = if (targetIp.contains(":")) targetIp.substringBefore(":") else targetIp
        val port = if (targetIp.contains(":")) targetIp.substringAfter(":") else "5055"
        val wsUrl = "ws://$host:$port/ws/remote-desktop?password=$encodedPass&token=$encodedPass"
        val request = Request.Builder().url(wsUrl).build()

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                connectionError = null
                Log.d("RemoteDesktop", "Connected to Remote Desktop server")

                // Immediately configure FPS & quality
                val configMsg = JSONObject().apply {
                    put("type", "set_quality")
                    put("fps", selectedFps)
                    put("quality", selectedQuality)
                    put("scale", selectedScale.toDouble())
                    put("monitor", selectedMonitorId)
                    put("mode", targetMode)
                    if (targetMode == "window") {
                        put("hwnd", targetHwnd)
                    }
                }
                webSocket.send(configMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "init" -> {
                            val monsArray = json.optJSONArray("monitors") ?: JSONArray()
                            val list = mutableListOf<MonitorInfo>()
                            for (i in 0 until monsArray.length()) {
                                val obj = monsArray.getJSONObject(i)
                                list.add(
                                    MonitorInfo(
                                        id = obj.optInt("id", 1),
                                        name = obj.optString("name", "Display ${i + 1}"),
                                        width = obj.optInt("width", 1920),
                                        height = obj.optInt("height", 1080),
                                        isPrimary = obj.optBoolean("is_primary", i == 0)
                                    )
                                )
                            }
                            monitors = list

                            val winsArray = json.optJSONArray("windows") ?: JSONArray()
                            val winList = mutableListOf<WindowInfo>()
                            for (i in 0 until winsArray.length()) {
                                val obj = winsArray.getJSONObject(i)
                                winList.add(
                                    WindowInfo(
                                        hwnd = obj.optLong("hwnd", 0L),
                                        title = obj.optString("title", "App Window"),
                                        width = obj.optInt("width", 1920),
                                        height = obj.optInt("height", 1080)
                                    )
                                )
                            }
                            activeWindows = winList
                        }
                        "window_list" -> {
                            val winsArray = json.optJSONArray("windows") ?: JSONArray()
                            val winList = mutableListOf<WindowInfo>()
                            for (i in 0 until winsArray.length()) {
                                val obj = winsArray.getJSONObject(i)
                                winList.add(
                                    WindowInfo(
                                        hwnd = obj.optLong("hwnd", 0L),
                                        title = obj.optString("title", "App Window"),
                                        width = obj.optInt("width", 1920),
                                        height = obj.optInt("height", 1080)
                                    )
                                )
                            }
                            activeWindows = winList
                        }
                        "target_fallback" -> {
                            targetMode = "desktop"
                            targetHwnd = 0L
                            targetWindowTitle = "Full Desktop"
                        }
                        "clipboard_data" -> {
                            val clipText = json.optString("text", "")
                            if (clipText.isNotEmpty()) {
                                coroutineScope.launch(Dispatchers.Main) {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(ClipData.newPlainText("PC Clipboard", clipText))
                                    Toast.makeText(context, "📋 Copied from PC: \"$clipText\"", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RemoteDesktop", "Error parsing json message: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                // Header format: [4B Magic: 'RD01'][2B MonId][2B Width][2B Height][2B FrameId][Payload]
                if (data.size > 12) {
                    val magic = String(data, 0, 4)
                    if (magic == "RD01") {
                        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                        val monId = buffer.getShort(4).toInt()
                        val w = buffer.getShort(6).toInt()
                        val h = buffer.getShort(8).toInt()

                        val jpegBytes = data.copyOfRange(12, data.size)
                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions)
                        if (bmp != null) {
                            currentBitmap = bmp
                            fpsCounter++
                            val newRes = "${w}x${h}"
                            if (frameResolution != newRes) {
                                frameResolution = newRes
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                val msg = t.message ?: "Connection lost"
                connectionError = if (msg.contains("404")) {
                    "Remote Desktop service not responding on $host:$port. Ensure Remote PC server is running."
                } else {
                    msg
                }
                Log.e("RemoteDesktop", "WebSocket error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
        })

        webSocketRef = ws

        onDispose {
            ws.cancel()
            stopAudio()
            webSocketRef = null
        }
    }

    // -------------------------------------------------------------------------
    // Main UI Container (Full Screen Landscape)
    // -------------------------------------------------------------------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { canvasSize = it }
    ) {
        val bmp = currentBitmap
        if (bmp != null) {
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

            // Calculate true laptop 16:9 aspect ratio letterbox fit on layout changes
            LaunchedEffect(canvasSize, imageBitmap.width, imageBitmap.height) {
                val canvasW = canvasSize.width.toFloat()
                val canvasH = canvasSize.height.toFloat()
                val imgW = imageBitmap.width.toFloat()
                val imgH = imageBitmap.height.toFloat()

                if (canvasW > 0 && canvasH > 0 && imgW > 0 && imgH > 0) {
                    val scaleFactor = minOf(canvasW / imgW, canvasH / imgH)
                    val fitW = imgW * scaleFactor
                    val fitH = imgH * scaleFactor
                    dstLeft = (canvasW - fitW) / 2f
                    dstTop = (canvasH - fitH) / 2f
                    dstWidth = fitW
                    dstHeight = fitH
                }
            }

            // Real Touch Screen Pointer Input
            var lastTapTime by remember { mutableLongStateOf(0L) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(selectedMonitorId, touchStyle, canvasSize, dstWidth, dstHeight) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPos = down.position

                            val canvasW = if (dstWidth > 0f) dstWidth else canvasSize.width.toFloat().coerceAtLeast(1f)
                            val canvasH = if (dstHeight > 0f) dstHeight else canvasSize.height.toFloat().coerceAtLeast(1f)
                            val left = if (dstWidth > 0f) dstLeft else 0f
                            val top = if (dstHeight > 0f) dstTop else 0f

                            val normX = ((startPos.x - left) / canvasW).coerceIn(0f, 1f)
                            val normY = ((startPos.y - top) / canvasH).coerceIn(0f, 1f)
                            var curNormX = normX
                            var curNormY = normY

                            // Immediately move cursor to touch point on PC
                            sendAction(JSONObject().apply {
                                put("type", "mouse_move")
                                put("x", normX.toDouble())
                                put("y", normY.toDouble())
                                put("is_normalized", true)
                                put("monitor", selectedMonitorId)
                            })

                            var isDragging = false
                            var isLongPress = false
                            var hasScrolled = false
                            val downTime = System.currentTimeMillis()
                            var lastPos = startPos

                            while (true) {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.isEmpty()) {
                                    break
                                }

                                if (activePointers.size == 1) {
                                    val currentChange = activePointers[0]
                                    val currentPos = currentChange.position
                                    val distFromStart = (currentPos - startPos).getDistance()
                                    val deltaX = currentPos.x - lastPos.x
                                    val deltaY = currentPos.y - lastPos.y
                                    lastPos = currentPos

                                    // Long press detection (> 500ms without movement) -> Right Click
                                    if (!isDragging && !hasScrolled && !isLongPress && System.currentTimeMillis() - downTime > 500 && distFromStart < 15f) {
                                        isLongPress = true
                                        triggerHaptic()
                                        sendAction(JSONObject().apply {
                                            put("type", "mouse_click")
                                            put("button", "right")
                                            put("x", normX.toDouble())
                                            put("y", normY.toDouble())
                                            put("is_normalized", true)
                                            put("monitor", selectedMonitorId)
                                        })
                                        currentChange.consume()
                                    } else if (!isLongPress && distFromStart > 10f) {
                                        if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) {
                                            // Single finger mobile-style scroll! Smooth calibrated sensitivity
                                            hasScrolled = true
                                            if (abs(deltaY) > 0.5f || abs(deltaX) > 0.5f) {
                                                sendAction(JSONObject().apply {
                                                    put("type", "mouse_scroll")
                                                    // Swipe up scrolls page down (natural mobile scrolling)
                                                    put("dy", (deltaY * 0.20).toDouble())
                                                    put("dx", (-deltaX * 0.20).toDouble())
                                                })
                                                currentChange.consume()
                                            }
                                        } else {
                                            // Direct Touch / Drag & Select mode: moves sliders, drags windows, selects text
                                            if (!isDragging) {
                                                isDragging = true
                                                sendAction(JSONObject().apply {
                                                    put("type", "mouse_down")
                                                    put("button", "left")
                                                    put("x", normX.toDouble())
                                                    put("y", normY.toDouble())
                                                    put("is_normalized", true)
                                                    put("monitor", selectedMonitorId)
                                                })
                                            }
                                            curNormX = ((currentPos.x - left) / canvasW).coerceIn(0f, 1f)
                                            curNormY = ((currentPos.y - top) / canvasH).coerceIn(0f, 1f)
                                            sendAction(JSONObject().apply {
                                                put("type", "mouse_move")
                                                put("x", curNormX.toDouble())
                                                put("y", curNormY.toDouble())
                                                put("is_normalized", true)
                                                put("monitor", selectedMonitorId)
                                            })
                                            currentChange.consume()
                                        }
                                    }
                                } else if (activePointers.size == 2) {
                                    // Two-finger smooth precision scrolling
                                    val p1 = activePointers[0]
                                    val p2 = activePointers[1]
                                    val dragY = (p1.position.y - p1.previousPosition.y + p2.position.y - p2.previousPosition.y) / 2f
                                    val dragX = (p1.position.x - p1.previousPosition.x + p2.position.x - p2.previousPosition.x) / 2f
                                    if (abs(dragY) > 1.5f || abs(dragX) > 1.5f) {
                                        sendAction(JSONObject().apply {
                                            put("type", "mouse_scroll")
                                            put("dy", (dragY * 0.20).toDouble())
                                            put("dx", (-dragX * 0.20).toDouble())
                                        })
                                        p1.consume()
                                        p2.consume()
                                    }
                                }
                            }

                            // Finger release
                            if (isDragging) {
                                sendAction(JSONObject().apply {
                                    put("type", "mouse_up")
                                    put("button", "left")
                                    put("x", curNormX.toDouble())
                                    put("y", curNormY.toDouble())
                                    put("is_normalized", true)
                                    put("monitor", selectedMonitorId)
                                })
                            } else if (!isLongPress && !hasScrolled && System.currentTimeMillis() - downTime < 400) {
                                // Clean Tap -> Click
                                triggerHaptic()
                                val now = System.currentTimeMillis()
                                val isDouble = (now - lastTapTime) < 320
                                lastTapTime = now
                                sendAction(JSONObject().apply {
                                    put("type", "mouse_click")
                                    put("button", "left")
                                    put("clicks", if (isDouble) 2 else 1)
                                    put("x", normX.toDouble())
                                    put("y", normY.toDouble())
                                    put("is_normalized", true)
                                    put("monitor", selectedMonitorId)
                                })
                            }
                        }
                    }
            ) {
                // Canvas renders with zero distortion in laptop ratio
                Canvas(modifier = Modifier.fillMaxSize()) {
                    translate(left = panOffset.x, top = panOffset.y) {
                        scale(zoomScale, pivot = Offset.Zero) {
                            drawImage(
                                image = imageBitmap,
                                dstOffset = IntOffset(dstLeft.toInt(), dstTop.toInt()),
                                dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt())
                            )
                        }
                    }
                }
            }
        } else {
            // Connecting or Error Placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    if (connectionError != null) {
                        Icon(
                            imageVector = Icons.Rounded.CloudOff,
                            contentDescription = "Error",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(52.dp)
                        )
                        Text(
                            text = "Connection Failed: $connectionError",
                            color = Color(0xFFEF4444),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    connectionError = null
                                    reconnectTrigger++
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Retry Connection")
                            }
                            OutlinedButton(
                                onClick = onBack,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Back", color = Color.White)
                            }
                        }
                    } else {
                        CircularProgressIndicator(
                            color = Color(0xFF6366F1),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Connecting to Remote PC ($targetIp)...",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Side Floating Navigation Rail (Auto-closes after 5s, slides open on pull tab tap)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = isSidebarVisible && showQuickNav && !showFullDrawer,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .heightIn(max = 350.dp)
                    .shadow(16.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xD90F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            lastSidebarInteractionTime = System.currentTimeMillis()
                        }
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Back / Exit Button (Instant 1-Tap Navigation, Restores Portrait)
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Exit Remote Desktop",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Show Desktop (Win+D)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("win"); put("d") })
                            })
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.DesktopWindows,
                            contentDescription = "Show Desktop (Win+D)",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Switch App (Alt+Tab)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("alt"); put("tab") })
                            })
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Tab,
                            contentDescription = "Switch App (Alt+Tab)",
                            tint = Color(0xFFA78BFA),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Window / App Mode Switcher (Isolated app vs Full Desktop)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "get_windows") })
                            showWindowPickerSheet = true
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (targetMode == "window") Color(0xFF10B981).copy(alpha = 0.25f) else Color.Transparent,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Rounded.FilterFrames,
                            contentDescription = "Target Window / App",
                            tint = if (targetMode == "window") Color(0xFF34D399) else Color(0xFFFBBF24),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Windows Start Menu (Win)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "key_press")
                                put("key", "win")
                            })
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Window,
                            contentDescription = "Start Menu (Win)",
                            tint = Color(0xFF818CF8),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Touch Mode Toggle (Scroll vs Drag)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            touchStyle = if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) {
                                TouchInteractionStyle.DRAG_SELECT
                            } else {
                                TouchInteractionStyle.MOBILE_SCROLL
                            }
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) Color(0xFF6366F1).copy(alpha = 0.3f) else Color(0xFF38BDF8).copy(alpha = 0.3f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) Icons.Rounded.TouchApp else Icons.Rounded.PanTool,
                            contentDescription = "Touch / Scroll Mode",
                            tint = if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) Color(0xFF818CF8) else Color(0xFF38BDF8),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Windows Keyboard Toggle
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            showKeyboardSheet = true
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Keyboard,
                            contentDescription = "Windows Keyboard",
                            tint = Color(0xFFA78BFA),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap Audio Mute / Unmute
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            toggleAudio()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .background(
                                if (isAudioPlaying) Color(0xFF10B981).copy(alpha = 0.25f) else Color.Transparent,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isAudioPlaying) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeMute,
                            contentDescription = "PC Audio",
                            tint = if (isAudioPlaying) Color(0xFF34D399) else Color(0xFF94A3B8),
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    // 1-Tap FPS Toggle (35 <-> 45 <-> 60 FPS)
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                            .clickable {
                                triggerHaptic()
                                selectedFps = when (selectedFps) {
                                    35 -> 45
                                    45 -> 60
                                    else -> 35
                                }
                                selectedQuality = if (selectedFps == 60) 55 else 62
                                selectedScale = if (selectedFps == 60) 0.85f else 0.95f
                                sendAction(JSONObject().apply {
                                    put("type", "set_quality")
                                    put("fps", selectedFps)
                                    put("quality", selectedQuality)
                                    put("scale", selectedScale.toDouble())
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${currentFps.coerceAtLeast(1)}",
                            color = if (currentFps >= 30) Color(0xFF10B981) else Color(0xFFFBBF24),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // More Controls (Expands full drawer)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            showFullDrawer = true
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Rounded.MoreHoriz,
                            contentDescription = "More Controls",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Sleek Edge Pull Tab Handle (Visible when navigation rail is closed)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = !isSidebarVisible && showQuickNav && !showFullDrawer,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 0.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xD90F172A))
                    .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .clickable {
                        triggerHaptic()
                        isSidebarVisible = true
                        lastSidebarInteractionTime = System.currentTimeMillis()
                    }
                    .padding(vertical = 14.dp, horizontal = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ChevronLeft,
                    contentDescription = "Open Navigation Controls",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ---------------------------------------------------------------------
        // Floating Top Header / Indicator (Hides permanently after 5s)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = isHeaderVisible && !showFullDrawer,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            if (targetMode == "window") {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xD90F172A))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(20.dp))
                        .clickable {
                            triggerHaptic()
                            targetMode = "desktop"
                            targetHwnd = 0L
                            targetWindowTitle = "Full Desktop"
                            sendAction(JSONObject().apply {
                                put("type", "set_target")
                                put("mode", "desktop")
                                put("monitor", selectedMonitorId)
                            })
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(0xFF10B981), CircleShape)
                        )
                        Text(
                            text = "App: ${targetWindowTitle.take(24)}${if (targetWindowTitle.length > 24) "…" else ""}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Return to Full Desktop",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xB30F172A))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                        .clickable {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "get_windows") })
                            showWindowPickerSheet = true
                        }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(0xFF38BDF8), CircleShape)
                        )
                        Text(
                            text = "Display $selectedMonitorId • $frameResolution • ${currentFps} FPS",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Slide-in Extended Flyout Menu Drawer (When user taps More ⋯)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = showFullDrawer,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp)
                    .shadow(24.dp, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(Color(0xF80F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header with Close and Back
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Desktop Controls",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Exit", tint = Color(0xFFEF4444))
                            }
                            IconButton(
                                onClick = { showFullDrawer = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close Menu", tint = Color(0xFF94A3B8))
                            }
                        }
                    }

                    // Telemetry Card
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E293B))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ $currentFps FPS",
                            color = if (currentFps >= 30) Color(0xFF34D399) else Color(0xFFFBBF24),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = frameResolution,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Text(
                            text = targetIp,
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp
                        )
                    }

                    // Direct Text Typing Input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            placeholder = { Text("Type text to PC...", fontSize = 12.sp, color = Color(0xFF64748B)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        IconButton(
                            onClick = {
                                if (typedText.isNotEmpty()) {
                                    triggerHaptic()
                                    sendAction(JSONObject().apply {
                                        put("type", "type_text")
                                        put("text", typedText)
                                    })
                                    typedText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF6366F1), RoundedCornerShape(12.dp))
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send Text", tint = Color.White)
                        }
                    }

                    // Windows Special Keys & Shortcuts Row
                    Text("Windows Shortcuts", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            KeyPill(label = "⊞ Win", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "win") })
                            }
                            KeyPill(label = "Esc", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "esc") })
                            }
                            KeyPill(label = "Tab", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "tab") })
                            }
                            KeyPill(label = "Enter", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "enter") })
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            ShortcutPill(label = "⊞ Win+D", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().apply { put("win"); put("d") }) })
                            }
                            ShortcutPill(label = "Alt+Tab", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().apply { put("alt"); put("tab") }) })
                            }
                            ShortcutPill(label = "TaskMgr", modifier = Modifier.weight(1f)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().apply { put("ctrl"); put("shift"); put("esc") }) })
                            }
                        }
                    }

                    // FPS & Quality Presets
                    Text("Quality & FPS Preset", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresetChip(
                            label = "35 FPS",
                            subLabel = "Balanced",
                            isSelected = selectedFps == 35,
                            modifier = Modifier.weight(1f)
                        ) {
                            triggerHaptic()
                            selectedFps = 35
                            selectedQuality = 60
                            selectedScale = 0.9f
                            sendAction(JSONObject().apply {
                                put("type", "set_quality")
                                put("fps", 35)
                                put("quality", 60)
                                put("scale", 0.9)
                            })
                        }

                        PresetChip(
                            label = "45 FPS",
                            subLabel = "Smooth (HD)",
                            isSelected = selectedFps == 45,
                            modifier = Modifier.weight(1f)
                        ) {
                            triggerHaptic()
                            selectedFps = 45
                            selectedQuality = 62
                            selectedScale = 0.95f
                            sendAction(JSONObject().apply {
                                put("type", "set_quality")
                                put("fps", 45)
                                put("quality", 62)
                                put("scale", 0.95)
                            })
                        }

                        PresetChip(
                            label = "60 FPS",
                            subLabel = "Ultra Fast",
                            isSelected = selectedFps == 60,
                            modifier = Modifier.weight(1f)
                        ) {
                            triggerHaptic()
                            selectedFps = 60
                            selectedQuality = 55
                            selectedScale = 0.85f
                            sendAction(JSONObject().apply {
                                put("type", "set_quality")
                                put("fps", 60)
                                put("quality", 55)
                                put("scale", 0.85)
                            })
                        }
                    }

                    // Clipboard & Monitor Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "clipboard_get") })
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy PC Clip", fontSize = 11.sp, color = Color.White)
                        }

                        OutlinedButton(
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "get_windows") })
                                showFullDrawer = false
                                showWindowPickerSheet = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (targetMode == "window") Color(0xFF10B981) else Color(0xFF334155)
                            )
                        ) {
                            Icon(
                                Icons.Rounded.FilterFrames,
                                contentDescription = null,
                                tint = if (targetMode == "window") Color(0xFF34D399) else Color(0xFFFBBF24),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (targetMode == "window") "Window Mode" else "Target App",
                                fontSize = 11.sp,
                                color = Color.White
                            )
                        }

                        if (monitors.size > 1) {
                            OutlinedButton(
                                onClick = {
                                    triggerHaptic()
                                    val nextMon = if (selectedMonitorId >= monitors.size) 1 else selectedMonitorId + 1
                                    selectedMonitorId = nextMon
                                    sendAction(JSONObject().apply {
                                        put("type", "set_monitor")
                                        put("monitor", nextMon)
                                    })
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                            ) {
                                Icon(Icons.Rounded.Monitor, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Display $selectedMonitorId", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Windows Keyboard & Special Keys Bottom Sheet
        // ---------------------------------------------------------------------
        if (showKeyboardSheet) {
            ModalBottomSheet(
                onDismissRequest = { showKeyboardSheet = false },
                containerColor = Color(0xFF0F172A),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF64748B)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .padding(bottom = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header & Active Modifiers Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Windows PC Keyboard",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val anyModActive = isCtrlActive || isAltActive || isShiftActive || isWinActive
                            if (anyModActive) {
                                val activeNames = buildList {
                                    if (isCtrlActive) add("CTRL")
                                    if (isAltActive) add("ALT")
                                    if (isShiftActive) add("SHIFT")
                                    if (isWinActive) add("WIN")
                                }.joinToString(" + ")
                                Text(
                                    text = "Active combo: $activeNames + [Key]",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "Tap Shift, Alt, Ctrl to latch combos (e.g. Alt+Tab, Shift+A)",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        if (isCtrlActive || isAltActive || isShiftActive || isWinActive) {
                            TextButton(
                                onClick = {
                                    triggerHaptic()
                                    isCtrlActive = false
                                    isAltActive = false
                                    isShiftActive = false
                                    isWinActive = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reset Modifiers", color = Color(0xFFF87171), fontSize = 11.sp)
                            }
                        }
                    }

                    // Direct Interactive Text Box
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            placeholder = { Text("Type text to PC...", fontSize = 11.sp, color = Color(0xFF64748B)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        IconButton(
                            onClick = {
                                if (typedText.isNotEmpty()) {
                                    triggerHaptic()
                                    sendAction(JSONObject().apply {
                                        put("type", "type_text")
                                        put("text", typedText)
                                    })
                                    typedText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF6366F1), RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send Text", tint = Color.White)
                        }
                    }

                    // Quick Shortcuts Horizontal Scroll Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShortcutPill(label = "⇥ Alt+Tab", modifier = Modifier.width(90.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("alt"); put("tab") })
                            })
                        }
                        ShortcutPill(label = "⊞ Win+D", modifier = Modifier.width(85.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("win"); put("d") })
                            })
                        }
                        ShortcutPill(label = "Ctrl+C", modifier = Modifier.width(75.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("ctrl"); put("c") })
                            })
                        }
                        ShortcutPill(label = "Ctrl+V", modifier = Modifier.width(75.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("ctrl"); put("v") })
                            })
                        }
                        ShortcutPill(label = "Ctrl+Z", modifier = Modifier.width(75.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("ctrl"); put("z") })
                            })
                        }
                        ShortcutPill(label = "Ctrl+A", modifier = Modifier.width(75.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("ctrl"); put("a") })
                            })
                        }
                        ShortcutPill(label = "TaskMgr", modifier = Modifier.width(85.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("ctrl"); put("shift"); put("esc") })
                            })
                        }
                        ShortcutPill(label = "Alt+F4", modifier = Modifier.width(75.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply {
                                put("type", "hotkey")
                                put("keys", JSONArray().apply { put("alt"); put("f4") })
                            })
                        }
                    }

                    // Windows PC Modifier Keys Row (Latchable)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        KeyPill(label = "Esc", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("esc")
                        }
                        KeyPill(label = "Tab", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("tab")
                        }
                        KeyPill(
                            label = if (isCtrlActive) "Ctrl ●" else "Ctrl",
                            isActive = isCtrlActive,
                            activeColor = Color(0xFF6366F1),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            triggerHaptic()
                            isCtrlActive = !isCtrlActive
                        }
                        KeyPill(
                            label = if (isAltActive) "Alt ●" else "Alt",
                            isActive = isAltActive,
                            activeColor = Color(0xFF0284C7),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            triggerHaptic()
                            isAltActive = !isAltActive
                        }
                        KeyPill(
                            label = if (isShiftActive) "Shift ⇧" else "Shift",
                            isActive = isShiftActive,
                            activeColor = Color(0xFF10B981),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            triggerHaptic()
                            isShiftActive = !isShiftActive
                        }
                        KeyPill(
                            label = if (isWinActive) "⊞ Win ●" else "⊞ Win",
                            isActive = isWinActive,
                            activeColor = Color(0xFFF59E0B),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            triggerHaptic()
                            isWinActive = !isWinActive
                        }
                    }

                    // PC Numbers & Symbols Row
                    val numRow = listOf(
                        Pair("1", "!"), Pair("2", "@"), Pair("3", "#"), Pair("4", "$"),
                        Pair("5", "%"), Pair("6", "^"), Pair("7", "&"), Pair("8", "*"),
                        Pair("9", "("), Pair("0", ")"), Pair("-", "_"), Pair("=", "+")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        numRow.forEach { (norm, shifted) ->
                            val display = if (isShiftActive) shifted else norm
                            val sub = if (isShiftActive) norm else shifted
                            CharKeyPill(
                                primary = display,
                                secondary = sub,
                                modifier = Modifier.weight(1f)
                            ) {
                                sendCharInput(display[0])
                            }
                        }
                        KeyPill(label = "⌫", modifier = Modifier.weight(1.2f)) {
                            sendKeyWithModifiers("backspace")
                        }
                    }

                    // QWERTY Row 1
                    val qwertyRow1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p', '[', ']')
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        qwertyRow1.forEach { ch ->
                            val display = if (isShiftActive) ch.uppercaseChar().toString() else ch.toString()
                            CharKeyPill(
                                primary = display,
                                modifier = Modifier.weight(1f)
                            ) {
                                sendCharInput(display[0])
                            }
                        }
                    }

                    // QWERTY Row 2
                    val qwertyRow2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', ';', '\'')
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        qwertyRow2.forEach { ch ->
                            val display = if (isShiftActive) ch.uppercaseChar().toString() else ch.toString()
                            CharKeyPill(
                                primary = display,
                                modifier = Modifier.weight(1f)
                            ) {
                                sendCharInput(display[0])
                            }
                        }
                        KeyPill(label = "⏎ Enter", modifier = Modifier.weight(1.8f)) {
                            sendKeyWithModifiers("enter")
                        }
                    }

                    // QWERTY Row 3
                    val qwertyRow3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm', ',', '.', '/')
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        KeyPill(
                            label = "⇧",
                            isActive = isShiftActive,
                            activeColor = Color(0xFF10B981),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            triggerHaptic()
                            isShiftActive = !isShiftActive
                        }
                        qwertyRow3.forEach { ch ->
                            val display = if (isShiftActive) ch.uppercaseChar().toString() else ch.toString()
                            CharKeyPill(
                                primary = display,
                                modifier = Modifier.weight(1f)
                            ) {
                                sendCharInput(display[0])
                            }
                        }
                        KeyPill(label = "Del", modifier = Modifier.weight(1.2f)) {
                            sendKeyWithModifiers("delete")
                        }
                    }

                    // Bottom Navigation & Space Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        KeyPill(label = "Space", modifier = Modifier.weight(3f)) {
                            sendKeyWithModifiers("space")
                        }
                        KeyPill(label = "◀", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("left")
                        }
                        KeyPill(label = "▲", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("up")
                        }
                        KeyPill(label = "▼", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("down")
                        }
                        KeyPill(label = "▶", modifier = Modifier.weight(1f)) {
                            sendKeyWithModifiers("right")
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Isolated App / Window Picker Bottom Sheet
        // ---------------------------------------------------------------------
        if (showWindowPickerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showWindowPickerSheet = false },
                containerColor = Color(0xFF0F172A),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF64748B)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 28.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Select View / Target App",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (targetMode == "window") "Currently streaming: $targetWindowTitle" else "Currently streaming: Full Desktop",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        IconButton(
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "get_windows") })
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Refresh Windows",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Full Desktop Mode Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                triggerHaptic()
                                targetMode = "desktop"
                                targetHwnd = 0L
                                targetWindowTitle = "Full Desktop"
                                sendAction(JSONObject().apply {
                                    put("type", "set_target")
                                    put("mode", "desktop")
                                    put("monitor", selectedMonitorId)
                                })
                                showWindowPickerSheet = false
                            },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (targetMode == "desktop") Color(0xFF1E293B) else Color(0xFF131C2E)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (targetMode == "desktop") Color(0xFF38BDF8) else Color(0xFF334155)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF38BDF8).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.DesktopWindows,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Full PC Desktop (All Apps & Taskbar)",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Control entire PC with taskbar and multi-windows",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }

                            if (targetMode == "desktop") {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Active",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "ISOLATED APP WINDOWS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (activeWindows.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No other running app windows detected.\nTap refresh button above to update.",
                                color = Color(0xFF64748B),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        activeWindows.forEach { win ->
                            val isCurrent = (targetMode == "window" && targetHwnd == win.hwnd)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        triggerHaptic()
                                        targetMode = "window"
                                        targetHwnd = win.hwnd
                                        targetWindowTitle = win.title
                                        sendAction(JSONObject().apply {
                                            put("type", "set_target")
                                            put("mode", "window")
                                            put("hwnd", win.hwnd)
                                        })
                                        showWindowPickerSheet = false
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) Color(0xFF1E293B) else Color(0xFF131C2E)
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isCurrent) Color(0xFF10B981) else Color(0xFF334155)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFF6366F1).copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Window,
                                            contentDescription = null,
                                            tint = Color(0xFFA78BFA),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = win.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${win.width} × ${win.height} px • Isolated",
                                            fontSize = 10.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }

                                    if (isCurrent) {
                                        Icon(
                                            Icons.Rounded.CheckCircle,
                                            contentDescription = "Active",
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(20.dp)
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
}

@Composable
fun KeyPill(
    label: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeColor: Color = Color(0xFF0284C7),
    textColor: Color = Color.White,
    height: androidx.compose.ui.unit.Dp = 38.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) activeColor else Color(0xFF334155))
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) Color(0xFF38BDF8) else Color(0xFF475569),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold
        )
    }
}

@Composable
fun CharKeyPill(
    primary: String,
    secondary: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1E293B))
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (secondary != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = secondary,
                    color = Color(0xFF94A3B8),
                    fontSize = 8.sp,
                    lineHeight = 9.sp
                )
                Text(
                    text = primary,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 13.sp
                )
            }
        } else {
            Text(
                text = primary,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ShortcutPill(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF6366F1).copy(alpha = 0.2f))
            .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFFA5B4FC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PresetChip(
    label: String,
    subLabel: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFF6366F1).copy(alpha = 0.25f) else Color(0xFF1E293B))
            .border(1.dp, if (isSelected) Color(0xFF6366F1) else Color(0xFF334155), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = if (isSelected) Color(0xFF818CF8) else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(subLabel, color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
    }
}

