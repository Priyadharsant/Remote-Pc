package com.example.remoteapp

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateDiscovery: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onResetSetup: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("RemoteAppPrefs", Context.MODE_PRIVATE) }

    var phoneName by remember { mutableStateOf(SecureStorage.getDeviceName(context)) }
    var autoConnectEnabled by remember { mutableStateOf(SecureStorage.isAutoConnectEnabled(context)) }
    var autostartOnBoot by remember { mutableStateOf(prefs.getBoolean("autostart_on_boot", true)) }
    var isAutoTheme by remember { mutableStateOf(ThemeState.isAutoTheme) }
    var isDarkTheme by remember { mutableStateOf(ThemeState.isDark) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<GithubReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val deviceId = remember { SecureStorage.getDeviceId(context) }
    val serverIp = remember { SecureStorage.getServerIp(context) }
    val serverPort = remember { SecureStorage.getServerPort(context) }
    val serverName = remember { SecureStorage.getServerName(context) }
    val hasToken = remember { SecureStorage.getDeviceToken(context).isNotEmpty() }

    Scaffold(
        containerColor = AppTheme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AppTheme.TextPrimary)
                        Text("Preferences & Security", fontSize = 11.sp, color = AppTheme.AccentSky)
                    }
                },
                navigationIcon = {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // SECTION 1: DEVICE & SECURITY
            Text("DEVICE & SECURITY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentCyan, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Phone Name input
                    OutlinedTextField(
                        value = phoneName,
                        onValueChange = {
                            phoneName = it
                            SecureStorage.saveDeviceName(context, it)
                        },
                        label = { Text("Phone Device Name") },
                        leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null, tint = AppTheme.AccentCyan) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentCyan,
                            unfocusedBorderColor = AppTheme.CardBorderSubtle,
                            focusedTextColor = AppTheme.TextPrimary,
                            unfocusedTextColor = AppTheme.TextPrimary
                        )
                    )

                    // Device ID
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Device Identity ID", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text(
                                if (deviceId.length > 18) "${deviceId.take(8)}...${deviceId.takeLast(8)}" else deviceId,
                                fontSize = 11.sp,
                                color = AppTheme.TextSecondary
                            )
                        }
                        Icon(Icons.Rounded.Fingerprint, null, tint = AppTheme.TextMuted, modifier = Modifier.size(24.dp))
                    }

                    HorizontalDivider(color = AppTheme.CardBorderSubtle, thickness = 0.5.dp)

                    // Token Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Hardware KeyStore Token", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text(
                                if (hasToken) "Active (256-bit AES Token Auth)" else "Not Registered (Password-only mode)",
                                fontSize = 11.sp,
                                color = if (hasToken) AppTheme.AccentEmerald else AppTheme.AccentAmber
                            )
                        }
                        Icon(
                            if (hasToken) Icons.Rounded.VerifiedUser else Icons.Rounded.WarningAmber,
                            null,
                            tint = if (hasToken) AppTheme.AccentEmerald else AppTheme.AccentAmber,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // SECTION 2: CONNECTION & TARGET
            Text("CONNECTION & TARGET PC", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentSky, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Paired PC", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text(
                                if (serverIp.isNotEmpty()) "$serverName • Connected via Wi-Fi" else "No PC configured",
                                fontSize = 11.sp,
                                color = AppTheme.TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = onNavigateDiscovery,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppTheme.AccentSky.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.Search, null, modifier = Modifier.size(14.dp), tint = AppTheme.AccentSky)
                            Spacer(Modifier.width(4.dp))
                            Text("Find PC", fontSize = 11.sp, color = AppTheme.AccentSky, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = AppTheme.CardBorderSubtle, thickness = 0.5.dp)

                    // Auto-Connect Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Connect on Wi-Fi", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text("Seamlessly connect when on the same Wi-Fi network", fontSize = 11.sp, color = AppTheme.TextSecondary)
                        }
                        Switch(
                            checked = autoConnectEnabled,
                            onCheckedChange = { checked ->
                                autoConnectEnabled = checked
                                SecureStorage.setAutoConnectEnabled(context, checked)
                                prefs.edit().putBoolean("auto_connect_enabled", checked).apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentSky)
                        )
                    }
                }
            }

            // SECTION 3: ALWAYS-ON BACKGROUND RELIABILITY
            Text("24/7 BACKGROUND RELIABILITY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentEmerald, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Service Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Foreground Service", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text(
                                if (RemoteBackgroundService.isRunning) "Running (Always-on connection active)" else "Inactive (Starts upon connection)",
                                fontSize = 11.sp,
                                color = if (RemoteBackgroundService.isRunning) AppTheme.AccentEmerald else AppTheme.TextMuted
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (RemoteBackgroundService.isRunning) AppTheme.AccentEmerald else AppTheme.TextMuted)
                        )
                    }

                    HorizontalDivider(color = AppTheme.CardBorderSubtle, thickness = 0.5.dp)

                    // Unrestricted Battery Optimization
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Unrestricted Battery Mode", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text("Whitelists app from OEM battery savers to prevent sleep kills", fontSize = 11.sp, color = AppTheme.TextSecondary)
                        }
                        OutlinedButton(
                            onClick = onRequestBatteryOptimization,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AppTheme.AccentEmerald.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Whitelist", fontSize = 11.sp, color = AppTheme.AccentEmerald, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = AppTheme.CardBorderSubtle, thickness = 0.5.dp)

                    // Autostart on Boot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Start on Phone Boot", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text("Restores background service automatically after reboot", fontSize = 11.sp, color = AppTheme.TextSecondary)
                        }
                        Switch(
                            checked = autostartOnBoot,
                            onCheckedChange = { checked ->
                                autostartOnBoot = checked
                                prefs.edit().putBoolean("autostart_on_boot", checked).apply()
                                Toast.makeText(context, if (checked) "Boot auto-start enabled" else "Boot auto-start disabled", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentEmerald)
                        )
                    }
                }
            }

            // SECTION 4: APPEARANCE
            Text("APPEARANCE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.TextMuted, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Row 1: Auto-Detect Device Theme
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Detect Device Theme", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text(
                                if (isAutoTheme) "Active • Following device system appearance (${if (isDarkTheme) "Dark" else "Light"})" else "Disabled • Using manual theme selection",
                                fontSize = 11.sp,
                                color = if (isAutoTheme) AppTheme.AccentEmerald else AppTheme.TextSecondary
                            )
                        }
                        Switch(
                            checked = isAutoTheme,
                            onCheckedChange = { checked ->
                                isAutoTheme = checked
                                ThemeState.isAutoTheme = checked
                                prefs.edit().putBoolean("auto_theme", checked).apply()
                                if (checked) {
                                    val isSystemDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                                    ThemeState.isDark = isSystemDark
                                    isDarkTheme = isSystemDark
                                }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentCyan)
                        )
                    }

                    if (!isAutoTheme) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = AppTheme.CardBorderSubtle)
                        Spacer(Modifier.height(14.dp))

                        // Row 2: Manual Dark/Light switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isDarkTheme) "Dark Cyber Theme" else "Light Neumorphic Theme", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                                Text(if (isDarkTheme) "High contrast deep black" else "Clean modern light style", fontSize = 11.sp, color = AppTheme.TextSecondary)
                            }
                            Switch(
                                checked = isDarkTheme,
                                onCheckedChange = { checked ->
                                    isDarkTheme = checked
                                    ThemeState.isDark = checked
                                    prefs.edit().putBoolean("dark_mode", checked).apply()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentCyan)
                            )
                        }
                    }
                }
            }

            // SECTION: ABOUT & UPDATES
            Text("ABOUT & UPDATES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentCyan, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.CardBorderSubtle)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Remote PC Mobile", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.TextPrimary)
                            Text("Current Version: ${AppUpdater.CURRENT_APP_VERSION}", fontSize = 11.sp, color = AppTheme.TextSecondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.AccentCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.4f))
                        ) {
                            Text(
                                "v${AppUpdater.CURRENT_APP_VERSION}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.AccentCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            isCheckingUpdate = true
                            scope.launch {
                                val info = AppUpdater.checkUpdate()
                                isCheckingUpdate = false
                                if (info != null && info.hasUpdate) {
                                    updateInfo = info
                                    showUpdateDialog = true
                                } else if (info != null) {
                                    Toast.makeText(context, "App is up to date (v${info.currentVersion})", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Could not check for updates. Check internet.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isCheckingUpdate && !isDownloadingUpdate,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AppTheme.AccentCyan.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentCyan),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Checking GitHub Releases...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Rounded.SystemUpdate, null, modifier = Modifier.size(16.dp), tint = AppTheme.AccentCyan)
                            Spacer(Modifier.width(8.dp))
                            Text("Check for Updates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // SECTION 5: FACTORY RESET
            Text("DATA & SYSTEM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppTheme.AccentRose, letterSpacing = 1.sp)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.CardDarkElevated),
                border = BorderStroke(1.dp, AppTheme.AccentRose.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Clear Pairing & Reset Setup", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.AccentRose)
                    Text(
                        "Removes all hardware-backed tokens, saved passwords, and configured PC connection settings.",
                        fontSize = 11.sp,
                        color = AppTheme.TextSecondary
                    )
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, AppTheme.AccentRose.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentRose),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.DeleteForever, null, modifier = Modifier.size(16.dp), tint = AppTheme.AccentRose)
                        Spacer(Modifier.width(8.dp))
                        Text("Reset Setup & Clear Paired Data", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                containerColor = AppTheme.CardDarkElevated,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = AppTheme.AccentRose, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reset All Setup?", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Text(
                        "This will wipe your paired 256-bit authentication token, remove saved credentials, and return to the initial setup screen.",
                        color = AppTheme.TextSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetConfirm = false
                            SecureStorage.clearAll(context)
                            prefs.edit().clear().apply()
                            RemoteBackgroundService.stop(context)
                            Toast.makeText(context, "Pairing cleared. Opening setup.", Toast.LENGTH_SHORT).show()
                            onResetSetup()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentRose)
                    ) {
                        Text("Confirm Reset", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) {
                        Text("Cancel", color = AppTheme.TextSecondary)
                    }
                }
            )
        }

        if (showUpdateDialog && updateInfo != null) {
            val info = updateInfo!!
            AlertDialog(
                onDismissRequest = { if (!isDownloadingUpdate) showUpdateDialog = false },
                containerColor = AppTheme.CardDarkElevated,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SystemUpdate, null, tint = AppTheme.AccentCyan, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Update Available", color = AppTheme.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Version ${info.latestVersion} is available!", fontWeight = FontWeight.SemiBold, color = AppTheme.AccentCyan, fontSize = 13.sp)
                        Text(info.notes, color = AppTheme.TextSecondary, fontSize = 12.sp, maxLines = 6)
                        if (isDownloadingUpdate) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { updateDownloadProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = AppTheme.AccentCyan,
                                trackColor = AppTheme.CardDark
                            )
                            Text(
                                "Downloading: ${(updateDownloadProgress * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = AppTheme.TextSecondary
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isDownloadingUpdate = true
                            scope.launch {
                                AppUpdater.downloadAndInstallApk(
                                    context = context,
                                    apkUrl = info.apkUrl,
                                    onProgress = { p -> updateDownloadProgress = p },
                                    onDownloaded = {
                                        isDownloadingUpdate = false
                                        showUpdateDialog = false
                                        Toast.makeText(context, "Installing update...", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        isDownloadingUpdate = false
                                        Toast.makeText(context, "Update error: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        },
                        enabled = !isDownloadingUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentCyan)
                    ) {
                        Text(if (isDownloadingUpdate) "Downloading..." else "Download & Install", color = AppTheme.BackgroundDark, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (!isDownloadingUpdate) {
                        TextButton(onClick = { showUpdateDialog = false }) {
                            Text("Later", color = AppTheme.TextSecondary)
                        }
                    }
                }
            )
        }
    }
}
