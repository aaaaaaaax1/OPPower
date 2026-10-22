package com.oppower.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oppower.data.PreferencesStore

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = PreferencesStore(context).load()
        if (!settings.masterEnabled && !settings.liveNotificationEnabled) return
        // Start on both edges: connect calibrates and enables the foreground
        // session; disconnect wakes the same session so it can restore cosa=0
        // immediately even if Android had stopped the service while plugged.
        if (intent?.action == Intent.ACTION_POWER_CONNECTED ||
            intent?.action == Intent.ACTION_POWER_DISCONNECTED
        ) {
            ChargeControlService.start(context)
        }
    }
}
