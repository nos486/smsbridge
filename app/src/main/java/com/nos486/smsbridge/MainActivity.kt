package com.nos486.smsbridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors

@SuppressLint("UseSwitchCompatOrMaterialCode", "SetTextI18n")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var warnings: TextView
    private lateinit var recent: TextView
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var deviceName: EditText
    private lateinit var insecure: CheckBox
    private lateinit var enabled: Switch

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        warnings = findViewById(R.id.warnings)
        recent = findViewById(R.id.recent)
        url = findViewById(R.id.url)
        token = findViewById(R.id.token)
        deviceName = findViewById(R.id.deviceName)
        insecure = findViewById(R.id.insecure)
        enabled = findViewById(R.id.enabled)

        url.setText(prefs.serverUrl)
        token.setText(prefs.token)
        deviceName.setText(prefs.deviceName)
        insecure.isChecked = prefs.allowInsecureTls
        enabled.isChecked = prefs.enabled

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.test).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.retry).setOnClickListener {
            io.execute {
                val n = SmsDatabase.get(this).retryFailed()
                Scheduler.flushImmediately(this)
                runOnUiThread { toast("ارسال مجدد شروع شد ($n پیام ناموفق دوباره در صف)") }
            }
        }
        findViewById<Button>(R.id.permissions).setOnClickListener { requestPermissionsIfNeeded() }
        findViewById<Button>(R.id.battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.appInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        requestPermissionsIfNeeded()
        if (prefs.enabled) ForwarderService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun save() {
        val u = url.text.toString().trim()
        if (u.isNotEmpty() && !(u.startsWith("http://") || u.startsWith("https://"))) {
            url.error = "باید با http:// یا https:// شروع شود"
            return
        }
        prefs.serverUrl = u
        prefs.token = token.text.toString().removePrefix("Bearer ").trim()
        prefs.deviceName = deviceName.text.toString()
        prefs.allowInsecureTls = insecure.isChecked
        prefs.enabled = enabled.isChecked
        if (prefs.enabled) {
            Scheduler.ensurePeriodic(this)
            ForwarderService.start(this)
            Scheduler.flushImmediately(this)
        } else {
            ForwarderService.stop(this)
        }
        toast("ذخیره شد")
        refresh()
    }

    private fun sendTest() {
        save()
        if (!prefs.isConfigured) return toast("ابتدا آدرس را وارد کنید")
        val sms = SmsRecord(
            id = UUID.randomUUID().toString(), sender = "TEST-SENDER", body = "این یک پیامک آزمایشی از SMS Bridge است",
            receivedAt = System.currentTimeMillis(), simSlot = null,
            status = SmsDatabase.STATUS_PENDING, attempts = 0, lastError = null,
        )
        toast("در حال ارسال…")
        io.execute {
            val msg = when (val r = ApiClient.send(prefs, sms)) {
                SendResult.Ok -> "✅ ارسال موفق بود"
                is SendResult.Reject -> "❌ سرور رد کرد: ${r.reason}"
                is SendResult.Retry -> "❌ خطا: ${r.reason}"
            }
            runOnUiThread { toast(msg, long = true) }
        }
    }

    private fun refresh() {
        io.execute {
            val db = SmsDatabase.get(this)
            val pending = db.count(SmsDatabase.STATUS_PENDING)
            val sent = db.count(SmsDatabase.STATUS_SENT)
            val failed = db.count(SmsDatabase.STATUS_FAILED)
            val items = db.recent(30)
            val last = prefs.lastSuccessAt
            val statusText = buildString {
                append(if (prefs.enabled) "● فعال" else "○ غیرفعال")
                append("\nدر صف: $pending   ارسال‌شده: $sent   ناموفق: $failed")
                append("\nآخرین ارسال موفق: ")
                append(if (last > 0) DateUtils.getRelativeTimeSpanString(last) else "—")
                if (prefs.lastError.isNotEmpty() && pending + failed > 0) append("\nآخرین خطا: ${prefs.lastError}")
                append("\nDevice ID: ${prefs.deviceId}")
            }
            val recentText = if (items.isEmpty()) "هنوز پیامکی دریافت نشده" else items.joinToString("\n\n") { r ->
                val icon = when (r.status) {
                    SmsDatabase.STATUS_SENT -> "✅"
                    SmsDatabase.STATUS_FAILED -> "❌"
                    else -> "⏳"
                }
                val time = DateUtils.formatDateTime(
                    this, r.receivedAt,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NUMERIC_DATE
                )
                val err = r.lastError?.takeIf { r.status != SmsDatabase.STATUS_SENT }?.let { "\n   ⚠ $it" } ?: ""
                "$icon ${r.sender} · $time · sim=${r.simSlot ?: "-"}\n   ${r.body.take(160)}$err"
            }
            val warn = collectWarnings()
            runOnUiThread {
                status.text = statusText
                recent.text = recentText
                warnings.text = warn.joinToString("\n")
                warnings.visibility = if (warn.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
    }

    private fun collectWarnings(): List<String> = buildList {
        if (!prefs.isConfigured) add("• آدرس سرور تنظیم نشده")
        if (!granted(Manifest.permission.RECEIVE_SMS)) add("• دسترسی دریافت پیامک داده نشده (اگر غیرفعال است: App info ← ⋮ ← Allow restricted settings)")
        if (!granted(Manifest.permission.READ_SMS)) add("• دسترسی خواندن پیامک داده نشده (برای بازیابی پیامک‌های از دست رفته)")
        if (!isIgnoringBatteryOptimizations()) add("• بهینه‌سازی باتری فعال است؛ ممکن است سیستم برنامه را ببندد")
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(this@MainActivity).areNotificationsEnabled())
            add("• اعلان‌ها خاموش است (برای سرویس پس‌زمینه پیشنهاد می‌شود)")
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filterNot(::granted)
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (prefs.enabled) ForwarderService.start(this)
            if (!isIgnoringBatteryOptimizations()) requestBatteryExemption()
            refresh()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) return toast("بهینه‌سازی باتری از قبل غیرفعال است")
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMS = 1
    }
}
