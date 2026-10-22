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
    fun initialApplyCalculatesTargetGearImmediately() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        val first = controller.evaluate(settings, battery())
        val second = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ACTIVE, first.phase)
        assertEquals(0, first.desiredGear)
        assertEquals(0, first.observedGear)
        assertEquals(0, first.calculatedGear)
        assertTrue(first.verified)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(0, second.observedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun baseGearSixTargetZeroWritesMinusSix() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 6, baseGear = 6)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(6, status.baseGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun cachedPolicyGearDoesNotAffectCalculatedGear() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 9, baseGear = 8)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(8, status.baseGear)
        assertEquals(-8, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertTrue(status.message.contains("计算档位=0"))
    }

    @Test
    fun baseGearChangeRecalculatesCosaEvenWithStaleCachedGear() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 7, baseGear = 7)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(settings, battery())
        gateway.writes.clear()
        val refreshBefore = gateway.refreshRequests
        gateway.baseGear = 6

        val status = controller.evaluate(settings, battery())

        assertEquals(6, status.baseGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertEquals(refreshBefore + 1, gateway.refreshRequests)
        assertTrue(status.verified)
    }

    @Test
    fun baseTierChangeRecalculatesCosaWithoutIntermediaryZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)

        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()
        val refreshBefore = gateway.refreshRequests

        val status = controller.evaluate(settings(BaseTier.GENTLE), battery())

        assertEquals(2, status.desiredGear)
        assertEquals(-6, status.appliedCosa)
        assertEquals(2, status.calculatedGear)
        assertEquals(listOf(-6), gateway.writes)
        assertFalse(gateway.writes.contains(0))
        assertEquals(refreshBefore + 1, gateway.refreshRequests)
    }

    @Test
    fun externalCosaDriftIsCorrectedFromBaseGear() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)
        assertTrue(controller.evaluate(settings, battery()).verified)

        gateway.writes.clear()
        gateway.refreshRequests = 0
        gateway.cosa = -6

        val corrected = controller.evaluate(settings, battery())
        controller.evaluate(settings, battery())

        assertEquals(-8, corrected.appliedCosa)
        assertEquals(0, corrected.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun reenablingAfterOffWritesTargetCosaWithoutZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)
        controller.evaluate(enabled, battery())
        controller.evaluate(enabled.copy(masterEnabled = false), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0

        val status = controller.evaluate(enabled, battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(0, status.desiredGear)
        assertEquals(listOf(-8), gateway.writes)
        assertFalse(gateway.writes.contains(0))
        assertEquals(1, gateway.refreshRequests)
    }

    @Test
    fun disablingMasterSwitchRestoresCosaZero() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
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
    fun refreshFailureDoesNotRewriteSameCalculatedTarget() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8, refreshSucceeds = false)
        val controller = ChargingController(gateway)
        val settings = settings(BaseTier.MAX_OUTPUT)

        val first = controller.evaluate(settings, battery())
        val second = controller.evaluate(settings, battery())

        assertTrue(first.message.contains("系统重算触发失败"))
        assertEquals(0, first.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(0, second.calculatedGear)
    }

    @Test
    fun readCurrentGearUsesOnlyBaseAndCosa() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 8, baseGear = 8)
        val controller = ChargingController(gateway)

        assertEquals(0, controller.readCurrentGear())
        val resolved = controller.readResolvedGear()
        assertEquals(0, resolved.gear)
        assertEquals(8, resolved.baseGear)
        assertEquals(-8, resolved.cosa)
        assertEquals("系统 Thermal Levels + cosa_ctc_value（忽略 policy.gear 缓存）", resolved.source)
    }

    @Test
    fun unreachableTargetDoesNotWriteOutsideCosaRange() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 20, baseGear = 20)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(ControllerPhase.ERROR, status.phase)
        assertTrue(gateway.writes.isEmpty())
        assertEquals(0, gateway.refreshRequests)
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
        var baseGear: Int = 8,
        var refreshSucceeds: Boolean = true,
    ) : ChargingGateway {
        override val isAvailable = true
        override val hasPermission = true
        val writes = mutableListOf<Int>()
        var refreshRequests = 0

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
            return "categoryName: test_scene\ngearVal: $gear\nchargeVal: 12\nspeedChargeAdd: 1"
        }

        override fun readThermalStatus(): String {
            return "Thermal status = $baseGear\nThermal Temperature = 35.0\nThermal Levels = [$baseGear]"
        }

        override fun requestThermalPolicyRefresh(): Boolean {
            refreshRequests += 1
            return refreshSucceeds
        }
    }
}
