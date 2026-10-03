package com.carhud.aaproxy

import android.content.Context
import android.content.SharedPreferences
import com.carhud.app.R
import org.json.JSONArray
import org.json.JSONObject

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class WebAppItem(
    val id: String,
    val name: String,
    val url: String,
    val iconRes: Int = R.drawable.ic_app_web,
    val iconPath: String? = null,
    val isDesktop: Boolean = false,
    val isBuiltIn: Boolean = true,
    val colorHex: String = "#2D9CDB"
)

object WebIconFetcher {
    private val executor = Executors.newCachedThreadPool()

    fun extractDomain(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (clean.startsWith("http://")) clean = clean.substring(7)
        if (clean.startsWith("https://")) clean = clean.substring(8)
        val slashIdx = clean.indexOf('/')
        if (slashIdx != -1) clean = clean.substring(0, slashIdx)
        val colonIdx = clean.indexOf(':')
        if (colonIdx != -1) clean = clean.substring(0, colonIdx)
        return clean.trim().lowercase()
    }

    fun extractPrettyName(domain: String): String {
        var d = domain.removePrefix("www.")
        val dotIdx = d.indexOf('.')
        val base = if (dotIdx != -1) d.substring(0, dotIdx) else d
        return base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun fetchFaviconAsync(domain: String, callback: (Bitmap?) -> Unit) {
        if (domain.isBlank()) {
            callback(null)
            return
        }
        executor.execute {
            val bmp = fetchFaviconBitmap(domain)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                callback(bmp)
            }
        }
    }

    fun fetchFaviconBitmap(domain: String): Bitmap? {
        if (domain.isBlank()) return null
        val urlsToTry = listOf(
            "https://www.google.com/s2/favicons?domain=$domain&sz=128",
            "https://icons.duckduckgo.com/ip3/$domain.ico",
            "https://$domain/apple-touch-icon.png",
            "https://$domain/favicon.ico"
        )

        for (urlStr in urlsToTry) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                conn.connect()
                if (conn.responseCode in 200..299) {
                    val stream = conn.inputStream
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream.close()
                    conn.disconnect()
                    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                        return bmp
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {}
        }
        return null
    }

    fun saveIconToDisk(context: Context, appId: String, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "app_icons")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$appId.png")
            val fos = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
            fos.close()
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

typealias WebAppRepository = WebAppManager

object WebAppManager {
    fun loadApps(context: Context): List<WebAppItem> = getEnabledApps(context)
    private const val PREFS_NAME = "carhud_web_apps_prefs"
    private const val KEY_CUSTOM_APPS = "custom_apps_json"
    private const val KEY_ACTIVE_APP_ID = "active_web_app_id"
    private const val KEY_DISABLED_APPS = "disabled_apps_set"
    private const val KEY_DELETED_APPS = "deleted_apps_set"
    private const val KEY_APP_DESKTOP_PREFIX = "app_desktop_override_"

    val DEFAULT_APPS = listOf(
        WebAppItem(
            id = "youtube",
            name = "YouTube Pro",
            url = "https://m.youtube.com",
            iconRes = R.drawable.ic_app_youtube,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#E50914"
        ),
        WebAppItem(
            id = "iptv",
            name = "IPTV Truyền hình",
            url = "file:///android_asset/iptv_player.html",
            iconRes = R.drawable.ic_app_iptv,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#0284C7"
        ),
        WebAppItem(
            id = "web",
            name = "Trình duyệt Web",
            url = "https://www.google.com",
            iconRes = R.drawable.ic_app_web,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#10B981"
        ),
        WebAppItem(
            id = "maps",
            name = "Google Maps",
            url = "https://www.google.com/maps",
            iconRes = R.drawable.ic_tab_globe,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#3B82F6"
        ),
        WebAppItem(
            id = "zing",
            name = "Zing MP3",
            url = "https://zingmp3.vn",
            iconRes = R.drawable.ic_app_zing,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#8B5CF6"
        ),
        WebAppItem(
            id = "spotify",
            name = "Spotify",
            url = "https://open.spotify.com",
            iconRes = R.drawable.ic_app_spotify,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#1DB954"
        ),
        WebAppItem(
            id = "tv360",
            name = "TV360 Trực tiếp",
            url = "https://tv360.vn",
            iconRes = R.drawable.ic_app_tv360,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#EF4444"
        ),
        WebAppItem(
            id = "vtvgo",
            name = "VTV Go",
            url = "https://vtvgo.vn",
            iconRes = R.drawable.ic_app_vtvgo,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#DC2626"
        ),
        WebAppItem(
            id = "tiktok",
            name = "TikTok",
            url = "https://www.tiktok.com",
            iconRes = R.drawable.ic_app_tiktok,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#111827"
        ),
        WebAppItem(
            id = "news",
            name = "Báo Mới",
            url = "https://baomoi.com",
            iconRes = R.drawable.ic_app_news,
            isDesktop = false,
            isBuiltIn = true,
            colorHex = "#0EA5E9"
        )
    )

