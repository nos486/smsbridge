package com.nos486.smsbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * Reads the system SMS inbox. Used for two things:
 *  - a periodic safety net that picks up SMS the broadcast receiver missed (aggressive OEM ROMs);
 *  - a manual "send older messages" import (last 24h / 7d / 30d).
 */
object InboxScanner {
    private const val TAG = "InboxScanner"
    /** Give the broadcast receiver time to handle a fresh SMS before the scanner looks at it. */
    private const val SETTLE_MS = 60_000L
    private const val DEDUP_WINDOW_MS = 3 * 60_000L

    data class InboxSms(val sender: String, val body: String, val date: Long, val dateSent: Long, val subId: Int?)

    /**
     * Deterministic id for an SMS, so the same message always maps to the same id (and
     * Idempotency-Key) whether it came from the receiver, the scanner or a manual import.
     * Returns a random id when the SMSC timestamp is unknown.
     */
    fun stableId(sender: String, dateSent: Long, body: String): String =
        if (dateSent > 0) UUID.nameUUIDFromBytes("$sender|$dateSent|$body".toByteArray()).toString()
        else UUID.randomUUID().toString()

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun scan(context: Context): Int {
        if (!hasPermission(context)) return 0
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
        return try {
            val added = store(context, read(context, since, until))
            prefs.lastInboxScan = until
            if (added > 0) Log.i(TAG, "Recovered $added SMS missed by the receiver")
            added
        } catch (t: Throwable) {
            Log.w(TAG, "Inbox scan failed", t)
            0
        }
    }

    /** Number of inbox messages received after [since] that are not in our outbox yet. */
    fun countNew(context: Context, since: Long): Int {
        if (!hasPermission(context)) return 0
        val db = SmsDatabase.get(context)
        return read(context, since, System.currentTimeMillis()).count { !alreadyStored(db, it) }
    }

    /** Queue every inbox message received after [since] that hasn't been forwarded yet. */
    @Synchronized
    fun importSince(context: Context, since: Long): Int {
        if (!hasPermission(context)) return 0
        val added = store(context, read(context, since, System.currentTimeMillis()))
        Log.i(TAG, "Imported $added older SMS")
        return added
    }

    private fun alreadyStored(db: SmsDatabase, sms: InboxSms): Boolean {
        val id = stableId(sms.sender, sms.dateSent, sms.body)
        return (sms.dateSent > 0 && db.exists(id)) || db.existsSimilar(sms.sender, sms.body, sms.date, DEDUP_WINDOW_MS)
    }

    private fun store(context: Context, items: List<InboxSms>): Int {
        val db = SmsDatabase.get(context)
        var added = 0
        db.inTransaction {
            for (sms in items) {
                if (alreadyStored(db, sms)) continue
                val slot = sms.subId?.let { SimSlotResolver.fromSubscriptionId(context, it) }
                db.insert(stableId(sms.sender, sms.dateSent, sms.body), sms.sender, sms.body, sms.date, slot)
                added++
            }
        }
        return added
    }

    private fun read(context: Context, from: Long, to: Long): List<InboxSms> {
        val base = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
        val selection = "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.DATE} <= ?"
        val args = arrayOf(from.toString(), to.toString())
        val order = "${Telephony.Sms.DATE} ASC"
        val resolver = context.contentResolver
        // Some ROMs don't expose sub_id; fall back to the basic columns.
        val cursor = runCatching {
            resolver.query(Telephony.Sms.Inbox.CONTENT_URI, base + "sub_id", selection, args, order)
        }.getOrNull() ?: resolver.query(Telephony.Sms.Inbox.CONTENT_URI, base, selection, args, order)

        val out = ArrayList<InboxSms>()
        cursor?.use { c ->
            val subIdx = c.getColumnIndex("sub_id")
            while (c.moveToNext()) {
                out += InboxSms(
                    sender = c.getString(0) ?: "unknown",
                    body = c.getString(1) ?: "",
                    date = c.getLong(2),
                    dateSent = if (c.isNull(3)) 0L else c.getLong(3),
                    subId = if (subIdx >= 0 && !c.isNull(subIdx)) c.getInt(subIdx) else null,
                )
            }
        }
        return out
    }
}
