package com.oppower.controller

import com.oppower.model.BaseTier
import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerPhase
import com.oppower.model.RuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargingControllerTest {
    @Test
    fun baseTierChangeWritesNewCosaOnFirstCycleWithoutZero() {
        val gateway = FakeChargingGateway(cosa = -9, gear = 0)
        val controller = ChargingController(gateway)

        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()

        val status = controller.evaluate(settings(BaseTier.GENTLE), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(2, status.desiredGear)
        assertEquals(listOf(-7), gateway.writes)
        assertFalse(gateway.writes.contains(0))
    }

    @Test
    fun staleHardwareEchoIsReprocessedOnNextEvaluationWithoutGraceWindow() {
        val gateway = FakeChargingGateway(cosa = -9, gear = 0)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.GENTLE)

        controller.evaluate(settings, battery())
        gateway.writes.clear()

        controller.evaluate(settings, battery())
        controller.evaluate(settings, battery())

        assertEquals(listOf(-5, -3), gateway.writes)
    }

    @Test
    fun unchangedTargetDriftIsCorrectedWithoutInputDebounce() {
        val gateway = FakeChargingGateway(cosa = -9, gear = 0)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        assertTrue(controller.evaluate(settings, battery()).verified)
        gateway.writes.clear()
        gateway.cosa = -6
        gateway.gear = 3

        val status = controller.evaluate(settings, battery())

        assertEquals(0, status.desiredGear)
        assertEquals(-9, gateway.cosa)
        assertEquals(listOf(-9), gateway.writes)
    }

    @Test
    fun disablingMasterSwitchImmediatelyRestoresCosaZero() {
        val gateway = FakeChargingGateway(cosa = -9, gear = 0)
        val controller = ChargingController(gateway)
        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()

        val status = controller.evaluate(
            settings(BaseTier.MAX_OUTPUT).copy(masterEnabled = false),
            battery(),
        )

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(0, gateway.cosa)
        assertEquals(listOf(0), gateway.writes)
    }

    @Test
    fun reenablingAfterOffRestoresTargetWithoutIntermediaryZero() {
        val gateway = FakeChargingGateway(cosa = -9, gear = 0)
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)
        controller.evaluate(enabled, battery())
        controller.evaluate(enabled.copy(masterEnabled = false), battery())
        gateway.gear = 8
        gateway.writes.clear()

        val status = controller.evaluate(enabled, battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(0, status.desiredGear)
        assertEquals(listOf(-8), gateway.writes)
        assertFalse(gateway.writes.contains(0))
    }

    private fun settings(baseTier: BaseTier) = RuntimeSettings(
        masterEnabled = true,
        baseTier = baseTier,
    )

    private fun battery() = BatterySnapshot(
        temperatureC = 30f,
        levelPercent = 30,
        status = 2,
        acOnline = true,
    )

    private class FakeChargingGateway(
        var cosa: Int,
        var gear: Int,
    ) : ChargingGateway {
        override val isAvailable = true
        override val hasPermission = true
        val writes = mutableListOf<Int>()

        override fun readGlobal(name: String): String {
            assertEquals("cosa_ctc_value", name)
            return cosa.toString()
        }

        override fun putGlobal(name: String, value: Int) {
            assertEquals("cosa_ctc_value", name)
            writes += value
            cosa = value
        }

        override fun readThermalPolicy(): String {
            return "gearVal: $gear\nchargeVal: 12\nspeedChargeAdd: 1"
        }
    }
}
