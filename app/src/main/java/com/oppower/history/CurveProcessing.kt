package com.oppower.history

import com.oppower.model.ChartPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A processed chart point. Timestamps are retained so long gaps can be split. */
data class CurvePoint(
    val epochMillis: Long,
    val value: Float,
)

data class CurveSegment(val points: List<CurvePoint>)

data class CurveAxis(
    val minimum: Float,
    val maximum: Float,
    val tickStep: Float,
) {
    val tickCount: Int
        get() = (((maximum - minimum) / tickStep).roundToInt() + 1).coerceIn(2, 41)
}

data class CurveSnapshot(
    val points: List<CurvePoint>,
    val segments: List<CurveSegment>,
    val axis: CurveAxis,
)

/**
 * Value processing follows LLPower's curve pipeline:
 * - at most a 900-bucket target across the available history;
 * - power uses 30-90 s buckets, keeps the robust midpoint and a compressed
 *   upward peak, then applies nine-point robust smoothing and tail settling;
 * - temperature is averaged into 60 s buckets, isolated jitter is corrected,
 *   and a seven-point triangular filter softens steps;
 * - points more than seven minutes apart are separate drawable segments.
 *
 * This only controls persisted curve samples. The live metric cards continue
 * to refresh once per second.
 */
object CurveProcessing {
    const val HISTORY_WINDOW_MS = 8L * 60L * 60L * 1_000L
    const val MAX_RENDER_POINTS = 900
    const val CURVE_GAP_MS = 420_000L
    const val TEMPERATURE_BUCKET_MS = 60_000L
    const val POWER_MIN_BUCKET_MS = 30_000L
    const val POWER_MAX_BUCKET_MS = 90_000L

    private const val POWER_GRID_STEP_W = 10f
    private const val TEMPERATURE_MIN_RANGE_C = 5f
    private const val POWER_MIN_DISPLAY_W = -400f
    private const val POWER_MAX_DISPLAY_W = 400f
    private const val TEMPERATURE_MIN_DISPLAY_C = -20f
    private const val TEMPERATURE_MAX_DISPLAY_C = 120f

    fun processPower(
        points: List<ChartPoint>,
        windowStart: Long,
        windowEnd: Long,
        recordIntervalMs: Long = 3_000L,
    ): CurveSnapshot {
        val source = normalized(points, windowStart, windowEnd) { it.powerW }
        if (source.isEmpty()) {
            return CurveSnapshot(emptyList(), emptyList(), resolvePowerAxis(emptyList()))
        }

        val bucketed = reducePower(source, windowStart, windowEnd, recordIntervalMs)
        val processed = stabilizePowerTail(smoothPower(bucketed, windowSize = 9), tailCount = 2, factor = 0.55f)
        return CurveSnapshot(
            points = processed,
            segments = splitSegments(processed, CURVE_GAP_MS),
            axis = resolvePowerAxis(processed),
        )
    }

    fun processTemperature(
        points: List<ChartPoint>,
        windowStart: Long,
        windowEnd: Long,
    ): CurveSnapshot {
        val source = normalized(points, windowStart, windowEnd) { it.temperatureC }
        if (source.isEmpty()) {
            return CurveSnapshot(emptyList(), emptyList(), resolveTemperatureAxis(emptyList()))
        }

        val processed = shapeTemperature(reduceTemperature(source))
        return CurveSnapshot(
            points = processed,
            segments = splitSegments(processed, CURVE_GAP_MS),
            axis = resolveTemperatureAxis(processed),
        )
    }

    /** Includes one point on either side of the viewport so clipped lines reach its edges. */
    fun clip(snapshot: CurveSnapshot, start: Long, end: Long): List<CurveSegment> {
        if (end < start) return emptyList()
        return snapshot.segments.mapNotNull { segment ->
            val points = segment.points
            val firstVisible = points.indexOfFirst { it.epochMillis in start..end }
            if (firstVisible < 0) return@mapNotNull null
            var lastVisible = firstVisible
            while (lastVisible + 1 < points.size && points[lastVisible + 1].epochMillis <= end) {
                lastVisible++
            }
            val from = (firstVisible - 1).coerceAtLeast(0)
            val to = (lastVisible + 1).coerceAtMost(points.lastIndex)
            CurveSegment(points.subList(from, to + 1))
        }
    }

