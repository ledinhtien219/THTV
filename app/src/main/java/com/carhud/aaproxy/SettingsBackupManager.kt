package com.carhud.aaproxy

import android.content.Context
import android.content.SharedPreferences
import com.carhud.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** Portable user preferences only: device identity, license, diagnostics and permissions stay local. */
object SettingsBackupManager {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val IMPORT_REVISION = "settings_import_revision"
    private val schema = mapOf(
        "carhud_settings" to mapOf(
            "audio_ducking_enabled" to "Boolean",
            "auto_detect_screen_layout" to "Boolean",
            "auto_fullscreen_enabled" to "Boolean",
            "auto_resume_last_track" to "Boolean",
            "background_audio_enabled" to "Boolean",
            "car_keyboard_telex" to "Boolean",
            "car_keyboard_input_mode" to "String",
            "car_video_aspect_mode" to "String",
            "carhud_day_mode" to "Boolean",
            "carhud_theme_mode" to "String",
            "custom_iptv_file_name" to "String",
            "custom_iptv_is_local_file" to "Boolean",
            "custom_iptv_m3u_url" to "String",
            "default_to_dashboard" to "Boolean",
            "desktop_mode_enabled" to "Boolean",
            "dock_scale" to "Int",
            "iptv_video_aspect_mode" to "String",
            "keep_screen_on" to "Boolean",
            "pref_cockpit_user_name" to "String",
            "pref_cockpit_wallpaper_type" to "String",
            "pref_custom_wallpaper_kind" to "String",
            "pref_fav_channels_set" to "StringSet",
            "pref_phone_accent_color" to "String",
            "pref_phone_theme_mode" to "String",
            "pref_show_bottom_nav" to "Boolean",
            "pref_show_card_browser" to "Boolean",
            "pref_show_card_iptv" to "Boolean",
            "pref_show_card_m3u" to "Boolean",
            "pref_show_card_youtube" to "Boolean",
            "pref_show_fav_channels" to "Boolean",
            "pref_show_hero_clock" to "Boolean",
            "pref_show_mini_player" to "Boolean",
            "pref_show_search_bar" to "Boolean",
            "settings_dark_mode" to "Boolean",
            "steering_double_click_speed" to "Int",
            "steering_double_click_voice" to "Boolean",
            "steering_double_play_voice" to "Boolean",
            "steering_next_action" to "String",
            "steering_prev_action" to "String",
            "steering_voice_enabled" to "Boolean",
            "system_voice_commands_enabled" to "Boolean",
            "toolbar_btn_back" to "Boolean",
            "toolbar_btn_browser" to "Boolean",
            "toolbar_btn_day_night" to "Boolean",
            "toolbar_btn_fullscreen" to "Boolean",
            "toolbar_btn_home" to "Boolean",
            "toolbar_btn_mic" to "Boolean",
            "toolbar_btn_next" to "Boolean",
            "toolbar_btn_play_pause" to "Boolean",
            "toolbar_btn_playlist_scroll" to "Boolean",
            "toolbar_btn_search" to "Boolean",
            "toolbar_btn_settings" to "Boolean",
            "toolbar_btn_time_pill" to "Boolean",
            "toolbar_btn_tv" to "Boolean",
            "toolbar_position" to "String",
            "toolbar_scale" to "Int",
            "webapp_toolbar_position" to "String"
        ),
        "waze_hud_settings_prefs" to mapOf(
            "key_active_hud_style" to "Int",
            "key_alert_audio_mode" to "String",
            "key_alert_speed_camera" to "Boolean",
            "key_alert_tone_style" to "String",
            "key_alert_turn_maneuver" to "Boolean",
            "key_alert_voice_tts" to "Boolean",
            "key_hud_is_locked" to "Boolean",
            "key_hud_opacity" to "Int",
            "key_hud_pos_x" to "Int",
            "key_hud_pos_y" to "Int",
            "key_hud_scale" to "Int",
            "key_master_alerts_enabled" to "Boolean",
            "key_overspeed_audio_alert" to "Boolean",
            "key_show_floating_hud_overlay" to "Boolean",
            "key_show_in_app_hud_preview" to "Boolean",
            "key_show_lane_guidance" to "Boolean"
        ),
        "vietmap_settings_prefs" to mapOf(
            "pref_vietmap_overspeed_offset" to "Int",
            "pref_vietmap_source_mode" to "String",
            "pref_vietmap_voice_alert" to "Boolean"
        ),
        "carhud_web_apps_prefs" to mapOf(
            "custom_apps_json" to "String",
            "deleted_apps_set" to "StringSet",
            "disabled_apps_set" to "StringSet"
        )
    )
    data class Backup(val groups: Map<String, Map<String, Any>>, val playlist: String?) {
        val count: Int get() = groups.values.sumOf { it.size }
        val favorites: Int get() = (groups[SettingsActivity.PREFS]?.get(MainActivity.PREF_FAV_CHANNELS_SET) as? Set<*>)?.size ?: 0
    }

