package com.oppower.shizuku

import android.content.Context
import android.os.RemoteException
import androidx.annotation.Keep
import com.oppower.battery.PrivilegedBatteryReading
import com.oppower.battery.PrivilegedBatterySource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class PrivilegedCommandService @Keep constructor() : IPrivilegedCommandService.Stub() {
    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    @Throws(RemoteException::class)
    override fun readBatteryFallback(): IntArray {
        return runCatching {
            val reading = readDirectBatteryFallback()
                ?: readForcedBatteryCurrent()
                ?: run {
                    val dump = runShell(BATTERY_DUMP_COMMAND, BATTERY_DUMP_TIMEOUT_MILLIS)
                    parsePrivilegedBatteryDump(dump)?.copy(source = PrivilegedBatterySource.DUMPSYS)
                }
                ?: error("Battery current is unavailable")
            intArrayOf(reading.currentMa, reading.voltageMv, reading.source.protocolCode)
        }.getOrElse { error ->
            throw RemoteException("OPPower battery fallback failed: ${error.message.orEmpty()}")
        }
    }

    /**
     * Some ColorOS builds expose power-supply sysfs values, while newer builds
     * hide them from the shell domain. Try sysfs first, then use BatteryService's
     * force-refresh command before falling back to the cached `dumpsys` dump.
     */
    private fun readDirectBatteryFallback(): PrivilegedBatteryReading? {
        val supplies = listOf("battery", "main")
        for (supply in supplies) {
            val base = "/sys/class/power_supply/$supply"
            val rawCurrent = readFirstLong(
                "$base/current_now",
                "$base/batt_current",
                "$base/current_avg",
            ) ?: continue
            if (rawCurrent == 0L || rawCurrent in INVALID_LONG_MARKERS) continue

            val currentMa = normalizePrivilegedCurrentToMa(rawCurrent)
            if (kotlin.math.abs(currentMa) > MAX_PRIVILEGED_CURRENT_MA) continue

            val rawVoltage = readFirstLong("$base/voltage_now", "$base/batt_vol")
                ?: readFirstLong(BATTERY_VOLTAGE_PATH)
            return PrivilegedBatteryReading(
                currentMa = currentMa.roundToInt(),
                voltageMv = normalizePrivilegedVoltageToMv(rawVoltage),
                source = if (supply == "main") {
                    PrivilegedBatterySource.MAIN_SYSFS
                } else {
                    PrivilegedBatterySource.BATTERY_SYSFS
                },
            )
        }
        return null
    }

    private fun readForcedBatteryCurrent(): PrivilegedBatteryReading? {
        val output = runCatching {
            runShell(FORCED_CURRENT_COMMAND, BATTERY_DUMP_TIMEOUT_MILLIS)
        }.getOrNull() ?: return null
        val currentMa = parseForcedBatteryCurrent(output) ?: return null
        if (kotlin.math.abs(currentMa) > MAX_PRIVILEGED_CURRENT_MA) return null
        return PrivilegedBatteryReading(
            currentMa = currentMa.roundToInt(),
            voltageMv = 0,
            source = PrivilegedBatterySource.BATTERY_SERVICE_FORCED,
        )
    }

    private fun readFirstLong(vararg paths: String): Long? {
        for (path in paths) {
            val value = runCatching {
                File(path).takeIf { it.isFile && it.canRead() }?.readText()?.trim()?.toLongOrNull()
            }.getOrNull()
            if (value != null && value !in INVALID_LONG_MARKERS) return value
        }
        return null
    }

    @Throws(RemoteException::class)
    override fun execute(command: String): String {
        if (!AllowedCommands.matches(command)) {
            throw SecurityException("OPPower command is not allowlisted")
        }
        return runCatching { runShell(command) }
            .getOrElse { error ->
                // AIDL exceptions must be a small, serializable message. The cause stays
                // inside the user-service process.
                throw RemoteException("OPPower command failed: ${error.message.orEmpty()}")
            }
    }

    private fun runShell(
        command: String,
        timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
    ): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Command timed out")
        }
        val output = process.inputStream.use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toString(Charsets.UTF_8.name())
        }.trim()
        if (process.exitValue() != 0) error(output.ifBlank { "Command failed" })
        return output
    }
    private companion object {
        const val BATTERY_DUMP_COMMAND = "dumpsys battery"
        const val FORCED_CURRENT_COMMAND = "cmd battery get -f current_now"
        const val BATTERY_DUMP_TIMEOUT_MILLIS = 1_500L
        const val COMMAND_TIMEOUT_MILLIS = 8_000L
        const val BATTERY_VOLTAGE_PATH = "/sys/class/power_supply/battery/voltage_now"
        const val MAX_PRIVILEGED_CURRENT_MA = 20_000f
    }
}

internal fun parseForcedBatteryCurrent(output: String): Float? =
    output.lineSequence()
        .map(String::trim)
        .lastOrNull { it.matches(Regex("-?\\d+")) }
        ?.toLongOrNull()
        ?.let(::normalizePrivilegedCurrentToMa)

internal fun normalizePrivilegedCurrentToMa(raw: Long): Float =
    if (kotlin.math.abs(raw) > 20_000L) raw / 1_000f else raw.toFloat()

internal fun normalizePrivilegedVoltageToMv(raw: Long?): Int {
    if (raw == null || raw <= 0L) return 0
    return when {
        raw > 100_000L -> (raw / 1_000L).toInt()
        raw > 1_000L -> raw.toInt()
        else -> (raw * 1_000L).toInt()
    }.takeIf { it in 3_000..5_500 } ?: 0
}

private val INVALID_LONG_MARKERS = setOf(
    Int.MIN_VALUE.toLong(),
    Int.MAX_VALUE.toLong(),
    Long.MIN_VALUE,
    Long.MAX_VALUE,
)
internal fun parsePrivilegedBatteryDump(output: String): PrivilegedBatteryReading? {
    val currentMa = BATTERY_CURRENT_PATTERN.find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: return null
    val voltageMv = BATTERY_VOLTAGE_PATTERN.find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: 0
    return PrivilegedBatteryReading(currentMa, voltageMv)
}

private val BATTERY_CURRENT_PATTERN = Regex(
    pattern = """(?im)^\s*Battery current\s*:\s*(-?\d+)\s*$""",
)
private val BATTERY_VOLTAGE_PATTERN = Regex(
    pattern = """(?im)^\s*voltage\s*:\s*(\d+)\s*$""",
)

internal object AllowedCommands {
    private val exact = setOf(
        "settings get global cosa_ctc_value",
        "settings get system oplus_camera_3rd_activity",
        "settings get system oplus_float_window_show",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 2",
    )
    private val putCosa = Regex("settings put global cosa_ctc_value (?:-10|-?\\d)")
    private val putThermalRefreshAlias = Regex(
        "settings put system (?:oplus_camera_3rd_activity|oplus_float_window_show) (?:0|1|00|01)",
    )

    fun matches(command: String): Boolean =
        command in exact ||
            putCosa.matches(command) ||
            putThermalRefreshAlias.matches(command)
}
