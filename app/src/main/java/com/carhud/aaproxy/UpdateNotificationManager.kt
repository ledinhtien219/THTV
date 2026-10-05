package com.carhud.aaproxy

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenResumed
import com.carhud.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Public release metadata; never sends license/device information. */
object UpdateNotificationManager {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/ledinhtien219/THTV/main/update.json"
    private const val SEEN = "update_notified_builds"
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private var checking = false
    private var showing = false

    fun check(activity: AppCompatActivity, manual: Boolean = false) {
        if (checking || showing || activity.isFinishing || activity.isDestroyed) return
        checking = true
        activity.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(MANIFEST_URL)
                        .header("Cache-Control", "no-cache").build()).execute().use { response ->
                        check(response.isSuccessful) { "HTTP ${response.code}" }
                        val body = response.body ?: error("Empty response")
                        val source = body.source()
                        require(!source.request(64 * 1024L + 1)) { "Metadata too large" }
                        val text = source.readUtf8()
                        val json = JSONObject(text)
                        val code = json.getInt("versionCode")
                        val name = json.getString("versionName").trim()
                        val url = json.getString("downloadUrl")
                        val uri = Uri.parse(url)
                        require(code > 0 && name.isNotEmpty() && name.length <= 40)
                        require(uri.scheme == "https" && uri.host == "github.com" &&
                            uri.path?.startsWith("/ledinhtien219/THTV/releases/download/") == true)
                        val notes = json.getJSONArray("changelog")
                        require(notes.length() in 1..30)
                        val features = (0 until notes.length()).map { notes.getString(it).trim() }
                        require(features.all { it.isNotEmpty() && it.length <= 2000 })
                        RemoteRelease(code, name, url, features)
                    }
                }
                activity.lifecycle.whenResumed {
                    if (activity.isFinishing || activity.isDestroyed) return@whenResumed
                    val prefs = activity.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                    val seen = prefs.getStringSet(SEEN, emptySet()).orEmpty()
                    if (release.code <= BuildConfig.VERSION_CODE) {
                        if (manual) toast(activity, "Bạn đang dùng phiên bản mới nhất: v${BuildConfig.VERSION_NAME}.")
                    } else if (UpdateVersionPolicy.shouldNotify(BuildConfig.VERSION_CODE, release.code, seen, manual)) {
                        val padding = (20 * activity.resources.displayMetrics.density).toInt()
                        val notes = TextView(activity).apply {
                            text = "Phiên bản đang dùng: v${BuildConfig.VERSION_NAME}\n\n" +
                                release.features.joinToString("\n\n") { "• $it" }
                            textSize = 16f
                            setPadding(padding, padding, padding, padding)
                        }
                        val dialog = AlertDialog.Builder(activity)
                            .setTitle("Có bản cập nhật mới: v${release.name}")
                            .setView(ScrollView(activity).apply { addView(notes) })
                            .setNegativeButton("Để sau", null)
                            .setPositiveButton("Cập nhật") { _, _ ->
                                try {
                                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                                } catch (_: ActivityNotFoundException) {
                                    toast(activity, "Không tìm thấy trình duyệt để tải APK.")
                                }
                            }.create()
                        dialog.setOnDismissListener { showing = false }
                        dialog.show()
                        showing = true
                        // Persist only after the notification actually appears, including Back/Để sau.
                        prefs.edit().putStringSet(SEEN, seen + release.code.toString()).apply()
                        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                                dialog.dismiss()
                                owner.lifecycle.removeObserver(this)
                            }
                        })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (manual && !activity.isFinishing && !activity.isDestroyed)
                    toast(activity, "Chưa kiểm tra được cập nhật. Vui lòng thử lại khi có mạng.")
            } finally {
                checking = false
            }
        }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    private data class RemoteRelease(val code: Int, val name: String, val url: String, val features: List<String>)
}
