package com.example.remoteapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.*
import android.os.*
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CameraRecording"

// ==============================================================================
// MPEG-TS PACKETIZER FOR ZERO-COPY HARDWARE H.264 & AAC STREAMING
// ==============================================================================

class MpegTsPacketizer(private val outputStream: OutputStream) {
    private var videoCc = 0
    private var audioCc = 0
    private var patCc = 0
    private var pmtCc = 0
    private var packetsSincePatPmt = 0

    private fun calcCrc(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until (offset + length)) {
            crc = crc xor (data[i].toInt() shl 24)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80000000.toInt()) != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    @Synchronized
    fun sendPatPmt() {
        // 1. PAT (PID = 0)
        val pat = ByteArray(188) { 0xFF.toByte() }
        pat[0] = 0x47.toByte()
        pat[1] = 0x40.toByte() // PUSI = 1, PID = 0
        pat[2] = 0x00.toByte()
        pat[3] = (0x10 or (patCc and 0x0F)).toByte()
        patCc = (patCc + 1) and 0x0F
        pat[4] = 0x00.toByte() // Pointer field

        val patBody = byteArrayOf(
            0x00.toByte(),               // Table ID = 0 (PAT)
            0xB0.toByte(), 0x0D.toByte(), // Section syntax = 1, length = 13
            0x00.toByte(), 0x01.toByte(), // Transport Stream ID = 1
            0xC1.toByte(),               // Version = 0, current/next = 1
            0x00.toByte(),               // Section number = 0
            0x00.toByte(),               // Last section number = 0
            0x00.toByte(), 0x01.toByte(), // Program number = 1
            0xF0.toByte(), 0x00.toByte()  // PMT PID = 0x1000 (PID 4096)
        )
        System.arraycopy(patBody, 0, pat, 5, patBody.size)
        val patCrc = calcCrc(patBody, 0, patBody.size)
        pat[5 + patBody.size] = ((patCrc ushr 24) and 0xFF).toByte()
        pat[6 + patBody.size] = ((patCrc ushr 16) and 0xFF).toByte()
        pat[7 + patBody.size] = ((patCrc ushr 8) and 0xFF).toByte()
        pat[8 + patBody.size] = (patCrc and 0xFF).toByte()

        // 2. PMT (PID = 0x1000)
        val pmt = ByteArray(188) { 0xFF.toByte() }
        pmt[0] = 0x47.toByte()
        pmt[1] = 0x50.toByte() // PUSI = 1, PID = 0x1000
        pmt[2] = 0x00.toByte()
        pmt[3] = (0x10 or (pmtCc and 0x0F)).toByte()
        pmtCc = (pmtCc + 1) and 0x0F
        pmt[4] = 0x00.toByte() // Pointer field

        val pmtBody = byteArrayOf(
            0x02.toByte(),               // Table ID = 2 (PMT)
            0xB0.toByte(), 0x17.toByte(), // Section syntax = 1, length = 23
            0x00.toByte(), 0x01.toByte(), // Program number = 1
            0xC1.toByte(),               // Version = 0, current/next = 1
            0x00.toByte(),               // Section number = 0
            0x00.toByte(),               // Last section = 0
            0xE1.toByte(), 0x00.toByte(), // PCR PID = 0x0100 (Video)
            0xF0.toByte(), 0x00.toByte(), // Program info length = 0
            // Stream 1: H.264 Video (PID 0x0100)
            0x1B.toByte(),               // Stream type = 0x1B
            0xE1.toByte(), 0x00.toByte(), // Elementary PID = 0x0100
            0xF0.toByte(), 0x00.toByte(), // ES info length = 0
            // Stream 2: AAC Audio (PID 0x0101)
            0x0F.toByte(),               // Stream type = 0x0F (AAC ADTS)
            0xE1.toByte(), 0x01.toByte(), // Elementary PID = 0x0101
            0xF0.toByte(), 0x00.toByte()  // ES info length = 0
        )
        System.arraycopy(pmtBody, 0, pmt, 5, pmtBody.size)
        val pmtCrc = calcCrc(pmtBody, 0, pmtBody.size)
        pmt[5 + pmtBody.size] = ((pmtCrc ushr 24) and 0xFF).toByte()
        pmt[6 + pmtBody.size] = ((pmtCrc ushr 16) and 0xFF).toByte()
        pmt[7 + pmtBody.size] = ((pmtCrc ushr 8) and 0xFF).toByte()
        pmt[8 + pmtBody.size] = (pmtCrc and 0xFF).toByte()

        outputStream.write(pat)
        outputStream.write(pmt)
        packetsSincePatPmt = 0
    }

