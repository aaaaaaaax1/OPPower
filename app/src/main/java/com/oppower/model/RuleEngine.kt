package com.oppower.model

data class RuleDecision(
    val tier: ControlTier,
    val targetGear: Int?,
    val reason: String,
)

class RuleEngine {

    fun decide(
        settings: RuntimeSettings,
        temperatureC: Float,
        levelPercent: Int,
        batteryFull: Boolean,
        sessionSocLocked: Boolean,
    ): RuleDecision {
        if (batteryFull || levelPercent >= 100) {
            return RuleDecision(ControlTier.PAUSED, null, "电量 100% / FULL：暂停快充增强")
        }
        val pauseSoc = socPauseThreshold(settings)
        if (sessionSocLocked || levelPercent >= pauseSoc) {
            return RuleDecision(ControlTier.PAUSED, null, "电量达到 $pauseSoc%：本次插电暂停")
        }

        val temperatureTier = resolveTier(settings, temperatureC)
        val socTier = resolveSocTier(settings, levelPercent)
        val tier = if (temperatureTier.conservatismRank() <= socTier.conservatismRank()) {
            temperatureTier
        } else {
            socTier
        }
        if (tier == ControlTier.PAUSED) {
            return RuleDecision(tier, null, "温度达到暂停阈值")
        }
        val gear = targetGear(settings, tier, temperatureC)
        val reason = if (tier == ControlTier.MAX_OUTPUT) { "最大输出 · 持续 gear 0" } else { "${tier.label} · 温度适配" }
        return RuleDecision(tier, gear, reason)
    }

    fun resolveTier(settings: RuntimeSettings, temperatureC: Float): ControlTier {
        val base = settings.baseTier.toControlTier()
        val rules = if (settings.customRulesEnabled) {
            settings.customRules.temperatureRules
        } else {
            defaultTemperatureRules(settings.baseTier)
        }
        var result = base
        // A higher threshold may hold the current tier or reduce power. It can
        // never increase the power after a lower-temperature rule already did
        // so. This enforces the ordering on the rule engine side even if an
        // invalid custom combination reaches it.
        rules.sortedBy { it.threshold }.forEach { rule ->
            if (temperatureC >= rule.threshold) {
                if (rule.target.conservatismRank() <= result.conservatismRank()) {
                    result = rule.target
                }
            }
        }
        return result
    }

    fun resolveSocTier(settings: RuntimeSettings, levelPercent: Int): ControlTier {
        val base = settings.baseTier.toControlTier()
        if (!settings.customRulesEnabled) return base
        var result = base
        settings.customRules.socRules.sortedBy { it.thresholdPercent }.forEach { rule ->
            if (levelPercent >= rule.thresholdPercent &&
                rule.target.conservatismRank() <= result.conservatismRank()
            ) {
                result = rule.target
            }
        }
        return result
    }

    fun socPauseThreshold(settings: RuntimeSettings): Int =
        if (settings.customRulesEnabled) {
            settings.customRules.socRules
                .filter { it.target == ControlTier.PAUSED }
                .minOfOrNull { it.thresholdPercent }
                ?: 100
        } else {
            settings.customRules.socStopPercent.coerceIn(1, 100)
        }

    fun defaultTemperatureRules(baseTier: BaseTier): List<TierRule> = when (baseTier) {
        BaseTier.MAX_OUTPUT -> emptyList()
        BaseTier.HIGH, BaseTier.GENTLE -> listOf(
            TierRule(43f, ControlTier.PAUSED),
        )
    }

    fun targetGear(settings: RuntimeSettings, tier: ControlTier, temperatureC: Float): Int? {
        val gears = when (tier) {
            ControlTier.MAX_OUTPUT -> listOf(0)
            ControlTier.HIGH -> BaseTier.HIGH.gearSet
            ControlTier.GENTLE -> BaseTier.GENTLE.gearSet
            ControlTier.PAUSED -> return null
        }
        if (tier == ControlTier.MAX_OUTPUT) return 0

        val t1 = if (settings.customRulesEnabled) {
            settings.customRules.temperatureRules.firstOrNull()?.threshold ?: 41f
        } else {
            41f
        }
        val t2 = if (settings.customRulesEnabled) {
            settings.customRules.temperatureRules.getOrNull(1)?.threshold ?: 43f
        } else {
            43f
        }
        val normalized = ((temperatureC - t1).coerceAtLeast(0f) / 0.5f).toInt()
        val maxSteps = (((t2 - t1) / 0.5f).toInt()).coerceAtLeast(1)
        val step = normalized.coerceAtMost(maxSteps - 1)
        val index = if (maxSteps == 1) 0 else (step * (gears.size - 1) / (maxSteps - 1))
        return gears[index.coerceIn(gears.indices)]
    }
}

fun BaseTier.toControlTier(): ControlTier = when (this) {
    BaseTier.MAX_OUTPUT -> ControlTier.MAX_OUTPUT
    BaseTier.HIGH -> ControlTier.HIGH
    BaseTier.GENTLE -> ControlTier.GENTLE
}

fun ControlTier.conservatismRank(): Int = when (this) {
    ControlTier.MAX_OUTPUT -> 3
    ControlTier.HIGH -> 2
    ControlTier.GENTLE -> 1
    ControlTier.PAUSED -> 0
}
