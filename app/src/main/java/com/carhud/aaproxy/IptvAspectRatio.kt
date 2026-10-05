package com.carhud.aaproxy

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView

/** IPTV's selection survives reloads and is independent from YouTube. */
internal object IptvAspectRatio {
    const val PREF = "iptv_video_aspect_mode"
    private val modes = setOf("fill", "contain", "cover", "4:3", "21:9")
    fun attach(web: WebView) {
        val prefs = web.context.applicationContext.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        web.addJavascriptInterface(object {
            @JavascriptInterface fun get(): String? = prefs.getString(PREF, null)?.takeIf { it in modes }
            @JavascriptInterface fun save(mode: String) {
                if (mode in modes && prefs.getString(PREF, null) != mode) prefs.edit().putString(PREF, mode).apply()
            }
        }, "ThtvIptvAspect")
    }
}
