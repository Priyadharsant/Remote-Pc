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

    // Stream Presets (Crisp 45 FPS, 60 quality, 0.9 scale)
    var selectedFps by remember { mutableIntStateOf(45) }
    var selectedQuality by remember { mutableIntStateOf(60) }
    var selectedScale by remember { mutableFloatStateOf(0.9f) }

    // Touch interaction mode
    var touchStyle by remember { mutableStateOf(TouchInteractionStyle.MOBILE_SCROLL) }

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
                    // Skip the 44-byte WAV header safely
                    val headerBytes = ByteArray(44)
                    var headerRead = 0
                    while (headerRead < 44) {
                        val r = inputStream.read(headerBytes, headerRead, 44 - headerRead)
                        if (r == -1) break
                        headerRead += r
                    }

                    val sampleRate = 44100
                    val minBufSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufferSize = minBufSize.coerceAtLeast(4096)

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
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize * 2)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    audioTrackRef = track
                    track.play()

                    val buffer = ByteArray(2048)
                    while (isActive) {
                        val bytesRead = inputStream.read(buffer, 0, buffer.size)
                        if (bytesRead == -1) break
                        if (bytesRead > 0) {
                            track.write(buffer, 0, bytesRead)
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
                ws.send(json.toString())
            } catch (e: Exception) {
                Log.e("RemoteDesktop", "Failed to send action: ${e.message}")
            }
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
                    .pointerInput(selectedMonitorId, touchStyle) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPos = down.position

                            // Verify touch occurred inside active desktop canvas
                            val inBounds = startPos.x in dstLeft..(dstLeft + dstWidth) &&
                                    startPos.y in dstTop..(dstTop + dstHeight)
                            if (!inBounds || dstWidth <= 0f || dstHeight <= 0f) {
                                return@awaitEachGesture
                            }

                            var isDragging = false
                            var isLongPress = false
                            var hasScrolled = false
                            val downTime = System.currentTimeMillis()
                            var lastPos = startPos

                            val normX = ((startPos.x - dstLeft) / dstWidth).coerceIn(0f, 1f)
                            val normY = ((startPos.y - dstTop) / dstHeight).coerceIn(0f, 1f)

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
                                    } else if (!isLongPress && distFromStart > 12f) {
                                        if (touchStyle == TouchInteractionStyle.MOBILE_SCROLL) {
                                            // Single finger mobile-style scroll!
                                            hasScrolled = true
                                            if (abs(deltaY) > 0.5f || abs(deltaX) > 0.5f) {
                                                sendAction(JSONObject().apply {
                                                    put("type", "mouse_scroll")
                                                    // Swipe up scrolls page down (natural mobile scrolling)
                                                    put("dy", (deltaY * 2.2).toDouble())
                                                    put("dx", (-deltaX * 2.2).toDouble())
                                                })
                                                currentChange.consume()
                                            }
                                        } else {
                                            // Drag & select mode
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
                                            val curNormX = ((currentPos.x - dstLeft) / dstWidth).coerceIn(0f, 1f)
                                            val curNormY = ((currentPos.y - dstTop) / dstHeight).coerceIn(0f, 1f)
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
                                    // Two-finger pinch to zoom or two-finger scroll
                                    val p1 = activePointers[0]
                                    val p2 = activePointers[1]
                                    val dragY = (p1.position.y - p1.previousPosition.y + p2.position.y - p2.previousPosition.y) / 2f
                                    val dragX = (p1.position.x - p1.previousPosition.x + p2.position.x - p2.previousPosition.x) / 2f
                                    if (abs(dragY) > 2f || abs(dragX) > 2f) {
                                        sendAction(JSONObject().apply {
                                            put("type", "mouse_scroll")
                                            put("dy", (dragY * 1.8).toDouble())
                                            put("dx", (-dragX * 1.8).toDouble())
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
        // Side Floating Navigation Rail (Easy 1-Tap Access on Right Edge)
        // ---------------------------------------------------------------------
        if (showQuickNav && !showFullDrawer) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .heightIn(max = 350.dp)
                    .shadow(16.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xD90F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
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
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Windows Virtual Keyboard",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Windows Special Keys Row 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyPill(label = "Esc", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "esc") })
                        }
                        KeyPill(label = "Tab", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "tab") })
                        }
                        KeyPill(label = "Ctrl", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "ctrl") })
                        }
                        KeyPill(label = "Alt", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "alt") })
                        }
                        KeyPill(label = "Shift", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "shift") })
                        }
                        KeyPill(label = "⊞ Win", modifier = Modifier.weight(1f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "win") })
                        }
                    }

                    // Windows Navigation Row 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyPill(label = "Backspace", modifier = Modifier.weight(1.5f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "backspace") })
                        }
                        KeyPill(label = "Delete", modifier = Modifier.weight(1.2f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "delete") })
                        }
                        KeyPill(label = "Enter", modifier = Modifier.weight(1.5f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "enter") })
                        }
                        KeyPill(label = "Space", modifier = Modifier.weight(2f)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "space") })
                        }
                    }

                    // Windows Arrow Keys
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        KeyPill(label = "◀", modifier = Modifier.width(60.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "left") })
                        }
                        Spacer(Modifier.width(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            KeyPill(label = "▲", modifier = Modifier.width(60.dp)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "up") })
                            }
                            KeyPill(label = "▼", modifier = Modifier.width(60.dp)) {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "down") })
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        KeyPill(label = "▶", modifier = Modifier.width(60.dp)) {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "key_press"); put("key", "right") })
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
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF334155))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
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

