package com.oppower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.oppower.model.BatterySnapshot
import com.oppower.model.RuntimeSettings

class BatteryReader(private val context: Context) {
    fun snapshot(settings: RuntimeSettings = RuntimeSettings()): BatterySnapshot {
        val manager = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val percent = level * 100 / scale
        val measuredVoltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val voltageMv = if (settings.virtualVoltage && measuredVoltageMv !in 3_000..5_500) {
            3_850
        } else {
            measuredVoltageMv
        }
        val temperatureC = ((intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
        val rawCurrent = runCatching { manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
        // BATTERY_PROPERTY_CURRENT_NOW is normally reported in microamperes.
        val acOnline = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val normalizedCurrent = if (kotlin.math.abs(rawCurrent) >= 10_000) rawCurrent / 1_000 else rawCurrent
        val directionalCurrent = when {
            normalizedCurrent == 0 -> 0
            acOnline -> kotlin.math.abs(normalizedCurrent)
            else -> -kotlin.math.abs(normalizedCurrent)
        }
        val signedCurrent = if (settings.invertCurrentSign) -directionalCurrent else directionalCurrent
        val cellMultiplier = if (settings.doubleCellCorrection) 2 else 1
        val correctedCurrent = signedCurrent * cellMultiplier
        val displayedCurrent = if (settings.absoluteCurrentPowerDisplay) {
            kotlin.math.abs(correctedCurrent)
        } else {
            correctedCurrent
        }
        val measuredPower = correctedCurrent / 1000f * voltageMv / 1000f
        val powerW = if (settings.absoluteCurrentPowerDisplay) kotlin.math.abs(measuredPower) else measuredPower
        return BatterySnapshot(
            currentMa = displayedCurrent,
            voltageMv = voltageMv,
            powerW = powerW,
            temperatureC = temperatureC,
            levelPercent = percent,
            status = status,
            acOnline = acOnline,
        )
    }
}
