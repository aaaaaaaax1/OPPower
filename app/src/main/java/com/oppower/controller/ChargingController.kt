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
    fun readThermalPolicy(): String
    fun readThermalStatus(): String
    fun requestThermalPolicyRefresh(): Boolean
    fun requestNativeChargeLevel(level: Int): Boolean = false
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
    private var lastAppliedGear: Int? = null
    private var lastObservedGear: Int? = null
    private var appliedTier: ControlTier? = null
    private var temperatureCandidateGear: Int? = null
    private var temperatureCandidateSince = 0L
    private var controlSettingsSnapshot: ControlSettings? = null
    private var pausedFromGear: Int? = null
    private var cachedDiagnosticPolicy: ThermalPolicy? = null
    private var cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
    private var levelZeroModeEntered = false
    private var nativeLevelZeroApplied = false
    private var nativeLevelRestorePending = false
    private var lastNativeLevelZeroAttemptAt: Long? = null

    private val levelZeroSafetyActive: Boolean
        get() = levelZeroModeEntered || nativeLevelZeroApplied || nativeLevelRestorePending

    @Synchronized
    fun evaluate(
        settings: RuntimeSettings,
        battery: BatterySnapshot,
        screenOn: Boolean = true,
    ): ControllerStatus {
        if (!settings.masterEnabled && !settings.nativeLevelZeroEnabled) {
            lastAcForSession = battery.acOnline
            val restoreFailure = runCatching { restoreEverything() }.exceptionOrNull()
            return if (restoreFailure == null) {
                ControllerStatus(ControllerPhase.DISABLED, "充电增强未开启")
            } else {
                ControllerStatus(
                    ControllerPhase.ERROR,
                    "关闭时恢复系统默认失败：${restoreFailure.message ?: restoreFailure.javaClass.simpleName}",
                    desiredGear = if (levelZeroSafetyActive) 0 else null,
                    levelZeroMode = levelZeroSafetyActive,
                )
            }
        }

        if (!shizuku.isAvailable) {
            return ControllerStatus(
                ControllerPhase.ERROR,
                "Shizuku 未运行，未写入新的充电设置",
                desiredGear = if (levelZeroSafetyActive) 0 else null,
                levelZeroMode = levelZeroSafetyActive,
            )
        }
        if (!shizuku.hasPermission) {
            return ControllerStatus(
                ControllerPhase.ERROR,
                "OPPower 尚未获得 Shizuku 授权",
                desiredGear = if (levelZeroSafetyActive) 0 else null,
                levelZeroMode = levelZeroSafetyActive,
            )
        }

        return runCatching {
            initializeFromDevice()
            if (lastAcForSession != null && lastAcForSession != battery.acOnline) {
                resetSession()
            }
            lastAcForSession = battery.acOnline

            if (!battery.acOnline) {
                restoreEverything()
                return ControllerStatus(ControllerPhase.WAITING_FOR_POWER, "等待接入电源")
            }

            val full = battery.levelPercent >= 100 || battery.status == 5
            if (full) {
                restoreEverything()
                lastFull = true
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "电量已充满：已恢复系统默认")
            }
            if (lastFull) {
                restoreEverything()
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "本次插电已充满，拔电后重新开始")
            }

            val pauseSoc = rules.socPauseThreshold(settings)
            if (battery.levelPercent >= pauseSoc) {
                sessionSocLocked = true
            }
            if (sessionSocLocked) {
                restoreEverything()
                return ControllerStatus(
                    ControllerPhase.SOC_PAUSED,
                    "电量达到 $pauseSoc%：本次插电已暂停充电增强",
                    sessionSocLocked = true,
                    levelZeroMode = settings.nativeLevelZeroEnabled,
                )
            }

            if (!screenOn && !settings.screenOffBoostEnabled) {
                restoreEverything()
                return ControllerStatus(
                    ControllerPhase.SCREEN_OFF_PAUSED,
                    "息屏暂停 · 开启“息屏也生效”后继续",
                    desiredGear = if (settings.nativeLevelZeroEnabled) 0 else lastAppliedGear,
                    appliedCosa = currentCosa,
                    levelZeroMode = settings.nativeLevelZeroEnabled,
                )
            }

            if (settings.nativeLevelZeroEnabled) {
                enterLevelZeroMode()
                return evaluateLevelZero()
            }
            exitLevelZeroMode()

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
                    restoreCosa()
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
                restoreCosa()
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
            val policy = runCatching { readPolicyForDiagnostics(nowMillis()) }.getOrDefault(ThermalPolicy())
            val chargeVal = policy.chargeVal
            val resolvedBeforeWrite = GearCalculator.resolve(
                policy = policy,
                thermalStatus = thermalStatus,
                cosa = currentCosa,
            )

            // The target is calculated only from the live thermal base and cosa.
            // policy.gear is deliberately ignored, including stale cached values.
            val requiredCosa = target - baseGear
            val targetReachable = requiredCosa in GearCalculator.COSA_MIN..GearCalculator.COSA_MAX
            val desiredCosa = GearCalculator.clampCosaForTarget(target, baseGear)

            val targetChanged = lastAppliedGear != target
            val needsApply = currentCosa != desiredCosa || targetChanged
            val wrote = if (needsApply && currentCosa != desiredCosa) writeCosa(desiredCosa) else false
            val refreshRequested = if (needsApply) requestThermalRefresh() else false
            val calculatedGear = baseGear + currentCosa
            val verified = targetReachable && calculatedGear == target

            lastAppliedGear = target
            lastObservedGear = calculatedGear

            return ControllerStatus(
                ControllerPhase.ACTIVE,
                buildString {
                    append(
                        when {
                            !targetReachable ->
                                "目标 gear $target 不可达：所需 cosa=$requiredCosa，已使用安全下界" +
                                    " ${GearCalculator.COSA_MIN}，当前计算档位=$calculatedGear"
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
                desiredGear = if (levelZeroSafetyActive) 0 else lastAppliedGear,
                levelZeroMode = levelZeroSafetyActive,
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
        val chargeVal = runCatching { readPolicyFresh().chargeVal }.getOrNull()
        return GearCalculator.resolve(
            policy = ThermalPolicy(chargeVal = chargeVal),
            thermalStatus = readThermalStatus(),
            cosa = cosa,
        )
    }

    @Synchronized
    fun restoreAll(forceNativeRestore: Boolean = false): ControllerStatus {
        val failure = runCatching {
            var restoreFailure: Throwable? = null
            try {
                restoreNativeLevel(force = forceNativeRestore || levelZeroSafetyActive)
            } catch (error: Throwable) {
                restoreFailure = error
            }
            restoreCosa()
            if (restoreFailure != null) throw restoreFailure
            levelZeroModeEntered = false
            nativeLevelZeroApplied = false
            nativeLevelRestorePending = false
            lastNativeLevelZeroAttemptAt = null
            resetSession()
        }.exceptionOrNull()
        return if (failure == null) {
            ControllerStatus(ControllerPhase.DISABLED, "已恢复系统默认")
        } else {
            ControllerStatus(
                ControllerPhase.ERROR,
                "恢复系统默认失败：${failure.message ?: failure.javaClass.simpleName}",
                levelZeroMode = levelZeroSafetyActive,
                appliedCosa = currentCosa,
            )
        }
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
        cachedDiagnosticPolicy = null
        cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
        lastNativeLevelZeroAttemptAt = null
    }

    private fun enterLevelZeroMode() {
        if (levelZeroModeEntered) return
        // Level 0 is an independent opt-in path. Clear any normal-mode cosa
        // offset first so the native charge-level write is the only active
        // charging control. This path is strict: a failed reset must surface
        // instead of silently combining both control mechanisms.
        restoreOffset()
        levelZeroModeEntered = true
        nativeLevelZeroApplied = false
        lastNativeLevelZeroAttemptAt = null
    }

    private fun exitLevelZeroMode() {
        if (!levelZeroSafetyActive) return
        restoreNativeLevel(force = true)
        levelZeroModeEntered = false
    }

    private fun evaluateLevelZero(): ControllerStatus {
        val now = nowMillis()
        val lastAttempt = lastNativeLevelZeroAttemptAt
        val attemptDue = lastAttempt == null || now - lastAttempt >= LEVEL_ZERO_POLL_MILLIS
        if (!attemptDue) {
            val remainingSeconds = ((LEVEL_ZERO_POLL_MILLIS - (now - lastAttempt!!) + 999L) / 1_000L)
                .coerceAtLeast(1L)
            return ControllerStatus(
                phase = if (nativeLevelZeroApplied) ControllerPhase.ACTIVE else ControllerPhase.ERROR,
                message = if (nativeLevelZeroApplied) {
                    "高功率保活中 · 温度过高仍会自动保护"
                } else {
                    "高功率保活暂未生效 · $remainingSeconds 秒后重试"
                },
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
                verified = nativeLevelZeroApplied,
            )
        }

        lastNativeLevelZeroAttemptAt = now
        val confirmed = runCatching { shizuku.requestNativeChargeLevel(NATIVE_LEVEL_ZERO) }
            .getOrDefault(false)
        nativeLevelZeroApplied = confirmed
        nativeLevelRestorePending = !confirmed
        return if (confirmed) {
            ControllerStatus(
                ControllerPhase.ACTIVE,
                "高功率保活中 · 温度过高仍会自动保护",
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
                verified = true,
            )
        } else {
            ControllerStatus(
                ControllerPhase.ERROR,
                "高功率保活暂未生效 · 3 秒后重试",
                desiredGear = 0,
                levelZeroMode = true,
                appliedCosa = currentCosa,
            )
        }
    }

    private fun restoreEverything() {
        var failure: Throwable? = null
        try {
            exitLevelZeroMode()
        } catch (error: Throwable) {
            failure = error
        }
        restoreCosa()
        if (failure != null) throw failure
    }

    private fun restoreNativeLevel(force: Boolean = false) {
        if (!force && !nativeLevelZeroApplied && !nativeLevelRestorePending) return
        val confirmed = try {
            shizuku.requestNativeChargeLevel(NATIVE_LEVEL_RESTORE)
        } catch (error: Throwable) {
            nativeLevelRestorePending = true
            throw error
        }
        if (!confirmed) {
            nativeLevelRestorePending = true
            throw IllegalStateException("系统未确认恢复普通充电")
        }
        nativeLevelZeroApplied = false
        nativeLevelRestorePending = false
        lastNativeLevelZeroAttemptAt = null
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

    private fun restoreCosa() {
        runCatching { restoreOffset() }
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
        cachedDiagnosticPolicy = null
        cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
    }

    private fun writeCosa(value: Int): Boolean {
        if (currentCosa == value) return false
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
        return true
    }

    private fun requestThermalRefresh(): Boolean {
        val requested = runCatching { shizuku.requestThermalPolicyRefresh() }.getOrDefault(false)
        if (requested) {
            cachedDiagnosticPolicy = null
            cachedDiagnosticPolicyAtMillis = Long.MIN_VALUE
        }
        return requested
    }

    /**
     * chargeVal and speedChargeAdd describe the native policy but do not drive
     * OPPower's gear calculation. Cache only this diagnostic query so the
     * one-second thermal-status and cosa checks remain untouched.
     */
    private fun readPolicyForDiagnostics(now: Long): ThermalPolicy {
        val cached = cachedDiagnosticPolicy
        val age = now - cachedDiagnosticPolicyAtMillis
        if (cached != null && age in 0L..POLICY_DIAGNOSTIC_CACHE_MILLIS) return cached
        return readPolicyFresh().also {
            cachedDiagnosticPolicy = it
            cachedDiagnosticPolicyAtMillis = now
        }
    }

    private fun readPolicyFresh(): ThermalPolicy {
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
        const val POLICY_DIAGNOSTIC_CACHE_MILLIS = 3_000L
        const val LEVEL_ZERO_POLL_MILLIS = 3_000L
        const val NATIVE_LEVEL_ZERO = 0
        const val NATIVE_LEVEL_RESTORE = 2
    }

    private data class ControlSettings(
        val baseTier: BaseTier,
        val customRulesEnabled: Boolean,
        val customRules: ControlRules,
    )
}
