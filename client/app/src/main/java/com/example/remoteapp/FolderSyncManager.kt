package com.example.remoteapp

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance background folder sync manager.
 * Detects newly created photos, videos, and files INSTANTLY via MediaStore ContentObserver
 * and synchronizes them directly to the PC even when the app is in background or screen is off.
 */
object FolderSyncManager {
    private const val TAG = "FolderSyncManager"
    private const val PREFS_NAME = "remote_folder_sync_prefs"

    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var isObserverRegistered = false
    private val isSyncRunning = AtomicBoolean(false)
    private var debounceJob: Job? = null

    // OkHttpClient with optimized timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Observable Live State
    data class SyncState(
        val isEnabled: Boolean = false,
        val folderUri: String? = null,
        val folderName: String = "No folder selected",
        val startTimeMs: Long = 0L,
        val totalSynced: Int = 0,
        val isScanning: Boolean = false,
        val statusMessage: String = "Ready",
        val pcDestinationDir: String = "D:\\SyncedFiles",
        val pcFreeDiskGb: Double = 0.0,
        val recentFiles: List<String> = emptyList()
    )

    private val _stateFlow = MutableStateFlow(SyncState())
    val stateFlow = _stateFlow.asStateFlow()

    // Cached connection coordinates for resilient background execution
    private var activeTargetIp: String? = null
    private var activePassword: String? = null

    // ContentObserver for instant, real-time file detection (< 150ms)
    private var mediaObserver: ContentObserver? = null

    fun start(context: Context, targetIp: String? = null, password: String? = null) {
        if (!targetIp.isNullOrBlank()) {
            activeTargetIp = targetIp
            activePassword = password
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("last_target_ip", targetIp)
                .putString("last_password", password ?: "")
                .apply()
        }
        loadSettings(context)
        registerObserver(context.applicationContext)
        startBackgroundWorker(context.applicationContext, targetIp, password)
        triggerSyncNow(context.applicationContext)
    }

    fun stop() {
        unregisterObserver()
        syncJob?.cancel()
        syncJob = null
    }

    private fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("is_sync_enabled", false)
        val folderUri = prefs.getString("sync_folder_uri", null)
        val folderName = prefs.getString("sync_folder_name", "No folder selected") ?: "No folder selected"
        val startTime = prefs.getLong("sync_start_time_ms", 0L)
        val count = prefs.getInt("total_synced_count", 0)

        val savedIp = prefs.getString("last_target_ip", null)
        if (!savedIp.isNullOrBlank() && activeTargetIp.isNullOrBlank()) {
            activeTargetIp = savedIp
            activePassword = prefs.getString("last_password", null)
        }

