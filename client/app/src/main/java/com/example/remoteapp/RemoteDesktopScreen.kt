package com.example.remoteapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

enum class DesktopControlMode {
    DIRECT_TOUCH,
    VIRTUAL_TRACKPAD
}

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

    // Screen State
    var isConnected by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var monitors by remember { mutableStateOf<List<MonitorInfo>>(emptyList()) }
    var selectedMonitorId by remember { mutableIntStateOf(1) }

    // Control & Display Settings
    var controlMode by remember { mutableStateOf(DesktopControlMode.DIRECT_TOUCH) }
    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Streaming Quality
    var selectedFps by remember { mutableIntStateOf(30) }
    var selectedQuality by remember { mutableIntStateOf(65) }
    var selectedScale by remember { mutableFloatStateOf(0.75f) }

    // Live Metrics
    var fpsCounter by remember { mutableIntStateOf(0) }
    var currentFps by remember { mutableIntStateOf(0) }
    var frameResolution by remember { mutableStateOf("1920x1080") }

    // UI Panels
    var showHud by remember { mutableStateOf(true) }
    var showKeyboardSheet by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var isAudioPlaying by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }

    // WebSocket Reference
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
                vibrator?.vibrate(30)
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

    // Connect WebSocket
    DisposableEffect(targetIp, password) {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val encodedPass = try { URLEncoder.encode(password, "UTF-8") } catch (_: Exception) { password }
        val wsUrl = "ws://$targetIp:5055/ws/remote-desktop?password=$encodedPass&token=$encodedPass"
        val request = Request.Builder().url(wsUrl).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                connectionError = null
                Log.d("RemoteDesktop", "Connected to Remote Desktop server")
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
                // Format: [4B Magic: 'RD01'][2B MonId][2B Width][2B Height][2B FrameId][Payload]
                if (data.size > 12) {
                    val magic = String(data, 0, 4)
                    if (magic == "RD01") {
                        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                        val monId = buffer.getShort(4).toInt()
                        val w = buffer.getShort(6).toInt()
                        val h = buffer.getShort(8).toInt()

                        val jpegBytes = data.copyOfRange(12, data.size)
                        val bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
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
        }
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // ---------------------------------------------------------------------
        // Interactive Screen Stream Canvas
        // ---------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(controlMode, zoomScale, panOffset, selectedMonitorId) {
                    if (controlMode == DesktopControlMode.DIRECT_TOUCH) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                triggerHaptic()
                                val normX = ((tapOffset.x - panOffset.x) / (canvasSize.width * zoomScale)).coerceIn(0f, 1f)
                                val normY = ((tapOffset.y - panOffset.y) / (canvasSize.height * zoomScale)).coerceIn(0f, 1f)
                                sendAction(
                                    JSONObject().apply {
                                        put("type", "mouse_click")
                                        put("button", "left")
                                        put("x", normX.toDouble())
                                        put("y", normY.toDouble())
                                        put("is_normalized", true)
                                        put("monitor", selectedMonitorId)
                                    }
                                )
                            },
                            onDoubleTap = { tapOffset ->
                                triggerHaptic()
                                val normX = ((tapOffset.x - panOffset.x) / (canvasSize.width * zoomScale)).coerceIn(0f, 1f)
                                val normY = ((tapOffset.y - panOffset.y) / (canvasSize.height * zoomScale)).coerceIn(0f, 1f)
                                sendAction(
                                    JSONObject().apply {
                                        put("type", "mouse_click")
                                        put("button", "left")
                                        put("clicks", 2)
                                        put("x", normX.toDouble())
                                        put("y", normY.toDouble())
                                        put("is_normalized", true)
                                        put("monitor", selectedMonitorId)
                                    }
                                )
                            },
                            onLongPress = { tapOffset ->
                                triggerHaptic()
                                val normX = ((tapOffset.x - panOffset.x) / (canvasSize.width * zoomScale)).coerceIn(0f, 1f)
                                val normY = ((tapOffset.y - panOffset.y) / (canvasSize.height * zoomScale)).coerceIn(0f, 1f)
                                sendAction(
                                    JSONObject().apply {
                                        put("type", "mouse_click")
                                        put("button", "right")
                                        put("x", normX.toDouble())
                                        put("y", normY.toDouble())
                                        put("is_normalized", true)
                                        put("monitor", selectedMonitorId)
                                    }
                                )
                                Toast.makeText(context, "Right Click", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
                .pointerInput(controlMode, zoomScale) {
                    if (controlMode == DesktopControlMode.DIRECT_TOUCH) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newZoom = (zoomScale * zoom).coerceIn(1.0f, 4.0f)
                            zoomScale = newZoom
                            if (newZoom > 1.05f) {
                                panOffset += pan
                            } else {
                                panOffset = Offset.Zero
                            }
                        }
                    } else {
                        // Virtual Trackpad relative movements
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            sendAction(
                                JSONObject().apply {
                                    put("type", "mouse_relative")
                                    put("dx", dragAmount.x.toDouble())
                                    put("dy", dragAmount.y.toDouble())
                                    put("sensitivity", 1.6)
                                }
                            )
                        }
                    }
                }
        ) {
            val bmp = currentBitmap
            if (bmp != null) {
                val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    translate(left = panOffset.x, top = panOffset.y) {
                        scale(zoomScale, pivot = Offset.Zero) {
                            drawImage(
                                image = imageBitmap,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                        }
                    }
                }
            } else {
                // Loading or Connecting Placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF6366F1),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (connectionError != null) "Connection Error: $connectionError" else "Connecting to Remote PC Screen...",
                            color = if (connectionError != null) Color(0xFFEF4444) else Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Top Floating HUD & Status Bar
        // ---------------------------------------------------------------------
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(20.dp))
                    .background(Color(0xE61E293B), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Back Button & Live Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Exit",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConnected) "LIVE • $currentFps FPS" else "CONNECTING",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "$targetIp ($frameResolution)",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                }

                // Action Buttons Tray
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Hybrid Mode Switcher (Touch vs Trackpad)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            controlMode = if (controlMode == DesktopControlMode.DIRECT_TOUCH) {
                                DesktopControlMode.VIRTUAL_TRACKPAD
                            } else {
                                DesktopControlMode.DIRECT_TOUCH
                            }
                            Toast.makeText(
                                context,
                                if (controlMode == DesktopControlMode.DIRECT_TOUCH) "Touch Mode: Tap anywhere to click" else "Trackpad Mode: Slide to steer cursor",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (controlMode == DesktopControlMode.DIRECT_TOUCH) Color(0xFF6366F1).copy(alpha = 0.25f) else Color(0xFF38BDF8).copy(alpha = 0.25f),
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (controlMode == DesktopControlMode.DIRECT_TOUCH) Icons.Rounded.TouchApp else Icons.Rounded.Mouse,
                            contentDescription = "Toggle Control Mode",
                            tint = if (controlMode == DesktopControlMode.DIRECT_TOUCH) Color(0xFF818CF8) else Color(0xFF38BDF8)
                        )
                    }

                    // Monitor Selector (if multiple monitors detected)
                    if (monitors.size > 1) {
                        IconButton(
                            onClick = {
                                triggerHaptic()
                                val nextMon = if (selectedMonitorId >= monitors.size) 1 else selectedMonitorId + 1
                                selectedMonitorId = nextMon
                                sendAction(
                                    JSONObject().apply {
                                        put("type", "set_monitor")
                                        put("monitor", nextMon)
                                    }
                                )
                                Toast.makeText(context, "Switched to Display $nextMon", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Monitor,
                                contentDescription = "Switch Display",
                                tint = Color(0xFF38BDF8)
                            )
                        }
                    }

                    // Audio Stream Toggle
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            toggleAudio()
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (isAudioPlaying) Color(0xFF10B981).copy(alpha = 0.25f) else Color.Transparent,
                                CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isAudioPlaying) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeMute,
                            contentDescription = "PC Audio",
                            tint = if (isAudioPlaying) Color(0xFF34D399) else Color(0xFF94A3B8)
                        )
                    }

                    // Clipboard Sync (Pull PC clipboard)
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            sendAction(JSONObject().apply { put("type", "clipboard_get") })
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentPaste,
                            contentDescription = "Fetch PC Clipboard",
                            tint = Color(0xFFFBBF24)
                        )
                    }

                    // Virtual Windows Keyboard Sheet Toggle
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            showKeyboardSheet = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Keyboard,
                            contentDescription = "Windows Keyboard",
                            tint = Color(0xFFA78BFA)
                        )
                    }

                    // Quality & FPS Dialog
                    IconButton(
                        onClick = {
                            triggerHaptic()
                            showQualityDialog = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Quality Settings",
                            tint = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Bottom Controls for Virtual Trackpad Mode
        // ---------------------------------------------------------------------
        if (controlMode == DesktopControlMode.VIRTUAL_TRACKPAD) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(24.dp))
                        .background(Color(0xF01E293B), RoundedCornerShape(24.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            triggerHaptic()
                            sendAction(
                                JSONObject().apply {
                                    put("type", "mouse_click")
                                    put("button", "left")
                                }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                    ) {
                        Text("Left Click", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            triggerHaptic()
                            sendAction(
                                JSONObject().apply {
                                    put("type", "mouse_click")
                                    put("button", "left")
                                    put("clicks", 2)
                                }
                            )
                        },
                        modifier = Modifier
                            .weight(0.9f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569))
                    ) {
                        Text("2x Click", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            triggerHaptic()
                            sendAction(
                                JSONObject().apply {
                                    put("type", "mouse_click")
                                    put("button", "right")
                                }
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                    ) {
                        Text("Right Click", color = Color.White, fontWeight = FontWeight.Bold)
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
                containerColor = Color(0xFF1E293B),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF64748B)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header & Text Send Box
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            placeholder = { Text("Type text to type on PC...", color = Color(0xFF64748B), fontSize = 13.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color(0xFF334155)
                            )
                        )

                        Button(
                            onClick = {
                                if (typedText.isNotEmpty()) {
                                    triggerHaptic()
                                    sendAction(
                                        JSONObject().apply {
                                            put("type", "text_input")
                                            put("text", typedText)
                                        }
                                    )
                                    typedText = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Send", fontWeight = FontWeight.Bold)
                        }
                    }

                    // Windows Special Keys Row 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("esc", "tab", "win", "ctrl", "alt", "shift").forEach { key ->
                            KeyPill(
                                label = key.uppercase(),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    triggerHaptic()
                                    sendAction(JSONObject().apply { put("type", "key_press"); put("key", key) })
                                }
                            )
                        }
                    }

                    // Windows Shortcuts Row 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShortcutPill(
                            label = "Alt+Tab",
                            modifier = Modifier.weight(1.2f),
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().put("alt").put("tab")) })
                            }
                        )
                        ShortcutPill(
                            label = "Win+D",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().put("win").put("d")) })
                            }
                        )
                        ShortcutPill(
                            label = "TaskMgr",
                            modifier = Modifier.weight(1.3f),
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "hotkey"); put("keys", JSONArray().put("ctrl").put("shift").put("esc")) })
                            }
                        )
                        KeyPill(
                            label = "DEL",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "del") })
                            }
                        )
                        KeyPill(
                            label = "ENTER",
                            modifier = Modifier.weight(1.2f),
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "key_press"); put("key", "enter") })
                            }
                        )
                    }

                    // Clipboard Sync Action Row 3
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                triggerHaptic()
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val phoneClip = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (phoneClip.isNotEmpty()) {
                                    sendAction(JSONObject().apply { put("type", "clipboard_set"); put("text", phoneClip) })
                                    Toast.makeText(context, "Sent phone clipboard to PC", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paste to PC", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                triggerHaptic()
                                sendAction(JSONObject().apply { put("type", "clipboard_get") })
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy from PC", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ---------------------------------------------------------------------
        // Quality & FPS Settings Dialog
        // ---------------------------------------------------------------------
        if (showQualityDialog) {
            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                containerColor = Color(0xFF1E293B),
                title = {
                    Text("Stream Quality & Performance", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Select preset streaming profile:", color = Color(0xFF94A3B8), fontSize = 13.sp)

                        QualityOption(
                            title = "Smooth (60 FPS, 1080p)",
                            subtitle = "Best for fast Wi-Fi networks (High Quality)",
                            isSelected = selectedFps == 60,
                            onClick = {
                                selectedFps = 60
                                selectedQuality = 75
                                selectedScale = 0.9f
                                sendAction(JSONObject().apply {
                                    put("type", "set_quality")
                                    put("fps", 60)
                                    put("quality", 75)
                                    put("scale", 0.9)
                                })
                                showQualityDialog = false
                            }
                        )

                        QualityOption(
                            title = "Balanced (30 FPS, 720p)",
                            subtitle = "Recommended for optimal battery & latency",
                            isSelected = selectedFps == 30,
                            onClick = {
                                selectedFps = 30
                                selectedQuality = 65
                                selectedScale = 0.75f
                                sendAction(JSONObject().apply {
                                    put("type", "set_quality")
                                    put("fps", 30)
                                    put("quality", 65)
                                    put("scale", 0.75)
                                })
                                showQualityDialog = false
                            }
                        )

                        QualityOption(
                            title = "Eco (20 FPS, 540p)",
                            subtitle = "Lowest network bandwidth & minimal lag",
                            isSelected = selectedFps == 20,
                            onClick = {
                                selectedFps = 20
                                selectedQuality = 50
                                selectedScale = 0.5f
                                sendAction(JSONObject().apply {
                                    put("type", "set_quality")
                                    put("fps", 20)
                                    put("quality", 50)
                                    put("scale", 0.5)
                                })
                                showQualityDialog = false
                            }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text("Close", color = Color(0xFF6366F1), fontWeight = FontWeight.Bold)
                    }
                }
            )
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
fun QualityOption(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0xFF6366F1).copy(alpha = 0.2f) else Color(0xFF334155).copy(alpha = 0.4f))
            .border(
                1.dp,
                if (isSelected) Color(0xFF6366F1) else Color(0xFF334155),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
        if (isSelected) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(20.dp))
        }
    }
}
