package com.oppower.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedCommandsTest {
    @Test
    fun acceptsOnlyExpectedSettingsCommands() {
        assertTrue(AllowedCommands.matches("settings get global cosa_ctc_value"))
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value -9"))
        assertTrue(AllowedCommands.matches("settings get system oplus_camera_3rd_activity"))
        assertTrue(AllowedCommands.matches("service call guardelfthermalcontrol 19"))
        assertTrue(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 01"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus"))
        assertTrue(
            AllowedCommands.matches(
                "am broadcast -a oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM " +
                    "-p com.oplus.battery --es name set_charge --ei level 0 " +
                    "--ei speedChargeAdd 0 --es foregroundPkgName com.oppower",
            ),
        )
    }

    @Test
    fun thermalRefreshAliasKeepsTheSameIntegerValue() {
        mapOf("0" to "00", "00" to "0", "1" to "01", "01" to "1").forEach { (source, alias) ->
            assertEquals(source.toInt(), alias.toInt())
            assertEquals(alias, thermalRefreshAlias(source))
        }
        assertNull(thermalRefreshAlias("2"))
        assertNull(thermalRefreshAlias("null"))
    }

    @Test
    fun rejectsShellInjectionAndUnknownSettings() {
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put global another_value 1"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 2"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 1"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 2"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00; reboot"))
        assertFalse(AllowedCommands.matches("settings put system customize_charge_switch_state 1"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 256"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0; reboot"))
        assertFalse(
            AllowedCommands.matches(
                "am broadcast -a oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM " +
                    "-p com.oplus.battery --es name set_charge --ei level 1 " +
                    "--ei speedChargeAdd 0 --es foregroundPkgName com.oppower",
            ),
        )
        assertFalse(AllowedCommands.matches("rm -rf /data/local/tmp/oppower"))
    }
}