    fun resolvePowerAxis(points: List<CurvePoint>): CurveAxis {
        val low = (points.minOfOrNull { it.value } ?: 0f)
            .coerceIn(POWER_MIN_DISPLAY_W, POWER_MAX_DISPLAY_W)
        val high = (points.maxOfOrNull { it.value } ?: 0f)
            .coerceIn(POWER_MIN_DISPLAY_W, POWER_MAX_DISPLAY_W)
        val minimum = floor(low / POWER_GRID_STEP_W) * POWER_GRID_STEP_W
        var maximum = ceil(high / POWER_GRID_STEP_W) * POWER_GRID_STEP_W
        if (maximum <= minimum) maximum = minimum + POWER_GRID_STEP_W
        return CurveAxis(minimum, maximum, POWER_GRID_STEP_W)
    }

    fun resolveTemperatureAxis(points: List<CurvePoint>): CurveAxis {
        val low = (points.minOfOrNull { it.value } ?: 25f)
            .coerceIn(TEMPERATURE_MIN_DISPLAY_C, TEMPERATURE_MAX_DISPLAY_C)
        val high = (points.maxOfOrNull { it.value } ?: 45f)
            .coerceIn(TEMPERATURE_MIN_DISPLAY_C, TEMPERATURE_MAX_DISPLAY_C)
        val span = (high - low).coerceAtLeast(0f)
        val padding = ((TEMPERATURE_MIN_RANGE_C - span) / 2f).coerceAtLeast(0f)
        val minimum = floor(low - padding)
        var maximum = ceil(high + padding)
        if (maximum <= minimum) maximum = minimum + TEMPERATURE_MIN_RANGE_C
        val range = maximum - minimum
        val gridLines = range.toInt().coerceIn(1, 40)
        return CurveAxis(minimum, maximum, range / gridLines)
    }

    private fun reducePower(
        source: List<CurvePoint>,
        windowStart: Long,
        windowEnd: Long,
        recordIntervalMs: Long,
    ): List<CurvePoint> {
        val span = (windowEnd - windowStart).coerceAtLeast(1L)
        val targetSlots = (MAX_RENDER_POINTS - 1).coerceAtLeast(1)
        // Reserve one slot for an inclusive endpoint so an exact eight-hour
        // window cannot cross the 900-point render budget.
        val targetBucketMs = max(POWER_MIN_BUCKET_MS, (span + targetSlots - 1L) / targetSlots)
        val recordBucketMs = (recordIntervalMs.coerceAtLeast(1_000L) * 2L)
            .coerceAtMost(POWER_MAX_BUCKET_MS)
        val bucketMs = max(targetBucketMs, recordBucketMs)
            .coerceIn(POWER_MIN_BUCKET_MS, POWER_MAX_BUCKET_MS)

        val buckets = mutableListOf<MutableList<CurvePoint>>()
        var currentStart = Long.MIN_VALUE
        var current: MutableList<CurvePoint>? = null
        source.sortedBy { it.epochMillis }.forEach { point ->
            val bucketStart = floorDiv(point.epochMillis, bucketMs) * bucketMs
            if (bucketStart != currentStart) {
                current?.takeIf { it.isNotEmpty() }?.let(buckets::add)
                current = mutableListOf()
                currentStart = bucketStart
            }
            current?.add(point)
        }
        current?.takeIf { it.isNotEmpty() }?.let(buckets::add)

        val result = ArrayList<CurvePoint>(buckets.size * 2)
        buckets.forEach { bucket -> result += trendAndUpwardPeak(bucket, currentStart = floorDiv(bucket.first().epochMillis, bucketMs) * bucketMs, bucketMs) }
        return ensureStrictlyIncreasingPower(result)
    }

    private fun trendAndUpwardPeak(
        values: List<CurvePoint>,
        currentStart: Long,
        bucketMs: Long,
    ): List<CurvePoint> {
        if (values.isEmpty()) return emptyList()
        val numbers = values.map { it.value }
        val center = robustPowerCenter(numbers)
        val midpoint = CurvePoint(currentStart + bucketMs / 2L, center)
        val peak = values.maxByOrNull { it.value } ?: return emptyList()
        val deviation = (peak.value - center).coerceAtLeast(0f)
        val compressedDeviation =
            deviation * continuousWeight(
                deviation,
                abs(center) * 0.32f + robustPowerScale(numbers, center),
            )
        val smoothedPeak = peak.copy(value = center + compressedDeviation)
        return ensureStrictlyIncreasingPower(mergeNearlyEqual(listOf(midpoint, smoothedPeak)))
    }

    private fun mergeNearlyEqual(source: List<CurvePoint>): List<CurvePoint> {
        if (source.size <= 1) return source
        val result = ArrayList<CurvePoint>(source.size)
        source.sortedBy { it.epochMillis }.forEach { point ->
            val previous = result.lastOrNull()
            if (previous == null || abs(point.value - previous.value) > 0.2f) {
                result += point
            } else {
                result[result.lastIndex] = CurvePoint(
                    epochMillis = (previous.epochMillis + point.epochMillis) / 2L,
                    value = (previous.value + point.value) / 2f,
                )
            }
        }
        return result
    }

