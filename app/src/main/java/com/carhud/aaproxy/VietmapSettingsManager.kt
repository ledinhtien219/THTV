package com.carhud.aaproxy

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object VietmapSettingsManager {

    private const val PREFS_NAME = "vietmap_settings_prefs"
    const val KEY_SOURCE_MODE = "pref_vietmap_source_mode" // "auto", "app", "gps"
    const val KEY_OVERSPEED_OFFSET = "pref_vietmap_overspeed_offset" // 0, 5, 10 km/h
    const val KEY_VOICE_ALERT = "pref_vietmap_voice_alert" // Boolean
    const val KEY_CARD_SIZE = "pref_vietmap_card_size" // "large", "compact"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabledPackages.contains(context.packageName)
    }

    fun openNotificationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSourceMode(context: Context): String {
        return getPrefs(context).getString(KEY_SOURCE_MODE, "auto") ?: "auto"
    }

    fun setSourceMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_SOURCE_MODE, mode).apply()
    }

    fun getOverspeedOffset(context: Context): Int {
        return getPrefs(context).getInt(KEY_OVERSPEED_OFFSET, 0)
    }

    fun setOverspeedOffset(context: Context, offset: Int) {
        getPrefs(context).edit().putInt(KEY_OVERSPEED_OFFSET, offset).apply()
    }

    fun isVoiceAlertEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOICE_ALERT, true)
    }

    fun setVoiceAlertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ALERT, enabled).apply()
    }
}
