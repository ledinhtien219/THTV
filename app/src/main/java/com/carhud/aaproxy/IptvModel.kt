package com.carhud.aaproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class IptvChannel(
    val id: String,
    val name: String,
    val logoUrl: String,
    val groupTitle: String,
    val streamUrl: String
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("logoUrl", logoUrl)
            put("groupTitle", groupTitle)
            put("streamUrl", streamUrl)
        }
    }
}

object IptvManager {
    private const val TAG = "IptvManager"
    const val PREF_IPTV_URL = "custom_iptv_m3u_url"
    const val PREF_IPTV_IS_FILE = "custom_iptv_is_local_file"
    const val PREF_IPTV_FILE_NAME = "custom_iptv_file_name"
    const val DEFAULT_IPTV_URL = "https://raw.githubusercontent.com/khanh71/All-In-One-IPTV/main/http-iptv.m3u"
    const val LOCAL_M3U_FILE = "custom_playlist.m3u"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cachedChannels: List<IptvChannel> = emptyList()
    private var isFetching = false

    fun isUsingLocalFile(context: Context): Boolean {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_IPTV_IS_FILE, false) && File(context.filesDir, LOCAL_M3U_FILE).exists()
    }

    fun getLocalFileName(context: Context): String? {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(PREF_IPTV_FILE_NAME, null)
    }

    fun getM3uUrl(context: Context): String {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        if (isUsingLocalFile(context)) {
            val name = getLocalFileName(context) ?: "playlist.m3u"
            return "File: $name"
        }
        return prefs.getString(PREF_IPTV_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_IPTV_URL
    }

    fun setM3uUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_IPTV_URL, url.trim())
            .putBoolean(PREF_IPTV_IS_FILE, false)
            .apply()
        cachedChannels = emptyList()
        try {
            File(context.cacheDir, "iptv_channels.json").delete()
        } catch (e: Exception) {}
    }

    fun setM3uFile(context: Context, inputStream: java.io.InputStream, displayName: String) {
        try {
            val targetFile = File(context.filesDir, LOCAL_M3U_FILE)
            targetFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
            val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(PREF_IPTV_IS_FILE, true)
                .putString(PREF_IPTV_FILE_NAME, displayName)
                .apply()
            val parsed = BufferedReader(InputStreamReader(targetFile.inputStream(), "UTF-8")).use { parseM3uStream(it) }
            if (parsed.isNotEmpty()) {
                cachedChannels = parsed
                saveToDisk(context, parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save local M3U file", e)
        }
    }

    fun setM3uContent(context: Context, content: String, displayName: String) {
        try {
            val targetFile = File(context.filesDir, LOCAL_M3U_FILE)
            targetFile.writeText(content, Charsets.UTF_8)
            val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(PREF_IPTV_IS_FILE, true)
                .putString(PREF_IPTV_FILE_NAME, displayName)
                .apply()
            val parsed = BufferedReader(InputStreamReader(targetFile.inputStream(), "UTF-8")).use { parseM3uStream(it) }
            if (parsed.isNotEmpty()) {
                cachedChannels = parsed
                saveToDisk(context, parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save M3U content", e)
        }
    }

    fun clearLocalFile(context: Context) {
        val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_IPTV_IS_FILE, false).apply()
        cachedChannels = emptyList()
        try {
            File(context.cacheDir, "iptv_channels.json").delete()
        } catch (e: Exception) {}
    }

    fun getCachedChannelsList(context: Context): List<IptvChannel> {
        if (cachedChannels.isNotEmpty()) {
            return cachedChannels
        }
        val disk = loadFromDisk(context)
        if (disk.isNotEmpty()) {
            cachedChannels = disk
            return disk
        }
        val parsed = parseLocalOrDefault(context)
        if (parsed.isNotEmpty()) {
            cachedChannels = parsed
            saveToDisk(context, parsed)
            return parsed
        }
        return emptyList()
    }

    internal fun invalidateCache(context: Context) {
        cachedChannels = emptyList()
        File(context.cacheDir, "iptv_channels.json").delete()
    }

    fun getCachedJson(context: Context): String {
        val list = getCachedChannelsList(context)
        val arr = JSONArray()
        for (ch in list) arr.put(ch.toJson())
        return arr.toString()
    }

    private fun parseLocalOrDefault(context: Context): List<IptvChannel> {
        try {
            val isLocal = isUsingLocalFile(context)
            val localFile = File(context.filesDir, LOCAL_M3U_FILE)
            if (isLocal && localFile.exists()) {
                val r = BufferedReader(InputStreamReader(localFile.inputStream(), "UTF-8"))
                val list = parseM3uStream(r)
                if (list.isNotEmpty()) return list
            }
            context.assets.open("default_channels.m3u").use { stream ->
                val r = BufferedReader(InputStreamReader(stream, "UTF-8"))
                return parseM3uStream(r)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed parseLocalOrDefault", e)
            return emptyList()
        }
    }

    fun refresh(context: Context, callback: ((List<IptvChannel>) -> Unit)? = null) {
        cachedChannels = emptyList()
        try {
            File(context.cacheDir, "iptv_channels.json").delete()
        } catch (e: Exception) {}
        executor.execute {
            fetchChannels(context) { list ->
                if (list.isNotEmpty()) {
                    cachedChannels = list
                    saveToDisk(context, list)
                }
                mainHandler.post { callback?.invoke(list) }
            }
        }
    }

    fun getChannels(context: Context, callback: (List<IptvChannel>) -> Unit) {
        if (cachedChannels.isNotEmpty()) {
            mainHandler.post { callback(cachedChannels) }
            return
        }

        executor.execute {
            val diskCache = loadFromDisk(context)
            if (diskCache.isNotEmpty()) {
                cachedChannels = diskCache
                mainHandler.post { callback(cachedChannels) }
            }

            fetchChannels(context) { freshList ->
                if (freshList.isNotEmpty()) {
                    cachedChannels = freshList
                    saveToDisk(context, freshList)
                    mainHandler.post { callback(freshList) }
                } else if (diskCache.isEmpty()) {
                    val fallback = parseLocalOrDefault(context)
                    if (fallback.isNotEmpty()) {
                        cachedChannels = fallback
                        saveToDisk(context, fallback)
                        mainHandler.post { callback(fallback) }
                    } else {
                        mainHandler.post { callback(emptyList()) }
                    }
                }
            }
        }
    }

    private fun fetchChannels(context: Context, onComplete: (List<IptvChannel>) -> Unit) {
        if (isFetching) return
        isFetching = true
        try {
            val isLocal = isUsingLocalFile(context)
            val localFile = File(context.filesDir, LOCAL_M3U_FILE)

            val parsedList = if (isLocal && localFile.exists()) {
                BufferedReader(InputStreamReader(localFile.inputStream(), "UTF-8")).use { parseM3uStream(it) }
            } else {
                val targetUrl = getM3uUrl(context)
                try {
                    val url = URL(targetUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 12000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        instanceFollowRedirects = true
                    }
                    val res = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { parseM3uStream(it) }
                    if (res.isNotEmpty()) res else parseLocalOrDefault(context)
                } catch (ex: Exception) {
                    Log.w(TAG, "Network fetch failed, fallback to local/asset: ${ex.message}")
                    parseLocalOrDefault(context)
                }
            }

            isFetching = false
            onComplete(parsedList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching IPTV m3u", e)
            isFetching = false
            onComplete(parseLocalOrDefault(context))
        }
    }

    fun parseM3uStream(reader: BufferedReader): List<IptvChannel> {
        val parsed = mutableListOf<IptvChannel>()
        reader.use { r ->
            var line: String? = r.readLine()
            var currentName = ""
            var currentLogo = ""
            var currentGroup = "Truyền Hình"
            var currentTvgId = ""
            var currentId = 0

            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(trimmed)
                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""

                    val groupMatch = Regex("""group-title="([^"]+)"""").find(trimmed)
                    currentGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "Truyền Hình"

                    val idMatch = Regex("""tvg-id="([^"]+)"""").find(trimmed)
                    currentTvgId = idMatch?.groupValues?.get(1)?.trim() ?: ""

                    val commaIdx = trimmed.lastIndexOf(',')
                    currentName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                        trimmed.substring(commaIdx + 1).trim()
                    } else {
                        "Kênh ${currentId + 1}"
                    }
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    if (!currentLogo.contains("info_card.php") && !trimmed.endsWith(".jpg") && !trimmed.endsWith(".png")) {
                        currentId++
                        val chId = if (currentTvgId.isNotBlank()) currentTvgId else "iptv_${currentId}"
                        parsed.add(
                            IptvChannel(
                                id = chId,
                                name = currentName,
                                logoUrl = currentLogo,
                                groupTitle = currentGroup,
                                streamUrl = trimmed
                            )
                        )
                        currentTvgId = ""
                    }
                }
                line = r.readLine()
            }
        }
        return parsed
    }

    private fun saveToDisk(context: Context, list: List<IptvChannel>) {
        try {
            val file = File(context.cacheDir, "iptv_channels.json")
            val arr = JSONArray()
            for (ch in list) {
                arr.put(ch.toJson())
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save IPTV to disk", e)
        }
    }

    private fun loadFromDisk(context: Context): List<IptvChannel> {
        try {
            val file = File(context.cacheDir, "iptv_channels.json")
            if (!file.exists()) return emptyList()
            val text = file.readText()
            val arr = JSONArray(text)
            val list = mutableListOf<IptvChannel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    IptvChannel(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        logoUrl = obj.optString("logoUrl", ""),
                        groupTitle = obj.optString("groupTitle", "Truyền Hình"),
                        streamUrl = obj.getString("streamUrl")
                    )
                )
            }
            return list
        } catch (e: Exception) {
            return emptyList()
        }
    }
}
