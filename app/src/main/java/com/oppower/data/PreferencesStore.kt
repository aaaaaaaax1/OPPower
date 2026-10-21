package com.oppower.data

import android.content.Context
import com.oppower.model.BaseTier
import com.oppower.model.ControlRules
import com.oppower.model.RuntimeSettings
import com.oppower.model.TierRule
import com.oppower.model.ControlTier

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
            widgetEnabled = prefs.getBoolean("widget_enabled", true),
            temperatureDownshiftSeconds = prefs.getInt("temp_down_seconds", 15),
            temperatureUpshiftSeconds = prefs.getInt("temp_up_seconds", 30),
            socDownshiftSeconds = prefs.getInt("soc_down_seconds", 10),
            socUpshiftSeconds = prefs.getInt("soc_up_seconds", 30),
        )
    }

    fun save(settings: RuntimeSettings) {
        prefs.edit()
            .putBoolean("master_enabled", settings.masterEnabled)
            .putString("base_tier", settings.baseTier.name)
            .putBoolean("custom_rules", settings.customRulesEnabled)
            .putBoolean("live_notification", settings.liveNotificationEnabled)
            .putBoolean("widget_enabled", settings.widgetEnabled)
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
            .apply()
    }

    fun introSeen(): Boolean = prefs.getBoolean("intro_seen", false)

    fun markIntroSeen() {
        prefs.edit().putBoolean("intro_seen", true).apply()
    }

    private fun controlTier(value: String?): ControlTier = runCatching {
        ControlTier.valueOf(value.orEmpty())
    }.getOrDefault(ControlTier.PAUSED)
}
