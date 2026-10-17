package com.example.remoteapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    const val CURRENT_APP_VERSION = "3.0"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun parseSemver(v: String): Triple<Int, Int, Int> {
        val clean = v.replace(Regex("^[^0-9]+"), "").trim()
        val parts = clean.split(".").mapNotNull { it.toIntOrNull() }
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        val patch = parts.getOrElse(2) { 0 }
        return Triple(major, minor, patch)
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val l = parseSemver(latest)
        val c = parseSemver(current)
        if (l.first > c.first) return true
        if (l.first < c.first) return false
        if (l.second > c.second) return true
        if (l.second < c.second) return false
        return l.third > c.third
    }

    suspend fun checkUpdate(currentVersion: String = CURRENT_APP_VERSION): GithubReleaseInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "RemotePC-Android-App")
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

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit,
        onDownloaded: () -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(apkUrl)
                    .addHeader("User-Agent", "RemotePC-Android-App")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) { onError("Download failed: HTTP ${resp.code}") }
                        return@withContext
                    }
                    val body = resp.body ?: run {
                        withContext(Dispatchers.Main) { onError("Empty response body") }
                        return@withContext
                    }

                    val targetDir = File(context.cacheDir, "updates")
                    targetDir.mkdirs()
                    val apkFile = File(targetDir, "Remote_PC_Update.apk")
                    if (apkFile.exists()) apkFile.delete()

                    val contentLength = body.contentLength()
                    var totalRead = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                totalRead += read
                                if (contentLength > 0) {
                                    val progress = totalRead.toFloat() / contentLength.toFloat()
                                    withContext(Dispatchers.Main) { onProgress(progress) }
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        onDownloaded()
                        installApk(context, apkFile)
                    }
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
