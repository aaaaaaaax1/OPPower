package com.oppower.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedBatterySamplingTest {
    @Test
    fun currentNormalizerHandlesColorOsMixedUnits() {
        assertEquals(2_000f, normalizePrivilegedCurrentToMa(2_000L), 0f)
        assertEquals(-10_010f, normalizePrivilegedCurrentToMa(-10_010L), 0f)
        assertEquals(2_000f, normalizePrivilegedCurrentToMa(2_000_000L), 0f)
    }

    @Test
    fun forcedBatteryServiceCurrentParsesLatestHardwareValue() {
        assertEquals(-1_603f, parseForcedBatteryCurrent("  -1603\n") ?: 0f, 0f)
        assertEquals(1_603f, parseForcedBatteryCurrent("1603") ?: 0f, 0f)
        assertNull(parseForcedBatteryCurrent("unknown"))
    }

    @Test
    fun voltageNormalizerReturnsMillivoltsOnlyForBatteryRange() {
        assertEquals(4_142, normalizePrivilegedVoltageToMv(4_142L))
        assertEquals(4_142, normalizePrivilegedVoltageToMv(4_142_000L))
        assertEquals(0, normalizePrivilegedVoltageToMv(10_000L))
        assertEquals(0, normalizePrivilegedVoltageToMv(null))
    }

    @Test
    fun dumpsysParserStillWorksAsTheLastFallback() {
        val reading = parsePrivilegedBatteryDump(
            """
            Current OPLUS Battery Service state:
              Battery current : -1257
            Current Battery Service state:
              voltage: 4142
            """.trimIndent(),
        )

        assertEquals(-1_257, reading?.currentMa)
        assertEquals(4_142, reading?.voltageMv)
        assertNull(parsePrivilegedBatteryDump("Battery current : unavailable"))
    }
}