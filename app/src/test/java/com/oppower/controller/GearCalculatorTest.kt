package com.oppower.controller

import com.oppower.model.ThermalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GearCalculatorTest {
    @Test
    fun parsesThermalStatusAndUsesFirstLevelAsBaseGear() {
        val status = GearCalculator.parseThermalStatus(
            """
            Thermal status = 7
            Thermal Temperature = 39.25

            Thermal Levels = [7, 8]
            """.trimIndent(),
        )

        assertEquals(7, status.status)
        assertEquals(39.25f, status.temperatureC)
        assertEquals(7, status.baseGear)
        assertEquals(listOf(7, 8), status.levels)
    }

    @Test
    fun targetCosaUsesOnlyTargetAndBaseGear() {
        assertEquals(-6, GearCalculator.cosaForTarget(targetGear = 0, baseGear = 6))
        assertEquals(-5, GearCalculator.cosaForTarget(targetGear = 1, baseGear = 6))
        assertEquals(-1, GearCalculator.cosaForTarget(targetGear = 5, baseGear = 6))
    }

    @Test
    fun targetOutsideSafeCosaRangeIsRejected() {
        assertNull(GearCalculator.cosaForTarget(targetGear = 0, baseGear = 20))
        assertNull(GearCalculator.cosaForTarget(targetGear = 9, baseGear = -1))
    }

    @Test
    fun resolvedGearUsesBaseAndCosaAndIgnoresCachedPolicyGear() {
        val policy = ThermalPolicy(gear = 7, chargeVal = 4)
        val status = GearCalculator.parseThermalStatus("Thermal Levels = [6]")

        val resolved = GearCalculator.resolve(policy, status, cosa = -6)

        assertEquals(0, resolved.gear)
        assertEquals(6, resolved.baseGear)
        assertEquals(0, resolved.calculatedGear)
        assertEquals(-6, resolved.cosa)
        assertEquals(4, resolved.chargeVal)
    }
}
