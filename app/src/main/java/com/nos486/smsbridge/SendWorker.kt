package com.nos486.smsbridge

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Drains the outbox. Stops at the first transient failure and lets WorkManager back off. */
class SendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val db = SmsDatabase.get(applicationContext)
        if (!prefs.enabled) return Result.success()
        if (!prefs.isConfigured) {
            prefs.lastError = "Server URL is not configured"
            return Result.success() // Re-triggered when the user saves settings.
        }

        var processed = 0
        while (!isStopped && processed < MAX_PER_RUN) {
            val batch = db.nextPending(20)
            if (batch.isEmpty()) break
            for (sms in batch) {
                if (isStopped) return Result.retry()
                when (val r = ApiClient.send(prefs, sms)) {
                    SendResult.Ok -> {
                        db.markSent(sms.id)
                        prefs.lastSuccessAt = System.currentTimeMillis()
                        prefs.lastError = ""
                    }
                    is SendResult.Reject -> {
                        Log.w(TAG, "Server rejected ${sms.id}: ${r.reason}")
                        db.markFailed(sms.id, r.reason)
                        prefs.lastError = r.reason
                    }
                    is SendResult.Retry -> {
                        Log.w(TAG, "Will retry ${sms.id}: ${r.reason}")
                        db.markAttempt(sms.id, r.reason)
                        prefs.lastError = r.reason
                        ForwarderService.refresh(applicationContext)
                        return Result.retry()
                    }
                }
                processed++
            }
        }
        db.purgeSent(RETENTION_MS)
        ForwarderService.refresh(applicationContext)
        // More left (hit MAX_PER_RUN)? Schedule another pass instead of hogging this one.
        if (db.count(SmsDatabase.STATUS_PENDING) > 0) Scheduler.flushNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "SendWorker"
        private const val MAX_PER_RUN = 200
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Periodic safety net: recover missed SMS, make sure the service is alive, kick the sender. */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs(ctx).enabled) return Result.success()
        InboxScanner.scan(ctx)
        ForwarderService.start(ctx)
        Scheduler.flushNow(ctx)
        return Result.success()
    }
}
