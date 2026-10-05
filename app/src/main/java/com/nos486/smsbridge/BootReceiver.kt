package com.nos486.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Prefs(app).enabled) return
        ForwarderService.start(app)
        Scheduler.ensurePeriodic(app)
        Scheduler.flushNow(app)
    }
}
