package com.reelkill.data.repository

import com.reelkill.data.db.entity.AppSettings
import org.json.JSONObject

object AppSettingsJson {
    fun toJson(settings: AppSettings): String {
        return JSONObject()
            .put("appId", settings.appId)
            .put("dailyLimit", settings.dailyLimit)
            .put("bingeThresholdReels", settings.bingeThresholdReels)
            .put("bingeWindowMinutes", settings.bingeWindowMinutes)
            .put("cooldownBaseSeconds", settings.cooldownBaseSeconds)
            .put("frictionThresholdPct", settings.frictionThresholdPct.toDouble())
            .put("blockReelsTab", settings.blockReelsTab)
            .put("blockExplore", settings.blockExplore)
            .put("blockStories", settings.blockStories)
            .put("blockSuggested", settings.blockSuggested)
            .put("allowReelsInDm", settings.allowReelsInDm)
            .put("antiScrollEnabled", settings.antiScrollEnabled)
            .put("antiScrollSensitivity", settings.antiScrollSensitivity)
            .putNullable("scheduledBreakStart", settings.scheduledBreakStart)
            .putNullable("scheduledBreakEnd", settings.scheduledBreakEnd)
            .put("strictModeEnabled", settings.strictModeEnabled)
            .putNullable("strictModePin", settings.strictModePin)
            .put("accountabilityPartnerEnabled", settings.accountabilityPartnerEnabled)
            .toString()
    }

    fun fromJson(json: String): AppSettings {
        val obj = JSONObject(json)
        return AppSettings(
            appId = obj.getString("appId"),
            dailyLimit = obj.getInt("dailyLimit").coerceIn(
                AppSettings.DAILY_LIMIT_MIN,
                AppSettings.DAILY_LIMIT_MAX
            ),
            bingeThresholdReels = obj.getInt("bingeThresholdReels"),
            bingeWindowMinutes = obj.getInt("bingeWindowMinutes"),
            cooldownBaseSeconds = obj.getInt("cooldownBaseSeconds"),
            frictionThresholdPct = obj.getDouble("frictionThresholdPct").toFloat().coerceIn(0f, 1f),
            blockReelsTab = obj.getBoolean("blockReelsTab"),
            blockExplore = obj.getBoolean("blockExplore"),
            blockStories = obj.getBoolean("blockStories"),
            blockSuggested = obj.getBoolean("blockSuggested"),
            allowReelsInDm = obj.getBoolean("allowReelsInDm"),
            antiScrollEnabled = obj.getBoolean("antiScrollEnabled"),
            antiScrollSensitivity = obj.getString("antiScrollSensitivity"),
            scheduledBreakStart = obj.optNullableString("scheduledBreakStart"),
            scheduledBreakEnd = obj.optNullableString("scheduledBreakEnd"),
            strictModeEnabled = obj.getBoolean("strictModeEnabled"),
            strictModePin = obj.optNullableString("strictModePin"),
            accountabilityPartnerEnabled = obj.getBoolean("accountabilityPartnerEnabled")
        )
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject {
        return if (value == null) put(name, JSONObject.NULL) else put(name, value)
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else optString(name)
    }
}
