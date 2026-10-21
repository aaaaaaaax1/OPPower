package com.oppower.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oppower.data.PreferencesStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val shouldStart = when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> PreferencesStore(context).load().masterEnabled
            else -> false
        }
        if (shouldStart) {
            ChargeControlService.start(context)
        }
    }
}
