package com.example.remoteapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    val receiveMode: Boolean
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
    val message: String,
    val id: String = ""
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

    // Mode: 'send' vs 'receive'
    var activeMode by remember { mutableStateOf("send") }

    // Sender QR state
    var showSenderQrDialog by remember { mutableStateOf(false) }
    var showReceiveQrDialog by remember { mutableStateOf(false) }
    var senderQrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var pendingTargetAfterFilePick by remember { mutableStateOf<AirPulseDiscoveredPeer?>(null) }

    // Receive Mode State
    var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var incomingRequestJson by remember { mutableStateOf<JSONObject?>(null) }
    var incomingRequestCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var incomingTransferToken by remember { mutableStateOf("") }
    var outgoingTransferToken by remember { mutableStateOf("") }
    var outgoingTargetIp by remember { mutableStateOf("") }
    var outgoingTargetPort by remember { mutableIntStateOf(5055) }

    // Sender Mode State
    var selectedFiles by remember { mutableStateOf<List<AirPulseSelectedFile>>(emptyList()) }
    var discoveredPeers by remember { mutableStateOf<List<AirPulseDiscoveredPeer>>(emptyList()) }
    var isScanningPeers by remember { mutableStateOf(false) }
    var activeTransfer by remember { mutableStateOf<AirPulseTransferState?>(null) }
    var activeCall by remember { mutableStateOf<Call?>(null) }
    val activeCalls = remember { mutableStateListOf<Call>() }
    var receiverBeaconJob by remember { mutableStateOf<Job?>(null) }

    // Manual PIN Dialog
    var showPinDialog by remember { mutableStateOf(false) }
    var showAirPulseQrScanner by remember { mutableStateOf(false) }
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

    // Helper: Collect broadcast addresses for all active network interfaces
    fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.interfaceAddresses) {
                    val bcast = ia.broadcast
                    if (bcast != null && bcast is java.net.Inet4Address) {
                        list.add(bcast)
                    }
                }
            }
        } catch (_: Exception) {}
        try {
            list.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        return list.distinct()
    }

    // Helper: Generate Sender QR Bitmap
    fun generateSenderQrCode() {
        scope.launch(Dispatchers.IO) {
            try {
                val ip = getLocalIp()
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                var ssid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: ""
                if (ssid == "<unknown ssid>") ssid = ""
                val payload = "REMOTEPC_AIRPULSE_SENDER|${Build.MODEL.take(40)}|$ip|5056|$ssid|"
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
                    senderQrBitmap = bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sender QR generation failed", e)
            }
        }
    }

    // Helper: Generate ZXing QR Bitmap
    fun generateQrCode() {
        scope.launch(Dispatchers.IO) {
            try {
                val ip = getLocalIp()
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                var ssid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: ""
                if (ssid == "<unknown ssid>") ssid = ""
                val payload = "REMOTEPC|${Build.MODEL.take(40)}|$ip|5056||$ssid"

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

    // Helper: Show Incoming AirPulse Transfer Notification
    fun showIncomingNotification(senderName: String, fileCount: Int, totalBytes: Long) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = "airpulse_transfers"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "AirPulse Transfers",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for incoming AirPulse file transfers"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                2001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Incoming AirPulse Beam")
                .setContentText("$senderName wants to send $fileCount file(s) (${formatFileSize(totalBytes)})")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(2001, notif)
        } catch (e: Exception) {
            Log.e(TAG, "Notification post failed", e)
        }
    }

    // Helper: Dismiss Notification
    fun dismissIncomingNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(2001)
        } catch (_: Exception) {}
    }

    // Receiver availability is advertised continuously while on this Quick Share screen.
    LaunchedEffect(Unit) {
        receiverBeaconJob?.cancel()
        receiverBeaconJob = null
        val srv = VideoStreamServer.getInstance()
        generateQrCode()
        srv?.let { server ->
            server.airPulseReceiveMode = true
            server.onAirPulseRequest = { req, cb ->
                scope.launch(Dispatchers.Main) {
                    incomingRequestJson = req
                    incomingRequestCallback = cb
                    val sName = req.optString("sender_name", "Nearby Device")
                    val fCount = req.optInt("total_count", 1)
                    val sBytes = req.optLong("total_size", 0L)
                    showIncomingNotification(sName, fCount, sBytes)
                }
            }
        }
        receiverBeaconJob = scope.launch(Dispatchers.IO) {
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wm?.createMulticastLock("airpulse_rx_lock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (_: Exception) {}

            val bcasts = getBroadcastAddresses()
            while (isActive && srv?.airPulseReceiveMode == true) {
                try {
                    val packetText = "AIRPULSE_PEER|${Build.MODEL.take(64)}|5056|android|1"
                    val bytes = packetText.toByteArray()
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        for (targetAddr in bcasts) {
                            try {
                                socket.send(DatagramPacket(bytes, bytes.size, targetAddr, 9999))
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) { }
                delay(1800)
            }

            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            receiverBeaconJob?.cancel()
            VideoStreamServer.getInstance()?.let { server ->
                server.airPulseReceiveMode = false
                server.onAirPulseRequest = null
            }
        }
    }

    var sendFilesRef by remember { mutableStateOf<((AirPulseDiscoveredPeer) -> Unit)?>(null) }

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
            val target = pendingTargetAfterFilePick
            if (target != null) {
                pendingTargetAfterFilePick = null
                sendFilesRef?.invoke(target)
            }
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

            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wm?.createMulticastLock("airpulse_scan_lock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }
            } catch (_: Exception) {}

            var sendSocket: DatagramSocket? = null
            var listenSocket: DatagramSocket? = null
            try {
                sendSocket = DatagramSocket().apply { broadcast = true }
                val msg = "AIRPULSE_DISCOVER".toByteArray()
                val bcasts = getBroadcastAddresses()
                for (bcast in bcasts) {
                    try {
                        val packet = DatagramPacket(msg, msg.size, bcast, 9999)
                        sendSocket.send(packet)
                    } catch (_: Exception) {}
                }

                listenSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1200
                }
                val buf = ByteArray(1024)
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 1800) {
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
                            if (rec) {
                                peers.add(AirPulseDiscoveredPeer(name, ip, port, os, true))
                                seenIps.add(ip)
                            }
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
                try {
                    if (multicastLock?.isHeld == true) multicastLock.release()
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    discoveredPeers = peers
                    isScanningPeers = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        scanPeers()
        while (isActive) {
            delay(5000)
            scanPeers()
        }
    }

    // Send Files Worker
    fun sendFilesToTarget(target: AirPulseDiscoveredPeer, directMode: Boolean = false) {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(context, "Select files to beam to ${target.name}", Toast.LENGTH_SHORT).show()
            pendingTargetAfterFilePick = target
            filePickerLauncher.launch(arrayOf("*/*"))
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
            message = if (directMode) "Connecting directly via QR…" else "Waiting for ${target.name}..."
        )

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Send transfer request
                val reqPayload = JSONObject().apply {
                    put("sender_name", Build.MODEL)
                    put("sender_os", "android")
                    put("sender_ip", getLocalIp())
                    put("total_size", totalBytes)
                    put("total_count", count)
                    put("direct", directMode)
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
                var transferToken: String? = null
                var reqId: String? = null

                call.execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    status = json.optString("status", "error")
                    remoteTxId = if (json.has("transfer_id")) json.getString("transfer_id") else null
                    transferToken = if (json.has("transfer_token")) json.getString("transfer_token") else null
                    reqId = if (json.has("request_id")) json.getString("request_id") else null
                }

                // If pending, poll check-request
                if (status == "pending" && reqId != null) {
                    val pollStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - pollStart < 110000L) {
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
                                    transferToken = if (cb.has("transfer_token")) cb.getString("transfer_token") else null
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

                withContext(Dispatchers.Main) {
                    activeTransfer = activeTransfer?.copy(id = remoteTxId.orEmpty())
                    outgoingTransferToken = transferToken.orEmpty()
                    outgoingTargetIp = target.ip
                    outgoingTargetPort = target.port
                }
                // Stream at most four selected files concurrently; each request uses a bounded buffer.
                if (remoteTxId.isNullOrBlank() || transferToken.isNullOrBlank()) {
                    throw IOException("Receiver did not issue transfer authorization")
                }
                val transferredTotal = AtomicLong(0L)
                var speedSampleBytes = 0L
                var speedSampleAt = System.currentTimeMillis()
                val progressLock = Any()
                coroutineScope {
                    selectedFiles.mapIndexed { idx, item ->
                        async(Dispatchers.IO) {
                            if (activeTransfer?.status == "cancelled") return@async
                            withContext(Dispatchers.Main) {
                                activeTransfer = activeTransfer?.copy(currentFile = item.name, fileIndex = idx + 1,
                                    status = "in_progress", message = "Sending ${idx + 1} of $count files…")
                            }
                            val encodedName = URLEncoder.encode(item.name, "UTF-8")
                            val encodedId = URLEncoder.encode(remoteTxId!!, "UTF-8")
                            val encodedToken = URLEncoder.encode(transferToken!!, "UTF-8")
                            val uploadUrl = "http://${target.ip}:${target.port}/airpulse/upload?transfer_id=$encodedId&token=$encodedToken&filename=$encodedName&file_index=${idx + 1}&total_files=$count&file_size=${item.size}"
                            val bodyStream = object : RequestBody() {
                                override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                                override fun contentLength() = item.size
                                override fun writeTo(sink: BufferedSink) {
                                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                                        val buf = ByteArray(256 * 1024)
                                        var n: Int
                                        while (input.read(buf).also { n = it } != -1) {
                                            if (activeTransfer?.status == "cancelled") throw IOException("Transfer cancelled")
                                            sink.write(buf, 0, n)
                                            val current = transferredTotal.addAndGet(n.toLong())
                                            val now = System.currentTimeMillis()
                                            synchronized(progressLock) {
                                                val dt = now - speedSampleAt
                                                if (dt >= 250L) {
                                                    val speed = ((current - speedSampleBytes) * 1000L) / dt
                                                    speedSampleAt = now; speedSampleBytes = current
                                                    val speedText = if (speed >= 1024L * 1024L) String.format(Locale.US, "%.1f MB/s", speed / 1048576.0) else String.format(Locale.US, "%d KB/s", speed / 1024)
                                                    val pct = if (totalBytes > 0) ((current * 100) / totalBytes).toInt().coerceIn(0, 99) else 0
                                                    scope.launch(Dispatchers.Main) { activeTransfer = activeTransfer?.copy(transferredBytes = current, speedStr = speedText, percent = pct) }
                                                }
                                            }
                                        }
                                    } ?: throw IOException("Cannot open ${item.name}")
                                }
                            }
                            val upReq = Request.Builder().url(uploadUrl).post(bodyStream).build()
                            val upCall = okHttpClient.newCall(upReq)
                            withContext(Dispatchers.Main) { activeCall = upCall; activeCalls.add(upCall) }
                            try {
                                upCall.execute().use { response ->
                                    if (!response.isSuccessful) throw IOException("${item.name}: receiver returned HTTP ${response.code}")
                                    val ack = JSONObject(response.body?.string() ?: "{}")
                                    if (ack.optString("status") != "ok" || ack.optLong("bytes", -1) != item.size) throw IOException("${item.name}: receiver did not confirm the complete file")
                                }
                            } finally { withContext(Dispatchers.Main) { activeCalls.remove(upCall) } }
                        }
                    }.awaitAll()
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
                    if (activeTransfer?.status != "cancelled") activeTransfer = activeTransfer?.copy(
                        status = "error", message = "Failed: ${e.message}"
                    )
                }
            }
        }
    }
    sendFilesRef = { sendFilesToTarget(it) }

    LaunchedEffect(activeTransfer?.id, activeTransfer?.direction) {
        val transfer = activeTransfer
        if (transfer != null && transfer.id.isNotBlank() && transfer.direction.contains("This phone")) {
            while (activeTransfer?.status == "in_progress") {
                try {
                    val req = Request.Builder().url("http://127.0.0.1:5056/airpulse/transfer-status?transfer_id=${URLEncoder.encode(transfer.id, "UTF-8")}").get().build()
                    val body = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() } }
                    val state = JSONObject(body)
                    if (state.optString("status") == "not_found") break
                    val speed = state.optDouble("speed_mb", 0.0)
                    val speedText = if (speed >= 1.0) String.format(Locale.US, "%.1f MB/s", speed) else String.format(Locale.US, "%d KB/s", (speed * 1024).toInt())
                    activeTransfer = activeTransfer?.copy(
                        currentFile = state.optString("current_file", transfer.currentFile),
                        fileIndex = state.optInt("file_index", 1),
                        transferredBytes = state.optLong("transferred_bytes", 0L),
                        totalBytes = state.optLong("total_bytes", transfer.totalBytes),
                        percent = state.optInt("percent", 0),
                        speedStr = speedText,
                        status = state.optString("status", "in_progress"),
                        message = state.optString("message", "Receiving files")
                    )
                    if (state.optString("status") == "completed") break
                } catch (_: Exception) { }
                delay(450)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "RadarSonar")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAlpha"
    )

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF06B6D4).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("AirPulse Quick Share", fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 16.sp)
                            Text("Ready to share & receive on Wi-Fi", fontSize = 11.sp, color = Color(0xFF10B981))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = AppTheme.TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { scanPeers() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Scan Peers", tint = Color(0xFF06B6D4))
                    }
                    IconButton(onClick = {
                        generateQrCode()
                        showReceiveQrDialog = true
                    }) {
                        Icon(Icons.Rounded.QrCode, contentDescription = "Receive QR", tint = AppTheme.TextPrimary)
                    }
                    IconButton(onClick = { showAirPulseQrScanner = true }) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan QR", tint = AppTheme.TextPrimary)
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




            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // =================================================================
                // 1. QUICK SHARE RADAR CARD (Always active & discoverable)
                // =================================================================
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                        border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.35f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Pulsing Radar Rings with Central Phone Node
                            Box(
                                modifier = Modifier
                                    .size(130.dp)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(
                                        color = Color(0xFF06B6D4).copy(alpha = pulseAlpha),
                                        radius = (size.minDimension / 2f) * pulseScale,
                                        style = Stroke(width = 3.dp.toPx())
                                    )
                                    drawCircle(
                                        color = Color(0xFF10B981).copy(alpha = (pulseAlpha * 0.7f).coerceIn(0f, 1f)),
                                        radius = (size.minDimension / 2f) * ((pulseScale + 0.35f) % 1.0f),
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF06B6D4).copy(alpha = 0.2f))
                                        .border(2.dp, Color(0xFF06B6D4), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Smartphone,
                                        contentDescription = null,
                                        tint = Color(0xFF06B6D4),
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                Build.MODEL,
                                fontWeight = FontWeight.ExtraBold,
                                color = AppTheme.TextPrimary,
                                fontSize = 16.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Quick Share Active • ${getLocalIp()}:5056",
                                    fontSize = 11.sp,
                                    color = Color(0xFF10B981),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // =================================================================
                // 2. QUICK ACTION BAR (Add Files, Photos, IP Beam)
                // =================================================================
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1.2f),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                        ) {
                            Icon(Icons.Rounded.AttachFile, contentDescription = null, tint = Color(0xFF0B0F19), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Select Files", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("image/*", "video/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                            border = BorderStroke(1.dp, AppTheme.CardBorder),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 10.dp)
                        ) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Media", color = AppTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showPinDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.CardDarkElevated),
                            border = BorderStroke(1.dp, AppTheme.CardBorder),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(0.9f),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Dialpad, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("IP Beam", color = AppTheme.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                    }
                }

                // =================================================================
                // 3. SELECTED FILES TRAY (Deck of items to beam)
                // =================================================================
                if (selectedFiles.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.FolderZip, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${selectedFiles.size} file(s) selected • ${formatFileSize(selectedFiles.sumOf { it.size })}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = AppTheme.TextPrimary
                                        )
                                    }
                                    TextButton(
                                        onClick = { selectedFiles = emptyList() },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("Clear", color = Color(0xFFF43F5E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    selectedFiles.take(4).forEachIndexed { idx, file ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(AppTheme.BackgroundDark)
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.InsertDriveFile, contentDescription = null, tint = AppTheme.TextMuted, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                file.name,
                                                fontSize = 11.sp,
                                                color = AppTheme.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                formatFileSize(file.size),
                                                fontSize = 10.sp,
                                                color = AppTheme.TextMuted,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            IconButton(
                                                onClick = {
                                                    selectedFiles = selectedFiles.toMutableList().also { it.removeAt(idx) }
                                                },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                    if (selectedFiles.size > 4) {
                                        Text(
                                            "+ ${selectedFiles.size - 4} more files...",
                                            fontSize = 10.sp,
                                            color = AppTheme.TextMuted,
                                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =================================================================
                // 4. NEARBY DEVICES (1-Tap Share Nodes)
                // =================================================================
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Nearby Devices",
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.TextPrimary,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF06B6D4).copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "${discoveredPeers.size} Found",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF06B6D4)
                                )
                            }
                        }

                        if (isScanningPeers) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF06B6D4)
                            )
                        }
                    }
                }

                if (discoveredPeers.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated.copy(alpha = 0.6f)),
                            border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Rounded.WifiTethering,
                                    contentDescription = null,
                                    tint = AppTheme.TextMuted,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "Looking for nearby devices...",
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.TextPrimary,
                                    fontSize = 13.sp
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Open AirPulse on your Ubuntu PC or another phone on the same Wi-Fi network to beam instantly.",
                                    fontSize = 11.sp,
                                    color = AppTheme.TextMuted,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(discoveredPeers) { peer ->
                        val isWin = peer.os == "windows"
                        val isLinux = peer.os == "linux"
                        val isMac = peer.os == "macos"
                        val isDesktop = isWin || isLinux || isMac
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sendFilesToTarget(peer)
                                },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isDesktop) Color(0xFF06B6D4).copy(alpha = 0.2f) else Color(0xFF10B981).copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        if (isDesktop) Icons.Rounded.Laptop else Icons.Rounded.Smartphone,
                                        contentDescription = null,
                                        tint = if (isDesktop) Color(0xFF06B6D4) else Color(0xFF10B981),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peer.name, fontWeight = FontWeight.Bold, color = AppTheme.TextPrimary, fontSize = 14.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                        Text(peer.ip, fontSize = 11.sp, color = AppTheme.TextMuted, fontFamily = FontFamily.Monospace)
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("QUICK SHARE", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Color(0xFF10B981))
                                        }
                                    }
                                }

                                Button(
                                    onClick = { sendFilesToTarget(peer) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, tint = Color(0xFF0B0F19), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (selectedFiles.isEmpty()) "Tap to Pick" else "Beam",
                                        color = Color(0xFF0B0F19),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
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
                val manifest = req.optJSONArray("files")
                val fileSummary = (0 until minOf(manifest?.length() ?: 0, 6)).joinToString("\n") { index ->
                    val file = manifest?.optJSONObject(index)
                    "• ${file?.optString("name", "File") ?: "File"}  ·  ${formatFileSize(file?.optLong("size", 0L) ?: 0L)}"
                }

                AlertDialog(
                    onDismissRequest = {
                        dismissIncomingNotification()
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
                                "Files:\n$fileSummary${if (fCount > 6) "\n… and ${fCount - 6} more" else ""}\n\nTotal: ${formatFileSize(sBytes)}\nFrom: $sIp\nSaving to: Downloads/RemotePC",
                                color = AppTheme.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                dismissIncomingNotification()
                                val acceptedReq = incomingRequestJson
                                val id = acceptedReq?.optString("airpulse_transfer_id").orEmpty()
                                incomingTransferToken = acceptedReq?.optString("airpulse_transfer_token").orEmpty()
                                val files = acceptedReq?.optJSONArray("files")
                                val firstName = files?.optJSONObject(0)?.optString("name") ?: "Incoming files"
                                activeTransfer = AirPulseTransferState(
                                    direction = "${acceptedReq?.optString("sender_name", "Nearby device")} → This phone",
                                    currentFile = firstName,
                                    fileIndex = 1,
                                    totalFiles = acceptedReq?.optInt("total_count", files?.length() ?: 1) ?: 1,
                                    transferredBytes = 0L,
                                    totalBytes = acceptedReq?.optLong("total_size", 0L) ?: 0L,
                                    speedStr = "Waiting for sender…",
                                    percent = 0,
                                    status = "in_progress",
                                    message = "Receiving approved files",
                                    id = id
                                )
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
                                dismissIncomingNotification()
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
                            Text("Connect by IP address", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (targetPeerForPin == null) {
                                OutlinedTextField(
                                    value = manualIpInput,
                                    onValueChange = { manualIpInput = it },
                                    label = { Text("Receiver IP (e.g. 192.168.1.10:5056)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val entered = manualIpInput.trim()
                                val splitAt = entered.lastIndexOf(':')
                                val explicitPort = splitAt > 0 && entered.substring(splitAt + 1).toIntOrNull() != null
                                val address = if (explicitPort) entered.substring(0, splitAt) else entered
                                val port = if (explicitPort) entered.substring(splitAt + 1).toIntOrNull() ?: 5055 else 5055
                                val target = targetPeerForPin ?: AirPulseDiscoveredPeer(
                                    name = "Device ($entered)",
                                    ip = address.removePrefix("[").removeSuffix("]"),
                                    port = port.coerceIn(1, 65535),
                                    os = "unknown",
                                    receiveMode = true
                                )
                                showPinDialog = false
                                sendFilesToTarget(target)
                            },
                            enabled = targetPeerForPin != null || manualIpInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Send request", fontWeight = FontWeight.Bold, color = Color(0xFF0B0F19))
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
                    colors = CardDefaults.cardColors(containerColor = AppTheme.CardDark),
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
                                        activeCalls.forEach { it.cancel() }
                                        val current = activeTransfer
                                        if (current?.id?.isNotBlank() == true) {
                                            val inbound = current.direction.contains("This phone")
                                            val host = if (inbound) "127.0.0.1" else outgoingTargetIp
                                            val port = if (inbound) 5056 else outgoingTargetPort
                                            val authToken = if (inbound) incomingTransferToken else outgoingTransferToken
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    if (host.isNotBlank() && authToken.isNotBlank()) {
                                                        val cancelUrl = "http://$host:$port/airpulse/cancel-transfer?transfer_id=${URLEncoder.encode(current.id, "UTF-8")}&token=${URLEncoder.encode(authToken, "UTF-8")}"
                                                        okHttpClient.newCall(Request.Builder().url(cancelUrl).post(ByteArray(0).toRequestBody(null)).build()).execute().close()
                                                    }
                                                } catch (_: Exception) { }
                                            }
                                        }
                                        activeTransfer = current?.copy(status = "cancelled", message = "Cancelled by user")
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
                            trackColor = AppTheme.CardBorder
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

    if (showReceiveQrDialog) {
        AlertDialog(
            onDismissRequest = { showReceiveQrDialog = false },
            containerColor = AppTheme.CardDarkElevated,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCode, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Quick Share QR Code", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Scan this QR code from any phone or PC on your Wi-Fi network to beam files directly.",
                        fontSize = 12.sp,
                        color = AppTheme.TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap!!,
                                contentDescription = "Receiver QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = Color(0xFF10B981))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${Build.MODEL} • ${getLocalIp()}:5056",
                        fontSize = 11.sp,
                        color = Color(0xFF10B981),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReceiveQrDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Done", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showSenderQrDialog) {
        AlertDialog(
            onDismissRequest = { showSenderQrDialog = false },
            containerColor = AppTheme.CardDarkElevated,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.QrCode, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sender AirPulse Beam", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Scan this QR from the receiver phone or PC to connect immediately.",
                        fontSize = 12.sp,
                        color = AppTheme.TextMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .padding(10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (senderQrBitmap != null) {
                            Image(
                                bitmap = senderQrBitmap!!,
                                contentDescription = "Sender QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = Color(0xFF06B6D4))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${Build.MODEL} • ${getLocalIp()}:5056",
                        fontSize = 11.sp,
                        color = AppTheme.TextMuted,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSenderQrDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close", color = Color(0xFF0B0F19), fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAirPulseQrScanner) {
        QrScannerDialog(
            onDismiss = { showAirPulseQrScanner = false },
            onQrScanned = { name, target, _ ->
                showAirPulseQrScanner = false
                val cleanTarget = target.removePrefix("http://").removePrefix("https://")
                val address = cleanTarget.substringBeforeLast(":", cleanTarget)
                val port = cleanTarget.substringAfterLast(":", "5056").toIntOrNull() ?: 5056
                sendFilesToTarget(AirPulseDiscoveredPeer(name, address, port, "android", true), directMode = true)
            },
            onQrScannedDetailed = { name, target, _, ssid, isDirect ->
                showAirPulseQrScanner = false
                val cleanTarget = target.removePrefix("http://").removePrefix("https://")
                val address = cleanTarget.substringBeforeLast(":", cleanTarget)
                val port = cleanTarget.substringAfterLast(":", "5056").toIntOrNull() ?: 5056

                val peer = AirPulseDiscoveredPeer(name, address, port, "android", true)
                if (ssid.isNotBlank()) {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val currentSsid = wm?.connectionInfo?.ssid?.replace("\"", "") ?: ""
                    if (currentSsid.isNotBlank() && currentSsid != "<unknown ssid>" && currentSsid != ssid) {
                        Toast.makeText(context, "Connecting to $ssid...", Toast.LENGTH_SHORT).show()
                        WifiNetworkHelper.connectToWifi(context, ssid) { connected, msg ->
                            scope.launch(Dispatchers.Main) {
                                if (connected) {
                                    Toast.makeText(context, "Connected to $ssid! Beaming files...", Toast.LENGTH_SHORT).show()
                                    sendFilesToTarget(peer, directMode = true)
                                } else {
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    sendFilesToTarget(peer, directMode = true)
                                }
                            }
                        }
                        return@QrScannerDialog
                    }
                }
                sendFilesToTarget(peer, directMode = true)
            }
        )
    }

}
