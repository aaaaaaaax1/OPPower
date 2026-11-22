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
    fun maxToGentleAndBackToMaxRestoresGearImmediately() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)

        controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0

        val gentle = controller.evaluate(settings(BaseTier.GENTLE), battery())
        gateway.writes.clear()
        gateway.refreshRequests = 0
        val restored = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(2, gentle.desiredGear)
        assertEquals(0, restored.desiredGear)
        assertEquals(0, restored.calculatedGear)
        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertTrue(restored.verified)
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
    fun unreachableTargetClampsToCosaBoundaryWithoutError() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 20, baseGear = 20)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(listOf(-10), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
        assertEquals(-10, status.appliedCosa)
        assertEquals(10, status.calculatedGear)
        assertFalse(status.verified)
        assertTrue(status.message.contains("目标 gear 0 不可达"))
    }

    @Test
    fun baseGearTenWritesMinusTenAndReachesGearZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 10, baseGear = 10)
        val controller = ChargingController(gateway)

        val status = controller.evaluate(settings(BaseTier.MAX_OUTPUT), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertEquals(listOf(-10), gateway.writes)
        assertEquals(-10, status.appliedCosa)
        assertEquals(0, status.calculatedGear)
        assertTrue(status.verified)
        assertTrue(status.message.contains("计算档位已达到 0"))
    }

    @Test
    fun disconnectRestoresCosaOnly() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 8)
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(enabled, battery())
        val status = controller.evaluate(
            enabled,
            battery().copy(acOnline = false, status = 3),
        )

        assertEquals(ControllerPhase.WAITING_FOR_POWER, status.phase)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun gearZeroWritesCosaOnceWithoutUnverifiedBroadcast() {
        val gateway = FakeChargingGateway(
            cosa = 0,
            gear = 8,
            policyCategory = "common_config",
            policyChargeVal = 0,
            policySpeedAdd = -2,
        )
        val controller = ChargingController(gateway)
        val enabled = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(enabled, battery())
        controller.evaluate(enabled, battery())

        assertEquals(listOf(-8), gateway.writes)
        assertEquals(1, gateway.refreshRequests)
    }




    @Test
    fun levelZeroStartsImmediatelyAndPollsOnlyEveryThreeSeconds() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        val first = controller.evaluate(settings, battery())
        assertEquals(ControllerPhase.ACTIVE, first.phase)
        assertTrue(first.levelZeroMode)
        assertEquals(0, first.desiredGear)
        assertEquals(listOf(0), gateway.writes)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
        assertEquals(0, gateway.policyReads)
        assertEquals(0, gateway.statusReads)

        now = 1_999L
        controller.evaluate(settings, battery())
        now = 3_999L
        controller.evaluate(settings, battery())
        assertEquals(listOf(0), gateway.nativeLevelRequests)

        now = 4_000L
        controller.evaluate(settings, battery())
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
        assertEquals(listOf(0), gateway.writes)
    }

    @Test
    fun levelZeroFailureIsReportedAndRetriedAfterThreeSeconds() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0).apply {
            nativeLevelRequestSucceeds = false
        }
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        val first = controller.evaluate(settings, battery())
        now = 2_000L
        val secondAtTwoSeconds = controller.evaluate(settings, battery())
        now = 4_000L
        val retry = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ERROR, first.phase)
        assertEquals(ControllerPhase.ERROR, secondAtTwoSeconds.phase)
        assertEquals(listOf(0), gateway.nativeLevelRequests.take(1))
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
        assertTrue(first.message.contains("3 秒后重试"))
        assertTrue(retry.message.contains("3 秒后重试"))
    }

    @Test
    fun levelZeroStartsWithMasterSwitchOff() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings().copy(masterEnabled = false)

        val status = controller.evaluate(settings, battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
        assertEquals(0, gateway.policyReads)
        assertEquals(0, gateway.statusReads)
    }

    @Test
    fun turningMasterOffDoesNotStopLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings()

        assertEquals(ControllerPhase.ACTIVE, controller.evaluate(settings, battery()).phase)
        now = 4_000L
        val status = controller.evaluate(settings.copy(masterEnabled = false), battery())

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0, 0), gateway.nativeLevelRequests)
    }

    @Test
    fun turningOffLevelZeroWhileMasterIsOffRestoresSafeLevel() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = levelZeroSettings().copy(masterEnabled = false)

        controller.evaluate(settings, battery())
        now = 2_000L
        val status = controller.evaluate(settings.copy(nativeLevelZeroEnabled = false), battery())

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun socPauseRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(levelPercent = 80))

        assertEquals(ControllerPhase.SOC_PAUSED, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun disconnectRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(acOnline = false, status = 3))

        assertEquals(ControllerPhase.WAITING_FOR_POWER, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun fullChargeRestoresNativeLevelTwoFromLevelZero() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery())
        val status = controller.evaluate(settings, battery().copy(levelPercent = 100, status = 5))

        assertEquals(ControllerPhase.FULL_PAUSED, status.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun screenOffPausesLevelZeroWhenScreenOffBoostIsDisabled() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings()

        controller.evaluate(settings, battery(), screenOn = true)
        val paused = controller.evaluate(settings, battery(), screenOn = false)
        controller.evaluate(settings, battery(), screenOn = false)

        assertEquals(ControllerPhase.SCREEN_OFF_PAUSED, paused.phase)
        assertEquals(listOf(0, 2), gateway.nativeLevelRequests)
        assertEquals(0, gateway.cosa)
    }

    @Test
    fun screenOffKeepsLevelZeroWhenScreenOffBoostIsEnabled() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        val settings = levelZeroSettings().copy(screenOffBoostEnabled = true)

        val status = controller.evaluate(settings, battery(), screenOn = false)

        assertEquals(ControllerPhase.ACTIVE, status.phase)
        assertTrue(status.levelZeroMode)
        assertEquals(listOf(0), gateway.nativeLevelRequests)
    }

    @Test
    fun failedExplicitRestoreDoesNotReportSuccess() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)
        controller.evaluate(levelZeroSettings(), battery())
        gateway.nativeLevelRequestSucceeds = false

        val status = controller.restoreAll(forceNativeRestore = true)

        assertEquals(ControllerPhase.ERROR, status.phase)
        assertTrue(status.levelZeroMode)
        assertTrue(status.message.contains("恢复系统默认失败"))
    }

    @Test
    fun failedFreshSafetyRestoreStaysPendingForNextControlCycle() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0).apply {
            nativeLevelRequestSucceeds = false
        }
        val controller = ChargingController(gateway)

        val failed = controller.restoreAll(forceNativeRestore = true)
        gateway.nativeLevelRequestSucceeds = true
        val retried = controller.evaluate(RuntimeSettings(), battery())

        assertEquals(ControllerPhase.ERROR, failed.phase)
        assertTrue(failed.levelZeroMode)
        assertEquals(ControllerPhase.DISABLED, retried.phase)
        assertEquals(listOf(2, 2), gateway.nativeLevelRequests)
    }

    @Test
    fun explicitRestoreAlsoWritesSafeNativeLevel() {
        val gateway = FakeChargingGateway(cosa = 0, gear = 0)
        val controller = ChargingController(gateway)

        val status = controller.restoreAll(forceNativeRestore = true)

        assertEquals(ControllerPhase.DISABLED, status.phase)
        assertEquals(listOf(2), gateway.nativeLevelRequests)
    }

    @Test
    fun diagnosticPolicyIsCachedWithoutSlowingGearStatusChecks() {
        val gateway = FakeChargingGateway(cosa = -8, gear = 0, baseGear = 8)
        var now = 1_000L
        val controller = ChargingController(gateway, nowMillis = { now })
        val settings = settings(BaseTier.MAX_OUTPUT)

        controller.evaluate(settings, battery())
        gateway.policyReads = 0
        gateway.statusReads = 0

        now = 2_000L
        controller.evaluate(settings, battery())
        now = 3_000L
        controller.evaluate(settings, battery())

        assertEquals(1, gateway.policyReads)
        assertEquals(2, gateway.statusReads)

        now = 5_001L
        controller.evaluate(settings, battery())

        assertEquals(2, gateway.policyReads)
        assertEquals(3, gateway.statusReads)
    }

    private fun settings(baseTier: BaseTier) = RuntimeSettings(
        masterEnabled = true,
        baseTier = baseTier,
    )

    private fun levelZeroSettings(baseTier: BaseTier = BaseTier.MAX_OUTPUT) =
        settings(baseTier).copy(nativeLevelZeroEnabled = true)

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


        var policyCategory: String = "test_scene",
        var policyChargeVal: Int? = 12,
        var policySpeedAdd: Int? = 1,
    ) : ChargingGateway {
        override val isAvailable = true
        override val hasPermission = true
        val writes = mutableListOf<Int>()

        var refreshRequests = 0
        var policyReads = 0
        var statusReads = 0
        var nativeLevelRequestSucceeds = true
        val nativeLevelRequests = mutableListOf<Int>()

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
            policyReads += 1
            return "categoryName: $policyCategory\ngearVal: $gear\nchargeVal: ${policyChargeVal ?: "--"}\nspeedChargeAdd: ${policySpeedAdd ?: "--"}"
        }

        override fun readThermalStatus(): String {
            statusReads += 1
            return "Thermal status = $baseGear\nThermal Temperature = 35.0\nThermal Levels = [$baseGear]"
        }

        override fun requestThermalPolicyRefresh(): Boolean {
            refreshRequests += 1
            return refreshSucceeds
        }

        override fun requestNativeChargeLevel(level: Int): Boolean {
            nativeLevelRequests += level
            return nativeLevelRequestSucceeds
        }
    }

}
