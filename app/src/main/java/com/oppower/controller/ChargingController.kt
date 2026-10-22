package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.BaseTier
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.ControlRules
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.RuleEngine
import com.oppower.model.ThermalPolicy

interface ChargingGateway {
    val isAvailable: Boolean
    val hasPermission: Boolean

    fun readGlobal(name: String): String
    fun putGlobal(name: String, value: Int)
    fun readThermalPolicy(): String
}

class ChargingController(
    private val shizuku: ChargingGateway,
    private val rules: RuleEngine = RuleEngine(),
) {
    private var sessionSocLocked = false
    private var lastAcForSession: Boolean? = null
    private var lastFull = false
    private var currentCosa = 0
    private var cosaInitialized = false
    private var appliedGear: Int? = null
    private var lastConfirmedGear: Int? = null
    private var lastUserRequestedGear: Int? = null
    private var appliedTier: ControlTier? = null
    private var temperatureCandidateGear: Int? = null
    private var temperatureCandidateSince = 0L
    private var controlSettingsSnapshot: ControlSettings? = null
    private var pausedFromGear: Int? = null
    private var forceRefreshRequested = true

    @Synchronized
    fun evaluate(settings: RuntimeSettings, battery: BatterySnapshot): ControllerStatus {
        if (!settings.masterEnabled) {
            restoreBoth()
            lastAcForSession = battery.acOnline
            return ControllerStatus(ControllerPhase.DISABLED, "总开关未开启")
        }

        if (!shizuku.isAvailable) {
            return ControllerStatus(ControllerPhase.ERROR, "Shizuku 未运行，未写入新的充电设置")
        }
        if (!shizuku.hasPermission) {
            return ControllerStatus(ControllerPhase.ERROR, "OPPower 尚未获得 Shizuku 授权")
        }

        return runCatching {
            initializeFromDevice()
            if (lastAcForSession != null && lastAcForSession != battery.acOnline) {
                resetSession()
            }
            lastAcForSession = battery.acOnline

            if (!battery.acOnline) {
                restoreBoth()
                return ControllerStatus(ControllerPhase.WAITING_FOR_POWER, "等待接入电源")
            }

            val full = battery.levelPercent >= 100 || battery.status == 5
            if (full) {
                restoreBoth()
                lastFull = true
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "电量已充满：已恢复系统默认")
            }
            if (lastFull) {
                restoreBoth()
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "本次插电已充满，拔电后重新开始")
            }

            val pauseSoc = rules.socPauseThreshold(settings)
            if (battery.levelPercent >= pauseSoc) {
                sessionSocLocked = true
            }
            if (sessionSocLocked) {
                restoreBoth()
                return ControllerStatus(
                    ControllerPhase.SOC_PAUSED,
                    "电量达到 $pauseSoc%：本次插电已暂停快充增强",
                    sessionSocLocked = true,
                )
            }

            val decision = rules.decide(
                settings = settings,
                temperatureC = battery.temperatureC,
                levelPercent = battery.levelPercent,
                batteryFull = full,
                sessionSocLocked = sessionSocLocked,
            )
            val requested = decision.targetGear
            if (requested == null) {
                lastUserRequestedGear = null
                if (appliedTier == ControlTier.PAUSED && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val current = appliedGear ?: pausedFromGear
                if (appliedGear == null && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                if (current == null) {
                    restoreBoth()
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val now = System.currentTimeMillis()
                val holdSeconds = settings.temperatureDownshiftSeconds.coerceAtLeast(1)
                if (temperatureCandidateGear != TEMPERATURE_PAUSE_CANDIDATE) {
                    temperatureCandidateGear = TEMPERATURE_PAUSE_CANDIDATE
                    temperatureCandidateSince = now
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 持续 $holdSeconds 秒后暂停",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastConfirmedGear,
                    )
                }
                if (now - temperatureCandidateSince < holdSeconds * 1_000L) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 等待确认（$holdSeconds 秒）",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastConfirmedGear,
                    )
                }
                restoreBoth()
                pausedFromGear = current
                appliedTier = ControlTier.PAUSED
                return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
            }

            val target = confirmGear(requested, settings, decision.tier)
            if (target == null) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "等待 ${if (requested > (appliedGear ?: requested)) settings.temperatureDownshiftSeconds else settings.temperatureUpshiftSeconds} 秒确认 · ${decision.reason}",
                    desiredGear = requested,
                    appliedCosa = currentCosa,
                    observedGear = lastConfirmedGear,
                )
            }

            val policy = readPolicy()
            val observedGear = policy.gear
            if (observedGear == null) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "系统暂未返回实际档位 · 保留当前偏移并继续跟踪",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                    observedGear = null,
                )
            }

            if (observedGear == target) {
                appliedGear = target
                lastUserRequestedGear = target
                lastConfirmedGear = target
                forceRefreshRequested = false
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "${decision.reason} · 实际档位已达到目标 $target",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                    observedGear = observedGear,
                    verified = true,
                )
            }

            val targetChanged = forceRefreshRequested || lastUserRequestedGear != target
            // Every cycle is applied immediately. A stale hardware echo is still
            // processed on the next cycle; there is no debounce or grace window.
            // Closed-loop correction: the target gear is the acceptance signal.
            // Re-read the live setting so external or failed writes cannot leave
            // a stale control value, then replace it without passing through 0.
            shizuku.readGlobal("cosa_ctc_value").toIntOrNull()?.let { currentCosa = it }
            val correctedCosa = (currentCosa + target - observedGear).coerceIn(COSA_MIN, COSA_MAX)
            if (correctedCosa == currentCosa && !targetChanged) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "实际 gear $observedGear 尚未达到 $target · cosa 已到安全边界 $currentCosa",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                    observedGear = observedGear,
                )
            }
            val wrote = writeCosa(correctedCosa, force = targetChanged)
            appliedGear = target
            lastUserRequestedGear = target
            return ControllerStatus(
                ControllerPhase.ACTIVE,
                buildString {
                    append("实际 gear $observedGear → 目标 $target")
                    if (wrote) append(" · 已直接写入 cosa=$currentCosa")
                },
                desiredGear = target,
                appliedCosa = currentCosa,
                observedGear = observedGear,
            )
        }.getOrElse { error ->
            ControllerStatus(
                ControllerPhase.ERROR,
                "控制暂时中断，已保持当前设置：${error.message ?: error.javaClass.simpleName}",
                desiredGear = appliedGear,
                appliedCosa = currentCosa,
                observedGear = lastConfirmedGear,
            )
        }
    }

    @Synchronized
    fun readCurrentGear(): Int? {
        if (!shizuku.isAvailable || !shizuku.hasPermission) {
            throw IllegalStateException("请先完成 Shizuku 授权")
        }
        return readPolicy().gear
    }

    @Synchronized
    fun restoreAll(): ControllerStatus {
        restoreBoth()
        resetSession()
        return ControllerStatus(ControllerPhase.DISABLED, "已恢复系统默认")
    }

    @Synchronized
    fun resetSession() {
        sessionSocLocked = false
        lastFull = false
        appliedGear = null
        lastUserRequestedGear = null
        appliedTier = null
        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        controlSettingsSnapshot = null
        pausedFromGear = null
    }

    private fun confirmGear(
        requested: Int,
        settings: RuntimeSettings,
        tier: ControlTier,
    ): Int? {
        val current = appliedGear ?: pausedFromGear
        val now = System.currentTimeMillis()
        val controlSettings = ControlSettings(
            baseTier = settings.baseTier,
            customRulesEnabled = settings.customRulesEnabled,
            customRules = settings.customRules,
        )
        val controlSettingsChanged = controlSettingsSnapshot?.let { it != controlSettings } == true
        controlSettingsSnapshot = controlSettings

        if (current == null) {
            appliedTier = tier
            return requested
        }

        // User rule and base-tier changes have no debounce. Return the new
        // target immediately so the closed loop can replace the current cosa
        // value on this cycle.
        if (controlSettingsChanged) {
            forceRefreshRequested = true
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            appliedTier = tier
            pausedFromGear = null
            return requested
        }

        if (appliedGear != null && requested == current) {
            appliedTier = tier
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            return requested
        }

        // Temperature movement, including a transition between large tiers and
        // recovery from a temperature pause, changes the concrete gear only after
        // it remains stable for the configured confirmation time.
        if (temperatureCandidateGear != requested) {
            temperatureCandidateGear = requested
            temperatureCandidateSince = now
            return null
        }
        val confirmSeconds = if (requested > current) {
            settings.temperatureDownshiftSeconds
        } else {
            settings.temperatureUpshiftSeconds
        }
        return if (now - temperatureCandidateSince >= confirmSeconds * 1_000L) {
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            appliedTier = tier
            pausedFromGear = null
            requested
        } else null
    }
    private fun initializeFromDevice() {
        if (cosaInitialized) return
        currentCosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull() ?: 0
        cosaInitialized = true
    }

    private fun restoreBoth() {
        runCatching { restoreOffset() }
    }

    private fun restoreOffset() {
        if (!cosaInitialized) initializeFromDevice()
        if (currentCosa != 0) writeCosa(0)
        lastUserRequestedGear = null
        appliedGear = null
        appliedTier = null
        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        pausedFromGear = null
        controlSettingsSnapshot = null
        forceRefreshRequested = true
    }

    private fun writeCosa(value: Int, force: Boolean = false): Boolean {
        if (!force && currentCosa == value) return false
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
        forceRefreshRequested = false
        return true
    }

    private fun readPolicy(): ThermalPolicy {
        val raw = shizuku.readThermalPolicy()
        val gear = Regex("gearVal:\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val charge = Regex("chargeVal:\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val speed = Regex("speedChargeAdd:\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val category = Regex("categoryName:\\s*([^\\n]+)").find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return ThermalPolicy(raw, category, gear, charge, speed)
    }

    private companion object {
        const val TEMPERATURE_PAUSE_CANDIDATE = -1
        const val COSA_MIN = -9
        const val COSA_MAX = 9
    }

    private data class ControlSettings(
        val baseTier: BaseTier,
        val customRulesEnabled: Boolean,
        val customRules: ControlRules,
    )
}