    @Synchronized
    fun writeVideoFrame(nalData: ByteArray, length: Int, ptsUs: Long, isKeyFrame: Boolean) {
        if (isKeyFrame || packetsSincePatPmt > 60) {
            sendPatPmt()
        }

        val pts90k = ptsUs * 90 / 1000
        val pesHeader = ByteArray(14)
        pesHeader[0] = 0x00.toByte()
        pesHeader[1] = 0x00.toByte()
        pesHeader[2] = 0x01.toByte()
        pesHeader[3] = 0xE0.toByte() // Video Stream 0
        pesHeader[4] = 0x00.toByte() // PES packet length = 0 (unbounded in TS)
        pesHeader[5] = 0x00.toByte()
        pesHeader[6] = 0x80.toByte() // Standard marker
        pesHeader[7] = 0x80.toByte() // PTS only
        pesHeader[8] = 0x05.toByte() // Header data length = 5
        // 5-byte PTS (90 kHz clock)
        pesHeader[9] = (0x21 or (((pts90k ushr 29) and 0x0E).toInt())).toByte()
        pesHeader[10] = ((pts90k ushr 22) and 0xFF).toByte()
        pesHeader[11] = (0x01 or (((pts90k ushr 14) and 0xFE).toInt())).toByte()
        pesHeader[12] = ((pts90k ushr 7) and 0xFF).toByte()
        pesHeader[13] = (0x01 or (((pts90k shl 1) and 0xFE).toInt())).toByte()

        val totalLen = pesHeader.size + length
        var offset = 0
        var isFirst = true

        while (offset < totalLen) {
            val tsPacket = ByteArray(188)
            tsPacket[0] = 0x47.toByte()
            val pusi = if (isFirst) 0x40 else 0x00
            tsPacket[1] = (pusi or 0x01).toByte() // PID 0x0100 high byte
            tsPacket[2] = 0x00.toByte()           // PID 0x0100 low byte

            val remaining = totalLen - offset
            if (remaining >= 184) {
                // Full payload, no adaptation field
                tsPacket[3] = (0x10 or (videoCc and 0x0F)).toByte()
                videoCc = (videoCc + 1) and 0x0F
                var written = 0
                while (written < 184) {
                    val curr = offset + written
                    val b = if (curr < pesHeader.size) pesHeader[curr] else nalData[curr - pesHeader.size]
                    tsPacket[4 + written] = b
                    written++
                }
                offset += 184
            } else {
                // Last slice: adaptation field padding
                tsPacket[3] = (0x30 or (videoCc and 0x0F)).toByte()
                videoCc = (videoCc + 1) and 0x0F
                val padLen = 184 - remaining
                tsPacket[4] = (padLen - 1).toByte() // Adaptation field length
                if (padLen > 1) {
                    tsPacket[5] = 0x00.toByte() // Flags
                    for (p in 6 until (4 + padLen)) {
                        tsPacket[p] = 0xFF.toByte() // Stuffing bytes
                    }
                }
                var written = 0
                while (written < remaining) {
                    val curr = offset + written
                    val b = if (curr < pesHeader.size) pesHeader[curr] else nalData[curr - pesHeader.size]
                    tsPacket[4 + padLen + written] = b
                    written++
                }
                offset += remaining
            }

            outputStream.write(tsPacket)
            packetsSincePatPmt++
            isFirst = false
        }
    }

    @Synchronized
    fun writeAudioFrame(aacData: ByteArray, length: Int, ptsUs: Long) {
        val pts90k = ptsUs * 90 / 1000
        val adtsHeader = ByteArray(7)
        addAdtsHeader(adtsHeader, length + 7, sampleRate = 48000, channelCount = 2)

        val pesPayloadLen = 8 + 7 + length // Flags + PTS (8 bytes) + ADTS (7 bytes) + AAC
        val pesHeader = ByteArray(14)
        pesHeader[0] = 0x00.toByte()
        pesHeader[1] = 0x00.toByte()
        pesHeader[2] = 0x01.toByte()
        pesHeader[3] = 0xC0.toByte() // Audio Stream 0
        pesHeader[4] = ((pesPayloadLen ushr 8) and 0xFF).toByte()
        pesHeader[5] = (pesPayloadLen and 0xFF).toByte()
        pesHeader[6] = 0x80.toByte()
        pesHeader[7] = 0x80.toByte()
        pesHeader[8] = 0x05.toByte()
        pesHeader[9] = (0x21 or (((pts90k ushr 29) and 0x0E).toInt())).toByte()
        pesHeader[10] = ((pts90k ushr 22) and 0xFF).toByte()
        pesHeader[11] = (0x01 or (((pts90k ushr 14) and 0xFE).toInt())).toByte()
        pesHeader[12] = ((pts90k ushr 7) and 0xFF).toByte()
        pesHeader[13] = (0x01 or (((pts90k shl 1) and 0xFE).toInt())).toByte()

        val totalLen = pesHeader.size + 7 + length
        var offset = 0
        var isFirst = true

        while (offset < totalLen) {
            val tsPacket = ByteArray(188)
            tsPacket[0] = 0x47.toByte()
            val pusi = if (isFirst) 0x40 else 0x00
            tsPacket[1] = (pusi or 0x01).toByte() // PID 0x0101 high byte
            tsPacket[2] = 0x01.toByte()           // PID 0x0101 low byte

            val remaining = totalLen - offset
            if (remaining >= 184) {
                tsPacket[3] = (0x10 or (audioCc and 0x0F)).toByte()
                audioCc = (audioCc + 1) and 0x0F
                var written = 0
                while (written < 184) {
                    val curr = offset + written
                    val b = when {
                        curr < pesHeader.size -> pesHeader[curr]
                        curr < (pesHeader.size + 7) -> adtsHeader[curr - pesHeader.size]
                        else -> aacData[curr - pesHeader.size - 7]
                    }
                    tsPacket[4 + written] = b
                    written++
                }
                offset += 184
            } else {
                tsPacket[3] = (0x30 or (audioCc and 0x0F)).toByte()
                audioCc = (audioCc + 1) and 0x0F
                val padLen = 184 - remaining
                tsPacket[4] = (padLen - 1).toByte()
                if (padLen > 1) {
                    tsPacket[5] = 0x00.toByte()
                    for (p in 6 until (4 + padLen)) {
                        tsPacket[p] = 0xFF.toByte()
                    }
                }
                var written = 0
                while (written < remaining) {
                    val curr = offset + written
                    val b = when {
                        curr < pesHeader.size -> pesHeader[curr]
                        curr < (pesHeader.size + 7) -> adtsHeader[curr - pesHeader.size]
                        else -> aacData[curr - pesHeader.size - 7]
                    }
                    tsPacket[4 + padLen + written] = b
                    written++
                }
                offset += remaining
            }

            outputStream.write(tsPacket)
            isFirst = false
        }
    }