    private fun ensureStrictlyIncreasingPower(source: List<CurvePoint>): List<CurvePoint> {
        if (source.size <= 1) return source
        val result = ArrayList<CurvePoint>(source.size)
        source.sortedBy { it.epochMillis }.forEach { point ->
            val previous = result.lastOrNull()
            when {
                previous == null -> result += point
                point.epochMillis > previous.epochMillis -> result += point
                point.epochMillis == previous.epochMillis ->
                    result[result.lastIndex] = previous.copy(value = (previous.value + point.value) / 2f)
            }
        }
        return result
    }

    private fun smoothPower(source: List<CurvePoint>, windowSize: Int): List<CurvePoint> {
        if (source.size <= 2 || windowSize <= 1) return source
        val half = windowSize / 2
        val localScales = FloatArray(source.size) { index ->
            localPowerScale(source, index, half)
        }
        return source.mapIndexed { index, point ->
            val power = point.value
            val previousDelta = source.getOrNull(index - 1)?.let { abs(power - it.value) } ?: 0f
            val nextDelta = source.getOrNull(index + 1)?.let { abs(it.value - power) } ?: 0f
            val maxNeighborDelta = max(previousDelta, nextDelta)
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(source.lastIndex)
            val scale = localScales[index]
            var robustWeighted = 0f
            var robustWeights = 0f
            var regularWeighted = 0f
            var regularWeights = 0f

            for (neighbor in from..to) {
                val triangularWeight = ((half + 1) - abs(neighbor - index)).coerceAtLeast(1).toFloat()
                val neighborValue = source[neighbor].value
                val pairScale = ((scale + localScales[neighbor]) / 2f).coerceAtLeast(0.001f)
                val delta = abs(neighborValue - power)
                val ratio = (delta / pairScale).coerceIn(0f, 1f)
                val robustWeight = (1f / (ratio * ratio + 1f)) * triangularWeight
                robustWeighted += neighborValue * robustWeight
                robustWeights += robustWeight
                regularWeighted += neighborValue * triangularWeight
                regularWeights += triangularWeight
            }

            val robustMean = robustWeighted / robustWeights.coerceAtLeast(1f)
            val regularMean = regularWeighted / regularWeights.coerceAtLeast(1f)
            val oscillationDensity = localOscillationDensity(source, index, half, localScales)
            val neighborWeight = 1f - continuousWeight(maxNeighborDelta, 2.15f * scale)
            val shapeWeight =
                ((neighborWeight * 0.65f) + 0.35f) * continuousWeight(oscillationDensity, 0.18f)
            val shaped = regularMean + (robustMean - regularMean) * shapeWeight
            val targetDelta = shaped - power
            val targetDistance = abs(targetDelta)
            val remainingWeight = 1f - shapeWeight
            val shiftWeight = (
                shapeWeight * 0.78f +
                    0.42f * neighborWeight * (1f - continuousWeight(targetDistance, scale * 1.45f)) * remainingWeight +
                    0.12f
            ).coerceIn(0.12f, 0.92f)
            val maxShift =
                (abs(power) * 0.12f * neighborWeight + ((0.55f * shapeWeight) + 0.78f) * scale)
                    .coerceAtLeast(0.8f)
            val hardLimit = (shapeWeight * 0.65f + 0.2f) * targetDistance + maxShift
            val baseShift = (targetDelta * shiftWeight).coerceIn(-hardLimit, hardLimit)
            val upwardHeight = (power - shaped).coerceAtLeast(0f)
            val smoothed = power + baseShift
            val downwardHeight = (power - smoothed).coerceAtLeast(0f)
            val upwardCorrection =
                downwardHeight *
                    continuousWeight(upwardHeight * remainingWeight, scale * 1.1f) *
                    remainingWeight
            point.copy(value = smoothed + upwardCorrection)
        }
    }

    private fun localPowerScale(source: List<CurvePoint>, index: Int, radius: Int): Float {
        val from = (index - radius).coerceAtLeast(0)
        val to = (index + radius).coerceAtMost(source.lastIndex)
        val values = source.subList(from, to + 1).map { it.value }
        return robustPowerScale(values, robustPowerCenter(values))
    }

