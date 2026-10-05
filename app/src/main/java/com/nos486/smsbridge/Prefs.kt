package com.nos486.smsbridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

class Prefs(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("smsbridge", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    var deviceName: String
        get() = sp.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()
        set(v) = sp.edit().putString(KEY_DEVICE_NAME, v.trim()).apply()

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var allowInsecureTls: Boolean
        get() = sp.getBoolean(KEY_INSECURE, false)
        set(v) = sp.edit().putBoolean(KEY_INSECURE, v).apply()

    /** Inbox rows with `date` <= this value have already been looked at by [InboxScanner]. */
    var lastInboxScan: Long
        get() = sp.getLong(KEY_LAST_SCAN, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_SCAN, v).apply()

    var lastSuccessAt: Long
        get() = sp.getLong(KEY_LAST_OK, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_OK, v).apply()

    var lastError: String
        get() = sp.getString(KEY_LAST_ERR, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ERR, v).apply()

    /** Stable per-install id; ANDROID_ID survives reinstalls (same signing key), fallback to a UUID. */
    @get:SuppressLint("HardwareIds")
    val deviceId: String
        get() {
            sp.getString(KEY_DEVICE_ID, null)?.let { return it }
            val androidId = runCatching {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") androidId
            else UUID.randomUUID().toString()
            sp.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }

    val isConfigured: Boolean get() = serverUrl.startsWith("http://") || serverUrl.startsWith("https://")

    companion object {
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INSECURE = "insecure_tls"
        private const val KEY_LAST_SCAN = "last_inbox_scan"
        private const val KEY_LAST_OK = "last_success"
        private const val KEY_LAST_ERR = "last_error"
        private const val KEY_DEVICE_ID = "device_id"

        fun defaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        }
    }
}
