package com.nos486.smsbridge

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ForwarderService.createChannel(this)
        val prefs = Prefs(this)
        // Messages older than first launch are not uploaded by the inbox scanner.
        if (prefs.lastInboxScan == 0L) prefs.lastInboxScan = System.currentTimeMillis()
        if (prefs.enabled) Scheduler.ensurePeriodic(this)
    }
}