    private fun localOscillationDensity(
        source: List<CurvePoint>,
        index: Int,
        radius: Int,
        localScales: FloatArray,
    ): Float {
        val from = (index - radius).coerceAtLeast(0)
        val to = (index + radius).coerceAtMost(source.lastIndex)
        if (to - from < 2) return 0f
        var weightedDensity = 0f
        var totalWeight = 0f
        var densityTotal = 0f
        var samples = 0
        for (center in (from + 1) until to) {
            val leftDelta = source[center].value - source[center - 1].value
            val rightDelta = source[center + 1].value - source[center].value
            val reversal = (abs(leftDelta) + abs(rightDelta) - abs(source[center + 1].value - source[center - 1].value))
                .coerceAtLeast(0f)
            val minDelta = min(abs(leftDelta), abs(rightDelta))
            val scale = localScales[center].coerceAtLeast(0.001f)
            val density =
                continuousWeight(reversal, scale * 1.1f) *
                    continuousWeight(minDelta, scale * 0.7f)
            val triangularWeight = ((radius + 1) - abs(center - index)).coerceAtLeast(1).toFloat()
            weightedDensity += density * triangularWeight
            totalWeight += triangularWeight
            densityTotal += density
            samples++
        }
        val averageDensity = densityTotal / samples.coerceAtLeast(1)
        return ((weightedDensity / totalWeight.coerceAtLeast(1f)) *
            continuousWeight(averageDensity, 0.45f)).coerceIn(0f, 1f)
    }

    private fun stabilizePowerTail(source: List<CurvePoint>, tailCount: Int, factor: Float): List<CurvePoint> {
        if (source.size <= 1 || tailCount <= 0) return source
        val stablePrefixSize = (source.size - tailCount).coerceAtLeast(1)
        if (stablePrefixSize >= source.size) return source
        val result = ArrayList<CurvePoint>(source.size)
        result += source.subList(0, stablePrefixSize)
        var previous = result.last().value
        for (index in stablePrefixSize until source.size) {
            val point = source[index]
            val delta = point.value - previous
            val scale = robustPowerScale(listOf(previous, point.value), previous) * 2.2f
            val settleWeight = ((1f - factor) * continuousWeight(abs(delta), scale)) + factor
            val value = previous + delta * settleWeight
            result += point.copy(value = value)
            previous = value
        }
        return result
    }

    private fun reduceTemperature(source: List<CurvePoint>): List<CurvePoint> {
        val sums = linkedMapOf<Long, Float>()
        val counts = linkedMapOf<Long, Int>()
        source.sortedBy { it.epochMillis }.forEach { point ->
            val bucketStart = floorDiv(point.epochMillis, TEMPERATURE_BUCKET_MS) * TEMPERATURE_BUCKET_MS
            sums[bucketStart] = (sums[bucketStart] ?: 0f) + point.value
            counts[bucketStart] = (counts[bucketStart] ?: 0) + 1
        }
        val points = sums.map { (bucketStart, sum) ->
            // LLPower places the aggregate half a base bucket after its start.
            CurvePoint(bucketStart + POWER_MIN_BUCKET_MS, sum / counts.getValue(bucketStart))
        }
        return ensureStrictlyIncreasingTemperature(points)
    }

    private fun ensureStrictlyIncreasingTemperature(source: List<CurvePoint>): List<CurvePoint> {
        if (source.size <= 1) return source
        val result = ArrayList<CurvePoint>(source.size)
        source.sortedBy { it.epochMillis }.forEach { point ->
            val previous = result.lastOrNull()
            when {
                previous == null -> result += point
                point.epochMillis > previous.epochMillis -> result += point
                point.epochMillis == previous.epochMillis ->
                    result[result.lastIndex] = previous.copy(value = (previous.value + point.value) / 2f)
            }
        }
        return result
    }

    private fun shapeTemperature(source: List<CurvePoint>): List<CurvePoint> {
        if (source.size <= 2) return source
        return softenTemperature(removeIsolatedTemperatureJitter(source), windowSize = 7)
    }

    private fun removeIsolatedTemperatureJitter(source: List<CurvePoint>): List<CurvePoint> {
        if (source.size <= 2) return source
        val result = ArrayList<CurvePoint>(source.size)
        result += source.first()
        for (index in 1 until source.lastIndex) {
            val previous = source[index - 1].value
            val current = source[index].value
            val next = source[index + 1].value
            val isReversal = (next - current) * (current - previous) < 0f
            val smallestAdjacentDelta = min(abs(current - previous), abs(current - next))
            val totalAdjacentDelta = abs(next - previous)
            result += if (isReversal && smallestAdjacentDelta <= 0.24f && totalAdjacentDelta <= 0.24f) {
                val correction = (((previous + next) / 2f) - current).coerceIn(-0.18f, 0.18f)
                source[index].copy(value = current + correction)
            } else {
                source[index]
            }
        }
        result += source.last()
        return result
    }

