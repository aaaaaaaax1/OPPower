package com.oppower.history

import com.oppower.model.ChargeRecord
import com.oppower.model.ChartPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A normal idle sample is three minutes apart. Five minutes leaves room for a
 * late scheduler tick while excluding sleep/resume gaps from weighted totals.
 */
internal const val MAX_RECORD_SAMPLE_GAP_MS = 5L * 60L * 1_000L

internal data class ChargeRecordBuildResult(
    val chargingRecords: List<ChargeRecord>,
    val dischargingRecords: List<ChargeRecord>,
    val currentSession: ChargeRecordAccumulator?,
)

/**
 * Builds all completed history summaries in one pass. The final accumulator is
 * retained so live samples can update their record without rereading the session.
 */
internal fun buildChargeRecords(points: List<ChartPoint>): ChargeRecordBuildResult {
    val charging = ArrayList<ChargeRecord>()
    val discharging = ArrayList<ChargeRecord>()
    var active: ChargeRecordAccumulator? = null

    points.sortedBy { it.epochMillis }.forEach { point ->
        val current = active
        if (current == null || current.isCharging != point.acOnline) {
            if (current != null && current.sampleCount >= 2) {
                val record = current.toRecord()
                if (current.isCharging) charging += record else discharging += record
            }
            active = ChargeRecordAccumulator(point)
        } else {
            current.append(point)
        }
    }

    val completed = active
    if (completed != null && completed.sampleCount >= 2) {
        val record = completed.toRecord()
        if (completed.isCharging) charging += record else discharging += record
    }
    return ChargeRecordBuildResult(
        chargingRecords = charging,
        dischargingRecords = discharging,
        currentSession = active,
    )
}

internal fun buildChargeRecord(
    points: List<ChartPoint>,
    endReason: String? = null,
): ChargeRecord? {
    val first = points.minByOrNull { it.epochMillis } ?: return null
    val accumulator = ChargeRecordAccumulator(first)
    points.sortedBy { it.epochMillis }.drop(1).forEach(accumulator::append)
    return accumulator.toRecord(endReason)
}

/**
 * Incremental, time-weighted session summary. Power, current and temperature use
 * trapezoidal integration between adjacent samples. Abnormal gaps are excluded
 * from averages and charge integration, while peak values still see every sample.
 */
internal class ChargeRecordAccumulator(
    firstPoint: ChartPoint,
    private val maxGapMillis: Long = MAX_RECORD_SAMPLE_GAP_MS,
) {
    val isCharging: Boolean = firstPoint.acOnline
    var sampleCount: Int = 0
        private set

    private val startedAtMillis = firstPoint.epochMillis
    private val startPercent = firstPoint.levelPercent
    private val startVoltageV = firstPoint.voltageMv / 1000f
    private var lastPoint = firstPoint
    private var peakPowerW = 0f
    private var maximumTemperatureC = 0f
    private var peakCurrentMa = 0f

    private var simplePowerSum = 0.0
    private var simplePowerCount = 0
    private var simpleCurrentSum = 0.0
    private var simpleCurrentCount = 0
    private var simpleTemperatureSum = 0.0
    private var simpleTemperatureCount = 0

    private var powerArea = 0.0
    private var powerWeightMillis = 0L
    private var currentArea = 0.0
    private var currentWeightMillis = 0L
    private var temperatureArea = 0.0
    private var temperatureWeightMillis = 0L
    private var chargeMah = 0.0

    init {
        includeSample(firstPoint)
    }

    fun append(point: ChartPoint) {
        require(point.acOnline == isCharging) { "Cannot append a sample from another AC session" }
        if (point.epochMillis <= lastPoint.epochMillis) return

        val intervalMillis = point.epochMillis - lastPoint.epochMillis
        if (intervalMillis <= maxGapMillis) {
            accumulateWeighted(lastPoint, point, intervalMillis)
        }
        includeSample(point)
        lastPoint = point
    }

    fun toRecord(endReason: String? = null): ChargeRecord {
        val last = lastPoint
        val charging = isCharging
        return ChargeRecord(
            startedAtMillis = startedAtMillis,
            endedAtMillis = last.epochMillis,
            startPercent = startPercent,
            endPercent = last.levelPercent,
            peakPowerW = peakPowerW,
            averagePowerW = weightedOrSimple(powerArea, powerWeightMillis, simplePowerSum, simplePowerCount),
            maxTemperatureC = maximumTemperatureC,
            startVoltageV = startVoltageV,
            endVoltageV = last.voltageMv / 1000f,
            peakCurrentMa = peakCurrentMa.roundToInt(),
            averageCurrentMa = weightedOrSimple(
                currentArea,
                currentWeightMillis,
                simpleCurrentSum,
                simpleCurrentCount,
            ).roundToInt(),
            averageTemperatureC = weightedOrSimple(
                temperatureArea,
                temperatureWeightMillis,
                simpleTemperatureSum,
                simpleTemperatureCount,
            ),
            chargeDeltaMah = if (charging && chargeMah > 0.0) chargeMah.roundToInt() else null,
            endReason = endReason ?: when {
                charging && last.levelPercent >= 100 -> "充满"
                charging -> "拔电"
                else -> "放电会话"
            },
        )
    }

    private fun includeSample(point: ChartPoint) {
        sampleCount += 1
        if (point.powerW.isFinite()) {
            peakPowerW = maxOf(peakPowerW, point.powerW)
            simplePowerSum += point.powerW
            simplePowerCount += 1
        }
        val currentMa = abs(point.currentMa)
        peakCurrentMa = maxOf(peakCurrentMa, currentMa.toFloat())
        simpleCurrentSum += currentMa
        simpleCurrentCount += 1
        if (point.temperatureC.isFinite()) {
            maximumTemperatureC = maxOf(maximumTemperatureC, point.temperatureC)
            simpleTemperatureSum += point.temperatureC
            simpleTemperatureCount += 1
        }
    }

    private fun accumulateWeighted(left: ChartPoint, right: ChartPoint, intervalMillis: Long) {
        if (left.powerW.isFinite() && right.powerW.isFinite()) {
            powerArea += trapezoid(left.powerW, right.powerW) * intervalMillis
            powerWeightMillis += intervalMillis
        }
        val leftCurrent = abs(left.currentMa).toDouble()
        val rightCurrent = abs(right.currentMa).toDouble()
        currentArea += trapezoid(leftCurrent, rightCurrent) * intervalMillis
        currentWeightMillis += intervalMillis
        if (isCharging) {
            chargeMah += trapezoid(leftCurrent, rightCurrent) * intervalMillis / 3_600_000.0
        }
        if (left.temperatureC.isFinite() && right.temperatureC.isFinite()) {
            temperatureArea += trapezoid(left.temperatureC, right.temperatureC) * intervalMillis
            temperatureWeightMillis += intervalMillis
        }
    }

    private fun weightedOrSimple(
        area: Double,
        weightMillis: Long,
        simpleSum: Double,
        simpleCount: Int,
    ): Float = when {
        weightMillis > 0L -> (area / weightMillis).toFloat()
        simpleCount > 0 -> (simpleSum / simpleCount).toFloat()
        else -> 0f
    }

    private fun trapezoid(left: Double, right: Double): Double = (left + right) / 2.0

    private fun trapezoid(left: Float, right: Float): Double =
        (left.toDouble() + right.toDouble()) / 2.0

}
