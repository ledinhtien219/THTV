package com.carhud.aaproxy

import android.content.Context
import android.content.SharedPreferences

data class HudStyle(
    val id: Int,
    val name: String,
    val category: String,
    val description: String,
    val isRecommended: Boolean = false
)

object WazeHudManager {
    private const val PREFS_NAME = "waze_hud_settings_prefs"

    const val KEY_ACTIVE_HUD_STYLE = "key_active_hud_style"
    const val KEY_HUD_SCALE = "key_hud_scale" // 60 to 150, default 120
    const val KEY_HUD_OPACITY = "key_hud_opacity" // 50 to 100, default 95
    const val KEY_SHOW_LANE_GUIDANCE = "key_show_lane_guidance" // default true
    const val KEY_ALERT_SPEED_CAMERA = "key_alert_speed_camera" // default true
    const val KEY_OVERSPEED_AUDIO_ALERT = "key_overspeed_audio_alert" // default true
    const val KEY_MASTER_ALERTS_ENABLED = "key_master_alerts_enabled" // default true
    const val KEY_ALERT_TURN_MANEUVER = "key_alert_turn_maneuver" // default true
    const val KEY_ALERT_VOICE_TTS = "key_alert_voice_tts" // legacy switch
    const val KEY_ALERT_AUDIO_MODE = "key_alert_audio_mode" // tone, voice, both, off
    const val KEY_ALERT_TONE_STYLE = "key_alert_tone_style" // 5 selectable warning tones

    const val ALERT_AUDIO_TONE = "tone"
    const val ALERT_AUDIO_VOICE = "voice"
    const val ALERT_AUDIO_BOTH = "both"
    const val ALERT_AUDIO_OFF = "off"

    const val ALERT_TONE_BEEP = "beep"
    const val ALERT_TONE_DOUBLE_BEEP = "double_beep"
    const val ALERT_TONE_ACK = "ack"
    const val ALERT_TONE_PROMPT = "prompt"
    const val ALERT_TONE_STRONG = "strong"

    const val KEY_HUD_POS_X = "key_hud_pos_x" // default 28
    const val KEY_HUD_POS_Y = "key_hud_pos_y" // default 32
    const val KEY_SHOW_IN_APP_PREVIEW = "key_show_in_app_hud_preview" // default true
    const val KEY_SHOW_FLOATING_OVERLAY = "key_show_floating_hud_overlay" // default true
    const val KEY_HUD_LOCKED = "key_hud_is_locked" // default false (allows drag until locked)

    const val CATEGORY_ALL = "Tất cả"
    const val CATEGORY_HORIZONTAL = "Bubble Ngang"
    const val CATEGORY_VERTICAL = "Dọc (Vertical)"

