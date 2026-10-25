package com.oppower.battery

import org.junit.Assert.assertEquals
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
}
