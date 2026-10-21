package com.oppower.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleEngineTest {
    private val engine = RuleEngine()

    @Test
    fun defaultMaxOutputRulesFollowMeasuredTiers() {
        val settings = RuntimeSettings(baseTier = BaseTier.MAX_OUTPUT)

        assertEquals(0, decision(settings, 40f, 30).targetGear)
        assertEquals(1, decision(settings, 41f, 30).targetGear)
        assertEquals(2, decision(settings, 41.5f, 30).targetGear)
        assertEquals(4, decision(settings, 42f, 30).targetGear)
        assertEquals(5, decision(settings, 42.5f, 30).targetGear)
        assertNull(decision(settings, 43f, 30).targetGear)
    }

    @Test
    fun socLimitPausesForCurrentPlugSession() {
        val settings = RuntimeSettings(baseTier = BaseTier.MAX_OUTPUT)

        val result = decision(settings, 30f, 80)

        assertEquals(ControlTier.PAUSED, result.tier)
        assertNull(result.targetGear)
    }

    @Test
    fun fullBatteryAlwaysPauses() {
        val result = engine.decide(
            settings = RuntimeSettings(baseTier = BaseTier.MAX_OUTPUT),
            temperatureC = 30f,
            levelPercent = 100,
            batteryFull = true,
            sessionSocLocked = false,
        )

        assertEquals(ControlTier.PAUSED, result.tier)
        assertNull(result.targetGear)
    }

    @Test
    fun customRuleCannotRaisePowerAtHigherTemperature() {
        val settings = RuntimeSettings(
            baseTier = BaseTier.MAX_OUTPUT,
            customRulesEnabled = true,
            customRules = ControlRules(
                temperatureRules = listOf(
                    TierRule(40f, ControlTier.HIGH),
                    TierRule(42f, ControlTier.MAX_OUTPUT),
                ),
                socStopPercent = 90,
            ),
        )

        assertEquals(ControlTier.HIGH, engine.resolveTier(settings, 42.5f))
    }

    @Test
    fun gentleTierUsesTemperatureForInternalGear() {
        val settings = RuntimeSettings(baseTier = BaseTier.GENTLE)

        assertEquals(2, decision(settings, 40f, 30).targetGear)
        assertEquals(2, decision(settings, 41f, 30).targetGear)
        assertEquals(3, decision(settings, 41.5f, 30).targetGear)
        assertEquals(4, decision(settings, 42f, 30).targetGear)
        assertEquals(5, decision(settings, 42.5f, 30).targetGear)
    }

    private fun decision(settings: RuntimeSettings, temperatureC: Float, levelPercent: Int): RuleDecision =
        engine.decide(
            settings = settings,
            temperatureC = temperatureC,
            levelPercent = levelPercent,
            batteryFull = false,
            sessionSocLocked = false,
        )
}
