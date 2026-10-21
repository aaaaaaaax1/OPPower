package com.oppower.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedCommandsTest {
    @Test
    fun acceptsOnlyExpectedSettingsCommands() {
        assertTrue(AllowedCommands.matches("settings get global cosa_ctc_value"))
        assertTrue(AllowedCommands.matches("settings put global cosa_ctc_value -9"))
        assertTrue(AllowedCommands.matches("dumpsys activity service com.oplus.battery/.OplusBatteryService getCurThermalPolicy"))
    }

    @Test
    fun rejectsShellInjectionAndUnknownSettings() {
        assertFalse(AllowedCommands.matches("settings put global cosa_ctc_value 0; reboot"))
        assertFalse(AllowedCommands.matches("settings put global another_value 1"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 2"))
        assertFalse(AllowedCommands.matches("settings put system single_speed_charge_state 1"))
        assertFalse(AllowedCommands.matches("rm -rf /data/local/tmp/oppower"))
    }
}
