package com.nos486.smsbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.text.format.DateUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process alive so SMS broadcasts are always delivered, reacts
 * instantly to connectivity coming back, and periodically scans the inbox for missed messages.
 */
class ForwarderService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastReconnectFlush = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                if (InboxScanner.scan(this@ForwarderService) > 0) Scheduler.flushNow(this@ForwarderService)
                updateNotification(this@ForwarderService)
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        goForeground()
        thread = HandlerThread("forwarder").apply { start() }
        handler = Handler(thread.looper)
        registerNetworkCallback()
        handler.postDelayed(tick, 5_000)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (!Prefs(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this), type)
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                val now = System.currentTimeMillis()
                // Internet is back: skip whatever backoff delay WorkManager is sitting on.
                if (now - lastReconnectFlush > 10_000 &&
                    SmsDatabase.get(this@ForwarderService).count(SmsDatabase.STATUS_PENDING) > 0
                ) {
                    lastReconnectFlush = now
                    Scheduler.flushImmediately(this@ForwarderService)
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb, handler)
            networkCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", t)
        }
    }

    override fun onDestroy() {
        running = false
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        super.onDestroy()
        // If we were killed while still enabled, the periodic worker / next SMS will bring us back.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped away from recents: make sure we come right back.
        start(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ForwarderService"
        const val CHANNEL_ID = "forwarder"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 60_000L

        @Volatile private var running = false

        fun start(context: Context) {
            if (!Prefs(context).enabled) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ForwarderService::class.java))
            } catch (t: Throwable) {
                // Android 12+ may forbid starting from the background unless battery optimisation
                // is disabled for us. WorkManager still delivers messages in that case.
                Log.w(TAG, "Could not start foreground service", t)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwarderService::class.java))
        }

        fun refresh(context: Context) {
            if (running) updateNotification(context)
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val ch = NotificationChannel(CHANNEL_ID, "SMS forwarding", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that SMS Bridge is running"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }

        private fun updateNotification(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(context))
            }
        }

        private fun buildNotification(context: Context): Notification {
            val prefs = Prefs(context)
            val pending = runCatching { SmsDatabase.get(context).count(SmsDatabase.STATUS_PENDING) }.getOrDefault(0)
            val text = when {
                !prefs.isConfigured -> "Server URL not set — tap to configure"
                pending > 0 -> "$pending waiting to send" +
                    (prefs.lastError.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                prefs.lastSuccessAt > 0 -> "All delivered · last " +
                    DateUtils.getRelativeTimeSpanString(prefs.lastSuccessAt)
                else -> "Listening for SMS"
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("SMS Bridge active")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(open)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
