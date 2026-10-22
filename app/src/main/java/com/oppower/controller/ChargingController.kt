package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.BaseTier
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
import com.oppower.model.ControlTier
import com.oppower.model.RuntimeSettings
import com.oppower.model.RuleEngine
import com.oppower.model.ThermalPolicy
import com.oppower.shizuku.ShizukuGateway

class ChargingController(
    private val shizuku: ShizukuGateway,
    private val rules: RuleEngine = RuleEngine(),
) {
    private var sessionSocLocked = false
    private var lastAcForSession: Boolean? = null
    private var lastFull = false
    private var currentCosa = 0
    private var cosaInitialized = false
    private var appliedGear: Int? = null
    private var pendingGear: Int? = null
    private var pendingSince = 0L
    private var lastAppliedAt = 0L
    private var lastConfirmedGear: Int? = null
    private var lastUserRequestedGear: Int? = null
    private var confirmedBaseGear: Int? = null
    private var lastObservedGear: Int? = null
    private var deviationSince = 0L
    private var rapidSwitchWindowUntil = 0L
    private var appliedTier: ControlTier? = null
    private var temperatureCandidateGear: Int? = null
    private var temperatureCandidateSince = 0L
    private var lastBaseTier: BaseTier? = null
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
                lastBaseTier = settings.baseTier
                return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
            }

            val target = confirmGear(requested, settings, decision.tier)
            if (target == null) {
                val debouncing = pendingGear == requested &&
                    System.currentTimeMillis() - pendingSince < SETTINGS_DEBOUNCE_MS
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    if (debouncing) {
                        "等待最新档位 · 停止操作 1.5 秒后校准"
                    } else {
                        "等待 ${if (requested > (appliedGear ?: requested)) settings.temperatureDownshiftSeconds else settings.temperatureUpshiftSeconds} 秒确认 · ${decision.reason}"
                    },
                    desiredGear = requested,
                    appliedCosa = currentCosa,
                    observedGear = lastConfirmedGear,
                )
            }

            val policy = readPolicy()
            if (!policy.isParsed) {
                if (appliedGear == requested || (target == 1 && currentCosa != 0)) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "目标功率档正在生效 · 系统状态暂未返回",
                        desiredGear = target,
                        appliedCosa = currentCosa,
                        observedGear = null,
                    )
                }
                return ControllerStatus(
                    ControllerPhase.ERROR,
                    "系统充电状态暂未返回 · 保持当前设置并继续跟踪",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                    observedGear = null,
                )
            }
            val observedGear = policy.gear
                ?: return ControllerStatus(
                    ControllerPhase.ERROR,
                    "系统充电状态无法确认，请稍后重试",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                )
            // Keep the last confirmed base and current cosa as the reference. A
            // transient system readback change is not allowed to redefine either.
            val baseGear = confirmedBaseGear
                ?: (observedGear - currentCosa).also { confirmedBaseGear = it }
            val desiredCosa = target - baseGear
            if (desiredCosa !in -9..9) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "当前充电策略暂未满足所选档位 · 保持现有设置并继续跟踪",
                    desiredGear = target,
                    appliedCosa = currentCosa,
                    observedGear = observedGear,
                )
            }

            val now = System.currentTimeMillis()
            if (appliedGear == null) {
                // First activation uses the current base gear and directly writes
                // the calculated offset. There is deliberately no intermediate 0.
                val wrote = currentCosa != desiredCosa
                if (wrote) writeCosa(desiredCosa)
                appliedGear = target
                lastAppliedAt = now
                lastUserRequestedGear = target
                return verifiedStatus(
                    target = target,
                    observedGear = observedGear,
                    reason = decision.reason,
                    wrote = wrote,
                )
            }

            val mismatched = observedGear != target
            // A persistent mismatch can mean the foreground thermal base changed.
            // A transient mismatch means the system has not finished refreshing.
            // Either way, preserve the last accepted cosa until a new value has
            // been calculated and validated; never emit an intermediate zero.
            val needsBaseRecheck = mismatched
            if (needsBaseRecheck) {
                if (lastObservedGear != observedGear) {
                    lastObservedGear = observedGear
                    deviationSince = now
                }
                if (now - deviationSince < DEVIATION_CONFIRM_MS) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "系统正在刷新档位 · 保持当前设置",
                        desiredGear = target,
                        appliedCosa = currentCosa,
                        observedGear = observedGear,
                    )
                }
                // Replace the old offset only after the mismatch persists. Never
                // clear cosa first: a direct replacement avoids a temporary default
                // power step and keeps the output smooth.
                confirmedBaseGear = observedGear - currentCosa
                val replacement = target - confirmedBaseGear!!
                if (replacement !in -9..9) {
                    return ControllerStatus(
                        ControllerPhase.ACTIVE,
                        "当前充电策略暂未满足所选档位 · 保持现有设置并继续跟踪",
                        desiredGear = target,
                        appliedCosa = currentCosa,
                        observedGear = observedGear,
                    )
                }
                val wrote = replacement != currentCosa
                if (wrote) writeCosa(replacement)
                lastObservedGear = null
                deviationSince = 0L
                appliedGear = target
                lastAppliedAt = now
                return verifiedStatus(
                    target = target,
                    observedGear = observedGear,
                    reason = "基础功率档已变化，已直接替换设置",
                    wrote = wrote,
                )
            }

            val needsWrite = currentCosa != desiredCosa
            if (needsWrite) {
                writeCosa(desiredCosa)
            }
            lastObservedGear = null
            deviationSince = 0L
            appliedGear = target
            lastAppliedAt = System.currentTimeMillis()
            return verifiedStatus(
                target = target,
                observedGear = observedGear,
                reason = decision.reason,
                wrote = needsWrite,
            )
        }.getOrElse { error ->
            ControllerStatus(
                ControllerPhase.ERROR,
                "控制暂时中断，已保持当前设置：${error.message ?: error.javaClass.simpleName}",
                desiredGear = appliedGear,
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
        pendingGear = null
        lastAppliedAt = 0L
        lastUserRequestedGear = null
        confirmedBaseGear = null
        lastObservedGear = null
        deviationSince = 0L
        rapidSwitchWindowUntil = 0L
        appliedTier = null
        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        lastBaseTier = null
        pausedFromGear = null
    }

    private fun confirmGear(
        requested: Int,
        settings: RuntimeSettings,
        tier: ControlTier,
    ): Int? {
        val current = appliedGear ?: pausedFromGear
        val now = System.currentTimeMillis()
        val previousBaseTier = lastBaseTier
        val baseTierChanged = previousBaseTier != null && previousBaseTier != settings.baseTier
        if (lastBaseTier == null) lastBaseTier = settings.baseTier
        if (current == null) {
            pendingGear = null
            lastUserRequestedGear = requested
            rapidSwitchWindowUntil = now + SETTINGS_DEBOUNCE_MS
            appliedTier = tier
            return requested
        }

        // Finish a pending user tier change after the trailing window. Each new
        // tap restarts this window, so only the final selected tier is written.
        if (pendingGear != null) {
            if (pendingGear != requested) {
                pendingGear = requested
                pendingSince = now
                return null
            }
            if (now - pendingSince < SETTINGS_DEBOUNCE_MS) return null
            pendingGear = null
            rapidSwitchWindowUntil = now + SETTINGS_DEBOUNCE_MS
            appliedTier = tier
            pausedFromGear = null
            return requested
        }

        if (baseTierChanged) {
            lastBaseTier = settings.baseTier
            temperatureCandidateGear = null
            temperatureCandidateSince = 0L
            lastUserRequestedGear = requested
            appliedTier = tier
            if (now >= rapidSwitchWindowUntil) {
                rapidSwitchWindowUntil = now + SETTINGS_DEBOUNCE_MS
                pausedFromGear = null
                return requested
            }
            pendingGear = requested
            pendingSince = now
            return null
        }

        if (appliedGear != null && requested == current) {
            pendingGear = null
            lastUserRequestedGear = requested
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

    private fun verifiedStatus(
        target: Int,
        observedGear: Int,
        reason: String,
        wrote: Boolean,
    ): ControllerStatus {
        val verification = runCatching<ThermalPolicy?> {
            val after = readPolicy()
            if (!after.isParsed) {
                // This firmware can return an empty policy for gear 1 even while
                // the calculated cosa value is active and measured power is high.
                if (target == 1) null else error("系统充电状态为空")
            } else {
                require(after.gear == target) { "目标功率档正在切换（当前 ${after.gear}）" }
                require((after.chargeVal ?: -1) >= 0) { "系统充电参数无效" }
                require((after.speedChargeAdd ?: -1) >= 0) { "系统快充参数无效" }
                after
            }
        }.getOrElse { error ->
            return ControllerStatus(
                ControllerPhase.ACTIVE,
                "档位正在刷新，软件会继续平稳跟踪：${error.message}",
                desiredGear = target,
                appliedCosa = currentCosa,
                observedGear = observedGear,
            )
        }
        return ControllerStatus(
            phase = ControllerPhase.ACTIVE,
            message = buildString {
                append(reason)
                append(" · 目标功率档已生效")
                if (wrote) append(" · 已直接替换设置")
                if (verification == null) append(" · 系统状态暂未返回")
            },
            desiredGear = target,
            appliedCosa = currentCosa,
            observedGear = verification?.gear ?: observedGear,
            verified = verification != null,
        )
            .also { lastConfirmedGear = it.observedGear }
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
        pendingGear = null
        confirmedBaseGear = null
        lastObservedGear = null
        deviationSince = 0L
        rapidSwitchWindowUntil = 0L
        appliedTier = null
        temperatureCandidateGear = null
        temperatureCandidateSince = 0L
        pausedFromGear = null
    }

    private fun writeCosa(value: Int) {
        if (currentCosa == value) return
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
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
        const val SETTINGS_DEBOUNCE_MS = 1_500L
        const val DEVIATION_CONFIRM_MS = 1_000L
        const val TEMPERATURE_PAUSE_CANDIDATE = -1
    }
}
