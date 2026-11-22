package com.oppower.model

data class SystemAccessState(
    val notificationsAllowed: Boolean = false,
    val statusNotificationChannelEnabled: Boolean = true,
    val liveNotificationChannelEnabled: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    val overlayAllowed: Boolean = false,
    val accessibilityEnabled: Boolean = false,
) {
    fun notificationsReady(usesLiveChannel: Boolean): Boolean =
        notificationsAllowed && if (usesLiveChannel) {
            liveNotificationChannelEnabled
        } else {
            statusNotificationChannelEnabled
        }
}

data class BatterySnapshot(
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val powerW: Float = 0f,
    val temperatureC: Float = 0f,
    val levelPercent: Int = 0,
    val status: Int = 1,
    val acOnline: Boolean = false,
    val sampleValid: Boolean = false,
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

data class ThermalStatus(
    val raw: String = "",
    val status: Int? = null,
    val temperatureC: Float? = null,
    val baseGear: Int? = null,
    val levels: List<Int> = emptyList(),
) {
    val isParsed: Boolean
        get() = baseGear != null
}

enum class BaseTier(val label: String, val gearSet: List<Int>) {
    MAX_OUTPUT("最大输出", listOf(0)),
    HIGH("20–40 W", listOf(1, 2, 3, 4)),
    GENTLE("10–30 W", listOf(2, 3, 4, 5)),
}

val BaseTier.friendlyLabel: String
    get() = when (this) {
        BaseTier.MAX_OUTPUT -> "最高能力"
        BaseTier.HIGH -> "快速充电"
        BaseTier.GENTLE -> "温和策略"
    }

enum class ControlTier(val label: String) {
    MAX_OUTPUT("最大输出"),
    HIGH("20–40 W"),
    GENTLE("10–30 W"),
    PAUSED("暂停 gear 策略"),
}

fun powerTierLabel(gear: Int?): String = when (gear) {
    0 -> "最大输出（无限制）"
    in 1..4 -> "高效快充（20–40 W）"
    5 -> "温和策略（10–30 W）"
    else -> "暂停快充增强"
}

enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

enum class ThemePalette(val label: String) {
    TEAL("青绿"),
    OCEAN("海湾蓝"),
    FOREST("森林绿"),
    AMBER("落日金"),
    ROSE("花瓣粉"),
    VIOLET("雾紫"),
}

data class TierRule(
    val threshold: Float,
    val target: ControlTier,
)

data class SocTierRule(
    val thresholdPercent: Int,
    val target: ControlTier,
)

data class ControlRules(
    val temperatureRules: List<TierRule> = listOf(
        TierRule(42f, ControlTier.HIGH),
        TierRule(43f, ControlTier.PAUSED),
    ),
    val socRules: List<SocTierRule> = listOf(
        SocTierRule(60, ControlTier.HIGH),
        SocTierRule(85, ControlTier.PAUSED),
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
    val startVoltageV: Float = 0f,
    val endVoltageV: Float = 0f,
    val peakCurrentMa: Int = 0,
    val averageCurrentMa: Int = 0,
    val averageTemperatureC: Float = 0f,
    val chargeDeltaMah: Int? = null,
    val endReason: String = "拔电",
)

data class RuntimeSettings(
    val masterEnabled: Boolean = false,
    val baseTier: BaseTier = BaseTier.MAX_OUTPUT,
    val nativeLevelZeroEnabled: Boolean = false,
    val screenOffBoostEnabled: Boolean = false,
    val customRulesEnabled: Boolean = false,
    val customRules: ControlRules = ControlRules(),
    val liveNotificationEnabled: Boolean = true,
    val historyEnabled: Boolean = true,
    val resumeAfterReboot: Boolean = true,
    val temperatureDownshiftSeconds: Int = 5,
    val temperatureUpshiftSeconds: Int = 8,
    val socDownshiftSeconds: Int = 10,
    val socUpshiftSeconds: Int = 30,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val themePalette: ThemePalette = ThemePalette.TEAL,
    val fontScale: Float = 1f,
    val uiScale: Float = 1f,
    val cardOpacity: Float = 1f,
    val hideFromRecents: Boolean = false,
    val notificationShowBatteryPercent: Boolean = true,
    val notificationShowCurrent: Boolean = true,
    val notificationShowVoltage: Boolean = true,
    val notificationShowTemperature: Boolean = true,
    val notificationShowSupply: Boolean = true,
    val showCurrentInAmpere: Boolean = false,
    val notificationIntegerPower: Boolean = false,
    val notificationRefreshSeconds: Int = 5,
    val notificationScreenOffRefreshSeconds: Int = 30,
    val notificationAutoCloseOnFull: Boolean = true,
    val notificationAutoCloseOnScreenOff: Boolean = false,
    val invertCurrentSign: Boolean = false,
    val doubleCellCorrection: Boolean = false,
    val virtualVoltage: Boolean = false,
    val absoluteCurrentPowerDisplay: Boolean = false,
    val debugMode: Boolean = false,
    val overlayKeepAliveEnabled: Boolean = false,
)

enum class ControllerPhase {
    DISABLED,
    WAITING_FOR_POWER,
    ACTIVE,
    TEMPERATURE_PAUSED,
    SOC_PAUSED,
    FULL_PAUSED,
    SCREEN_OFF_PAUSED,
    ERROR,
}

data class GearReadResult(
    val gear: Int?,
    val readAtMillis: Long,
    val baseGear: Int? = null,
    val calculatedGear: Int? = null,
    val cosa: Int? = null,
    val chargeVal: Int? = null,
    val source: String? = null,
    val error: String? = null,
)

data class ControllerStatus(
    val phase: ControllerPhase = ControllerPhase.DISABLED,
    val message: String = "总开关未开启",
    val desiredGear: Int? = null,
    val levelZeroMode: Boolean = false,
    val appliedCosa: Int = 0,
    val sessionSocLocked: Boolean = false,
    val observedGear: Int? = null,
    val baseGear: Int? = null,
    val calculatedGear: Int? = null,
    val chargeVal: Int? = null,
    val gearSource: String? = null,
    val verified: Boolean = false,
)

data class ChartPoint(
    val epochMillis: Long,
    val powerW: Float,
    val temperatureC: Float,
    val levelPercent: Int,
    val acOnline: Boolean,
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
)
