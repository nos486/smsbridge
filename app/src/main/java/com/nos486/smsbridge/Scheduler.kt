package com.nos486.smsbridge

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val FLUSH = "flush"
    private const val MAINTENANCE = "maintenance"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Send pending messages as soon as there is a network. WorkManager persists this across
     * reboots and process death, and waits for connectivity on its own.
     */
    fun flushNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a run already in progress finishes, then another run picks up new rows.
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Like [flushNow] but discards any pending backoff delay (used on reconnect / manual retry). */
    fun flushImmediately(context: Context) {
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FLUSH, ExistingWorkPolicy.REPLACE, req)
    }

    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
