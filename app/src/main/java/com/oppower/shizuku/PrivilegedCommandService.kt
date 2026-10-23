package com.oppower.shizuku

import android.content.Context
import android.os.RemoteException
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

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

    private fun runShell(command: String): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Command timed out")
        }
        val output = process.inputStream.use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toString(Charsets.UTF_8.name())
        }.trim()
        if (process.exitValue() != 0) error(output.ifBlank { "Command failed" })
        return output
    }
}

internal object AllowedCommands {
    private val exact = setOf(
        "settings get global cosa_ctc_value",
        "settings get system oplus_camera_3rd_activity",
        "service call guardelfthermalcontrol 19",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy",
        "dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus",
    )
    private val putCosa = Regex("settings put global cosa_ctc_value -?\\d{1,3}")
    private val putCameraActivityAlias =
        Regex("settings put system oplus_camera_3rd_activity (?:0|1|00|01)")
    private val setSmartChgMode =
        Regex("service call guardelfthermalcontrol 20 s16 (?:[0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])")
    private val setNativeChargeLevel = Regex(
        """dumpsys activity service com\.oplus\.battery/\.OplusBatteryService setChargingLevel (?:0|2|3|5|7)""",
    )
    fun matches(command: String): Boolean =
        command in exact ||
            putCosa.matches(command) ||
            putCameraActivityAlias.matches(command) ||
            setSmartChgMode.matches(command) ||
            setNativeChargeLevel.matches(command)
}