    private fun addAdtsHeader(packet: ByteArray, packetLen: Int, sampleRate: Int = 48000, channelCount: Int = 2) {
        val profile = 2 // AAC LC
        val freqIdx = 3 // 48000 Hz
        val chanCfg = channelCount
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte() // MPEG-4, no CRC
        packet[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = (((chanCfg and 3) shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }
}

// ==============================================================================
// CAMERA RECORDING SCREEN
// ==============================================================================

@Composable
fun CameraRecordingScreen(
    targetIp: String,
    password: String = "",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasCameraPermission = perms[Manifest.permission.CAMERA] == true && perms[Manifest.permission.RECORD_AUDIO] == true
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    BackHandler {
        onBack()
    }

    // Camera & UI State
    var isBackCamera by remember { mutableStateOf(true) }
    var isTorchOn by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }
    var selectedRes by remember { mutableStateOf("1080p") } // "1080p", "720p", "4K"
    var defaultCameraRatio by remember { mutableStateOf("4:3") }
    var selectedAspectRatio by remember { mutableStateOf("4:3") } // "4:3", "16:9"
    var showGrid by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var pcFreeDiskGb by remember { mutableStateOf(0.0) }
    var isRecording by remember { mutableStateOf(false) }
    var isFinalizing by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableIntStateOf(0) }
    var bytesTransferred by remember { mutableLongStateOf(0L) }
    var liveBitrateKbps by remember { mutableIntStateOf(0) }
    var savedSuccessMsg by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }

