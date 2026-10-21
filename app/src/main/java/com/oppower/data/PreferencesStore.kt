package com.oppower.data

import android.content.Context
import com.oppower.model.BaseTier
import com.oppower.model.ControlRules
import com.oppower.model.RuntimeSettings
import com.oppower.model.TierRule
import com.oppower.model.ControlTier
import com.oppower.model.NotificationIcon
import com.oppower.model.ThemeMode
import com.oppower.model.ThemePalette

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("oppower", Context.MODE_PRIVATE)

    fun load(): RuntimeSettings {
        val default = RuntimeSettings()
        val base = runCatching { BaseTier.valueOf(prefs.getString("base_tier", default.baseTier.name).orEmpty()) }
            .getOrDefault(default.baseTier)
        val custom = ControlRules(
            temperatureRules = listOf(
                TierRule(prefs.getFloat("temp_1", 41f), controlTier(prefs.getString("tier_1", ControlTier.HIGH.name))),
                TierRule(prefs.getFloat("temp_2", 42f), controlTier(prefs.getString("tier_2", ControlTier.GENTLE.name))),
                TierRule(prefs.getFloat("temp_3", 43f), controlTier(prefs.getString("tier_3", ControlTier.PAUSED.name))),
            ),
            socStopPercent = prefs.getInt("soc_stop", 80),
        )
        return RuntimeSettings(
            masterEnabled = prefs.getBoolean("master_enabled", false),
            baseTier = base,
            customRulesEnabled = prefs.getBoolean("custom_rules", false),
            customRules = custom,
            liveNotificationEnabled = prefs.getBoolean("live_notification", true),
            historyEnabled = prefs.getBoolean("history_enabled", true),
            resumeAfterReboot = prefs.getBoolean("resume_after_reboot", true),
            temperatureDownshiftSeconds = prefs.getInt("temp_down_seconds", 15),
            temperatureUpshiftSeconds = prefs.getInt("temp_up_seconds", 30),
            socDownshiftSeconds = prefs.getInt("soc_down_seconds", 10),
            socUpshiftSeconds = prefs.getInt("soc_up_seconds", 30),
            themeMode = enumValue(prefs.getString("theme_mode", null), ThemeMode.DARK),
            themePalette = enumValue(prefs.getString("theme_palette", null), ThemePalette.TEAL),
            fontScale = prefs.getFloat("font_scale", 1f).coerceIn(0.85f, 1.2f),
            uiScale = prefs.getFloat("ui_scale", 1f).coerceIn(0.85f, 1.15f),
            cardOpacity = prefs.getFloat("card_opacity", 1f).coerceIn(0.65f, 1f),
            hideFromRecents = prefs.getBoolean("hide_from_recents", false),
            notificationShowBatteryPercent = prefs.getBoolean("notify_battery", true),
            notificationShowCurrent = prefs.getBoolean("notify_current", true),
            notificationShowVoltage = prefs.getBoolean("notify_voltage", true),
            notificationShowTemperature = prefs.getBoolean("notify_temperature", true),
            notificationShowSupply = prefs.getBoolean("notify_supply", true),
            notificationIntegerPower = prefs.getBoolean("notify_integer_power", false),
            notificationIcon = enumValue(prefs.getString("notify_icon", null), NotificationIcon.BATTERY),
            notificationRefreshSeconds = prefs.getInt("notify_refresh_seconds", 5).coerceIn(2, 30),
            notificationScreenOffRefreshSeconds = prefs.getInt("notify_screen_off_seconds", 30).coerceIn(3, 300),
            notificationCloseOnceEnabled = prefs.getBoolean("notify_close_once", true),
            notificationAutoCloseOnFull = prefs.getBoolean("notify_close_full", true),
            notificationAutoCloseOnScreenOff = prefs.getBoolean("notify_close_screen_off", false),
            invertCurrentSign = prefs.getBoolean("invert_current_sign", false),
            doubleCellCorrection = prefs.getBoolean("double_cell_correction", false),
            virtualVoltage = prefs.getBoolean("virtual_voltage", false),
            absoluteCurrentPowerDisplay = prefs.getBoolean("absolute_current_power", false),
            debugMode = prefs.getBoolean("debug_mode", false),
            overlayKeepAliveEnabled = prefs.getBoolean("overlay_keep_alive", false),
        )
    }

    fun save(settings: RuntimeSettings) {
        prefs.edit()
            .putBoolean("master_enabled", settings.masterEnabled)
            .putString("base_tier", settings.baseTier.name)
            .putBoolean("custom_rules", settings.customRulesEnabled)
            .putBoolean("live_notification", settings.liveNotificationEnabled)
            .putBoolean("history_enabled", settings.historyEnabled)
            .putBoolean("resume_after_reboot", settings.resumeAfterReboot)
            .putFloat("temp_1", settings.customRules.temperatureRules.getOrNull(0)?.threshold ?: 41f)
            .putFloat("temp_2", settings.customRules.temperatureRules.getOrNull(1)?.threshold ?: 42f)
            .putFloat("temp_3", settings.customRules.temperatureRules.getOrNull(2)?.threshold ?: 43f)
            .putString("tier_1", (settings.customRules.temperatureRules.getOrNull(0)?.target ?: ControlTier.HIGH).name)
            .putString("tier_2", (settings.customRules.temperatureRules.getOrNull(1)?.target ?: ControlTier.GENTLE).name)
            .putString("tier_3", (settings.customRules.temperatureRules.getOrNull(2)?.target ?: ControlTier.PAUSED).name)
            .putInt("soc_stop", settings.customRules.socStopPercent)
            .putInt("temp_down_seconds", settings.temperatureDownshiftSeconds)
            .putInt("temp_up_seconds", settings.temperatureUpshiftSeconds)
            .putInt("soc_down_seconds", settings.socDownshiftSeconds)
            .putInt("soc_up_seconds", settings.socUpshiftSeconds)
            .putString("theme_mode", settings.themeMode.name)
            .putString("theme_palette", settings.themePalette.name)
            .putFloat("font_scale", settings.fontScale)
            .putFloat("ui_scale", settings.uiScale)
            .putFloat("card_opacity", settings.cardOpacity)
            .putBoolean("hide_from_recents", settings.hideFromRecents)
            .putBoolean("notify_battery", settings.notificationShowBatteryPercent)
            .putBoolean("notify_current", settings.notificationShowCurrent)
            .putBoolean("notify_voltage", settings.notificationShowVoltage)
            .putBoolean("notify_temperature", settings.notificationShowTemperature)
            .putBoolean("notify_supply", settings.notificationShowSupply)
            .putBoolean("notify_integer_power", settings.notificationIntegerPower)
            .putString("notify_icon", settings.notificationIcon.name)
            .putInt("notify_refresh_seconds", settings.notificationRefreshSeconds)
            .putInt("notify_screen_off_seconds", settings.notificationScreenOffRefreshSeconds)
            .putBoolean("notify_close_once", settings.notificationCloseOnceEnabled)
            .putBoolean("notify_close_full", settings.notificationAutoCloseOnFull)
            .putBoolean("notify_close_screen_off", settings.notificationAutoCloseOnScreenOff)
            .putBoolean("invert_current_sign", settings.invertCurrentSign)
            .putBoolean("double_cell_correction", settings.doubleCellCorrection)
            .putBoolean("virtual_voltage", settings.virtualVoltage)
            .putBoolean("absolute_current_power", settings.absoluteCurrentPowerDisplay)
            .putBoolean("debug_mode", settings.debugMode)
            .putBoolean("overlay_keep_alive", settings.overlayKeepAliveEnabled)
            .apply()
    }

    fun introSeen(): Boolean = prefs.getBoolean("intro_seen", false)

    fun markIntroSeen() {
        prefs.edit().putBoolean("intro_seen", true).apply()
    }

    private fun controlTier(value: String?): ControlTier = runCatching {
        ControlTier.valueOf(value.orEmpty())
    }.getOrDefault(ControlTier.PAUSED)

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)
}
