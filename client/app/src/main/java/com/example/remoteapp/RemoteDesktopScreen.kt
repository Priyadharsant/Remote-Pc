package com.example.remoteapp

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
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
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class MonitorInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isPrimary: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDesktopScreen(
    targetIp: String,
    password: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Force Landscape on Enter, Restore Previous on Exit
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }

    // Intercept hardware/gesture Back button
    BackHandler {
        onBack()
    }

    // Live Frame and Connection State
    var isConnected by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var reconnectTrigger by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var monitors by remember { mutableStateOf<List<MonitorInfo>>(emptyList()) }
    var selectedMonitorId by remember { mutableIntStateOf(1) }

    // Stream Presets (High performance defaults: 35 FPS, 55 quality, 0.8 scale)
    var selectedFps by remember { mutableIntStateOf(35) }
    var selectedQuality by remember { mutableIntStateOf(55) }
    var selectedScale by remember { mutableFloatStateOf(0.85f) }

    // Live Metrics
    var fpsCounter by remember { mutableIntStateOf(0) }
    var currentFps by remember { mutableIntStateOf(0) }
    var frameResolution by remember { mutableStateOf("1920x1080") }

    // Side Drawer / Flyout Menu & Panels
    var showSideMenu by remember { mutableStateOf(false) }
    var showKeyboardSheet by remember { mutableStateOf(false) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }

    // Canvas & Destination Fit Geometry
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var dstLeft by remember { mutableFloatStateOf(0f) }
    var dstTop by remember { mutableFloatStateOf(0f) }
    var dstWidth by remember { mutableFloatStateOf(0f) }
    var dstHeight by remember { mutableFloatStateOf(0f) }

    // WebSocket Reference & Media Player
    var webSocketRef by remember { mutableStateOf<WebSocket?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<MediaPlayer?>(null) }

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

    // Audio stream toggle helper
    val toggleAudio: () -> Unit = {
        if (isAudioPlaying) {
            mediaPlayerRef?.stop()
            mediaPlayerRef?.release()
            mediaPlayerRef = null
            isAudioPlaying = false
            Toast.makeText(context, "PC Audio muted", Toast.LENGTH_SHORT).show()
        } else {
            try {
                val audioUrl = "http://$targetIp:5055/audio/stream"
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(audioUrl)
                    setOnPreparedListener {
                        start()
                        isAudioPlaying = true
                        Toast.makeText(context, "🔊 PC Audio streaming live", Toast.LENGTH_SHORT).show()
                    }
                    setOnErrorListener { _, _, _ ->
                        isAudioPlaying = false
                        Toast.makeText(context, "Could not start audio stream", Toast.LENGTH_SHORT).show()
                        true
                    }
                    prepareAsync()
                }
                mediaPlayerRef = mp
            } catch (e: Exception) {
                Toast.makeText(context, "Audio error: ${e.message}", Toast.LENGTH_SHORT).show()
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

    // Connect WebSocket with 15s timeout
    DisposableEffect(targetIp, password, reconnectTrigger) {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val encodedPass = try { URLEncoder.encode(password, "UTF-8") } catch (_: Exception) { password }
        val wsUrl = "ws://$targetIp:5055/ws/remote-desktop?password=$encodedPass&token=$encodedPass"
        val request = Request.Builder().url(wsUrl).build()

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                connectionError = null
                Log.d("RemoteDesktop", "Connected to Remote Desktop server")

                // Immediately request configured FPS & quality
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
                            frameResolution = "${w}x${h}"
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                connectionError = t.message ?: "Connection lost"
                Log.e("RemoteDesktop", "WebSocket error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }
        })

        webSocketRef = ws

        onDispose {
            ws.cancel()
            mediaPlayerRef?.stop()
            mediaPlayerRef?.release()
            mediaPlayerRef = null
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

            // Calculate true laptop 16:9 aspect ratio letterbox fit
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

            // Real Touch Screen Pointer Input: Tap to click, double tap, hold right click, drag to drag, 2-finger scroll
            var lastTapTime by remember { mutableLongStateOf(0L) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(dstLeft, dstTop, dstWidth, dstHeight, selectedMonitorId) {
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
                            val downTime = System.currentTimeMillis()

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
                                    val dist = (currentPos - startPos).getDistance()

                                    // Long press detection: held > 500ms without dragging -> Right Click
                                    if (!isDragging && !isLongPress && System.currentTimeMillis() - downTime > 500 && dist < 20f) {
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
                                    } else if (!isLongPress && dist > 14f) {
                                        // Real touch drag: trigger mouse_down then stream mouse_move
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
                                } else if (activePointers.size == 2) {
                                    // Two-finger precision scroll wheel
                                    val p1 = activePointers[0]
                                    val p2 = activePointers[1]
                                    val dragY = (p1.position.y - p1.previousPosition.y + p2.position.y - p2.previousPosition.y) / 2f
                                    val dragX = (p1.position.x - p1.previousPosition.x + p2.position.x - p2.previousPosition.x) / 2f
                                    if (Math.abs(dragY) > 2f || Math.abs(dragX) > 2f) {
                                        sendAction(JSONObject().apply {
                                            put("type", "mouse_scroll")
                                            put("dy", (dragY * 1.6).toDouble())
                                            put("dx", (-dragX * 1.6).toDouble())
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
                            } else if (!isLongPress && System.currentTimeMillis() - downTime < 450) {
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
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(dstLeft.toInt(), dstTop.toInt()),
                        dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt())
                    )
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
        // Floating Side Menu Edge Pill (Subtle, unobtrusive)
        // ---------------------------------------------------------------------
        if (!showSideMenu) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .shadow(12.dp, RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(Color(0xD90F172A))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .clickable {
                        triggerHaptic()
                        showSideMenu = true
                    }
                    .padding(horizontal = 10.dp, vertical = 14.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Open Remote Menu",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "$currentFps",
                        color = if (currentFps >= 25) Color(0xFF10B981) else Color(0xFFFBBF24),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ---------------------------------------------------------------------
        // Slide-in Flyout Side Menu Drawer (Appears only on click)
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = showSideMenu,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp)
                    .shadow(24.dp, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(Color(0xF50F172A))
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
                                text = "Remote Controls",
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
                                onClick = { showSideMenu = false },
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
                            color = if (currentFps >= 25) Color(0xFF34D399) else Color(0xFFFBBF24),
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

                    // Main Quick Tools Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Keyboard Button
                        Button(
                            onClick = {
                                triggerHaptic()
                                showKeyboardSheet = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                        ) {
                            Icon(Icons.Rounded.Keyboard, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Keyboard", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Audio Toggle Button
                        Button(
                            onClick = {
                                triggerHaptic()
                                toggleAudio()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isAudioPlaying) Color(0xFF10B981) else Color(0xFF334155)
                            )
                        ) {
                            Icon(
                                if (isAudioPlaying) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeMute,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (isAudioPlaying) "Audio ON" else "Audio OFF", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
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
                            Icon(Icons.Rounded.Send, contentDescription = "Send Text", tint = Color.White)
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
                            selectedQuality = 55
                            selectedScale = 0.85f
                            sendAction(JSONObject().apply {
                                put("type", "set_quality")
                                put("fps", 35)
                                put("quality", 55)
                                put("scale", 0.85)
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
                            selectedQuality = 50
                            selectedScale = 0.75f
                            sendAction(JSONObject().apply {
                                put("type", "set_quality")
                                put("fps", 60)
                                put("quality", 50)
                                put("scale", 0.75)
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
                                    Toast.makeText(context, "Display $nextMon", Toast.LENGTH_SHORT).show()
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
