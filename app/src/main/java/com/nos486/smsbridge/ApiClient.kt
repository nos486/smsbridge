package com.nos486.smsbridge

import android.annotation.SuppressLint
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

sealed class SendResult {
    object Ok : SendResult()
    /** Network problem / server down / 5xx / 429 — keep the message and try again later. */
    data class Retry(val reason: String) : SendResult()
    /** The server rejected the payload itself; retrying the same body will never succeed. */
    data class Reject(val reason: String) : SendResult()
}

object ApiClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val secureClient: OkHttpClient by lazy { baseBuilder().build() }

    private val insecureClient: OkHttpClient by lazy {
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        baseBuilder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun baseBuilder() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)

    fun formatTimestamp(millis: Long): String =
        // e.g. 2026-10-05T12:34:56.789+03:30
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))

    fun buildPayload(prefs: Prefs, sms: SmsRecord): JSONObject = JSONObject().apply {
        put("id", sms.id)
        put("sender", sms.sender)
        put("message", sms.body)
        put("received_at", formatTimestamp(sms.receivedAt))
        put("sim_slot", sms.simSlot ?: JSONObject.NULL)
        put("device_id", prefs.deviceId)
        put("device_name", prefs.deviceName)
    }

    fun send(prefs: Prefs, sms: SmsRecord): SendResult {
        val url = prefs.serverUrl
        if (!prefs.isConfigured) return SendResult.Retry("Server URL is not configured")

        val request = try {
            Request.Builder()
                .url(url)
                .post(buildPayload(prefs, sms).toString().toRequestBody(JSON))
                .header("Accept", "application/json, */*")
                .header("User-Agent", "SmsBridge/${BuildConfig.VERSION_NAME} (Android)")
                // The message id never changes between retries, so the server can dedupe safely.
                .header("Idempotency-Key", sms.id)
                .apply { if (prefs.token.isNotEmpty()) header("Authorization", "Bearer ${prefs.token}") }
                .build()
        } catch (e: IllegalArgumentException) {
            return SendResult.Retry("Invalid server URL: ${e.message}")
        }

        val client = if (prefs.allowInsecureTls) insecureClient else secureClient
        return try {
            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val snippet = runCatching { resp.peekBody(300).string() }.getOrDefault("")
                when {
                    resp.isSuccessful -> SendResult.Ok
                    // Duplicate idempotency key => the server already has this message.
                    code == 409 -> SendResult.Ok
                    code == 400 || code == 413 || code == 415 || code == 422 ->
                        SendResult.Reject("HTTP $code $snippet".trim())
                    // 401/403/404/408/429/5xx etc: likely config/token/server issue that can be fixed — keep retrying.
                    else -> SendResult.Retry("HTTP $code $snippet".trim())
                }
            }
        } catch (e: IOException) {
            SendResult.Retry("${e.javaClass.simpleName}: ${e.message ?: "network error"}")
        } catch (e: Exception) {
            SendResult.Retry("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
