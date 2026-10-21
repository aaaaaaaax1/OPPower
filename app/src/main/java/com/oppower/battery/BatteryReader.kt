package com.oppower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.oppower.model.BatterySnapshot

class BatteryReader(private val context: Context) {
    fun snapshot(): BatterySnapshot {
        val manager = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val percent = level * 100 / scale
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temperatureC = ((intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
        val rawCurrent = runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
        // BATTERY_PROPERTY_CURRENT_NOW is normally reported in microamperes.
        val currentMa = if (kotlin.math.abs(rawCurrent) >= 10_000) rawCurrent / 1_000 else rawCurrent
        val acOnline = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val currentForPower = if (currentMa < 0) -currentMa else currentMa
        val powerW = currentForPower / 1000f * voltageMv / 1000f
        return BatterySnapshot(
            currentMa = currentMa,
            voltageMv = voltageMv,
            powerW = powerW,
            temperatureC = temperatureC,
            levelPercent = percent,
            status = status,
            acOnline = acOnline,
        )
    }
}
