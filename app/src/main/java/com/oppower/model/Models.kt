package com.oppower.model

data class BatterySnapshot(
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val powerW: Float = 0f,
    val temperatureC: Float = 0f,
    val levelPercent: Int = 0,
    val status: Int = 1,
    val acOnline: Boolean = false,
) {
    val isCharging: Boolean
        get() = acOnline && (status == 2 || status == 5)
}

data class ThermalPolicy(
    val raw: String = "",
    val category: String = "",
    val gear: Int? = null,
    val chargeVal: Int? = null,
    val speedChargeAdd: Int? = null,
) {
    val isParsed: Boolean
        get() = gear != null
}

enum class BaseTier(val label: String, val gearSet: List<Int>) {
    MAX_OUTPUT("最大输出", listOf(0)),
    HIGH("20–40 W", listOf(1, 2, 3, 4)),
    GENTLE("10–30 W", listOf(2, 3, 4, 5)),
}

enum class ControlTier(val label: String) {
    MAX_OUTPUT("最大输出"),
    HIGH("20–40 W"),
    GENTLE("10–30 W"),
    PAUSED("暂停 gear 策略"),
}

data class TierRule(
    val threshold: Float,
    val target: ControlTier,
)

data class ControlRules(
    val temperatureRules: List<TierRule> = listOf(
        TierRule(41f, ControlTier.HIGH),
        TierRule(42f, ControlTier.GENTLE),
        TierRule(43f, ControlTier.PAUSED),
    ),
    val socStopPercent: Int = 80,
)

data class ChargeRecord(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val startPercent: Int,
    val endPercent: Int,
    val peakPowerW: Float,
    val averagePowerW: Float,
    val maxTemperatureC: Float,
)

data class RuntimeSettings(
    val masterEnabled: Boolean = false,
    val baseTier: BaseTier = BaseTier.MAX_OUTPUT,
    val customRulesEnabled: Boolean = false,
    val customRules: ControlRules = ControlRules(),
    val liveNotificationEnabled: Boolean = true,
    val widgetEnabled: Boolean = true,
    val temperatureDownshiftSeconds: Int = 15,
    val temperatureUpshiftSeconds: Int = 30,
    val socDownshiftSeconds: Int = 10,
    val socUpshiftSeconds: Int = 30,
)

enum class ControllerPhase {
    DISABLED,
    WAITING_FOR_POWER,
    ACTIVE,
    TEMPERATURE_PAUSED,
    SOC_PAUSED,
    FULL_PAUSED,
    ERROR,
}

data class ControllerStatus(
    val phase: ControllerPhase = ControllerPhase.DISABLED,
    val message: String = "总开关未开启",
    val desiredGear: Int? = null,
    val appliedCosa: Int = 0,
    val sessionSocLocked: Boolean = false,
)

data class ChartPoint(
    val epochMillis: Long,
    val powerW: Float,
    val temperatureC: Float,
    val levelPercent: Int,
    val acOnline: Boolean,
)
