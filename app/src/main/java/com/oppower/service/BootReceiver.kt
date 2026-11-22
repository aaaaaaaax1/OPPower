package com.oppower.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.oppower.data.PreferencesStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val shouldStart = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> PreferencesStore(context).load().let {
                ((it.masterEnabled || it.nativeLevelZeroEnabled) && it.resumeAfterReboot) ||
                    it.liveNotificationEnabled
            }
            else -> false
        }
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (shouldStart && plugged) {
            ChargeControlService.start(context, ChargeControlService.ACTION_POWER_CONNECTED)
        }
    }
}
