package com.oppower.controller

import com.oppower.model.BatterySnapshot
import com.oppower.model.ControllerPhase
import com.oppower.model.ControllerStatus
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
    private var singleSpeedState: Int? = null
    private var appliedGear: Int? = null
    private var pendingGear: Int? = null
    private var pendingSince = 0L
    private var lastAppliedAt = 0L

    @Synchronized
    fun evaluate(settings: RuntimeSettings, battery: BatterySnapshot): ControllerStatus {
        if (!settings.masterEnabled) {
            restoreBoth()
            lastAcForSession = battery.acOnline
            return ControllerStatus(ControllerPhase.DISABLED, "总开关未开启")
        }

        if (!shizuku.isAvailable) {
            return ControllerStatus(ControllerPhase.ERROR, "Shizuku 未运行，未写入新策略")
        }
        if (!shizuku.hasPermission) {
            return ControllerStatus(ControllerPhase.ERROR, "Shizuku 未授权，未写入新策略")
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
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "电量 100% / FULL：已恢复 cosa=0 并关闭单速")
            }
            if (lastFull) {
                restoreBoth()
                return ControllerStatus(ControllerPhase.FULL_PAUSED, "本次插电已充满，拔电后重新开始")
            }

            if (battery.levelPercent >= settings.customRules.socStopPercent) {
                sessionSocLocked = true
            }
            if (sessionSocLocked) {
                restoreOffset(keepSingleSpeed = true)
                return ControllerStatus(
                    ControllerPhase.SOC_PAUSED,
                    "电量达到 ${settings.customRules.socStopPercent}%：本次插电暂停 gear 策略",
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
                restoreOffset(keepSingleSpeed = true)
                return ControllerStatus(ControllerPhase.TEMPERATURE_PAUSED, decision.reason)
            }

            val target = confirmGear(requested, settings)
            if (target == null) {
                return ControllerStatus(
                    ControllerPhase.ACTIVE,
                    "等待 ${if (requested > (appliedGear ?: requested)) settings.temperatureDownshiftSeconds else settings.temperatureUpshiftSeconds} 秒确认 · ${decision.reason}",
                    desiredGear = appliedGear,
                    appliedCosa = currentCosa,
                )
            }

            val policy = readPolicy()
            if (!policy.isParsed) {
                restoreOffset(keepSingleSpeed = true)
                return ControllerStatus(ControllerPhase.ERROR, "热控策略无法回读，已恢复默认")
            }
            val baseGear = (policy.gear ?: 0) - currentCosa
            val desiredCosa = target - baseGear
            if (desiredCosa !in -9..9) {
                restoreOffset(keepSingleSpeed = true)
                return ControllerStatus(ControllerPhase.ERROR, "目标档位超出安全范围，已恢复默认")
            }

            writeCosa(desiredCosa)
            setSingleSpeed(1)
            appliedGear = target
            lastAppliedAt = System.currentTimeMillis()
            val verification = runCatching<ThermalPolicy?> {
                val after = readPolicy()
                if (!after.isParsed) {
                    // gear 1 is known to return an empty policy on this firmware. The
                    // measured power remains the acceptance signal, while cosa itself
                    // was already checked for readback above.
                    if (target == 1) {
                        null
                    } else {
                        error("策略回读为空")
                    }
                } else {
                    require(after.gear == target) { "回读 gear ${after.gear}，目标 $target" }
                    require((after.chargeVal ?: -1) >= 0) { "chargeVal 无效" }
                    require((after.speedChargeAdd ?: -1) >= 0) { "speedChargeAdd 无效" }
                    after
                }
            }.getOrElse { error ->
                restoreOffset(keepSingleSpeed = true)
                return ControllerStatus(
                    ControllerPhase.ERROR,
                    "策略校验失败，已恢复默认：${error.message}",
                )
            }
            ControllerStatus(
                phase = ControllerPhase.ACTIVE,
                message = buildString {
                    append(decision.reason)
                    append(" · 实际 gear $target")
                    if (verification == null) append(" · 策略回读为空")
                },
                desiredGear = target,
                appliedCosa = desiredCosa,
            )
        }.getOrElse { error ->
            runCatching { restoreOffset(keepSingleSpeed = true) }
            ControllerStatus(ControllerPhase.ERROR, "控制失败：${error.message ?: error.javaClass.simpleName}")
        }
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
    }

    private fun confirmGear(requested: Int, settings: RuntimeSettings): Int? {
        val current = appliedGear
        if (current == null || requested == current) {
            pendingGear = null
            return requested
        }
        val now = System.currentTimeMillis()
        if (lastAppliedAt > 0 && now - lastAppliedAt < MINIMUM_CHANGE_INTERVAL_MS) return null
        if (pendingGear != requested) {
            pendingGear = requested
            pendingSince = now
            return null
        }
        val delaySeconds = if (requested > current) {
            settings.temperatureDownshiftSeconds
        } else {
            settings.temperatureUpshiftSeconds
        }
        return if (now - pendingSince >= delaySeconds * 1_000L) {
            pendingGear = null
            requested
        } else {
            null
        }
    }

    private fun initializeFromDevice() {
        if (cosaInitialized) return
        currentCosa = shizuku.readGlobal("cosa_ctc_value").toIntOrNull() ?: 0
        cosaInitialized = true
    }

    private fun restoreBoth() {
        runCatching { restoreOffset(keepSingleSpeed = false) }
    }

    private fun restoreOffset(keepSingleSpeed: Boolean) {
        if (!cosaInitialized) initializeFromDevice()
        if (currentCosa != 0) writeCosa(0)
        setSingleSpeed(if (keepSingleSpeed) 1 else 0)
        appliedGear = null
        pendingGear = null
    }

    private fun writeCosa(value: Int) {
        if (currentCosa == value) return
        shizuku.putGlobal("cosa_ctc_value", value)
        val readBack = shizuku.readGlobal("cosa_ctc_value").toIntOrNull()
        if (readBack != value) throw IllegalStateException("cosa 回读不一致")
        currentCosa = value
    }

    private fun setSingleSpeed(value: Int) {
        if (singleSpeedState == value) return
        shizuku.putSystem("single_speed_charge_state", value)
        singleSpeedState = value
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
        const val MINIMUM_CHANGE_INTERVAL_MS = 20_000L
    }
}
