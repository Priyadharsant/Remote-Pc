package com.example.remoteapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "FolderSync"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSyncScreen(
    targetIp: String,
    password: String = "",
    onBack: () -> Unit,
    onMenuClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observe background sync manager live state
    val syncState by FolderSyncManager.stateFlow.collectAsState()

    // Dialog state for editing PC saving folder
    var showEditPcFolderDialog by remember { mutableStateOf(false) }
    var editingPcFolderText by remember { mutableStateOf("") }
    var isSavingPcFolder by remember { mutableStateOf(false) }

    // Start background sync manager when screen is displayed
    LaunchedEffect(targetIp, password) {
        FolderSyncManager.start(context, targetIp, password)
    }

    // Launcher for Android SAF Folder Picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                val doc = DocumentFile.fromTreeUri(context, uri)
                val name = doc?.name ?: uri.lastPathSegment ?: "Selected Folder"

                val now = System.currentTimeMillis()
                // Requirement: Only sync files created AFTER this feature is turned on
                FolderSyncManager.updateSettings(
                    context = context,
                    enabled = true,
                    folderUri = uri.toString(),
                    folderName = name,
                    startTimeMs = now
                )

                Toast.makeText(context, "Monitoring new files in: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error taking folder permission", e)
                Toast.makeText(context, "Error selecting folder: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Mobile Folder Sync", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Text(
                            text = if (syncState.isEnabled) "Auto-syncing to PC (Background & Instant)" else "Sync paused",
                            fontSize = 11.sp,
                            color = if (syncState.isEnabled) Color(0xFF10B981) else Color(0xFF94A3B8)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { FolderSyncManager.triggerSyncNow(context) }
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Sync Now", tint = Color(0xFF38BDF8))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF0B101E)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Folder Selection & Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF06B6D4).copy(alpha = 0.2f), RoundedCornerShape(20.dp))
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFF06B6D4).copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.FolderShared, contentDescription = null, tint = Color(0xFF06B6D4), modifier = Modifier.size(24.dp))
                            }
                            Column {
                                Text(syncState.folderName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                                Text("Mobile Folder", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        Switch(
                            checked = syncState.isEnabled && syncState.folderUri != null,
                            onCheckedChange = { enable ->
                                if (syncState.folderUri == null) {
                                    folderPickerLauncher.launch(null)
                                } else {
                                    val now = System.currentTimeMillis()
                                    FolderSyncManager.updateSettings(
                                        context = context,
                                        enabled = enable,
                                        folderUri = syncState.folderUri,
                                        folderName = syncState.folderName,
                                        startTimeMs = if (enable) now else syncState.startTimeMs
                                    )
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF06B6D4),
                                uncheckedThumbColor = Color(0xFF64748B),
                                uncheckedTrackColor = Color(0xFF1E293B)
                            )
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // Destination Info on PC + Edit button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("PC Destination Folder", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                IconButton(
                                    onClick = {
                                        editingPcFolderText = syncState.pcDestinationDir
                                        showEditPcFolderDialog = true
                                    },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Edit PC Folder", tint = Color(0xFF06B6D4), modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                syncState.pcDestinationDir,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Free: ${syncState.pcFreeDiskGb} GB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                        }
                    }

                    // Policy Badge: Only files created after feature turned on
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                            Text(
                                text = "Only files created after ${if (syncState.startTimeMs > 0) SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(syncState.startTimeMs)) else "activation"} are synced.",
                                fontSize = 11.sp,
                                color = Color(0xFFCBD5E1)
                            )
                        }
                    }

                    // Choose / Change Folder Button
                    Button(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.DriveFolderUpload, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text(if (syncState.folderUri == null) "Select Folder to Sync" else "Change Folder", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }

            // Sync Status & Statistics Bar
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Files Synced", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        Text("${syncState.totalSynced} files", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF06B6D4))
                    }
                    if (syncState.isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF06B6D4))
                            Text("Checking...", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        }
                    } else {
                        Text(
                            if (syncState.isEnabled) "● Instant Detection Active" else "Paused",
                            fontSize = 12.sp,
                            color = if (syncState.isEnabled) Color(0xFF10B981) else Color(0xFF94A3B8),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Status Log Message
            Text(
                syncState.statusMessage,
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Recent Synced Files List
            Text("Recently Synced to PC", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White)
            if (syncState.recentFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF131D31).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Any new photos, videos, or documents saved to the selected folder will sync automatically and instantly to your PC.",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(syncState.recentFiles) { fname ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF131D31), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                            Text(fname, fontSize = 13.sp, color = Color.White, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    // Dialog for Editing PC Destination Folder
    if (showEditPcFolderDialog) {
        AlertDialog(
            onDismissRequest = { if (!isSavingPcFolder) showEditPcFolderDialog = false },
            title = { Text("Edit PC Destination Folder", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter the destination path on your PC where synced files will be saved (e.g., D:\\SyncedFiles or C:\\Users\\Name\\Sync):",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    OutlinedTextField(
                        value = editingPcFolderText,
                        onValueChange = { editingPcFolderText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("D:\\SyncedFiles") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF06B6D4),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = editingPcFolderText.trim()
                        if (path.isNotEmpty()) {
                            isSavingPcFolder = true
                            scope.launch {
                                val (ok, msg) = FolderSyncManager.setPcDestination(context, path, targetIp, password)
                                isSavingPcFolder = false
                                if (ok) {
                                    Toast.makeText(context, "PC folder updated!", Toast.LENGTH_SHORT).show()
                                    showEditPcFolderDialog = false
                                } else {
                                    Toast.makeText(context, "Error: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF06B6D4)),
                    enabled = !isSavingPcFolder && editingPcFolderText.isNotBlank()
                ) {
                    if (isSavingPcFolder) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    } else {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditPcFolderDialog = false },
                    enabled = !isSavingPcFolder
                ) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = Color(0xFF131D31)
        )
    }
}
