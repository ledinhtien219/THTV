package com.carhud.aaproxy

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SettingsBackupManagerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
    @Test fun roundTripPreservesTypesFavoritesAndHudWhileKeepingDeviceData() {
        prefs.edit().putInt("toolbar_scale", 120).putBoolean("car_keyboard_telex", true)
            .putStringSet(MainActivity.PREF_FAV_CHANNELS_SET, setOf("vtv1", "htv7"))
            .putString("iptv_video_aspect_mode", "4:3")
            .putInt("car_screen_width", 1920).putBoolean("audit_activation_check_v171", true)
            .putStringSet("update_notified_builds", setOf("202")).commit()
        val hud = app.getSharedPreferences("waze_hud_settings_prefs", Context.MODE_PRIVATE)
        hud.edit().putInt("key_hud_pos_x", 500).commit()
        val text = SettingsBackupManager.export(app)
        assertFalse(text.contains("car_screen_width"))
        assertFalse(text.contains("audit_activation"))
        assertFalse(text.contains("update_notified_builds"))
        prefs.edit().putInt("toolbar_scale", 60).putBoolean("car_keyboard_telex", false)
            .putStringSet(MainActivity.PREF_FAV_CHANNELS_SET, emptySet()).putInt("car_screen_width", 800).commit()
        hud.edit().putInt("key_hud_pos_x", 20).commit()
        val backup = SettingsBackupManager.read(ByteArrayInputStream(text.toByteArray()))
        SettingsBackupManager.apply(app, backup)
        assertEquals(120, prefs.getInt("toolbar_scale", 0))
        assertTrue(prefs.getBoolean("car_keyboard_telex", false))
        assertEquals(setOf("vtv1", "htv7"), prefs.getStringSet(MainActivity.PREF_FAV_CHANNELS_SET, null))
        assertEquals("4:3", prefs.getString("iptv_video_aspect_mode", null))
        assertEquals(800, prefs.getInt("car_screen_width", 0))
        assertEquals(500, hud.getInt("key_hud_pos_x", 0))
    }
    @Test fun emptyFavoritesRemainExplicitlyEmptyAndCustomAppsTransfer() {
        prefs.edit().putStringSet(MainActivity.PREF_FAV_CHANNELS_SET, emptySet()).commit()
        WebAppManager.addCustomApp(app, "Trang tin", "https://example.com", iconPath = "/private/icon.png")
        val backup = SettingsBackupManager.parse(SettingsBackupManager.export(app))
        assertEquals(0, backup.favorites)
        assertEquals(emptySet<String>(), backup.groups[SettingsActivity.PREFS]?.get(MainActivity.PREF_FAV_CHANNELS_SET))
        assertFalse((backup.groups["carhud_web_apps_prefs"]?.get("custom_apps_json") as String).contains("iconPath"))
        SettingsBackupManager.apply(app, backup)
        assertEquals("Trang tin", WebAppManager.getCustomApps(app).single().name)
    }
    @Test fun invalidTypeOrValueIsRejectedBeforeSettingsChange() {
        prefs.edit().putInt("toolbar_scale", 100).commit()
        val root = JSONObject(SettingsBackupManager.export(app))
        val entry = root.getJSONObject("groups").getJSONObject(SettingsActivity.PREFS).getJSONObject("toolbar_scale")
        entry.put("type", "String").put("value", "bad")
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupManager.parse(root.toString()) }
        assertEquals(100, prefs.getInt("toolbar_scale", 0))
        entry.put("type", "Int").put("value", 999999)
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupManager.parse(root.toString()) }
        assertEquals(100, prefs.getInt("toolbar_scale", 0))
    }
    @Test fun localPlaylistIsBundledAndRestored() {
        val content = "#EXTM3U\n#EXTINF:-1,VTV1\nhttps://example.com/live.m3u8\n"
        IptvManager.setM3uContent(app, content, "kenh.m3u")
        prefs.edit().putStringSet(MainActivity.PREF_FAV_CHANNELS_SET, setOf("vtv1")).commit()
        val backup = SettingsBackupManager.parse(SettingsBackupManager.export(app))
        File(app.filesDir, IptvManager.LOCAL_M3U_FILE).delete()
        SettingsBackupManager.apply(app, backup)
        assertEquals(content, File(app.filesDir, IptvManager.LOCAL_M3U_FILE).readText())
        assertTrue(IptvManager.isUsingLocalFile(app))
    }
    @Test fun unrelatedFileAndOversizedInputAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SettingsBackupManager.parse("{\"format\":\"another-app\"}") }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupManager.read(ByteArrayInputStream(ByteArray(SettingsBackupManager.MAX_BYTES + 1)))
        }
    }
    @Test fun restoringDefaultPresetRemovesLaterOverridesWithoutClearingDeviceData() {
        prefs.edit().clear().commit()
        val backup = SettingsBackupManager.parse(SettingsBackupManager.export(app))
        prefs.edit().putBoolean("toolbar_btn_mic", false)
            .putStringSet(MainActivity.PREF_FAV_CHANNELS_SET, setOf("vtv3"))
            .putInt("car_screen_width", 1920).commit()
        SettingsBackupManager.apply(app, backup)
        assertFalse(prefs.contains("toolbar_btn_mic"))
        assertFalse(prefs.contains(MainActivity.PREF_FAV_CHANNELS_SET))
        assertEquals(1920, prefs.getInt("car_screen_width", 0))
    }
}
