package com.nos486.smsbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Safety net: some OEM ROMs kill apps so aggressively that the SMS_RECEIVED broadcast is never
 * delivered. Periodically we look at the system inbox and pick up anything we didn't see.
 */
object InboxScanner {
    private const val TAG = "InboxScanner"
    /** Give the broadcast receiver time to handle a fresh SMS before the scanner looks at it. */
    private const val SETTLE_MS = 60_000L
    private const val DEDUP_WINDOW_MS = 3 * 60_000L

    @Synchronized
    fun scan(context: Context): Int {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return 0
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val since = prefs.lastInboxScan
        if (since == 0L) {
            // First run: don't upload the user's whole SMS history.
            prefs.lastInboxScan = now
            return 0
        }
        val until = now - SETTLE_MS
        if (until <= since) return 0

        val db = SmsDatabase.get(context)
        var added = 0
        try {
            val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, "sub_id")
            val cursor = runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI, projection,
                    "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ?",
                    arrayOf(since.toString(), until.toString()), "${Telephony.Sms.DATE} ASC"
                )
            }.getOrNull() ?: context.contentResolver.query(
                // Some ROMs don't expose sub_id.
                Telephony.Sms.Inbox.CONTENT_URI, projection.copyOf(3).requireNoNulls(),
                "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ?",
                arrayOf(since.toString(), until.toString()), "${Telephony.Sms.DATE} ASC"
            )
            cursor?.use { c ->
                val subIdx = c.getColumnIndex("sub_id")
                while (c.moveToNext()) {
                    val sender = c.getString(0) ?: "unknown"
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    if (db.existsSimilar(sender, body, date, DEDUP_WINDOW_MS)) continue
                    val slot = if (subIdx >= 0 && !c.isNull(subIdx))
                        SimSlotResolver.fromSubscriptionId(context, c.getInt(subIdx)) else null
                    db.insert(UUID.randomUUID().toString(), sender, body, date, slot)
                    added++
                }
            }
            prefs.lastInboxScan = until
        } catch (t: Throwable) {
            Log.w(TAG, "Inbox scan failed", t)
        }
        if (added > 0) Log.i(TAG, "Recovered $added SMS missed by the receiver")
        return added
    }
}