        _stateFlow.value = _stateFlow.value.copy(
            isEnabled = isEnabled,
            folderUri = folderUri,
            folderName = folderName,
            startTimeMs = startTime,
            totalSynced = count
        )
    }

    fun updateSettings(
        context: Context,
        enabled: Boolean,
        folderUri: String?,
        folderName: String,
        startTimeMs: Long
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("is_sync_enabled", enabled)
            .putString("sync_folder_uri", folderUri)
            .putString("sync_folder_name", folderName)
            .putLong("sync_start_time_ms", startTimeMs)
            .apply()

        _stateFlow.value = _stateFlow.value.copy(
            isEnabled = enabled,
            folderUri = folderUri,
            folderName = folderName,
            startTimeMs = startTimeMs
        )

        // Observe folder URI directly for instant updates
        if (folderUri != null) {
            try {
                mediaObserver?.let { obs ->
                    context.applicationContext.contentResolver.registerContentObserver(Uri.parse(folderUri), true, obs)
                }
            } catch (_: Exception) {}
        }

        if (enabled && folderUri != null) {
            triggerSyncNow(context)
        }
    }

    fun resetLedger(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putStringSet("synced_files_ledger", emptySet())
            .putInt("total_synced_count", 0)
            .putLong("sync_start_time_ms", now)
            .apply()

        _stateFlow.value = _stateFlow.value.copy(
            totalSynced = 0,
            startTimeMs = now,
            recentFiles = emptyList(),
            statusMessage = "Sync history reset. Watching for new files..."
        )
    }

    private fun registerObserver(appContext: Context) {
        if (isObserverRegistered) return
        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    // High-speed debounce (150ms) for instant detection
                    debounceJob?.cancel()
                    debounceJob = syncScope.launch {
                        delay(150)
                        triggerSyncNow(appContext)
                    }
                }
            }
            val resolver = appContext.contentResolver
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer)
            }
            _stateFlow.value.folderUri?.let { uriStr ->
                try {
                    resolver.registerContentObserver(Uri.parse(uriStr), true, observer)
                } catch (_: Exception) {}
            }
            mediaObserver = observer
            isObserverRegistered = true
            Log.d(TAG, "MediaStore and SAF ContentObservers registered for instant file sync")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register ContentObserver", e)
        }
    }

    private fun unregisterObserver() {
        if (!isObserverRegistered) return
        try {
            mediaObserver?.let {
                // observer lifecycle
            }
        } catch (_: Exception) {}
        isObserverRegistered = false
    }

    fun triggerSyncNow(context: Context) {
        syncScope.launch {
            performSync(context.applicationContext)
        }
    }

    private fun startBackgroundWorker(appContext: Context, explicitIp: String?, explicitPass: String?) {
        syncJob?.cancel()
        syncJob = syncScope.launch {
            while (isActive) {
                try {
                    // Check PC status periodically
                    fetchPcStatus(appContext, explicitIp, explicitPass)

                    val curr = _stateFlow.value
                    if (curr.isEnabled && !curr.folderUri.isNullOrBlank()) {
                        performSync(appContext, explicitIp, explicitPass)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background sync tick error: ${e.message}")
                }
                delay(1200) // 1.2s rapid fallback interval (instant detection handles immediate triggers)
            }
        }
    }

    private suspend fun performSync(appContext: Context, explicitIp: String? = null, explicitPass: String? = null) {
        if (!isSyncRunning.compareAndSet(false, true)) {
            return
        }

        try {
            val currState = _stateFlow.value
            if (!currState.isEnabled || currState.folderUri.isNullOrBlank()) {
                return
            }

            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val targetIp = explicitIp 
                ?: activeTargetIp 
                ?: prefs.getString("last_target_ip", null)
                ?: SecureStorage.getServerIp(appContext)
            val password = explicitPass 
                ?: activePassword 
                ?: prefs.getString("last_password", null)
                ?: SecureStorage.getPassword(appContext)
            if (targetIp.isBlank() || targetIp == "127.0.0.1") {
                return
            }

            val treeUri = Uri.parse(currState.folderUri)
            val rootDoc = DocumentFile.fromTreeUri(appContext, treeUri)
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                _stateFlow.value = _stateFlow.value.copy(statusMessage = "Waiting for folder access...")
                return
            }

            _stateFlow.value = _stateFlow.value.copy(isScanning = true)

            val syncedLedger = prefs.getStringSet("synced_files_ledger", emptySet())?.toMutableSet() ?: mutableSetOf()
            val newlyFound = mutableListOf<DocumentFile>()

            // Crawl tree for newly created files
            fun scanDir(dir: DocumentFile) {
                val children = try { dir.listFiles() } catch (_: Exception) { emptyArray() }
                for (child in children) {
                    if (child.isDirectory) {
                        scanDir(child)
                    } else if (child.isFile) {
                        val modTime = child.lastModified()
                        // Only sync files created AFTER feature was enabled (with 2s grace for clock drift)
                        if (modTime >= (currState.startTimeMs - 2000L)) {
                            val fileKey = "${child.name}_${child.length()}_$modTime"
                            if (!syncedLedger.contains(fileKey)) {
                                newlyFound.add(child)
                            }
                        }
                    }
                }
            }

            scanDir(rootDoc)

            if (newlyFound.isNotEmpty()) {
                Log.d(TAG, "Found ${newlyFound.size} new file(s) to sync to PC")
            }

            var newlyUploadedCount = 0
            for (fileDoc in newlyFound) {
                val fileName = fileDoc.name ?: "synced_file"
                val fileKey = "${fileName}_${fileDoc.length()}_${fileDoc.lastModified()}"

                _stateFlow.value = _stateFlow.value.copy(
                    statusMessage = "Syncing $fileName to PC..."
                )

                var uploadSuccess = false
                try {
                    val stream: InputStream? = appContext.contentResolver.openInputStream(fileDoc.uri)
                    if (stream != null) {
                        val bytes = stream.use { it.readBytes() }
                        val encodedRel = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        val uploadUrl = "http://$targetIp:5055/sync/upload?relative_path=$encodedRel"

                        val req = Request.Builder()
                            .url(uploadUrl)
                            .header("x-remote-password", password)
                            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                            .build()

                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                uploadSuccess = true
                            } else {
                                Log.w(TAG, "Upload failed for $fileName HTTP ${resp.code}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error uploading $fileName: ${e.message}")
                }

                if (uploadSuccess) {
                    syncedLedger.add(fileKey)
                    newlyUploadedCount++
                    val newTotal = currState.totalSynced + newlyUploadedCount
                    prefs.edit()
                        .putStringSet("synced_files_ledger", syncedLedger)
                        .putInt("total_synced_count", newTotal)
                        .apply()

                    val updatedRecent = (listOf(fileName) + _stateFlow.value.recentFiles).take(25)
                    val mbStr = String.format(Locale.US, "%.1f", fileDoc.length() / (1024.0 * 1024.0))
                    _stateFlow.value = _stateFlow.value.copy(
                        totalSynced = newTotal,
                        recentFiles = updatedRecent,
                        statusMessage = "Synced $fileName ($mbStr MB) to PC."
                    )
                }
            }

            _stateFlow.value = _stateFlow.value.copy(
                isScanning = false,
                statusMessage = if (newlyFound.isEmpty()) "Folder up to date. Watching for new files..." else "Sync complete."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in performSync: ${e.message}")
            _stateFlow.value = _stateFlow.value.copy(isScanning = false)
        } finally {
            isSyncRunning.set(false)
        }
    }

    suspend fun fetchPcStatus(appContext: Context, explicitIp: String? = null, explicitPass: String? = null) {
        val targetIp = explicitIp ?: SecureStorage.getServerIp(appContext)
        val password = explicitPass ?: SecureStorage.getPassword(appContext)
        if (targetIp.isBlank() || targetIp == "127.0.0.1") return

        try {
            val req = Request.Builder()
                .url("http://$targetIp:5055/sync/status")
                .header("x-remote-password", password)
                .build()

            withContext(Dispatchers.IO) {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string() ?: "{}")
                        val pcDir = json.optString("destination_dir", _stateFlow.value.pcDestinationDir)
                        val freeGb = json.optDouble("disk_free_gb", _stateFlow.value.pcFreeDiskGb)
                        _stateFlow.value = _stateFlow.value.copy(
                            pcDestinationDir = pcDir,
                            pcFreeDiskGb = freeGb
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun setPcDestination(
        appContext: Context,
        newDir: String,
        explicitIp: String? = null,
        explicitPass: String? = null
    ): Pair<Boolean, String> {
        val targetIp = explicitIp ?: SecureStorage.getServerIp(appContext)
        val password = explicitPass ?: SecureStorage.getPassword(appContext)
        if (targetIp.isBlank() || targetIp == "127.0.0.1") {
            return Pair(false, "Not connected to PC")
        }

        return withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply { put("destination_dir", newDir) }
                val req = Request.Builder()
                    .url("http://$targetIp:5055/sync/destination")
                    .header("x-remote-password", password)
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val resJson = JSONObject(resp.body?.string() ?: "{}")
                        val updatedDir = resJson.optString("destination_dir", newDir)
                        val freeGb = resJson.optDouble("disk_free_gb", 0.0)
                        _stateFlow.value = _stateFlow.value.copy(
                            pcDestinationDir = updatedDir,
                            pcFreeDiskGb = freeGb,
                            statusMessage = "PC saving folder updated!"
                        )
                        Pair(true, "Updated to: $updatedDir")
                    } else {
                        Pair(false, "PC returned HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Pair(false, e.message ?: "Failed to update folder")
            }
        }
    }
}
