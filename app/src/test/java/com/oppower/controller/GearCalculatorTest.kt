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
    fun clampsUnreachableTargetToSafeCosaBoundary() {
        assertEquals(-10, GearCalculator.cosaForTarget(targetGear = 0, baseGear = 10))
        assertNull(GearCalculator.cosaForTarget(targetGear = 0, baseGear = 11))
        assertEquals(-10, GearCalculator.clampCosaForTarget(targetGear = 0, baseGear = 11))
        assertEquals(-8, GearCalculator.clampCosaForTarget(targetGear = 0, baseGear = 8))
    }

}
