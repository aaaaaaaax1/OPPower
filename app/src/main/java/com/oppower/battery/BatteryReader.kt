package com.oppower.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.oppower.model.BatterySnapshot
import com.oppower.model.RuntimeSettings
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class PrivilegedBatteryReading(
    val currentMa: Int,
    val voltageMv: Int,
)

class BatteryReader(
    private val context: Context,
    private val privilegedFallback: (() -> PrivilegedBatteryReading?)? = null,
    private val nowMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val currentStabilizer = CurrentSampleStabilizer()

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
        val primaryVoltageMv = if (settings.virtualVoltage && measuredVoltageMv !in 3_000..5_500) {
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
        val primaryCurrentMa = normalizeCurrentToMa(rawCurrent)
        val fallback = if (shouldReadPrivilegedFallback(
                currentMa = primaryCurrentMa,
                voltageMv = primaryVoltageMv,
                levelPercent = percent,
                status = status,
                acOnline = acOnline,
            )
        ) {
            runCatching { privilegedFallback?.invoke() }.getOrNull()
        } else {
            null
        }
        val resolved = resolveBatteryMeasurement(
            primaryCurrentMa = primaryCurrentMa,
            primaryVoltageMv = primaryVoltageMv,
            fallback = fallback,
        )
        val measuredCurrent = sanitizeCurrentReading(
            currentMa = resolved.currentMa,
            levelPercent = percent,
            plugged = acOnline,
        )
        val stableMeasurement = currentStabilizer.stabilize(
            currentMa = measuredCurrent,
            voltageMv = resolved.voltageMv,
            acOnline = acOnline,
            chargingEligible = percent < 99 && status != BatteryManager.BATTERY_STATUS_FULL,
            nowMillis = nowMillis(),
        )
        val normalizedCurrent = stableMeasurement.currentMa
        val voltageMv = stableMeasurement.voltageMv
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

internal fun shouldReadPrivilegedFallback(
    currentMa: Float,
    voltageMv: Int,
    levelPercent: Int,
    status: Int,
    acOnline: Boolean,
): Boolean = acOnline &&
    levelPercent < 99 &&
    status != BatteryManager.BATTERY_STATUS_FULL &&
    (currentMa == 0f || voltageMv !in VALID_VOLTAGE_RANGE)

internal data class ResolvedBatteryMeasurement(
    val currentMa: Float,
    val voltageMv: Int,
)

/**
 * ColorOS occasionally returns one or two all-zero current samples while the
 * charger remains connected. Restrict the short hold to AC charging states in
 * which zero can be transient; a disconnect, a full battery, or zero signals
 * lasting beyond the hold window are accepted immediately.
 */
internal class CurrentSampleStabilizer(
    private val holdMillis: Long = 3_500L,
) {
    private var lastReliable: ResolvedBatteryMeasurement? = null
    private var lastReliableAtMillis = 0L

    fun stabilize(
        currentMa: Float,
        voltageMv: Int,
        acOnline: Boolean,
        chargingEligible: Boolean,
        nowMillis: Long,
    ): ResolvedBatteryMeasurement {
        if (!acOnline || !chargingEligible) {
            lastReliable = null
            return ResolvedBatteryMeasurement(currentMa, voltageMv)
        }

        if (abs(currentMa) > ZERO_CURRENT_EPSILON) {
            val sample = ResolvedBatteryMeasurement(currentMa, voltageMv)
            lastReliable = sample
            lastReliableAtMillis = nowMillis
            return sample
        }

        val previous = lastReliable
        val ageMillis = nowMillis - lastReliableAtMillis
        if (previous != null && ageMillis in 0L..holdMillis) {
            return previous
        }

        lastReliable = null
        return ResolvedBatteryMeasurement(currentMa, voltageMv)
    }

    private companion object {
        const val ZERO_CURRENT_EPSILON = 0.5f
    }
}

internal fun resolveBatteryMeasurement(
    primaryCurrentMa: Float,
    primaryVoltageMv: Int,
    fallback: PrivilegedBatteryReading?,
): ResolvedBatteryMeasurement {
    if (fallback == null) {
        return ResolvedBatteryMeasurement(primaryCurrentMa, primaryVoltageMv)
    }
    val fallbackCurrent = fallback.currentMa
    val fallbackCurrentIsUsable = fallbackCurrent != 0 &&
        fallbackCurrent.toLong() !in invalidCurrentMarkers &&
        abs(fallbackCurrent) <= MAX_PLAUSIBLE_CURRENT_MA
    val fallbackVoltageIsUsable = fallback.voltageMv in VALID_VOLTAGE_RANGE
    val useFallbackCurrent = primaryCurrentMa == 0f && fallbackCurrentIsUsable
    return ResolvedBatteryMeasurement(
        currentMa = if (useFallbackCurrent) fallbackCurrent.toFloat() else primaryCurrentMa,
        voltageMv = if ((useFallbackCurrent || primaryVoltageMv !in VALID_VOLTAGE_RANGE) &&
            fallbackVoltageIsUsable
        ) {
            fallback.voltageMv
        } else {
            primaryVoltageMv
        },
    )
}

private val invalidCurrentMarkers = setOf(
    Int.MIN_VALUE.toLong(),
    Int.MAX_VALUE.toLong(),
    Long.MIN_VALUE,
    Long.MAX_VALUE,
)

private val VALID_VOLTAGE_RANGE = 3_000..5_500
private const val MAX_PLAUSIBLE_CURRENT_MA = 20_000

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
