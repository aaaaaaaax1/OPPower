package com.oppower.history

import com.oppower.model.ChargeRecord
import com.oppower.model.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ChargeRecordBuilderTest {
    private val baseTime = 1_700_000_000_000L

    @Test
    fun averagesUseTrapezoidalTimeWeightsForUnequalIntervals() {
        val points = listOf(
            point(seconds = 0, power = 10f, currentMa = 1_000, temperature = 30f),
            point(seconds = 10, power = 10f, currentMa = 3_000, temperature = 30f),
            point(seconds = 20, power = 40f, currentMa = 5_000, temperature = 40f),
        )

        val record = buildChargeRecord(points)

        assertNotNull(record)
        assertEquals(17.5f, record!!.averagePowerW, 0.001f)
        assertEquals(3_000, record.averageCurrentMa)
        assertEquals(32.5f, record.averageTemperatureC, 0.001f)
        assertEquals(17, record.chargeDeltaMah)
        assertEquals(40f, record.peakPowerW, 0.001f)
    }

    @Test
    fun abnormalGapsAreExcludedFromWeightedTotalsAndChargeIntegration() {
        val points = listOf(
            point(seconds = 0, power = 10f, currentMa = 1_000),
            point(seconds = 10, power = 10f, currentMa = 1_000),
            point(seconds = 10 + MAX_RECORD_SAMPLE_GAP_MS / 1_000L + 1, power = 100f, currentMa = 5_000),
        )

        val record = buildChargeRecord(points)!!

        assertEquals(10f, record.averagePowerW, 0.001f)
        assertEquals(1_000, record.averageCurrentMa)
        assertEquals(3, record.chargeDeltaMah)
        assertEquals(100f, record.peakPowerW, 0.001f)
    }

    @Test
    fun incrementalAndBatchBuildersProduceTheSameCurrentSession() {
        val points = listOf(
            point(seconds = 0, power = 12f, currentMa = 900, acOnline = true),
            point(seconds = 3, power = 20f, currentMa = 1_800, acOnline = true),
            point(seconds = 9, power = 32f, currentMa = 2_800, acOnline = true),
        )
        val batch = buildChargeRecords(points)
        val batchCurrent = batch.currentSession!!.toRecord()
        val incremental = ChargeRecordAccumulator(points.first())
        points.drop(1).forEach(incremental::append)
        val incrementalRecord = incremental.toRecord()

        assertRecordEquals(batchCurrent, incrementalRecord)
    }

    private fun assertRecordEquals(expected: ChargeRecord, actual: ChargeRecord) {
        assertEquals(expected.startedAtMillis, actual.startedAtMillis)
        assertEquals(expected.endedAtMillis, actual.endedAtMillis)
        assertEquals(expected.startPercent, actual.startPercent)
        assertEquals(expected.endPercent, actual.endPercent)
        assertEquals(expected.peakPowerW, actual.peakPowerW, 0.001f)
        assertEquals(expected.averagePowerW, actual.averagePowerW, 0.001f)
        assertEquals(expected.maxTemperatureC, actual.maxTemperatureC, 0.001f)
        assertEquals(expected.peakCurrentMa, actual.peakCurrentMa)
        assertEquals(expected.averageCurrentMa, actual.averageCurrentMa)
        assertEquals(expected.averageTemperatureC, actual.averageTemperatureC, 0.001f)
        assertEquals(expected.chargeDeltaMah, actual.chargeDeltaMah)
        assertEquals(expected.endReason, actual.endReason)
    }

    private fun point(
        seconds: Long,
        power: Float,
        currentMa: Int,
        temperature: Float = 35f,
        acOnline: Boolean = true,
    ) = ChartPoint(
        epochMillis = baseTime + seconds * 1_000L,
        powerW = power,
        temperatureC = temperature,
        levelPercent = (seconds / 10).toInt(),
        acOnline = acOnline,
        currentMa = currentMa,
        voltageMv = 18_000,
    )
}
