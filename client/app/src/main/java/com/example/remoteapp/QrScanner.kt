package com.example.remoteapp

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

private const val TAG = "QrScanner"

@Composable
fun QrScannerDialog(
    onDismiss: () -> Unit,
    onQrScanned: (pcName: String, pcTarget: String, pin: String) -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithScanner(
                    onQrScanned = onQrScanned,
                    onClose = onDismiss
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.VideocamOff,
                            contentDescription = null,
                            tint = AppTheme.AccentRose,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Camera Permission Needed",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Please grant camera access to scan your PC pairing QR code.",
                            fontSize = 13.sp,
                            color = Color.LightGray,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Grant Permission", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreviewWithScanner(
    onQrScanned: (pcName: String, pcTarget: String, pin: String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    // Laser animation
    val infiniteTransition = rememberInfiniteTransition(label = "LaserTransition")
    val laserOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LaserOffset"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // CameraX Surface View
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = Executors.newSingleThreadExecutor()
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val reader = MultiFormatReader().apply {
                        val hints = mapOf<DecodeHintType, Any>(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true
                        )
                        setHints(hints)
                    }

                    imageAnalysis.setAnalyzer(executor) { imageProxy ->
                        if (hasScanned) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val buffer = imageProxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        val width = imageProxy.width
                        val height = imageProxy.height
                        val source = PlanarYUVLuminanceSource(
                            bytes, width, height, 0, 0, width, height, false
                        )
                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                        try {
                            val result = reader.decodeWithState(binaryBitmap)
                            val text = result.text
                            if (!text.isNullOrEmpty() && !hasScanned) {
                                hasScanned = true
                                parseAndDispatchQr(text, onQrScanned)
                            }
                        } catch (_: NotFoundException) {
                            // No QR code in this frame
                        } catch (e: Exception) {
                            Log.e(TAG, "QR decode error", e)
                        } finally {
                            reader.reset()
                            imageProxy.close()
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                        cameraControl = camera.cameraControl
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera bind error", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay with Scanning Reticle & Frame
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Controls Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }

                Text(
                    "Scan PC QR Code",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                IconButton(
                    onClick = {
                        isTorchOn = !isTorchOn
                        cameraControl?.enableTorch(isTorchOn)
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Icon(
                        if (isTorchOn) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                        contentDescription = "Flashlight",
                        tint = if (isTorchOn) AppTheme.AccentAmber else Color.White
                    )
                }
            }

            // Central Scanning Reticle
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .border(BorderStroke(2.dp, AppTheme.AccentCyan), RoundedCornerShape(20.dp))
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow corner accents
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppTheme.AccentCyan.copy(alpha = 0.05f))
                )

                // Animated Laser Line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .offset(y = (-110).dp + (220.dp * laserOffset))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    AppTheme.AccentCyan,
                                    Color.White,
                                    AppTheme.AccentCyan,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }

            // Bottom Instructions Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 50.dp, start = 24.dp, end = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppTheme.AccentCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.QrCode,
                            contentDescription = null,
                            tint = AppTheme.AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Align the QR code from the PC desktop dashboard within the frame to connect instantly.",
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

private fun parseAndDispatchQr(
    rawText: String,
    onQrScanned: (pcName: String, pcTarget: String, pin: String) -> Unit
) {
    try {
        if (rawText.startsWith("REMOTEPC|")) {
            val parts = rawText.split("|")
            val pcName = parts.getOrNull(1)?.trim() ?: "PC"
            val ip = parts.getOrNull(2)?.trim() ?: ""
            val port = parts.getOrNull(3)?.trim() ?: "5055"
            val pin = parts.getOrNull(4)?.trim() ?: ""
            if (ip.isNotEmpty()) {
                val target = if (port.isNotEmpty()) "$ip:$port" else "$ip:5055"
                onQrScanned(pcName, target, pin)
                return
            }
        }

        // Alternative standard format: http://ip:port or ip:port
        val clean = rawText.replace("http://", "").replace("https://", "").trim()
        if (clean.contains(":")) {
            onQrScanned("PC", clean, "")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse QR content: $rawText", e)
    }
}
