package com.nos486.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val receivedAt = System.currentTimeMillis()
        val app = context.applicationContext
        val pending = goAsync()
        executor.execute {
            try {
                store(app, intent, receivedAt)
                Scheduler.flushNow(app)
                ForwarderService.start(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to store SMS", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun store(context: Context, intent: Intent, receivedAt: Long) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return
        val slot = SimSlotResolver.fromIntent(context, intent)
        val db = SmsDatabase.get(context)
        // One broadcast normally holds the parts of a single (possibly multipart) message,
        // but group by sender just in case a vendor batches several.
        parts.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "unknown" }
            .forEach { (sender, msgs) ->
                val body = msgs.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                db.insert(UUID.randomUUID().toString(), sender, body, receivedAt, slot)
                Log.i(TAG, "Stored SMS from $sender (${body.length} chars, slot=$slot)")
            }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
