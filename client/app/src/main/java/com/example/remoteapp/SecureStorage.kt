package com.example.remoteapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureStorage {
    private const val TAG = "SecureStorage"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "RemotePcKeyStoreMasterKey"
    private const val PREFS_NAME = "RemoteAppSecureVault"

    private const val KEY_IS_CONFIGURED = "sec_is_configured"
    private const val KEY_DEVICE_ID = "sec_device_id"
    private const val KEY_DEVICE_NAME = "sec_device_name"
    private const val KEY_DEVICE_TOKEN = "sec_device_token"
    private const val KEY_SERVER_IP = "sec_server_ip"
    private const val KEY_SERVER_PORT = "sec_server_port"
    private const val KEY_SERVER_NAME = "sec_server_name"
    private const val KEY_SERVER_PASS = "sec_server_pass"
    private const val KEY_AUTO_CONNECT = "sec_auto_connect"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(cipherText, Base64.NO_WRAP)
            "$ivB64:$ctB64"
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            plainText
        }
    }

    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty() || !encryptedText.contains(":")) return encryptedText
        return try {
            val parts = encryptedText.split(":", limit = 2)
            if (parts.size != 2) return encryptedText
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed, fallback", e)
            encryptedText
        }
    }

    fun isConfigured(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_CONFIGURED, false)
    }

    fun setConfigured(context: Context, configured: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_CONFIGURED, configured).apply()
    }

    fun getDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_DEVICE_NAME, null) ?: Build.MODEL ?: "Android Phone"
    }

    fun saveDeviceName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_NAME, name.trim()).apply()
    }

    fun getDeviceToken(context: Context): String {
        val prefs = getPrefs(context)
        val enc = prefs.getString(KEY_DEVICE_TOKEN, "") ?: ""
        return decrypt(enc)
    }

    fun saveDeviceToken(context: Context, token: String) {
        val enc = encrypt(token)
        getPrefs(context).edit().putString(KEY_DEVICE_TOKEN, enc).apply()
    }

    fun getServerIp(context: Context): String {
        return getPrefs(context).getString(KEY_SERVER_IP, "") ?: ""
    }

    fun getServerPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_SERVER_PORT, 5055)
    }

    fun getServerName(context: Context): String {
        return getPrefs(context).getString(KEY_SERVER_NAME, "PC") ?: "PC"
    }

    fun saveServerTarget(context: Context, hostAndPort: String, serverName: String = "PC") {
        val clean = if (hostAndPort.contains(":")) hostAndPort else "$hostAndPort:5055"
        val parts = clean.split(":")
        val ip = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 5055

        getPrefs(context).edit()
            .putString(KEY_SERVER_IP, ip)
            .putInt(KEY_SERVER_PORT, port)
            .putString(KEY_SERVER_NAME, serverName)
            .apply()
    }

    fun getPassword(context: Context): String {
        val enc = getPrefs(context).getString(KEY_SERVER_PASS, "") ?: ""
        return decrypt(enc)
    }

    fun savePassword(context: Context, pass: String) {
        val enc = encrypt(pass)
        getPrefs(context).edit().putString(KEY_SERVER_PASS, enc).apply()
    }

    fun isAutoConnectEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_CONNECT, true)
    }

    fun setAutoConnectEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