    // Detect device native camera sensor aspect ratio (defaulting to device's hardware sensor ratio)
    LaunchedEffect(isBackCamera) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (manager != null && manager.cameraIdList.isNotEmpty()) {
                val facing = if (isBackCamera) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
                val id = manager.cameraIdList.firstOrNull { camId ->
                    try {
                        manager.getCameraCharacteristics(camId).get(CameraCharacteristics.LENS_FACING) == facing
                    } catch (_: Exception) { false }
                } ?: manager.cameraIdList.firstOrNull()

                if (id != null) {
                    val chars = manager.getCameraCharacteristics(id)
                    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (activeArray != null && activeArray.height() > 0) {
                        val r = activeArray.width().toFloat() / activeArray.height().toFloat()
                        val detected = if (kotlin.math.abs(r - (16f / 9f)) < 0.15f) "16:9" else "4:3"
                        defaultCameraRatio = detected
                        selectedAspectRatio = detected
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Streaming & Codec References
    val isStreamingActive = remember { AtomicBoolean(false) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var previewSurfaceRef by remember { mutableStateOf<Surface?>(null) }
    var currentPreviewSize by remember { mutableStateOf(Size(640, 480)) }
    var cameraDeviceRef by remember { mutableStateOf<CameraDevice?>(null) }
    var captureSessionRef by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var videoCodecRef by remember { mutableStateOf<MediaCodec?>(null) }
    var encoderSurfaceRef by remember { mutableStateOf<Surface?>(null) }
    var audioCodecRef by remember { mutableStateOf<MediaCodec?>(null) }
    var audioRecordRef by remember { mutableStateOf<AudioRecord?>(null) }
    var socketRef by remember { mutableStateOf<Socket?>(null) }
    var previewReaderRef by remember { mutableStateOf<ImageReader?>(null) }

    val cameraThread = remember {
        HandlerThread("CameraBgThread").apply { start() }
    }
    val cameraHandler = remember(cameraThread) {
        Handler(cameraThread.looper)
    }

    // Fetch PC Camera Status periodically (safely guarded against empty/invalid targetIp)
    LaunchedEffect(targetIp) {
        val client = OkHttpClient.Builder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
        while (isActive) {
            if (!targetIp.isNullOrBlank() && targetIp != "127.0.0.1") {
                val url = runCatching { "http://$targetIp:5055/camera/status".toHttpUrlOrNull() }.getOrNull()
                if (url != null) {
                    try {
                        val req = Request.Builder().url(url).build()
                        withContext(Dispatchers.IO) {
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val json = JSONObject(resp.body?.string() ?: "{}")
                                    pcFreeDiskGb = json.optDouble("disk_free_gb", 0.0)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            delay(3000)
        }
    }

    // Elapsed Timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSec = 0
            while (isRecording && isActive) {
                delay(1000)
                recordingDurationSec++
            }
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    // Dimensions matching exact selected aspect ratio without geometric distortion
    val resDimensions = remember(selectedRes, selectedAspectRatio) {
        when (selectedAspectRatio) {
            "4:3" -> when (selectedRes) {
                "720p" -> Pair(960, 720)
                "4K" -> Pair(2880, 2160)
                else -> Pair(1440, 1080)
            }
            "16:9" -> when (selectedRes) {
                "720p" -> Pair(1280, 720)
                "4K" -> Pair(3840, 2160)
                else -> Pair(1920, 1080)
            }
            else -> when (selectedRes) {
                "720p" -> Pair(960, 720)
                "4K" -> Pair(2880, 2160)
                else -> Pair(1440, 1080)
            }
        }
    }

    // Function to Start Direct-to-PC Recording
    fun startStreaming() {
        if (isRecording || isFinalizing) return
        statusError = null
        savedSuccessMsg = null

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Tell PC server to spawn FFmpeg container
                if (targetIp.isNullOrBlank() || targetIp == "127.0.0.1") {
                    withContext(Dispatchers.Main) {
                        statusError = "Please select a connected PC first."
                    }
                    return@launch
                }
                val startUrl = runCatching { "http://$targetIp:5055/camera/start".toHttpUrlOrNull() }.getOrNull()
                if (startUrl == null) {
                    withContext(Dispatchers.Main) {
                        statusError = "Invalid target IP address: $targetIp"
                    }
                    return@launch
                }

                val client = OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                val recordOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                val startBody = JSONObject().apply {
                    put("resolution", "${resDimensions.first}x${resDimensions.second}")
                    put("fps", 30)
                    put("audio", "AAC 48kHz")
                    put("orientation", recordOrientation)
                }.toString().toRequestBody("application/json".toMediaType())

                val startReq = Request.Builder()
                    .url(startUrl)
                    .header("x-remote-password", password)
                    .post(startBody)
                    .build()

                val resp = client.newCall(startReq).execute()
                if (!resp.isSuccessful) {
                    val err = resp.body?.string() ?: "Server rejected recording start"
                    withContext(Dispatchers.Main) {
                        statusError = err
                    }
                    return@launch
                }

                // 2. Open TCP socket to PC port 5058
                val socket = Socket(targetIp, 5058)
                socket.tcpNoDelay = true
                socket.sendBufferSize = 1024 * 512
                val out = BufferedOutputStream(socket.getOutputStream(), 1024 * 128)
                val packetizer = MpegTsPacketizer(out)

                socketRef = socket
                isStreamingActive.set(true)

                withContext(Dispatchers.Main) {
                    isRecording = true
                }

                // 3. Configure Hardware Video Encoder (MediaCodec H.264)
                val videoFormat = MediaFormat.createVideoFormat("video/avc", resDimensions.first, resDimensions.second).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (selectedRes == "4K") 25000000 else if (selectedRes == "1080p") 10000000 else 5000000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1s keyframe interval
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }

                val videoCodec = MediaCodec.createEncoderByType("video/avc")
                videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val encoderSurface = videoCodec.createInputSurface()
                videoCodec.start()
                videoCodecRef = videoCodec
                encoderSurfaceRef = encoderSurface

                // 4. Configure Audio Encoder (MediaCodec AAC) & AudioRecord
                val sampleRate = 48000
                val minAudioBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val audioBufSize = if (minAudioBuf > 0) minAudioBuf * 2 else 4096
                val audioRecord = try {
                    AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, audioBufSize)
                } catch (e: Exception) {
                    null
                }

                val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioBufSize)
                }

                val audioCodec = MediaCodec.createEncoderByType("audio/mp4a-latm")
                audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec.start()
                audioCodecRef = audioCodec
                audioRecordRef = audioRecord

                if (!isMuted && audioRecord != null && audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    try { audioRecord.startRecording() } catch (_: Exception) {}
                }

                // 5. Update Camera2 Capture Session to include encoderSurface
                val camera = cameraDeviceRef
                val previewSurface = previewSurfaceRef
                if (camera != null && previewSurface != null) {
                    try {
                        captureSessionRef?.stopRepeating()
                        captureSessionRef?.close()
                    } catch (_: Exception) {}

                    val surfaces = listOfNotNull(previewSurface, encoderSurface, previewReaderRef?.surface)
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSessionRef = session
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            builder.addTarget(previewSurface)
                            builder.addTarget(encoderSurface)
                            previewReaderRef?.surface?.let { builder.addTarget(it) }
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            val jpegOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            if (isTorchOn && isBackCamera) {
                                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Capture session configuration failed")
                        }
                    }, cameraHandler)
                }

                // 6. Launch Video Drain Loop (Read H.264 NALUs -> MPEG-TS -> PC)
                launch(Dispatchers.IO) {
                    val bufferInfo = MediaCodec.BufferInfo()
                    var bytesInSec = 0
                    var lastBitrateTime = System.currentTimeMillis()

                    while (isStreamingActive.get()) {
                        val outIdx = videoCodec.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outIdx >= 0) {
                            val encodedBuf = videoCodec.getOutputBuffer(outIdx)
                            if (encodedBuf != null && bufferInfo.size > 0) {
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                encodedBuf.get(chunk)

                                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                packetizer.writeVideoFrame(chunk, chunk.size, bufferInfo.presentationTimeUs, isKey)
                                bytesTransferred += chunk.size
                                bytesInSec += chunk.size

                                val now = System.currentTimeMillis()
                                if (now - lastBitrateTime >= 1000) {
                                    val dt = (now - lastBitrateTime) / 1000.0
                                    liveBitrateKbps = ((bytesInSec * 8) / (dt * 1000)).toInt()
                                    bytesInSec = 0
                                    lastBitrateTime = now
                                }
                            }
                            videoCodec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }

                // 7. Launch Audio Feed & Drain Loop
                val record = audioRecord
                launch(Dispatchers.IO) {
                    val pcmBuf = ByteArray(2048)
                    val audioBufInfo = MediaCodec.BufferInfo()

                    while (isStreamingActive.get()) {
                        if (!isMuted && record != null && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val read = record.read(pcmBuf, 0, pcmBuf.size)
                            if (read > 0) {
                                val inIdx = audioCodec.dequeueInputBuffer(5000)
                                if (inIdx >= 0) {
                                    val inBuf = audioCodec.getInputBuffer(inIdx)
                                    inBuf?.clear()
                                    inBuf?.put(pcmBuf, 0, read)
                                    val ptsUs = System.nanoTime() / 1000
                                    audioCodec.queueInputBuffer(inIdx, 0, read, ptsUs, 0)
                                }
                            }
                        }

                        val outIdx = audioCodec.dequeueOutputBuffer(audioBufInfo, 5000)
                        if (outIdx >= 0) {
                            val outBuf = audioCodec.getOutputBuffer(outIdx)
                            if (outBuf != null && audioBufInfo.size > 0) {
                                outBuf.position(audioBufInfo.offset)
                                outBuf.limit(audioBufInfo.offset + audioBufInfo.size)
                                val aacBytes = ByteArray(audioBufInfo.size)
                                outBuf.get(aacBytes)
                                packetizer.writeAudioFrame(aacBytes, aacBytes.size, audioBufInfo.presentationTimeUs)
                            }
                            audioCodec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusError = e.message ?: "Failed to start streaming"
                    isRecording = false
                }
            }
        }
    }

    // Function to Stop Recording & Cleanly Finalize on PC
    fun stopStreaming() {
        if (!isRecording || isFinalizing) return
        isFinalizing = true

        scope.launch(Dispatchers.IO) {
            try {
                isStreamingActive.set(false)

                // 1. Switch back to preview-only capture session FIRST before releasing encoderSurface
                try {
                    captureSessionRef?.stopRepeating()
                    captureSessionRef?.close()
                } catch (_: Exception) {}

                val camera = cameraDeviceRef
                val previewSurface = previewSurfaceRef
                if (camera != null && previewSurface != null) {
                    val surfaces = listOfNotNull(previewSurface, previewReaderRef?.surface)
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSessionRef = session
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(previewSurface)
                            previewReaderRef?.surface?.let { builder.addTarget(it) }
                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            val jpegOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            if (isTorchOn && isBackCamera) {
                                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }
                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, cameraHandler)
                }

                // 2. Stop audio
                try { audioRecordRef?.stop(); audioRecordRef?.release() } catch (_: Exception) {}
                try { audioCodecRef?.stop(); audioCodecRef?.release() } catch (_: Exception) {}

                // 3. Stop video encoder
                try { videoCodecRef?.signalEndOfInputStream() } catch (_: Exception) {}
                delay(200)
                try { videoCodecRef?.stop(); videoCodecRef?.release() } catch (_: Exception) {}
                videoCodecRef = null
                encoderSurfaceRef = null

                // 4. Close TCP socket cleanly
                try { socketRef?.close() } catch (_: Exception) {}

                // Call PC /camera/stop to finalize container and verify MP4
                val stopUrl = runCatching { "http://$targetIp:5055/camera/stop".toHttpUrlOrNull() }.getOrNull()
                if (stopUrl != null) {
                    val client = OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()
                    val stopReq = Request.Builder()
                        .url(stopUrl)
                        .header("x-remote-password", password)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()

                    val resp = client.newCall(stopReq).execute()
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)

                    withContext(Dispatchers.Main) {
                        isRecording = false
                        isFinalizing = false
                        liveBitrateKbps = 0
                        if (json.optString("status") == "ok") {
                            val sizeMb = Math.round(json.optDouble("file_size_bytes", 0.0) / (1024 * 1024)).toInt()
                            savedSuccessMsg = "Saved to PC: ${json.optString("file_path")} ($sizeMb MB)"
                        } else {
                            statusError = json.optString("message", "Finalization issue")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isRecording = false
                        isFinalizing = false
                        liveBitrateKbps = 0
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isRecording = false
                    isFinalizing = false
                    statusError = e.message ?: "Error finalizing recording"
                }
            }
        }
    }

    fun configureTransform(
        view: TextureView?,
        viewWidth: Int,
        viewHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
        isPortrait: Boolean,
        isBackCamera: Boolean
    ) {
        if (view == null || viewWidth <= 0 || viewHeight <= 0 || previewWidth <= 0 || previewHeight <= 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        // Camera2 SurfaceTexture is already in natural portrait orientation on modern Android devices.
        // Applying matrix.postRotate(90f) in portrait forces an unwanted 90 degree tilt sideways.
        // In accordance with Google's official Camera2 specification, rotation == Surface.ROTATION_0
        // leaves the transform as IDENTITY (straight preview), and only handles device rotation when tilted.
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = kotlin.math.max(
                viewHeight.toFloat() / previewHeight,
                viewWidth.toFloat() / previewWidth
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        view.setTransform(matrix)
    }

    fun takePhoto() {
        if (targetIp.isNullOrBlank() || targetIp == "127.0.0.1") {
            statusError = "Connect to PC to capture and save photos."
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val url = runCatching { "http://$targetIp:5055/camera/snapshot".toHttpUrlOrNull() }.getOrNull()
                if (url != null) {
                    val req = Request.Builder()
                        .url(url)
                        .header("x-remote-password", password)
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    val photoClient = OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                    val resp = photoClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    resp.close()
                    val json = JSONObject(body)
                    if (json.optString("status") == "ok") {
                        val fn = json.optString("filename", "Photo")
                        withContext(Dispatchers.Main) {
                            savedSuccessMsg = "📸 Photo saved to PC: $fn"
                        }
                    } else {
                        val err = json.optString("message", "Failed to capture photo")
                        withContext(Dispatchers.Main) {
                            statusError = err
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusError = "Photo capture failed: ${e.message}"
                }
            }
        }
    }

    // Camera Lifecycle & Setup
    fun startCameraPreview(surface: Surface) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            if (manager.cameraIdList.isEmpty()) return

            val facing = if (isBackCamera) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                try {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
                } catch (_: Exception) { false }
            } ?: manager.cameraIdList.first()

            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

            // Match native camera sensor aspect ratio without geometric distortion or stretching
            val targetRatio = if (selectedAspectRatio == "16:9") (16f / 9f) else (4f / 3f)
            val matchedSizes = jpegSizes.filter { sz ->
                val r = sz.width.toFloat() / sz.height.toFloat()
                kotlin.math.abs(r - targetRatio) < 0.15f
            }

            val previewSize = matchedSizes.firstOrNull { it.width in 480..960 }
                ?: matchedSizes.firstOrNull()
                ?: jpegSizes.firstOrNull { it.width in 480..800 }
                ?: Size(if (selectedAspectRatio == "16:9") 854 else 640, 480)

            currentPreviewSize = previewSize
            textureViewRef?.let { tv ->
                configureTransform(tv, tv.width, tv.height, previewSize.width, previewSize.height, isPortrait, isBackCamera)
            }

            val imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 2)
            try { previewReaderRef?.close() } catch (_: Exception) {}
            previewReaderRef = imageReader

            val previewClient = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            var lastPostTime = 0L

            imageReader.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPostTime >= 33) { // Smooth ~30 FPS live stream to PC
                        lastPostTime = now
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        if (!targetIp.isNullOrBlank() && targetIp != "127.0.0.1") {
                            val url = runCatching { "http://$targetIp:5055/camera/preview-frame".toHttpUrlOrNull() }.getOrNull()
                            if (url != null) {
                                val req = Request.Builder()
                                    .url(url)
                                    .post(bytes.toRequestBody("image/jpeg".toMediaType()))
                                    .build()
                                previewClient.newCall(req).enqueue(object : Callback {
                                    override fun onFailure(call: Call, e: java.io.IOException) {}
                                    override fun onResponse(call: Call, response: Response) { response.close() }
                                })
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { image.close() } catch (_: Exception) {}
                }
            }, cameraHandler)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    try {
                        val surfaces = listOfNotNull(surface, imageReader.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSessionRef = session
                                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                builder.addTarget(surface)
                                builder.addTarget(imageReader.surface)
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                val jpegOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                                builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                                if (isTorchOn && isBackCamera) {
                                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                }
                                session.setRepeatingRequest(builder.build(), null, cameraHandler)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Preview capture session configuration failed")
                            }
                        }, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating preview session: $e")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDeviceRef = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDeviceRef = null
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera: $e")
        }
    }

    fun stopCamera() {
        try { captureSessionRef?.close() } catch (_: Exception) {}
        try { cameraDeviceRef?.close() } catch (_: Exception) {}
        try { previewReaderRef?.close() } catch (_: Exception) {}
        captureSessionRef = null
        cameraDeviceRef = null
        previewReaderRef = null
    }

    DisposableEffect(cameraThread) {
        CameraActionBridge.isCameraScreenActive = true
        CameraActionBridge.onAction = { action, value ->
            when (action) {
                "start_camera_record" -> {
                    scope.launch(Dispatchers.Main) {
                        if (!isRecording && !isFinalizing) startStreaming()
                    }
                }
                "stop_camera_record" -> {
                    scope.launch(Dispatchers.Main) {
                        if (isRecording && !isFinalizing) stopStreaming()
                    }
                }
                "flip_camera" -> {
                    scope.launch(Dispatchers.Main) {
                        isBackCamera = !isBackCamera
                    }
                }
                "toggle_torch" -> {
                    scope.launch(Dispatchers.Main) {
                        isTorchOn = when (value) {
                            "on" -> true
                            "off" -> false
                            else -> !isTorchOn
                        }
                    }
                }
                "toggle_mic" -> {
                    scope.launch(Dispatchers.Main) {
                        isMuted = when (value) {
                            "mute" -> true
                            "unmute" -> false
                            else -> !isMuted
                        }
                    }
                }
            }
        }

        // Notify PC that camera is open
        if (!targetIp.isNullOrBlank() && targetIp != "127.0.0.1") {
            scope.launch(Dispatchers.IO) {
                try {
                    val url = runCatching { "http://$targetIp:5055/camera/client-state?state=open".toHttpUrlOrNull() }.getOrNull()
                    if (url != null) {
                        val client = OkHttpClient.Builder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                        val req = Request.Builder()
                            .url(url)
                            .post("{}".toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(req).execute().close()
                    }
                } catch (_: Exception) {}
            }
        }

        onDispose {
            CameraActionBridge.isCameraScreenActive = false
            CameraActionBridge.onAction = null
            stopCamera()
            try { cameraThread.quitSafely() } catch (_: Exception) {}

            // Notify PC that camera is closed
            if (!targetIp.isNullOrBlank() && targetIp != "127.0.0.1") {
                scope.launch(Dispatchers.IO) {
                    try {
                        val url = runCatching { "http://$targetIp:5055/camera/client-state?state=closed".toHttpUrlOrNull() }.getOrNull()
                        if (url != null) {
                            val client = OkHttpClient.Builder().connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
                            val req = Request.Builder()
                                .url(url)
                                .post("{}".toRequestBody("application/json".toMediaType()))
                                .build()
                            client.newCall(req).execute().close()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // Lens flip effect
    LaunchedEffect(isBackCamera) {
        val surface = previewSurfaceRef
        if (surface != null && surface.isValid) {
            stopCamera()
            startCameraPreview(surface)
        }
    }

    // Aspect ratio change effect: smoothly adjusts camera preview size and buffer to avoid stretching
    LaunchedEffect(selectedAspectRatio) {
        val surface = previewSurfaceRef
        if (surface != null && surface.isValid) {
            stopCamera()
            startCameraPreview(surface)
        }
    }

    // Torch toggle effect
    LaunchedEffect(isTorchOn) {
        try {
            val session = captureSessionRef
            val camera = cameraDeviceRef
            val previewSurface = previewSurfaceRef
            if (session != null && camera != null && previewSurface != null) {
                val template = if (isStreamingActive.get()) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                val builder = camera.createCaptureRequest(template)
                builder.addTarget(previewSurface)
                if (isStreamingActive.get()) {
                    encoderSurfaceRef?.let { builder.addTarget(it) }
                }
                previewReaderRef?.surface?.let { builder.addTarget(it) }
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                val jpegOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                if (isTorchOn && isBackCamera) {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                session.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
        } catch (_: Exception) {}
    }

    val surfaceModifier = when (selectedAspectRatio) {
        "4:3" -> if (isPortrait) Modifier.fillMaxWidth().aspectRatio(3f / 4f) else Modifier.fillMaxHeight().aspectRatio(4f / 3f)
        "16:9" -> if (isPortrait) Modifier.fillMaxWidth().aspectRatio(9f / 16f) else Modifier.fillMaxHeight().aspectRatio(16f / 9f)
        else -> Modifier.fillMaxSize()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    showControls = !showControls
                }
            }
    ) {
        // 1. Hardware Camera Texture View with exact native aspect ratio & orientation
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            textureViewRef = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                                    st.setDefaultBufferSize(currentPreviewSize.width, currentPreviewSize.height)
                                    val surface = Surface(st)
                                    previewSurfaceRef = surface
                                    startCameraPreview(surface)
                                    configureTransform(this@apply, width, height, currentPreviewSize.width, currentPreviewSize.height, isPortrait, isBackCamera)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                                    configureTransform(this@apply, width, height, currentPreviewSize.width, currentPreviewSize.height, isPortrait, isBackCamera)
                                }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    stopCamera()
                                    previewSurfaceRef = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                            }
                        }
                    },
                    update = { tv ->
                        textureViewRef = tv
                        configureTransform(tv, tv.width, tv.height, currentPreviewSize.width, currentPreviewSize.height, isPortrait, isBackCamera)
                    },
                    modifier = surfaceModifier
                )
            }

            // 3x3 Rule-of-Thirds Grid Overlay
            if (showGrid) {
                Box(modifier = surfaceModifier) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        repeat(3) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .border(0.5.dp, Color.White.copy(alpha = 0.35f))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Viewfinder Reticle / Corner Brackets framed to video
            Box(
                modifier = surfaceModifier.padding(16.dp)
            ) {
                Box(modifier = Modifier.size(24.dp).align(Alignment.TopStart).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(topStart = 6.dp)))
                Box(modifier = Modifier.size(24.dp).align(Alignment.TopEnd).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(topEnd = 6.dp)))
                Box(modifier = Modifier.size(24.dp).align(Alignment.BottomStart).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(bottomStart = 6.dp)))
                Box(modifier = Modifier.size(24.dp).align(Alignment.BottomEnd).border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(bottomEnd = 6.dp)))
            }
        }

        // 2. Animated Controls Overlay (Toggles with single screen tap)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Control Header
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (isRecording) stopStreaming()
                                onBack()
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Top Header Recording Timer (Clean & Non-obstructive)
                            if (isRecording) {
                                val infiniteTransition = rememberInfiniteTransition(label = "rec")
                                val pulseAlpha by infiniteTransition.animateFloat(
                                    initialValue = 0.2f,
                                    targetValue = 1f,
                                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                                    label = "pulse"
                                )
                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFF881337).copy(alpha = 0.85f), RoundedCornerShape(20.dp))
                                        .border(1.dp, Color(0xFFE11D48), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFFE11D48).copy(alpha = pulseAlpha), CircleShape)
                                    )
                                    val minutes = recordingDurationSec / 60
                                    val seconds = recordingDurationSec % 60
                                    Text(
                                        text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }

                            // PC Storage & Destination Badge
                            Row(
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (pcFreeDiskGb > 1.0) Color(0xFF10B981) else Color(0xFFF59E0B), CircleShape)
                                )
                                Text(
                                    text = "PC Disk: ${String.format(Locale.US, "%.1f", pcFreeDiskGb)} GB Free",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Quick Switchers: Aspect Ratio, Grid & Resolution
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Aspect Ratio Switcher Pill (Default 4:3)
                        Row(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            listOf("4:3", "16:9", "Full").forEach { ratio ->
                                val isSelected = selectedAspectRatio == ratio
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) Color(0xFF06B6D4) else Color.Transparent)
                                        .clickable { selectedAspectRatio = ratio }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = ratio,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        // Rule-of-Thirds Grid 3x3 Toggle Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (showGrid) Color(0xFF06B6D4) else Color.Black.copy(alpha = 0.65f))
                                .clickable { showGrid = !showGrid }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (showGrid) "Grid ON" else "Grid OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                color = if (showGrid) Color.Black else Color.White.copy(alpha = 0.7f)
                            )
                        }

                        // Resolution Switcher Pill
                        Row(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(20.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            listOf("720p", "1080p", "4K").forEach { res ->
                                val isSelected = selectedRes == res
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (isSelected) Color(0xFF06B6D4) else Color.Transparent)
                                        .clickable(enabled = !isRecording) { selectedRes = res }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = res,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Action & Control Bar
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Success Banner
                    savedSuccessMsg?.let { msg ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF064E3B).copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Text(msg, color = Color(0xFFA7F3D0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Error Banner
                    statusError?.let { err ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF881337).copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                .padding(12.dp)
                        ) {
                            Text(err, color = Color(0xFFFECDD3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Floating Bottom Buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Torch Toggle
                        IconButton(
                            onClick = {
                                isTorchOn = !isTorchOn
                                captureSessionRef?.let { session ->
                                    cameraDeviceRef?.let { camera ->
                                        try {
                                            val builder = camera.createCaptureRequest(if (isRecording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                                            previewSurfaceRef?.let { builder.addTarget(it) }
                                            if (isRecording) {
                                                encoderSurfaceRef?.let { builder.addTarget(it) }
                                            }
                                            previewReaderRef?.surface?.let { builder.addTarget(it) }
                                            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                            val jpegOrientation = if (isPortrait) (if (isBackCamera) 90 else 270) else 0
                                            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                                            builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                            session.setRepeatingRequest(builder.build(), null, cameraHandler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error toggling torch: $e")
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(if (isTorchOn) Color(0xFFF59E0B) else Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                if (isTorchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                                contentDescription = "Torch",
                                tint = if (isTorchOn) Color.Black else Color.White
                            )
                        }

                        // Snap Photo Button
                        IconButton(
                            onClick = { takePhoto() },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = "Take Photo", tint = Color.White)
                        }

                        // Giant Record / Stop Button
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .border(4.dp, Color.White, CircleShape)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                                .clickable {
                                    if (isRecording) {
                                        stopStreaming()
                                    } else {
                                        startStreaming()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isFinalizing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = Color(0xFFF59E0B),
                                    strokeWidth = 3.dp
                                )
                            } else if (isRecording) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(Color(0xFFE11D48), RoundedCornerShape(6.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(Color(0xFFE11D48), CircleShape)
                                )
                            }
                        }

                        // Flip Camera Button
                        IconButton(
                            onClick = {
                                if (!isRecording) {
                                    isBackCamera = !isBackCamera
                                }
                            },
                            enabled = !isRecording,
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Rounded.FlipCameraAndroid, contentDescription = "Flip Lens", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun Double.format(digits: Int) = "%.${digits}f".format(Locale.US, this)
