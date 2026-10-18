package com.example.remoteapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class GithubReleaseInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String
)

object AppUpdater {
    private const val TAG = "AppUpdater"
    const val GITHUB_REPO = "Priyadharsant/Remote-Pc"
    const val DEFAULT_APP_VERSION = "3.0.1"
    const val CURRENT_APP_VERSION = "3.0.1"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getAppVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: DEFAULT_APP_VERSION
        } catch (e: Exception) {
            DEFAULT_APP_VERSION
        }
    }

    private fun parseSemver(v: String): Triple<Int, Int, Int> {
        val clean = v.replace(Regex("^[^0-9]+"), "").trim()
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return Triple(major, minor, patch)
    }

    fun isNewer(latest: String, current: String): Boolean {
        val l = parseSemver(latest)
        val c = parseSemver(current)
        if (l.first > c.first) return true
        if (l.first < c.first) return false
        if (l.second > c.second) return true
        if (l.second < c.second) return false
        return l.third > c.third
    }

    suspend fun checkUpdate(currentVersion: String = DEFAULT_APP_VERSION): GithubReleaseInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "RemotePC-Android-App/3.0")
                    .addHeader("Accept", "application/vnd.github+json")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "").trim()
                    val title = json.optString("name", tag)
                    val notes = json.optString("body", "No release notes provided.")
                    val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases")

                    var apkUrl = ""
                    var apkSize = 0L

                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "").lowercase()
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }

                    val updateAvailable = isNewer(tag, currentVersion) && apkUrl.isNotEmpty()

                    GithubReleaseInfo(
                        hasUpdate = updateAvailable,
                        currentVersion = currentVersion,
                        latestVersion = tag,
                        title = title,
                        notes = notes,
                        apkUrl = apkUrl,
                        apkSize = apkSize,
                        htmlUrl = htmlUrl
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check update", e)
                null
            }
        }
    }

    /**
     * High-Speed Multi-Stream Parallel Range Downloader
     * Modeled after Finance Application's high-performance chunked download architecture.
     * Uses 4 concurrent HTTP Range coroutines and RandomAccessFile seeking.
     */
    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (progress: Float, speed: String, streams: Int, transferredBytes: Long, totalBytes: Long) -> Unit,
        onDownloaded: () -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val targetDir = File(context.cacheDir, "updates")
                targetDir.mkdirs()
                val apkFile = File(targetDir, "Remote_PC_Update.apk")
                if (apkFile.exists()) apkFile.delete()

                // 1. Resolve redirect and metadata
                var finalUrl = apkUrl
                var totalBytes = -1L
                var acceptRanges = false

                val probeReq = Request.Builder()
                    .url(apkUrl)
                    .addHeader("User-Agent", "RemotePC-Android-App/3.0")
                    .build()

                httpClient.newCall(probeReq).execute().use { probeResp ->
                    if (!probeResp.isSuccessful) {
                        withContext(Dispatchers.Main) { onError("HTTP error: ${probeResp.code}") }
                        return@withContext
                    }
                    finalUrl = probeResp.request.url.toString()
                    totalBytes = probeResp.body?.contentLength() ?: -1L
                    val acceptHeader = probeResp.header("Accept-Ranges") ?: ""
                    acceptRanges = acceptHeader.contains("bytes", ignoreCase = true) || totalBytes > 0
                }

                val concurrency = 4
                if (acceptRanges && totalBytes > 2 * 1024 * 1024) {
                    // ==========================================
                    // Multi-Stream 4-Coroutine Parallel Download
                    // ==========================================
                    Log.d(TAG, "Multi-stream download starting: $concurrency streams, total: $totalBytes bytes")
                    RandomAccessFile(apkFile, "rw").use { raf ->
                        raf.setLength(totalBytes)
                    }

                    val chunkSize = (totalBytes + concurrency - 1) / concurrency
                    val transferredAtomic = AtomicLong(0L)
                    val startTime = System.currentTimeMillis()
                    var lastReportTime = 0L

                    val deferreds = (0 until concurrency).map { streamIdx ->
                        val startByte = streamIdx * chunkSize
                        val endByte = minOf(totalBytes - 1, (streamIdx + 1) * chunkSize - 1)

                        async(Dispatchers.IO) {
                            val rangeReq = Request.Builder()
                                .url(finalUrl)
                                .addHeader("Range", "bytes=$startByte-$endByte")
                                .addHeader("User-Agent", "RemotePC-Android-App/3.0")
                                .build()

                            httpClient.newCall(rangeReq).execute().use { chunkResp ->
                                if (chunkResp.code != 206 && chunkResp.code != 200) {
                                    throw IOException("Stream $streamIdx failed: HTTP ${chunkResp.code}")
                                }
                                val inputStream = chunkResp.body?.byteStream() ?: throw IOException("Empty chunk stream")
                                RandomAccessFile(apkFile, "rw").use { raf ->
                                    raf.seek(startByte)
                                    val buffer = ByteArray(32 * 1024)
                                    var read: Int
                                    while (inputStream.read(buffer).also { read = it } != -1) {
                                        raf.write(buffer, 0, read)
                                        val curTransferred = transferredAtomic.addAndGet(read.toLong())
                                        val now = System.currentTimeMillis()
                                        if (now - lastReportTime > 120 || curTransferred >= totalBytes) {
                                            lastReportTime = now
                                            val elapsedSec = (now - startTime) / 1000.0.coerceAtLeast(0.001)
                                            val mbps = (curTransferred / (1024.0 * 1024.0) / elapsedSec)
                                            val speedStr = String.format(Locale.US, "%.1f MB/s", mbps)
                                            val progress = (curTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                            withContext(Dispatchers.Main) {
                                                onProgress(progress, speedStr, concurrency, curTransferred, totalBytes)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    deferreds.awaitAll()
                } else {
                    // ==========================================
                    // Fallback: High-Speed Buffered Single Stream
                    // ==========================================
                    Log.d(TAG, "Single stream download starting for $apkUrl")
                    val dlReq = Request.Builder()
                        .url(finalUrl)
                        .addHeader("User-Agent", "RemotePC-Android-App/3.0")
                        .build()

                    httpClient.newCall(dlReq).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            withContext(Dispatchers.Main) { onError("Download failed: HTTP ${resp.code}") }
                            return@withContext
                        }
                        val body = resp.body ?: run {
                            withContext(Dispatchers.Main) { onError("Empty response body") }
                            return@withContext
                        }

                        val streamBytes = body.contentLength().takeIf { it > 0 } ?: totalBytes
                        var transferred = 0L
                        val startTime = System.currentTimeMillis()
                        var lastReport = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    transferred += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport > 120 || (streamBytes > 0 && transferred >= streamBytes)) {
                                        lastReport = now
                                        val elapsedSec = (now - startTime) / 1000.0.coerceAtLeast(0.001)
                                        val mbps = (transferred / (1024.0 * 1024.0) / elapsedSec)
                                        val speedStr = String.format(Locale.US, "%.1f MB/s", mbps)
                                        val progress = if (streamBytes > 0) (transferred.toFloat() / streamBytes.toFloat()).coerceIn(0f, 1f) else 0f
                                        withContext(Dispatchers.Main) {
                                            onProgress(progress, speedStr, 1, transferred, streamBytes)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!apkFile.exists() || apkFile.length() == 0L) {
                    withContext(Dispatchers.Main) { onError("Downloaded file is empty or missing") }
                    return@withContext
                }

                withContext(Dispatchers.Main) {
                    onDownloaded()
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update", e)
                withContext(Dispatchers.Main) { onError(e.localizedMessage ?: "Download failed") }
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
        }
    }
}