    fun getCategoryName(appId: String): String {
        return when (appId) {
            "youtube" -> "Video & Nhạc"
            "iptv" -> "Truyền hình HD"
            "web" -> "Tra cứu mạng"
            "maps" -> "Bản đồ dẫn đường"
            "zing" -> "BXH Nhạc Việt"
            "spotify" -> "Nhạc trực tuyến"
            "tv360" -> "Bóng đá & TV"
            "vtvgo" -> "Kênh quốc gia"
            "tiktok" -> "Video ngắn"
            "news" -> "Điểm tin 24/7"
            else -> "Ứng dụng Web"
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAppDesktop(context: Context, appId: String): Boolean {
        val prefs = getPrefs(context)
        val key = KEY_APP_DESKTOP_PREFIX + appId
        if (prefs.contains(key)) {
            return prefs.getBoolean(key, false)
        }
        val app = getAllApps(context).find { it.id == appId }
        return app?.isDesktop ?: false
    }

    fun setAppDesktop(context: Context, appId: String, isDesktop: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_APP_DESKTOP_PREFIX + appId, isDesktop).apply()
    }

    fun getAllApps(context: Context): List<WebAppItem> {
        val deleted = getPrefs(context).getStringSet(KEY_DELETED_APPS, emptySet()) ?: emptySet()
        val list = mutableListOf<WebAppItem>()
        list.addAll(DEFAULT_APPS.filter { !deleted.contains(it.id) })
        list.addAll(getCustomApps(context).filter { !deleted.contains(it.id) })
        return list
    }

    fun isAppEnabled(context: Context, appId: String): Boolean {
        val disabled = getPrefs(context).getStringSet(KEY_DISABLED_APPS, emptySet()) ?: emptySet()
        return !disabled.contains(appId)
    }

    fun setAppEnabled(context: Context, appId: String, enabled: Boolean) {
        val prefs = getPrefs(context)
        val disabled = (prefs.getStringSet(KEY_DISABLED_APPS, emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) {
            disabled.remove(appId)
        } else {
            disabled.add(appId)
        }
        prefs.edit().putStringSet(KEY_DISABLED_APPS, disabled).apply()
    }

    fun getEnabledApps(context: Context): List<WebAppItem> {
        val all = getAllApps(context)
        val filtered = all.filter { isAppEnabled(context, it.id) }
        return if (filtered.isEmpty()) listOf(all.firstOrNull() ?: DEFAULT_APPS.first()) else filtered
    }

    fun getActiveAppId(context: Context): String {
        return getPrefs(context).getString(KEY_ACTIVE_APP_ID, "youtube") ?: "youtube"
    }

    fun setActiveAppId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_APP_ID, id).apply()
    }

    fun getActiveApp(context: Context): WebAppItem {
        val activeId = getActiveAppId(context)
        val resolvedId = if (activeId == "tv") "iptv" else activeId
        return getAllApps(context).find { it.id == resolvedId } ?: DEFAULT_APPS.first()
    }

    fun getCustomApps(context: Context): List<WebAppItem> {
        val jsonStr = getPrefs(context).getString(KEY_CUSTOM_APPS, null) ?: return emptyList()
        val result = mutableListOf<WebAppItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    WebAppItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        iconRes = R.drawable.ic_app_web,
                        iconPath = obj.optString("iconPath", "").takeIf { it.isNotBlank() },
                        isDesktop = obj.optBoolean("isDesktop", false),
                        isBuiltIn = false,
                        colorHex = obj.optString("colorHex", "#2D9CDB")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun addCustomApp(context: Context, name: String, url: String, isDesktop: Boolean = false, iconPath: String? = null): WebAppItem {
        val id = "custom_" + System.currentTimeMillis()
        val cleanUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val newItem = WebAppItem(
            id = id,
            name = name.trim().ifEmpty { "Web App" },
            url = cleanUrl,
            iconRes = R.drawable.ic_app_web,
            iconPath = iconPath,
            isDesktop = isDesktop,
            isBuiltIn = false,
            colorHex = "#2D9CDB"
        )
        val current = getCustomApps(context).toMutableList()
        current.add(newItem)
        saveCustomApps(context, current)
        return newItem
    }

    fun removeCustomApp(context: Context, id: String) {
        val current = getCustomApps(context).filter { it.id != id }
        saveCustomApps(context, current)
    }

    fun deleteApp(context: Context, id: String) {
        val prefs = getPrefs(context)
        val deleted = (prefs.getStringSet(KEY_DELETED_APPS, emptySet()) ?: emptySet()).toMutableSet()
        deleted.add(id)
        prefs.edit().putStringSet(KEY_DELETED_APPS, deleted).apply()

        setAppEnabled(context, id, false)
        removeCustomApp(context, id)
    }

    fun restoreDefaultApps(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_DISABLED_APPS)
            .remove(KEY_DELETED_APPS)
            .apply()
    }

    private fun saveCustomApps(context: Context, apps: List<WebAppItem>) {
        val arr = JSONArray()
        for (app in apps) {
            val obj = JSONObject().apply {
                put("id", app.id)
                put("name", app.name)
                put("url", app.url)
                put("isDesktop", app.isDesktop)
                put("colorHex", app.colorHex)
                app.iconPath?.let { put("iconPath", it) }
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_CUSTOM_APPS, arr.toString()).apply()
    }
}


/**
 * Provides ultra-responsive touch feedback:
 * - Instant visual down-scale bounce (0.90x on ACTION_DOWN, 1.0f on ACTION_UP/CANCEL)
 * - Micro haptic feedback (KEYBOARD_TAP) on down press
 * - Non-blocking: returns false so OnClickListener and OnLongClickListener still trigger perfectly.
 */
@android.annotation.SuppressLint("ClickableViewAccessibility")
fun android.view.View.enableTouchBounce(scaleDown: Float = 0.90f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(scaleDown).scaleY(scaleDown).setDuration(60).start()
                try {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                } catch (e: Exception) {}
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
            }
        }
        false
    }
}