    val STYLES = listOf(
        HudStyle(
            id = 1,
            name = "#1 Bubble Ngang Tiêu Chuẩn",
            category = CATEGORY_HORIZONTAL,
            description = "Tốc độ to, biển 80km/h tròn viền đỏ, mũi tên rẽ gọn gàng",
            isRecommended = true
        ),
        HudStyle(
            id = 2,
            name = "#2 Bubble Ngang Đầy Đủ (Full Info)",
            category = CATEGORY_HORIZONTAL,
            description = "Thêm tên đường, khoảng cách, ETA và biển phụ",
            isRecommended = false
        ),
        HudStyle(
            id = 3,
            name = "#3 Dọc Cột Trái (Left Dock)",
            category = CATEGORY_VERTICAL,
            description = "Thanh dọc sát mép trái màn hình Android Auto",
            isRecommended = false
        ),
        HudStyle(
            id = 4,
            name = "#4 Dọc Cột Phải (Right Dock)",
            category = CATEGORY_VERTICAL,
            description = "Thanh dọc sát mép phải màn hình Android Auto",
            isRecommended = false
        ),
        HudStyle(
            id = 5,
            name = "#5 Siêu Tinh Gọn (Chỉ Tốc Độ & Khóa)",
            category = CATEGORY_HORIZONTAL,
            description = "Chỉ gồm vòng tròn giới hạn tốc độ, tốc độ hiện tại và nút khóa",
            isRecommended = true
        )
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getActiveStyleId(context: Context): Int {
        val id = getPrefs(context).getInt(KEY_ACTIVE_HUD_STYLE, 1)
        return if (STYLES.any { it.id == id }) id else 1
    }

    fun setActiveStyleId(context: Context, styleId: Int) {
        val validId = if (STYLES.any { it.id == styleId }) styleId else 1
        getPrefs(context).edit().putInt(KEY_ACTIVE_HUD_STYLE, validId).apply()
    }

    fun getActiveStyle(context: Context): HudStyle {
        val id = getActiveStyleId(context)
        return STYLES.find { it.id == id } ?: STYLES[0]
    }

    fun cycleNextStyle(context: Context): HudStyle {
        val currentId = getActiveStyleId(context)
        val nextId = if (currentId >= STYLES.size) 1 else currentId + 1
        setActiveStyleId(context, nextId)
        return getActiveStyle(context)
    }

    fun getScale(context: Context): Int {
        return getPrefs(context).getInt(KEY_HUD_SCALE, 120).coerceIn(60, 150)
    }

    fun setScale(context: Context, scale: Int) {
        getPrefs(context).edit().putInt(KEY_HUD_SCALE, scale.coerceIn(60, 150)).apply()
    }

    fun getOpacity(context: Context): Int {
        return getPrefs(context).getInt(KEY_HUD_OPACITY, 95).coerceIn(50, 100)
    }

    fun setOpacity(context: Context, opacity: Int) {
        getPrefs(context).edit().putInt(KEY_HUD_OPACITY, opacity.coerceIn(50, 100)).apply()
    }

    fun isLaneGuidanceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_LANE_GUIDANCE, true)
    }

    fun setLaneGuidanceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_LANE_GUIDANCE, enabled).apply()
    }

    fun isSpeedCameraEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALERT_SPEED_CAMERA, true)
    }

    fun setSpeedCameraEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALERT_SPEED_CAMERA, enabled).apply()
    }

    fun isOverspeedAudioEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OVERSPEED_AUDIO_ALERT, true)
    }

    fun setOverspeedAudioEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OVERSPEED_AUDIO_ALERT, enabled).apply()
    }

    fun isMasterAlertsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_MASTER_ALERTS_ENABLED, true)
    }

    fun setMasterAlertsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MASTER_ALERTS_ENABLED, enabled).apply()
    }

    fun isOverspeedAlertEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_OVERSPEED_AUDIO_ALERT, true)
    }

    fun setOverspeedAlertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_OVERSPEED_AUDIO_ALERT, enabled).apply()
    }

    fun isTurnManeuverEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALERT_TURN_MANEUVER, true)
    }

    fun setTurnManeuverEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALERT_TURN_MANEUVER, enabled).apply()
    }

    fun isVoiceTtsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALERT_VOICE_TTS, true)
    }

    fun setVoiceTtsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ALERT_VOICE_TTS, enabled).apply()
    }

    /**
     * Audio style for road warnings (Ting, Giọng nói, Cả hai, Tắt)
     */
    fun getAlertAudioMode(context: Context): String {
        val value = getPrefs(context).getString(KEY_ALERT_AUDIO_MODE, ALERT_AUDIO_TONE)
        return when (value) {
            ALERT_AUDIO_TONE, ALERT_AUDIO_VOICE, ALERT_AUDIO_BOTH, ALERT_AUDIO_OFF -> value
            else -> ALERT_AUDIO_TONE
        }
    }

    fun setAlertAudioMode(context: Context, mode: String) {
        val safeMode = when (mode) {
            ALERT_AUDIO_TONE, ALERT_AUDIO_VOICE, ALERT_AUDIO_BOTH, ALERT_AUDIO_OFF -> mode
            else -> ALERT_AUDIO_TONE
        }
        getPrefs(context).edit().putString(KEY_ALERT_AUDIO_MODE, safeMode).apply()
    }

    fun alertAudioModeLabel(context: Context): String = when (getAlertAudioMode(context)) {
        ALERT_AUDIO_TONE -> "Ting ngắn"
        ALERT_AUDIO_VOICE -> "Giọng nói"
        ALERT_AUDIO_BOTH -> "Ting + giọng"
        else -> "Tắt"
    }

    fun getAlertToneStyle(context: Context): String {
        val value = getPrefs(context).getString(KEY_ALERT_TONE_STYLE, ALERT_TONE_BEEP)
        return when (value) {
            ALERT_TONE_BEEP,
            ALERT_TONE_DOUBLE_BEEP,
            ALERT_TONE_ACK,
            ALERT_TONE_PROMPT,
            ALERT_TONE_STRONG -> value
            else -> ALERT_TONE_BEEP
        }
    }

    fun setAlertToneStyle(context: Context, style: String) {
        val safeStyle = when (style) {
            ALERT_TONE_BEEP,
            ALERT_TONE_DOUBLE_BEEP,
            ALERT_TONE_ACK,
            ALERT_TONE_PROMPT,
            ALERT_TONE_STRONG -> style
            else -> ALERT_TONE_BEEP
        }
        getPrefs(context).edit().putString(KEY_ALERT_TONE_STYLE, safeStyle).apply()
    }

    fun alertToneStyleLabel(context: Context): String = when (getAlertToneStyle(context)) {
        ALERT_TONE_BEEP -> "Ting ngắn"
        ALERT_TONE_DOUBLE_BEEP -> "Ting đôi"
        ALERT_TONE_ACK -> "Bíp xác nhận"
        ALERT_TONE_PROMPT -> "Nhắc nhở"
        ALERT_TONE_STRONG -> "Cảnh báo mạnh"
        else -> "Ting ngắn"
    }


    fun getPosition(context: Context): Pair<Int, Int> {
        val x = getPrefs(context).getInt(KEY_HUD_POS_X, 28)
        val y = getPrefs(context).getInt(KEY_HUD_POS_Y, 32)
        return Pair(x, y)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        getPrefs(context).edit()
            .putInt(KEY_HUD_POS_X, x)
            .putInt(KEY_HUD_POS_Y, y)
            .apply()
    }

    fun resetPosition(context: Context) {
        setPosition(context, 28, 32)
    }

    fun isInAppPreviewEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_IN_APP_PREVIEW, false)
    }

    fun setInAppPreviewEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_IN_APP_PREVIEW, enabled).apply()
    }

    fun isFloatingOverlayEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_FLOATING_OVERLAY, true)
    }

    fun setFloatingOverlayEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_FLOATING_OVERLAY, enabled).apply()
    }

    fun isLocked(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HUD_LOCKED, true)
    }

    fun setLocked(context: Context, locked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HUD_LOCKED, locked).apply()
    }
}
