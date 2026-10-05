package com.carhud.aaproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.carhud.app.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Remote update checker backed by the same Google Apps Script used for licensing.
 *
 * No APK is silently installed. When a newer version is available the user gets
 * an in-app release-note dialog and may open the configured download page.
 */
object AppUpdateManager {

    private const val PREFS = "app_update_manager"
    private const val KEY_LAST_PROMPT_VERSION = "last_prompt_version"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val SNOOZE_MS = 24L * 60L * 60L * 1000L

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    data class UpdateInfo(
        val latestVersionName: String,
        val latestVersionCode: Int,
        val title: String,
        val notes: List<String>,
        val updateUrl: String,
        val mandatory: Boolean
    )

    fun checkForUpdate(activity: Activity, force: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now < prefs.getLong(KEY_SNOOZE_UNTIL, 0L)) return

        val baseUrl = LicenseConfig.getApiUrl(activity)
        if (baseUrl.isBlank()) return

        val sep = if (baseUrl.contains("?")) "&" else "?"
        val url = baseUrl +
            sep +
            "action=update_info" +
            "&versionCode=" + BuildConfig.VERSION_CODE +
            "&versionName=" + Uri.encode(BuildConfig.VERSION_NAME)

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-cache")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return
                    val body = it.body?.string().orEmpty()
                    if (body.isBlank()) return

                    val info = try {
                        parseInfo(JSONObject(body))
                    } catch (_: Throwable) {
                        null
                    } ?: return

                    if (info.latestVersionCode <= BuildConfig.VERSION_CODE) return

                    val lastPrompted = prefs.getInt(KEY_LAST_PROMPT_VERSION, 0)
                    if (!force && lastPrompted == info.latestVersionCode) return

                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        showUpdateDialog(activity, info)
                    }
                }
            }
        })
    }

    private fun parseInfo(json: JSONObject): UpdateInfo? {
        if (!json.optBoolean("success", false)) return null

        val versionCode = json.optInt("latestVersionCode", 0)
        if (versionCode <= 0) return null

        val notesJson = json.optJSONArray("notes")
        val notes = buildList {
            if (notesJson != null) {
                for (i in 0 until notesJson.length()) {
                    val item = notesJson.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }
        }

        return UpdateInfo(
            latestVersionName = json.optString("latestVersionName", "").ifBlank { versionCode.toString() },
            latestVersionCode = versionCode,
            title = json.optString("title", "").ifBlank {
                "THTV v${json.optString("latestVersionName", versionCode.toString())} đã có"
            },
            notes = notes,
            updateUrl = json.optString("updateUrl", "").trim(),
            mandatory = json.optBoolean("mandatory", false)
        )
    }

    private fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        var dialog: AlertDialog? = null
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            background = rounded(
                Color.parseColor("#0F172A"),
                18f,
                Color.parseColor("#38BDF8"),
                2,
                activity
            )
        }

        val title = TextView(activity).apply {
            text = "🚀 ${info.title}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val current = TextView(activity).apply {
            text = "Đang dùng v${BuildConfig.VERSION_NAME} • Bản mới v${info.latestVersionName}"
            textSize = 11.5f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(activity, 5), 0, dp(activity, 12))
        }
        root.addView(current)

        val section = TextView(activity).apply {
            text = "NỘI DUNG MỚI"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(activity, 5))
        }
        root.addView(section)

        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val notesBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            if (info.notes.isEmpty()) {
                addView(TextView(activity).apply {
                    text = "• Có phiên bản THTV mới với các cải tiến và sửa lỗi."
                    textSize = 13f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setPadding(dp(activity, 4), dp(activity, 5), dp(activity, 4), dp(activity, 5))
                })
            } else {
                info.notes.forEach { note ->
                    addView(TextView(activity).apply {
                        text = if (note.startsWith("•")) note else "• $note"
                        textSize = 13f
                        setTextColor(Color.parseColor("#E2E8F0"))
                        setLineSpacing(0f, 1.12f)
                        setPadding(dp(activity, 4), dp(activity, 5), dp(activity, 4), dp(activity, 5))
                    })
                }
            }
        }
        scroll.addView(notesBox)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                weight = 1f
                bottomMargin = dp(activity, 14)
            }
        )

        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (!info.mandatory) {
            val later = TextView(activity).apply {
                text = "Để sau"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#CBD5E1"))
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                background = rounded(
                    Color.parseColor("#1E293B"),
                    11f,
                    Color.parseColor("#475569"),
                    1,
                    activity
                )
                setOnClickListener {
                    prefs.edit()
                        .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
                        .apply()
                    dialog?.dismiss()
                }
            }
            buttons.addView(
                later,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(activity, 8)
                }
            )
        }

        val update = TextView(activity).apply {
            text = "⬆ CẬP NHẬT"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            background = rounded(
                Color.parseColor("#0284C7"),
                11f,
                Color.parseColor("#38BDF8"),
                1,
                activity
            )
            setOnClickListener {
                prefs.edit()
                    .putInt(KEY_LAST_PROMPT_VERSION, info.latestVersionCode)
                    .putLong(KEY_SNOOZE_UNTIL, 0L)
                    .apply()

                if (info.updateUrl.isNotBlank()) {
                    try {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(info.updateUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                    }
                }
                dialog?.dismiss()
            }
        }
        buttons.addView(
            update,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(buttons)

        dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(!info.mandatory)
            .create()
        dialog?.setOnDismissListener {
            // A mandatory dialog should return on next app open until updated.
            if (!info.mandatory) {
                prefs.edit().putInt(KEY_LAST_PROMPT_VERSION, info.latestVersionCode).apply()
            }
        }
        dialog?.show()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
        )
    }

    private fun rounded(
        fill: Int,
        radiusDp: Float,
        stroke: Int,
        strokeDp: Int,
        context: Context
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            if (strokeDp > 0) {
                setStroke(dp(context, strokeDp), stroke)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
