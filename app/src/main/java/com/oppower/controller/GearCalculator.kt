package com.oppower.controller

import com.oppower.model.ThermalPolicy
import com.oppower.model.ThermalStatus

data class ResolvedGear(
    val gear: Int?,
    val baseGear: Int?,
    val calculatedGear: Int?,
    val cosa: Int?,
    val chargeVal: Int?,
    val source: String,
)

/**
 * OPPower uses its own calculated gear:
 *
 *   calculated gear = thermal base + cosa_ctc_value
 *
 * The cached policy.gear value is intentionally ignored. It is not used to
 * calculate cosa, calibrate a correction, display the current gear or verify
 * whether the target was reached.
 */
object GearCalculator {
    const val COSA_MIN = -10
    const val COSA_MAX = 9
    fun parseThermalStatus(raw: String): ThermalStatus {
        val levels = Regex("Thermal Levels = \\[([^]]*)]")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()
        val status = Regex("Thermal status = (-?\\d+)")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val temperature = Regex("Thermal Temperature = (-?\\d+(?:\\.\\d+)?)")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
        return ThermalStatus(
            raw = raw,
            status = status,
            temperatureC = temperature,
            baseGear = levels.firstOrNull(),
            levels = levels,
        )
    }

    fun cosaForTarget(targetGear: Int, baseGear: Int): Int? {
        val required = targetGear - baseGear
        return required.takeIf { it in COSA_MIN..COSA_MAX }
    }

    fun clampCosaForTarget(targetGear: Int, baseGear: Int): Int =
        (targetGear - baseGear).coerceIn(COSA_MIN, COSA_MAX)

    fun resolve(
        policy: ThermalPolicy,
        thermalStatus: ThermalStatus,
        cosa: Int?,
    ): ResolvedGear {
        val calculatedGear = if (thermalStatus.baseGear != null && cosa != null) {
            thermalStatus.baseGear + cosa
        } else {
            null
        }
        val source = when {
            thermalStatus.baseGear == null -> "系统 Thermal Levels 暂未返回"
            cosa == null -> "cosa_ctc_value 暂未返回"
            else -> "系统 Thermal Levels + cosa_ctc_value（忽略 policy.gear 缓存）"
        }
        return ResolvedGear(
            gear = calculatedGear,
            baseGear = thermalStatus.baseGear,
            calculatedGear = calculatedGear,
            cosa = cosa,
            chargeVal = policy.chargeVal,
            source = source,
        )
    }
}
