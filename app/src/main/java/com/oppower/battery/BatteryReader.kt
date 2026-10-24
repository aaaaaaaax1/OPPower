package com.oppower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.oppower.model.BatterySnapshot
import com.oppower.model.RuntimeSettings
import kotlin.math.roundToInt

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
        val acOnline = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        // Match LLPower's battery source order. Some ColorOS builds return a
        // transient sentinel or zero from CURRENT_NOW while CURRENT_AVERAGE is
        // still valid, which made the UI appear stuck at 0 W during charging.
        val currentNow = runCatching {
            manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrDefault(0L)
        val currentAverage = if (currentNow == 0L) {
            runCatching {
                manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            }.getOrDefault(0L)
        } else {
            0L
        }
        val rawCurrent = if (currentNow != 0L) currentNow else currentAverage
        // BATTERY_PROPERTY_CURRENT_NOW is normally reported in microamperes.
        val normalizedCurrent = if (kotlin.math.abs(rawCurrent) >= 10_000L) {
            rawCurrent / 1_000f
        } else {
            rawCurrent.toFloat()
        }
        // ColorOS reports current sign inconsistently across charge modes, and
        // some drivers continue to expose charging current while the screen-on
        // draw exceeds charger input. Use AC state for the power direction and
        // retain the measured current magnitude. The explicit inversion option
        // below remains available for devices with a reversed hardware sign.
        val directionalCurrent = when {
            normalizedCurrent == 0f -> 0f
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
            currentMa = displayedCurrent.roundToInt(),
            voltageMv = voltageMv,
            powerW = powerW,
            temperatureC = temperatureC,
            levelPercent = percent,
            status = status,
            acOnline = acOnline,
        )
    }
}
