package com.oppower.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return

        // ACTION_POWER_CONNECTED can arrive before ACTION_BATTERY_CHANGED
        // exposes the new AC state. Keep the broadcast alive while retrying so
        // the foreground notification starts without toggling any app setting.
        val pendingResult = goAsync()
        ChargeControlService.startWhenAvailable(
            context.applicationContext,
            action = ChargeControlService.ACTION_POWER_CONNECTED,
        ) {
            pendingResult.finish()
        }
    }
}