    private fun expectedType(group: String, key: String): String? = schema[group]?.get(key)
        ?: if (group == "carhud_web_apps_prefs" && key.startsWith("app_desktop_override_") && key.length <= 160) "Boolean" else null

    fun read(input: InputStream): Backup {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= MAX_BYTES) { "File cấu hình vượt quá 4 MB." }
            out.write(buffer, 0, n)
        }
        return parse(out.toString("UTF-8"))
    }

    fun export(context: Context): String {
        val groups = JSONObject()
        for ((name, _) in schema) {
            val values = JSONObject()
            for ((key, value) in context.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val type = expectedType(name, key) ?: continue
                if (value == null) continue
                val portable = if (key == "custom_apps_json") portableApps(value as String) else value
                values.put(key, JSONObject().put("type", type).put("value", if (portable is Set<*>) JSONArray(portable.sortedBy { it.toString() }) else portable))
            }
            groups.put(name, values)
        }
        val root = JSONObject().put("format", "THTV-settings").put("schemaVersion", 1)
            .put("appVersion", BuildConfig.VERSION_NAME).put("createdAt", System.currentTimeMillis()).put("groups", groups)
        if (IptvManager.isUsingLocalFile(context)) {
            val file = File(context.filesDir, IptvManager.LOCAL_M3U_FILE)
            require(file.length() <= 1024 * 1024) { "Danh sách M3U quá lớn để kèm cấu hình (tối đa 1 MB)." }
            root.put("localPlaylist", file.readText(Charsets.UTF_8))
        }
        val text = root.toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Cấu hình vượt quá 4 MB." }
        parse(text) // Validate before writing a file the user will later restore.
        return text
    }

    fun parse(text: String): Backup {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "File cấu hình vượt quá 4 MB." }
        val root = JSONObject(text)
        require(root.optString("format") == "THTV-settings" && root.optInt("schemaVersion") == 1) { "File không phải cấu hình THTV được hỗ trợ." }
        val source = root.getJSONObject("groups")
        val groups = linkedMapOf<String, Map<String, Any>>()
        for (name in source.keys()) {
            require(name in schema) { "Nhóm cấu hình không được hỗ trợ: $name" }
            val json = source.getJSONObject(name)
            require(json.length() <= 300) { "Quá nhiều tùy chọn trong file." }
            val values = linkedMapOf<String, Any>()
            for (key in json.keys()) {
                val type = expectedType(name, key) ?: continue
                val entry = json.getJSONObject(key)
                require(entry.getString("type") == type) { "Sai kiểu dữ liệu: $key" }
                val value = entry.get("value")
                values[key] = when (type) {
                    "Boolean" -> { require(value is Boolean) { "Sai kiểu dữ liệu: $key" }; value }
                    "Int" -> {
                        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "Sai số nguyên: $key" }
                        validateInt(key, value.toInt()); value.toInt()
                    }
                    "String" -> {
                        require(value is String && value.length <= 512 * 1024) { "Sai chuỗi dữ liệu: $key" }
                        validateString(key, value)
                        if (key == "custom_apps_json") portableApps(value) else value
                    }
                    "StringSet" -> {
                        require(value is JSONArray && value.length() <= 2000) { "Sai danh sách: $key" }
                        (0 until value.length()).map { i ->
                            val item = value.get(i)
                            require(item is String && item.length <= 512) { "Sai phần tử danh sách: $key" }
                            item
                        }.toSet()
                    }
                    else -> error("Kiểu dữ liệu không hỗ trợ: $type")
                }
            }
            groups[name] = values
        }
        val playlist = if (root.has("localPlaylist")) root.getString("localPlaylist").also {
            require(it.toByteArray(Charsets.UTF_8).size <= 1024 * 1024 && it.trimStart().startsWith("#EXTM3U")) { "Danh sách M3U kèm theo không hợp lệ." }
        } else null
        require(groups.isNotEmpty()) { "File không có nhóm cài đặt THTV để nạp." }
        require(groups[SettingsActivity.PREFS]?.get(IptvManager.PREF_IPTV_IS_FILE) != true || playlist != null) { "File thiếu danh sách M3U đã lưu." }
        return Backup(groups, playlist)
    }

    fun apply(context: Context, backup: Backup) {
        // Parsing completes before any preference or playlist is changed.
        backup.playlist?.let {
            val target = File(context.filesDir, IptvManager.LOCAL_M3U_FILE)
            val temp = File(context.filesDir, "settings_playlist.tmp")
            temp.writeText(it, Charsets.UTF_8)
            require(temp.renameTo(target)) { "Không thể lưu danh sách M3U." }
        }
        for ((name, values) in backup.groups) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // Absence means the built-in default, so restoring a preset must remove
            // later overrides too. Device/license keys are outside the schema.
            for (key in schema.getValue(name).keys + prefs.all.keys) {
                if (expectedType(name, key) != null && key !in values) editor.remove(key)
            }
            for ((key, value) in values) put(editor, key, value)
            require(editor.commit()) { "Không thể lưu cài đặt. Vui lòng thử lại." }
        }
        IptvManager.invalidateCache(context)
        context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putLong(IMPORT_REVISION, System.currentTimeMillis()).commit()
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            else -> error("Kiểu dữ liệu không được hỗ trợ.")
        }
    }

    private fun validateInt(key: String, value: Int) {
        val range = when (key) {
            "toolbar_scale", "dock_scale" -> 50..150
            "steering_double_click_speed" -> 200..1500
            "key_active_hud_style" -> 1..5
            "key_hud_scale" -> 60..150
            "key_hud_opacity" -> 50..100
            "key_hud_pos_x", "key_hud_pos_y" -> 0..10000
            "pref_vietmap_overspeed_offset" -> 0..30
            else -> Int.MIN_VALUE..Int.MAX_VALUE
        }
        require(value in range) { "Giá trị không hợp lệ: $key" }
    }

    private fun validateString(key: String, value: String) {
        val choices = when (key) {
            "car_keyboard_input_mode" -> setOf("native", "thtv")
            "iptv_video_aspect_mode", "car_video_aspect_mode" -> setOf("fill", "contain", "cover", "4:3", "21:9")
            "carhud_theme_mode", "pref_phone_theme_mode" -> setOf("auto", "day", "night", "dark", "light", "system")
            "toolbar_position" -> setOf("auto", "left", "right", "bottom")
            "webapp_toolbar_position" -> setOf("left", "top")
            "steering_next_action" -> setOf("next", "voice")
            "steering_prev_action" -> setOf("prev", "voice")
            "pref_vietmap_source_mode" -> setOf("auto", "app", "gps")
            "key_alert_audio_mode" -> setOf("tone", "voice", "both", "off")
            "key_alert_tone_style" -> setOf("beep", "double_beep", "ack", "prompt", "strong")
            else -> null
        }
        require(choices == null || value in choices) { "Giá trị không hợp lệ: $key" }
        if (key == "pref_phone_accent_color") require(value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Mã màu không hợp lệ." }
    }

    private fun portableApps(text: String): String {
        val apps = JSONArray(text)
        require(apps.length() <= 100) { "Quá nhiều ứng dụng tùy chỉnh." }
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            require(app.getString("id").length in 1..160 && app.getString("name").length in 1..200) { "Ứng dụng tùy chỉnh không hợp lệ." }
            val url = app.getString("url")
            require(url.length <= 4096 && (url.startsWith("https://") || url.startsWith("http://") || url == "file:///android_asset/iptv_player.html")) { "Địa chỉ ứng dụng không hợp lệ." }
            require(app.optString("colorHex", "#38BDF8").matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) { "Màu ứng dụng không hợp lệ." }
            if (app.has("isDesktop")) require(app.get("isDesktop") is Boolean) { "Ứng dụng tùy chỉnh không hợp lệ." }
            app.remove("iconPath") // Private paths/icons cannot be transferred to another phone.
        }
        return apps.toString()
    }
}