    private fun softenTemperature(source: List<CurvePoint>, windowSize: Int): List<CurvePoint> {
        if (source.size <= 2 || windowSize <= 1) return source
        val half = windowSize / 2
        return source.mapIndexed { index, point ->
            val previous = source.getOrNull(index - 1)?.value
            val next = source.getOrNull(index + 1)?.value
            val isReversal = previous != null && next != null &&
                (next - point.value) * (point.value - previous) < 0f
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(source.lastIndex)
            var weighted = 0f
            var weights = 0f
            var localMin = Float.POSITIVE_INFINITY
            var localMax = Float.NEGATIVE_INFINITY
            for (neighbor in from..to) {
                val weight = ((half + 1) - abs(neighbor - index)).coerceAtLeast(1).toFloat()
                val value = source[neighbor].value
                weighted += value * weight
                weights += weight
                localMin = min(localMin, value)
                localMax = max(localMax, value)
            }
            val localMean = (weighted / weights.coerceAtLeast(1f)).coerceIn(localMin, localMax)
            val factor = if (isReversal) 0.24f else 0.72f
            val correction = ((localMean - point.value) * factor).coerceIn(-0.16f, 0.16f)
            point.copy(value = point.value + correction)
        }
    }

    private fun robustPowerCenter(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        if (values.size == 1) return values.first()
        val sorted = values.sorted()
        return (
            percentile(sorted, 0.5f) * 2f +
                percentile(sorted, 0.25f) +
                percentile(sorted, 0.75f)
        ) / 4f
    }

    private fun robustPowerScale(values: List<Float>, center: Float): Float {
        if (values.isEmpty()) return 0.8f
        if (values.size == 1) return abs(values.first()) * 0.035f + 0.8f
        val sorted = values.sorted()
        val iqrScale = (percentile(sorted, 0.75f) - percentile(sorted, 0.25f)) * 0.7413f
        val deviations = values.map { abs(it - center) }.sorted()
        val madScale = percentile(deviations, 0.5f) * 1.4826f
        return max(0.8f, max(abs(center) * 0.035f, max(iqrScale, madScale)))
    }

    private fun percentile(sorted: List<Float>, fraction: Float): Float =
        sorted[(fraction * sorted.lastIndex).toInt().coerceIn(0, sorted.lastIndex)]

    private fun continuousWeight(value: Float, scale: Float): Float {
        val safeScale = scale.coerceAtLeast(0.001f)
        val ratio = (value / safeScale).coerceIn(0f, 1f)
        val squared = ratio * ratio
        return squared / (squared + 1f)
    }

    private fun normalized(
        points: List<ChartPoint>,
        windowStart: Long,
        windowEnd: Long,
        value: (ChartPoint) -> Float,
    ): List<CurvePoint> {
        if (windowEnd < windowStart || points.isEmpty()) return emptyList()

        // History is kept in timestamp order by ChartHistoryBus and SQLite.
        // Use the visible range directly instead of scanning all 24 hours on
        // every chart refresh, then collapse duplicate timestamps in one pass.
        var low = 0
        var high = points.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].epochMillis < windowStart) low = middle + 1 else high = middle
        }
        val first = low
        high = points.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (points[middle].epochMillis <= windowEnd) low = middle + 1 else high = middle
        }
        val endExclusive = low
        if (first >= endExclusive) return emptyList()

        val result = ArrayList<CurvePoint>(endExclusive - first)
        var timestamp = Long.MIN_VALUE
        var sum = 0.0
        var count = 0
        for (index in first until endExclusive) {
            val point = points[index]
            val pointValue = value(point)
            if (!pointValue.isFinite()) continue
            if (timestamp != Long.MIN_VALUE && point.epochMillis != timestamp) {
                result += CurvePoint(timestamp, (sum / count).toFloat())
                sum = 0.0
                count = 0
            }
            timestamp = point.epochMillis
            sum += pointValue
            count++
        }
        if (count > 0) result += CurvePoint(timestamp, (sum / count).toFloat())
        return result
    }

    private fun splitSegments(points: List<CurvePoint>, gapMs: Long): List<CurveSegment> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<CurveSegment>()
        var start = 0
        for (index in 1 until points.size) {
            if (points[index].epochMillis - points[index - 1].epochMillis > gapMs) {
                result += CurveSegment(points.subList(start, index).toList())
                start = index
            }
        }
        result += CurveSegment(points.subList(start, points.size).toList())
        return result
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        val remainder = value % divisor
        return if (remainder != 0L && value < 0L) quotient - 1 else quotient
    }
}
