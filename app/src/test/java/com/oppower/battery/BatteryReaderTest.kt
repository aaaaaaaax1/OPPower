package com.oppower.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryReaderTest {
    @Test
    fun colorOsTenAmpValueDoesNotCrossTheOldZeroWThreshold() {
        assertEquals(-10_010f, normalizeCurrentToMa(-10_010L), 0f)
        assertEquals(9_947f, normalizeCurrentToMa(9_947L), 0f)
        assertEquals(10_000f, normalizeCurrentToMa(10_000L), 0f)
        assertEquals(10_009f, normalizeCurrentToMa(10_009L), 0f)
        assertEquals(39.4394f, calculatePowerW(10_010f, 3_940), 0.01f)
    }

    @Test
    fun microampereSourcesUseLlpowerTwentyThousandThreshold() {
        assertEquals(20.001f, normalizeCurrentToMa(20_001L), 0f)
        assertEquals(-1_500f, normalizeCurrentToMa(-1_500_000L), 0f)
        assertEquals(0f, normalizeCurrentToMa(Int.MAX_VALUE.toLong()), 0f)
        assertEquals(0f, normalizeCurrentToMa(Long.MIN_VALUE), 0f)
    }

    @Test
    fun privilegedProbeRunsForEveryEligibleChargingSample() {
        assertTrue(shouldProbePrivilegedBattery(levelPercent = 72, status = 2, acOnline = true))
        assertFalse(shouldProbePrivilegedBattery(levelPercent = 72, status = 3, acOnline = false))
        assertFalse(shouldProbePrivilegedBattery(levelPercent = 99, status = 2, acOnline = true))
        assertFalse(shouldProbePrivilegedBattery(levelPercent = 98, status = 5, acOnline = true))
    }
    @Test
    fun privilegedFallbackReplacesPrimaryZeroWithoutMixingSamples() {
        val resolved = resolveBatteryMeasurement(
            primaryCurrentMa = 0f,
            primaryVoltageMv = 4_220,
            fallback = PrivilegedBatteryReading(currentMa = 493, voltageMv = 3_978),
        )

        assertEquals(493f, resolved.currentMa, 0f)
        assertEquals(3_978, resolved.voltageMv)
        assertEquals(1.961154f, calculatePowerW(resolved.currentMa, resolved.voltageMv), 0.000001f)
    }

    @Test
    fun directPrivilegedSourceCorrectsAPlausibleButWrongPrimaryReading() {
        val resolved = resolveBatteryMeasurement(
            primaryCurrentMa = 5_000f,
            primaryVoltageMv = 4_100,
            fallback = PrivilegedBatteryReading(
                currentMa = 8_000,
                voltageMv = 4_050,
                source = PrivilegedBatterySource.BATTERY_SYSFS,
            ),
        )

        assertEquals(8_000f, resolved.currentMa, 0f)
        assertEquals(4_050, resolved.voltageMv)
    }

    @Test
    fun missingOrImplausibleFallbackDoesNotFabricateCurrent() {
        assertEquals(
            0f,
            resolveBatteryMeasurement(
                primaryCurrentMa = 0f,
                primaryVoltageMv = 4_100,
                fallback = PrivilegedBatteryReading(currentMa = 0, voltageMv = 4_100),
            ).currentMa,
            0f,
        )
        assertEquals(
            0f,
            resolveBatteryMeasurement(
                primaryCurrentMa = 0f,
                primaryVoltageMv = 4_100,
                fallback = PrivilegedBatteryReading(currentMa = 20_001, voltageMv = 4_100),
            ).currentMa,
            0f,
        )
        assertEquals(
            512f,
            resolveBatteryMeasurement(
                primaryCurrentMa = 512f,
                primaryVoltageMv = 4_100,
                fallback = PrivilegedBatteryReading(currentMa = 0, voltageMv = 3_978),
            ).currentMa,
            0f,
        )
    }

    @Test
    fun powerUsesTheSameCurrentValueShownToTheUser() {
        assertEquals(39.4394f, calculatePowerW(10_010f, 3_940), 0.0001f)
        assertEquals(0.9042f, calculatePowerW(268f, 3_374), 0.0001f)
    }

    @Test
    fun nearFullIntegerAmpMarkerIsSanitizedLikeLlpower() {
        assertEquals(
            0f,
            sanitizeCurrentReading(
                currentMa = 10_000f,
                levelPercent = 99,
                plugged = true,
            ),
            0f,
        )
        assertEquals(
            10_010f,
            sanitizeCurrentReading(
                currentMa = 10_010f,
                levelPercent = 99,
                plugged = true,
            ),
            0f,
        )
        assertEquals(
            10_000f,
            sanitizeCurrentReading(
                currentMa = 10_000f,
                levelPercent = 98,
                plugged = true,
            ),
            0f,
        )
    }

    @Test
    fun transientAcZeroKeepsTheLastReliableSample() {
        val stabilizer = CurrentSampleStabilizer(holdMillis = 3_500L)

        assertEquals(
            ResolvedBatteryMeasurement(10_010f, 3_940),
            stabilizer.stabilize(
                currentMa = 10_010f,
                voltageMv = 3_940,
                acOnline = true,
                chargingEligible = true,
                nowMillis = 1_000L,
            ),
        )
        assertEquals(
            ResolvedBatteryMeasurement(10_010f, 3_940),
            stabilizer.stabilize(
                currentMa = 0f,
                voltageMv = 0,
                acOnline = true,
                chargingEligible = true,
                nowMillis = 2_000L,
            ),
        )
    }

    @Test
    fun normalNonZeroCurrentChangesAreNotDebounced() {
        val stabilizer = CurrentSampleStabilizer(holdMillis = 3_500L)
        stabilizer.stabilize(10_010f, 3_940, true, true, 1_000L)

        assertEquals(
            ResolvedBatteryMeasurement(8_320f, 3_925),
            stabilizer.stabilize(8_320f, 3_925, true, true, 1_100L),
        )
    }

    @Test
    fun zeroIsAcceptedAfterTheShortHoldWindow() {
        val stabilizer = CurrentSampleStabilizer(holdMillis = 3_500L)
        stabilizer.stabilize(8_500f, 4_000, true, true, 1_000L)

        assertEquals(
            0f,
            stabilizer.stabilize(0f, 0, true, true, 4_501L).currentMa,
            0f,
        )
    }

    @Test
    fun plausibilityRejectsMissingAndExtremeMeasurements() {
        assertTrue(isPlausibleMeasurement(10_010f, 3_940))
        assertFalse(isPlausibleMeasurement(0f, 3_940))
        assertFalse(isPlausibleMeasurement(10_010f, 0))
        assertFalse(isPlausibleMeasurement(20_001f, 3_940))
    }

    @Test
    fun expiredMissingSampleIsExplicitlyInvalidInsteadOfZeroWatts() {
        val stabilizer = CurrentSampleStabilizer(holdMillis = 5_000L)
        stabilizer.stabilize(8_500f, 4_000, true, true, 1_000L)

        val expired = stabilizer.stabilize(0f, 0, true, true, 6_001L)

        assertFalse(expired.valid)
        assertEquals(0f, expired.currentMa, 0f)
    }

    @Test
    fun disconnectAndFullBatteryNeverReuseAChargingSample() {
        val stabilizer = CurrentSampleStabilizer(holdMillis = 3_500L)
        stabilizer.stabilize(8_500f, 4_000, true, true, 1_000L)

        assertEquals(
            0f,
            stabilizer.stabilize(0f, 0, false, true, 2_000L).currentMa,
            0f,
        )

        val fullStabilizer = CurrentSampleStabilizer(holdMillis = 3_500L)
        fullStabilizer.stabilize(8_500f, 4_000, true, true, 1_000L)
        assertEquals(
            0f,
            fullStabilizer.stabilize(0f, 0, true, false, 2_000L).currentMa,
            0f,
        )
    }
}
