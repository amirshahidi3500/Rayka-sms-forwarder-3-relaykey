package com.rayka.smsforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // after a reboot AND after this app was updated (an update stops the service and nothing restarts it)
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context)
        if (Prefs.serviceEnabled) {
            try { ForwarderService.start(context) } catch (_: Exception) { }
        }
    }
}
