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
import com.oppower.model.ThermalStatus

interface ChargingGateway {
    val isAvailable: Boolean
    val hasPermission: Boolean

    fun readGlobal(name: String): String
    fun putGlobal(name: String, value: Int)
    fun readSystemSetting(name: String): String
    fun putSystemSetting(name: String, value: Int)
    fun readThermalPolicy(): String
    fun readThermalStatus(): String
    fun requestThermalPolicyRefresh(): Boolean
}

class ChargingController(
    private val shizuku: ChargingGateway,
    private val rules: RuleEngine = RuleEngine(),
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var sessionSocLocked = false
    private var lastAcForSession: Boolean? = null
    private var lastFull = false
    private var currentCosa = 0
    private var cosaInitialized = false
    private var originalSmartChargeSwitch: Int? = null
    private var lastAppliedGear: Int? = null
    private var lastObservedGear: Int? = null
    private var appliedTier: ControlTier? = null
    private var temperatureCandidateGear: Int? = null
    private var temperatureCandidateSince = 0L
    private var controlSettingsSnapshot: ControlSettings? = null
    private var pausedFromGear: Int? = null

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
                if (appliedTier == ControlTier.PAUSED && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val current = lastAppliedGear ?: pausedFromGear
                if (lastAppliedGear == null && pausedFromGear != null) {
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                if (current == null) {
                    restoreBoth()
                    return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
                }
                val now = nowMillis()
                val holdSeconds = settings.temperatureDownshiftSeconds.coerceAtLeast(1)
                if (temperatureCandidateGear != TEMPERATURE_PAUSE_CANDIDATE) {
                    temperatureCandidateGear = TEMPERATURE_PAUSE_CANDIDATE
                    temperatureCandidateSince = now
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 持续 $holdSeconds 秒后暂停",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastObservedGear,
                    )
                }
                if (now - temperatureCandidateSince < holdSeconds * 1_000L) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "温度达到暂停阈值 · 等待确认（$holdSeconds 秒）",
                        desiredGear = current,
                        appliedCosa = currentCosa,
                        observedGear = lastObservedGear,
                    )
                }
                restoreBoth()
                pausedFromGear = current
                appliedTier = ControlTier.PAUSED
                return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
            }

            val target = confirmGear(requested!!, settings, decision.tier)
            if (target == null) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "等待 ${if (requested!! > (lastAppliedGear ?: requested)) settings.temperatureDownshiftSeconds else settings.temperatureUpshiftSeconds} 秒确认 · ${decision.reason}",
                    desiredGear = requested,
                    appliedCosa = currentCosa,
                    observedGear = lastObservedGear,
                )
            }

            shizuku.readGlobal("cosa_ctc_value").toIntOrNull()?.let { currentCosa = it }
            val thermalStatus = readThermalStatus()
            val baseGear = thermalStatus.baseGear
                ?: throw IllegalStateException("系统热控基础档位暂未返回")
            val policy = runCatching { readPolicy() }.getOrDefault(ThermalPolicy())
            val chargeVal = policy.chargeVal
            val resolvedBeforeWrite = GearCalculator.resolve(
                policy = policy,
                thermalStatus = thermalStatus,
                cosa = currentCosa,
            )

            // The target is calculated only from the live thermal base and cosa.
            // policy.gear is deliberately ignored, including stale cached values.
            val desiredCosa = GearCalculator.cosaForTarget(target, baseGear)
                ?: throw IllegalStateException(
                    "目标 gear $target 不可达：基础档=$baseGear，所需 cosa=" +
                        (target - baseGear) +
                        "，安全范围 ${GearCalculator.COSA_MIN}..${GearCalculator.COSA_MAX}",
                )

            ensureSmartChargeSwitchDisabled()

            val targetChanged = lastAppliedGear != target
            val needsApply = currentCosa != desiredCosa || targetChanged
            val wrote = if (needsApply && currentCosa != desiredCosa) writeCosa(desiredCosa) else false
            val refreshRequested = if (needsApply) requestThermalRefresh() else false
            val calculatedGear = baseGear + currentCosa
            val verified = calculatedGear == target

            lastAppliedGear = target
            lastObservedGear = calculatedGear

            return ControllerStatus(
                ControllerPhase.ACTIVE,
                buildString {
                    append(
                        when {
                            wrote && refreshRequested ->
                                "目标 cosa 已写入，计算档位已达到 $target，已触发一次系统重算"
                            wrote ->
                                "目标 cosa 已写入，计算档位=$target，但系统重算触发失败"
                            refreshRequested -> "基础档变化，已按公式重算 cosa 并触发系统重算"
                            verified -> "计算档位已达到目标 $target"
                            else -> "已按基础档和 cosa 更新计算档位"
                        },
                    )
                    append(" · 计算档位=")
                    append(calculatedGear)
                    append(" · 基础档=")
                    append(baseGear)
                    append(" · cosa=")
                    append(currentCosa)
                    append(" · 目标=")
                    append(target)

                    chargeVal?.let { append(" · chargeVal=$it") }
                },
                desiredGear = target,
                appliedCosa = currentCosa,
                observedGear = calculatedGear,
                baseGear = baseGear,
                calculatedGear = calculatedGear,
                chargeVal = chargeVal,
                gearSource = resolvedBeforeWrite.source,
                verified = verified,
            )
        }.getOrElse { error ->
            ControllerStatus(
                ControllerPhase.ERROR,
                "控制暂时中断，已保持当前设置：${error.message ?: error.javaClass.simpleName}",
                desiredGear = lastAppliedGear,
                appliedCosa = currentCosa,
                observedGear = lastObservedGear,
            )
        }
    }

    @Synchronized
    fun readCurrentGear(): Int? {
        if (!shizuku.isAvailable || !shizuku.hasPermission) {
            throw IllegalStateException("请先完成 Shizuku 授权")
        }
        val cosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        return GearCalculator.resolve(
            policy = ThermalPolicy(),
            thermalStatus = readThermalStatus(),
            cosa = cosa,
        ).gear
    }

    @Synchronized
    fun readResolvedGear(): ResolvedGear {
        if (!shizuku.isAvailable || !shizuku.hasPermission) {
            throw IllegalStateException("请先完成 Shizuku 授权")
        }
        val cosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        val chargeVal = runCatching { readPolicy().chargeVal }.getOrNull()
        return GearCalculator.resolve(
            policy = ThermalPolicy(chargeVal = chargeVal),
            thermalStatus = readThermalStatus(),
            cosa = cosa,
        )
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
        lastAppliedGear = null
        lastObservedGear = null

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
        val current = lastAppliedGear ?: pausedFromGear
        val now = nowMillis()
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

        // User rule and base-tier changes are applied on this cycle. The closed
        // loop writes the dynamically calculated cosa exactly once and never
        // clears to zero while moving between targets.
        if (controlSettingsChanged) {
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            appliedTier = tier
            pausedFromGear = null
            return requested
        }

        if (lastAppliedGear != null && requested == current) {
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
        runCatching { restoreSmartChargeSwitch() }
        runCatching { restoreOffset() }
    }

    private fun ensureSmartChargeSwitchDisabled() {
        if (originalSmartChargeSwitch == null) {
            originalSmartChargeSwitch = runCatching {
                shizuku.readSystemSetting(SMART_CHARGE_SWITCH).toIntOrNull()
            }.getOrNull() ?: return
        }
        val current = runCatching {
            shizuku.readSystemSetting(SMART_CHARGE_SWITCH).toIntOrNull()
        }.getOrNull() ?: return
        if (current == 0) return
        runCatching {
            shizuku.putSystemSetting(SMART_CHARGE_SWITCH, 0)
            val readBack = shizuku.readSystemSetting(SMART_CHARGE_SWITCH).toIntOrNull()
            check(readBack == 0) { "smart_charge_switch_state 回读不一致" }
        }
    }

    private fun restoreSmartChargeSwitch() {
        val original = originalSmartChargeSwitch ?: return
        val current = runCatching {
            shizuku.readSystemSetting(SMART_CHARGE_SWITCH).toIntOrNull()
        }.getOrNull() ?: return
        if (current == original) {
            originalSmartChargeSwitch = null
            return
        }
        val restored = runCatching {
            shizuku.putSystemSetting(SMART_CHARGE_SWITCH, original)
            shizuku.readSystemSetting(SMART_CHARGE_SWITCH).toIntOrNull() == original
        }.getOrDefault(false)
        if (restored) originalSmartChargeSwitch = null
    }

    private fun restoreOffset() {
        if (!cosaInitialized) initializeFromDevice()
        val wrote = if (currentCosa != 0) writeCosa(0) else false
        if (wrote) requestThermalRefresh()
        lastAppliedGear = null
        lastObservedGear = null

        appliedTier = null

        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        pausedFromGear = null
        controlSettingsSnapshot = null
    }

    private fun writeCosa(value: Int): Boolean {
        if (currentCosa == value) return false
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
        return true
    }

    private fun requestThermalRefresh(): Boolean =
        runCatching { shizuku.requestThermalPolicyRefresh() }.getOrDefault(false)

    private fun readPolicy(): ThermalPolicy {
        val raw = shizuku.readThermalPolicy()
        val charge = Regex("chargeVal:\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val speed = Regex("speedChargeAdd:\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val category = Regex("categoryName:\\s*([^\\s]+)").find(raw)?.groupValues?.getOrNull(1).orEmpty()
        return ThermalPolicy(raw = raw, category = category, chargeVal = charge, speedChargeAdd = speed)
    }

    private fun readThermalStatus(): ThermalStatus =
        GearCalculator.parseThermalStatus(shizuku.readThermalStatus())

    private companion object {
        const val TEMPERATURE_PAUSE_CANDIDATE = -1
        const val SMART_CHARGE_SWITCH = "smart_charge_switch_state"
    }

    private data class ControlSettings(
        val baseTier: BaseTier,
        val customRulesEnabled: Boolean,
        val customRules: ControlRules,
    )
}
