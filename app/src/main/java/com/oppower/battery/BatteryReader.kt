package com.oppower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.oppower.model.BatterySnapshot
import com.oppower.model.RuntimeSettings
import java.io.File
import kotlin.math.abs
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
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val acOnline = plugged != 0
        var measuredVoltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (measuredVoltageMv <= 0) measuredVoltageMv = readFirstPositiveVoltageMv()
        val voltageMv = if (settings.virtualVoltage && measuredVoltageMv !in 3_000..5_500) {
            3_850
        } else {
            measuredVoltageMv
        }
        val temperatureC = ((intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f)
        // Match LLPower's current source order. CURRENT_NOW is not guaranteed
        // to use microamperes on ColorOS: this device reports fast-charge values
        // around 10_000 as mA, so the 20_000 threshold must match LLPower.
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
        val rawCurrent = when {
            currentNow != 0L -> currentNow
            currentAverage != 0L -> currentAverage
            else -> readFirstNonZeroCurrent()
        }
        val normalizedCurrent = sanitizeCurrentReading(
            currentMa = normalizeCurrentToMa(rawCurrent),
            levelPercent = percent,
            plugged = acOnline,
        )
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
        val measuredPower = calculatePowerW(correctedCurrent, voltageMv)
        val powerW = if (settings.absoluteCurrentPowerDisplay) abs(measuredPower) else measuredPower
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

private val invalidCurrentMarkers = setOf(
    Int.MIN_VALUE.toLong(),
    Int.MAX_VALUE.toLong(),
    Long.MIN_VALUE,
    Long.MAX_VALUE,
)

private val currentSysfsPaths = listOf(
    "/sys/class/power_supply/battery/current_now",
    "/sys/class/power_supply/main/current_now",
    "/sys/class/power_supply/battery/batt_current",
)

private val voltageSysfsPaths = listOf(
    "/sys/class/power_supply/battery/voltage_now",
    "/sys/class/power_supply/battery/batt_vol",
    "/sys/class/power_supply/Battery/voltage_now",
    "/sys/class/power_supply/battery/voltage_ocv",
)

internal fun normalizeCurrentToMa(rawCurrent: Long): Float {
    if (rawCurrent in invalidCurrentMarkers) return 0f
    return if (abs(rawCurrent) > 20_000L) rawCurrent / 1_000f else rawCurrent.toFloat()
}

internal fun sanitizeCurrentReading(
    currentMa: Float,
    levelPercent: Int,
    plugged: Boolean,
): Float {
    if (!plugged || levelPercent <= 98) return currentMa
    val rounded = currentMa.roundToInt()
    val integerMilliampMarker = abs(currentMa - rounded) < 0.001f &&
        abs(rounded) >= 1_000 &&
        abs(rounded) % 1_000 == 0
    return if (integerMilliampMarker) 0f else currentMa
}

internal fun calculatePowerW(currentMa: Float, voltageMv: Int): Float =
    currentMa / 1_000f * voltageMv / 1_000f

private fun readFirstNonZeroCurrent(): Long = currentSysfsPaths.firstNotNullOfOrNull { path ->
    runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()?.takeIf { it != 0L && it !in invalidCurrentMarkers }
} ?: 0L

private fun readFirstPositiveVoltageMv(): Int = voltageSysfsPaths.firstNotNullOfOrNull { path ->
    runCatching {
        val raw = File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
        raw?.takeIf { it > 0L }?.let { value ->
            val volts = when {
                value > 100_000L -> value / 1_000_000f
                value > 1_000L -> value / 1_000f
                else -> value.toFloat()
            }
            (volts * 1_000f).roundToInt()
        }
    }.getOrNull()
} ?: 0
