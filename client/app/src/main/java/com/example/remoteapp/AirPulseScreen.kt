package com.example.remoteapp

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

private const val TAG = "AirPulse"

data class AirPulseSelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long
)

data class AirPulseDiscoveredPeer(
    val name: String,
    val ip: String,
    val port: Int,
    val os: String,
    val receiveMode: Boolean,
    val pin: String = ""
)

data class AirPulseTransferState(
    val direction: String,
    val currentFile: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val transferredBytes: Long,
    val totalBytes: Long,
    val speedStr: String,
    val percent: Int,
    val status: String,
    val message: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirPulseScreen(
    onBack: () -> Unit,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Mode: 'send' vs 'receive' (Option A - Dedicated Receive Radar)
    var activeMode by remember { mutableStateOf("send") }

    // Receive Mode State
    var receivePin by remember {
        mutableStateOf(String.format(Locale.US, "%06d", (100000..999999).random()))
    }
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var incomingRequestJson by remember { mutableStateOf<JSONObject?>(null) }
    var incomingRequestCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var isCopiedPin by remember { mutableStateOf(false) }

    // Sender Mode State
    var selectedFiles by remember { mutableStateOf<List<AirPulseSelectedFile>>(emptyList()) }
    var discoveredPeers by remember { mutableStateOf<List<AirPulseDiscoveredPeer>>(emptyList()) }
    var isScanningPeers by remember { mutableStateOf(false) }
    var activeTransfer by remember { mutableStateOf<AirPulseTransferState?>(null) }
    var activeCall by remember { mutableStateOf<Call?>(null) }

    // Manual PIN Dialog
    var showPinDialog by remember { mutableStateOf(false) }
    var manualPinInput by remember { mutableStateOf("") }
    var manualIpInput by remember { mutableStateOf("") }
    var targetPeerForPin by remember { mutableStateOf<AirPulseDiscoveredPeer?>(null) }

    val okHttpClient = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // Helper: Resolve local IP
    fun getLocalIp(): String {
        return try {
            val interfaces = Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    // Helper: Generate ZXing QR Bitmap
    fun generateQrCode(pin: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val ip = getLocalIp()
                val payload = JSONObject().apply {
                    put("airpulse", true)
                    put("dev", Build.MODEL)
                    put("ip", ip)
                    put("port", 5056)
                    put("pin", pin)
                    put("os", "android")
                }.toString()

                val writer = QRCodeWriter()
                val matrix = writer.encode(payload, BarcodeFormat.QR_CODE, 400, 400)
                val w = matrix.width
                val h = matrix.height
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
                withContext(Dispatchers.Main) {
                    qrBitmap = bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "QR generation failed", e)
            }
        }
    }

    // Receive Mode Lifecycle: sync with VideoStreamServer
    LaunchedEffect(activeMode, receivePin) {
        if (activeMode == "receive") {
            generateQrCode(receivePin)
            VideoStreamServer.getInstance()?.let { srv ->
                srv.airPulseReceiveMode = true
                srv.airPulsePin = receivePin
                srv.onAirPulseRequest = { req, cb ->
                    scope.launch(Dispatchers.Main) {
                        incomingRequestJson = req
                        incomingRequestCallback = cb
                    }
                }
            }
        } else {
            VideoStreamServer.getInstance()?.let { srv ->
                srv.airPulseReceiveMode = false
                srv.onAirPulseRequest = null
            }
            incomingRequestJson = null
        }
    }

    // SAF Multi-File Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            val list = mutableListOf<AirPulseSelectedFile>()
            for (u in uris) {
                var name = "File_${System.currentTimeMillis()}"
                var size = 0L
                context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }
                list.add(AirPulseSelectedFile(u, name, size))
            }
            selectedFiles = selectedFiles + list
        }
    }

    // Discover Nearby Peers via UDP 9999
    fun scanPeers() {
        if (isScanningPeers) return
        isScanningPeers = true
        scope.launch(Dispatchers.IO) {
            val peers = mutableListOf<AirPulseDiscoveredPeer>()
            val seenIps = mutableSetOf<String>()
            val localIp = getLocalIp()
            seenIps.add(localIp)
            seenIps.add("127.0.0.1")

            var sendSocket: DatagramSocket? = null
            var listenSocket: DatagramSocket? = null
            try {
                sendSocket = DatagramSocket().apply { broadcast = true }
                val msg = "AIRPULSE_DISCOVER".toByteArray()
                val packet = DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), 9999)
                sendSocket.send(packet)

                listenSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1200
                }
                val buf = ByteArray(1024)
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 1500) {
                    try {
                        val rp = DatagramPacket(buf, buf.size)
                        listenSocket.receive(rp)
                        val ip = rp.address.hostAddress ?: continue
                        if (seenIps.contains(ip)) continue

                        val text = String(rp.data, 0, rp.length).trim()
                        if (text.startsWith("AIRPULSE_PEER|")) {
                            val parts = text.split("|")
                            val name = parts.getOrNull(1) ?: "Peer ($ip)"
                            val port = parts.getOrNull(2)?.toIntOrNull() ?: 5055
                            val os = parts.getOrNull(3) ?: "unknown"
                            val rec = parts.getOrNull(4) == "1"
                            val pin = parts.getOrNull(5) ?: ""
                            peers.add(AirPulseDiscoveredPeer(name, ip, port, os, rec, pin))
                            seenIps.add(ip)
                        } else if (text.startsWith("REMOTE_SERVER|")) {
                            val parts = text.split("|")
                            val name = parts.getOrNull(1) ?: "PC ($ip)"
                            val port = parts.getOrNull(2)?.toIntOrNull() ?: 5055
                            peers.add(AirPulseDiscoveredPeer(name, ip, port, "windows", false))
                            seenIps.add(ip)
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                sendSocket?.close()
                listenSocket?.close()
                withContext(Dispatchers.Main) {
                    discoveredPeers = peers
                    isScanningPeers = false
                }
            }
        }
    }

    LaunchedEffect(activeMode) {
        if (activeMode == "send") {
            scanPeers()
        }
    }

    // Send Files Worker
    fun sendFilesToTarget(target: AirPulseDiscoveredPeer, pinCode: String = "") {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "Select at least one file to beam", Toast.LENGTH_SHORT).show()
            return
        }

        val totalBytes = selectedFiles.sumOf { it.size }
        val count = selectedFiles.size

        activeTransfer = AirPulseTransferState(
            direction = "Phone ➔ ${target.name}",
            currentFile = selectedFiles[0].name,
            fileIndex = 1,
            totalFiles = count,
            transferredBytes = 0L,
            totalBytes = totalBytes,
            speedStr = "Connecting...",
            percent = 0,
            status = "requesting",
            message = "Waiting for ${target.name}..."
        )

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Send transfer request
                val reqPayload = JSONObject().apply {
                    put("sender_name", Build.MODEL)
                    put("sender_os", "android")
                    put("sender_ip", getLocalIp())
                    put("pin", if (pinCode.isNotEmpty()) pinCode else target.pin)
                    put("total_size", totalBytes)
                    put("total_count", count)
                    val arr = JSONArray()
                    for (f in selectedFiles) {
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("size", f.size)
                        })
                    }
                    put("files", arr)
                }.toString()

                val reqUrl = "http://${target.ip}:${target.port}/airpulse/request"
                val postReq = Request.Builder()
                    .url(reqUrl)
                    .post(reqPayload.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val call = okHttpClient.newCall(postReq)
                withContext(Dispatchers.Main) { activeCall = call }

                var status = "pending"
                var remoteTxId: String? = null
                var reqId: String? = null

                call.execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    status = json.optString("status", "error")
                    remoteTxId = if (json.has("transfer_id")) json.getString("transfer_id") else null
                    reqId = if (json.has("request_id")) json.getString("request_id") else null
                }

                // If pending, poll check-request
                if (status == "pending" && reqId != null) {
                    val pollStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - pollStart < 40000L) {
                        delay(1200)
                        if (activeTransfer?.status == "cancelled") return@launch
                        val chkUrl = "http://${target.ip}:${target.port}/airpulse/check-request?request_id=$reqId"
                        val chkReq = Request.Builder().url(chkUrl).get().build()
                        try {
                            okHttpClient.newCall(chkReq).execute().use { cr ->
                                val cb = JSONObject(cr.body?.string() ?: "")
                                val st = cb.optString("status")
                                if (st == "accepted") {
                                    status = "accepted"
                                    remoteTxId = if (cb.has("transfer_id")) cb.getString("transfer_id") else null
                                } else if (st == "declined") {
                                    status = "declined"
                                }
                            }
                        } catch (_: Exception) {}
                        if (status == "accepted" || status == "declined") break
                    }
                }

                if (status != "accepted") {
                    withContext(Dispatchers.Main) {
                        activeTransfer = activeTransfer?.copy(
                            status = if (status == "declined") "declined" else "error",
                            message = if (status == "declined") "Receiver declined transfer" else "Connection timed out"
                        )
                    }
                    return@launch
                }

                // 2. Stream each file
                var transferredTotal = 0L
                for (idx in selectedFiles.indices) {
                    if (activeTransfer?.status == "cancelled") break
                    val item = selectedFiles[idx]

                    withContext(Dispatchers.Main) {
                        activeTransfer = activeTransfer?.copy(
                            currentFile = item.name,
                            fileIndex = idx + 1,
                            status = "in_progress",
                            message = "Streaming file ${idx + 1} of $count..."
                        )
                    }

                    val encodedName = URLEncoder.encode(item.name, "UTF-8")
                    val uploadUrl = if (target.port == 5056) {
                        "http://${target.ip}:${target.port}/airpulse/upload?filename=$encodedName&file_index=${idx + 1}&total_files=$count&file_size=${item.size}"
                    } else {
                        "http://${target.ip}:${target.port}/airpulse/upload?transfer_id=${remoteTxId ?: "tx"}&filename=$encodedName&file_index=${idx + 1}&total_files=$count&file_size=${item.size}"
                    }

                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L

                    val bodyStream = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                        override fun contentLength() = item.size
                        override fun writeTo(sink: BufferedSink) {
                            context.contentResolver.openInputStream(item.uri)?.use { input ->
                                val buf = ByteArray(32 * 1024)
                                var r: Int
                                var fileTransferred = 0L
                                while (input.read(buf).also { r = it } != -1) {
                                    sink.write(buf, 0, r)
                                    fileTransferred += r
                                    transferredTotal += r
                                    val now = System.currentTimeMillis()
                                    val dt = now - lastTime
                                    if (dt >= 200) {
                                        val speedBytesPerSec = ((transferredTotal - lastBytes) * 1000L) / dt
                                        val speedStr = if (speedBytesPerSec > 1024 * 1024) {
                                            String.format(Locale.US, "%.1f MB/s", speedBytesPerSec / 1048576.0)
                                        } else {
                                            String.format(Locale.US, "%d KB/s", speedBytesPerSec / 1024)
                                        }
                                        val pct = if (totalBytes > 0) ((transferredTotal * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                        lastTime = now
                                        lastBytes = transferredTotal

                                        scope.launch(Dispatchers.Main) {
                                            activeTransfer = activeTransfer?.copy(
                                                transferredBytes = transferredTotal,
                                                speedStr = speedStr,
                                                percent = pct
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val upReq = Request.Builder().url(uploadUrl).post(bodyStream).build()
                    val upCall = okHttpClient.newCall(upReq)
                    withContext(Dispatchers.Main) { activeCall = upCall }

                    upCall.execute().use { _ -> }
                }

                withContext(Dispatchers.Main) {
                    activeTransfer = activeTransfer?.copy(
                        status = "completed",
                        percent = 100,
                        message = "All files beamed successfully!"
                    )
                    Toast.makeText(context, "AirPulse beam complete!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    activeTransfer = activeTransfer?.copy(
                        status = "error",
                        message = "Failed: ${e.message}"
                    )
                }
            }
        }
    }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF06B6D4).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirPulse Drop", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 16.sp)
                            Text("Zero-Setup P2P Beam", fontSize = 11.sp, color = Color(0xFF10B981))
                        }
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
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Mode Switcher Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0B0F19))
                    .border(1.dp, AppTheme.CardBorderSubtle, RoundedCornerShape(16.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Send Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activeMode == "send") Color(0xFF06B6D4) else Color.Transparent)
                        .clickable { activeMode = "send" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            tint = if (activeMode == "send") Color(0xFF0B0F19) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Send Files",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeMode == "send") Color(0xFF0B0F19) else Color(0xFF94A3B8)
                        )
                    }
                }

                // Receive Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (activeMode == "receive") Color(0xFF10B981) else Color.Transparent)
                        .clickable { activeMode = "receive" }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            tint = if (activeMode == "receive") Color(0xFF0B0F19) else Color(0xFF94A3B8),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Receive Radar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (activeMode == "receive") Color(0xFF0B0F19) else Color(0xFF94A3B8)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // =================================================================
            // RECEIVER MODE (Option A: Radar Sonar, PIN, QR Code)
            // =================================================================
            if (activeMode == "receive") {
                val infiniteTransition = rememberInfiniteTransition(label = "RadarSonar")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "PulseScale"
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 0.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "PulseAlpha"
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Pulsing Sonar Graphic
                                Box(
                                    modifier = Modifier
                                        .size(140.dp)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawCircle(
                                            color = Color(0xFF10B981).copy(alpha = pulseAlpha),
                                            radius = (size.minDimension / 2f) * pulseScale,
                                            style = Stroke(width = 3.dp.toPx())
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(30.dp))
                                    }
                                }

                                Text("Radar Receiver Active", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary)
                                Text(
                                    "Nearby devices can detect you or connect via PIN",
                                    fontSize = 12.sp,
                                    color = AppTheme.TextMuted,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(Modifier.height(16.dp))

                                // 6-Digit PIN Display Card
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
                                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("AIRPULSE PAIRING PIN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                                            Text(
                                                "${receivePin.take(3)} ${receivePin.drop(3)}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.Black,
                                                color = Color(0xFF10B981)
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(receivePin))
                                                isCopiedPin = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(
                                                if (isCopiedPin) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(if (isCopiedPin) "Copied" else "Copy", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))

                                // Dynamic QR Code Box
                                Text("OR SCAN TO BEAM INSTANTLY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextMuted, letterSpacing = 1.sp)
                                Spacer(Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .size(200.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color.White)
                                        .padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (qrBitmap != null) {
                                        Image(
                                            bitmap = qrBitmap!!,
                                            contentDescription = "AirPulse QR",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        CircularProgressIndicator(color = Color(0xFF10B981))
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Saves in: Downloads/RemotePC", fontSize = 11.sp, color = AppTheme.TextMuted)
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // SENDER MODE (Select files, radar list, PIN, beam)
            // =================================================================
            if (activeMode == "send") {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // File Selection Box
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF06B6D4).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (selectedFiles.isEmpty()) "Tap to Select Files to Beam" else "Add More Files",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = AppTheme.TextPrimary
                                )
                                Text("Photos, 4K videos, documents, APKs", fontSize = 11.sp, color = AppTheme.TextMuted)
                            }
                        }
                    }

                    // Selected Files Queue
                    if (selectedFiles.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "QUEUED FILES (${selectedFiles.size} • ${formatFileSize(selectedFiles.sumOf { it.size })})",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.TextMuted,
                                    letterSpacing = 1.sp
                                )
                                TextButton(onClick = { selectedFiles = emptyList() }) {
                                    Text("Clear All", fontSize = 11.sp, color = Color(0xFFF43F5E))
                                }
                            }
                        }

                        items(selectedFiles) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.Description, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatFileSize(file.size), fontSize = 10.sp, color = AppTheme.TextMuted)
                                    }
                                    IconButton(
                                        onClick = { selectedFiles = selectedFiles.filter { it.uri != file.uri } },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = AppTheme.TextMuted, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Nearby Devices Radar List
                    item {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "NEARBY AIRPULSE RECEIVERS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.TextMuted,
                                letterSpacing = 1.sp
                            )
                            Row {
                                TextButton(onClick = {
                                    targetPeerForPin = null
                                    showPinDialog = true
                                }) {
                                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF06B6D4))
                                    Spacer(Modifier.width(4.dp))
                                    Text("PIN", fontSize = 11.sp, color = Color(0xFF06B6D4), fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { scanPeers() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Scan", tint = Color(0xFF06B6D4), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    if (discoveredPeers.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Searching for Devices...", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 13.sp)
                                    Text("Make sure the target PC or phone is in Receiver Mode", fontSize = 11.sp, color = AppTheme.TextMuted, textAlign = TextAlign.Center)
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            targetPeerForPin = null
                                            showPinDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFF0B0F19), modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Connect with 6-Digit PIN", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        items(discoveredPeers) { peer ->
                            val isWin = peer.os == "windows"
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                                border = if (peer.receiveMode) BorderStroke(1.5.dp, Color(0xFF10B981)) else BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isWin) Color(0xFF06B6D4).copy(alpha = 0.2f) else Color(0xFF10B981).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            if (isWin) Icons.Rounded.Laptop else Icons.Rounded.Smartphone,
                                            contentDescription = null,
                                            tint = if (isWin) Color(0xFF06B6D4) else Color(0xFF10B981),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(peer.name, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 13.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(peer.ip, fontSize = 11.sp, color = AppTheme.TextMuted, fontFamily = FontFamily.Monospace)
                                            if (peer.receiveMode) {
                                                Spacer(Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                                ) {
                                                    Text("RADAR READY", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF10B981))
                                                }
                                            }
                                        }
                                    }

                                    Button(
                                        onClick = { sendFilesToTarget(peer) },
                                        enabled = selectedFiles.isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color(0xFF0B0F19), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Beam", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // INCOMING TRANSFER REQUEST DIALOG (Receiver side)
            // =================================================================
            if (incomingRequestJson != null) {
                val req = incomingRequestJson!!
                val sName = req.optString("sender_name", "Remote Device")
                val fCount = req.optInt("total_count", 1)
                val sBytes = req.optLong("total_size", 0L)
                val sIp = req.optString("sender_ip", "")

                AlertDialog(
                    onDismissRequest = {
                        incomingRequestCallback?.invoke(false)
                        incomingRequestJson = null
                    },
                    containerColor = AppTheme.CardDarkElevated,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Sensors, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Incoming AirPulse Beam", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column {
                            Text(
                                "$sName wants to beam $fCount file(s) to this device.",
                                color = AppTheme.TextPrimary,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Total Payload: ${formatFileSize(sBytes)}\nFrom: $sIp\nSaving to: Downloads/RemotePC",
                                color = AppTheme.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                incomingRequestCallback?.invoke(true)
                                incomingRequestJson = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Accept & Save", fontWeight = FontWeight.Bold, color = Color(0xFF0B0F19))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                incomingRequestCallback?.invoke(false)
                                incomingRequestJson = null
                            }
                        ) {
                            Text("Decline", color = Color(0xFFF43F5E))
                        }
                    }
                )
            }

            // =================================================================
            // 6-DIGIT PIN ENTRY DIALOG
            // =================================================================
            if (showPinDialog) {
                AlertDialog(
                    onDismissRequest = { showPinDialog = false },
                    containerColor = AppTheme.CardDarkElevated,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Direct PIN Pairing", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (targetPeerForPin == null) {
                                OutlinedTextField(
                                    value = manualIpInput,
                                    onValueChange = { manualIpInput = it },
                                    label = { Text("Receiver IP (e.g. 192.168.1.10)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = manualPinInput,
                                onValueChange = { manualPinInput = it.take(6) },
                                label = { Text("6-Digit PIN") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val target = targetPeerForPin ?: AirPulseDiscoveredPeer(
                                    name = "Device ($manualIpInput)",
                                    ip = manualIpInput.trim(),
                                    port = 5055,
                                    os = "unknown",
                                    receiveMode = true
                                )
                                showPinDialog = false
                                sendFilesToTarget(target, manualPinInput.trim())
                            },
                            enabled = manualPinInput.length >= 4 && (targetPeerForPin != null || manualIpInput.isNotEmpty()),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Beam Files", fontWeight = FontWeight.Bold, color = Color(0xFF0B0F19))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPinDialog = false }) {
                            Text("Cancel", color = AppTheme.TextMuted)
                        }
                    }
                )
            }

            // =================================================================
            // ACTIVE TRANSFER HUD OVERLAY
            // =================================================================
            activeTransfer?.let { tx ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF06B6D4).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(tx.currentFile, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${tx.direction} • ⚡ ${tx.speedStr}", fontSize = 10.sp, color = Color(0xFF06B6D4), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            if (tx.status == "in_progress" || tx.status == "requesting") {
                                OutlinedButton(
                                    onClick = {
                                        activeCall?.cancel()
                                        activeTransfer = activeTransfer?.copy(status = "cancelled", message = "Cancelled")
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Cancel", fontSize = 10.sp, color = Color(0xFFF43F5E))
                                }
                            } else {
                                TextButton(onClick = { activeTransfer = null }) {
                                    Text("Dismiss", fontSize = 10.sp, color = AppTheme.TextMuted)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { (tx.percent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF06B6D4),
                            trackColor = Color(0xFF1E293B)
                        )

                        Spacer(Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${formatFileSize(tx.transferredBytes)} / ${formatFileSize(tx.totalBytes)}", fontSize = 10.sp, color = AppTheme.TextMuted)
                            Text("${tx.percent}%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF06B6D4))
                        }
                    }
                }
            }
        }
    }
}
