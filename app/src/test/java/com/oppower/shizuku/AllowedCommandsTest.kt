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
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value -10"))
        assertTrue(AllowedCommands.matches("settings get system oplus_camera_3rd_activity"))
        assertTrue(AllowedCommands.matches("settings get system oplus_float_window_show"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00"))
        assertTrue(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 01"))
        assertTrue(AllowedCommands.matches("settings put system oplus_float_window_show 00"))
        assertTrue(AllowedCommands.matches("settings put system oplus_float_window_show 01"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getThermalStatus"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 2"))
    }

    @Test
    fun privilegedBatteryDumpUsesCurrentAndVoltageFromOneSample() {
        val dump = """
            Current OPLUS Battery Service state:
              Charger voltage : 19
              Battery current : 493
            Current Battery Service state:
              AC powered: true
              voltage: 3978
              temperature: 405
        """.trimIndent()

        assertEquals(
            com.oppower.battery.PrivilegedBatteryReading(currentMa = 493, voltageMv = 3_978),
            parsePrivilegedBatteryDump(dump),
        )
    }

    @Test
    fun privilegedBatteryDumpRequiresAnActualCurrentField() {
        assertNull(parsePrivilegedBatteryDump("voltage: 3978"))
        assertEquals(
            com.oppower.battery.PrivilegedBatteryReading(currentMa = 0, voltageMv = 0),
            parsePrivilegedBatteryDump("Battery current : 0"),
        )
    }

    @Test
    fun thermalRefreshAliasKeepsTheSameIntegerValue() {
        mapOf("0" to "00", "00" to "0", "1" to "01", "01" to "1").forEach { (source, alias) ->
            val write = thermalRefreshWrite(source)
            assertEquals(source.toInt(), alias.toInt())
            assertEquals(ThermalRefreshWrite(alias, source.toInt()), write)
        }
        assertEquals(ThermalRefreshWrite("0", 0), thermalRefreshWrite("null"))
        assertNull(thermalRefreshWrite("2"))
        assertNull(thermalRefreshWrite(""))
    }

    @Test
    fun rejectsShellInjectionAndUnknownSettings() {
        assertFalse(AllowedCommands.matches("am broadcast -a oplus.intent.action.ACTION_THERMAL_CONTROL_USER_SYSTEM -p com.oplus.battery --es name set_charge --ei level 0 --ei speedChargeAdd -2 --es foregroundPkgName com.oppower"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value -11"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 10"))
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put global another_value 1"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 2"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 1"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 2"))
        assertFalse(AllowedCommands.matches("settings put system oplus_camera_3rd_activity 00; reboot"))
        assertFalse(AllowedCommands.matches("settings put system oplus_float_window_show 2"))
        assertFalse(AllowedCommands.matches("settings put system oplus_float_window_show 00; reboot"))
        assertFalse(AllowedCommands.matches("settings get system smart_charge_switch_state"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 0"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 1"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 2"))
        assertFalse(AllowedCommands.matches("settings put system smart_charge_switch_state 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put system customize_charge_switch_state 1"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 19"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0"))
        assertFalse(AllowedCommands.matches("service call guardelfthermalcontrol 20 s16 0; reboot"))
        assertFalse(
            AllowedCommands.matches(
                "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 1",
            ),
        )
        assertFalse(
            AllowedCommands.matches(
                "dumpsys activity service com.oplus.battery/.OplusBatteryService setChargingLevel 0; reboot",
            ),
        )
        assertFalse(AllowedCommands.matches("rm -rf /data/local/tmp/oppower"))
    }
}
