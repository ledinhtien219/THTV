package com.carhud.aaproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.carhud.app.BuildConfig

object ChangelogManager {

    private const val PREF_LAST_SEEN_VERSION = "pref_last_seen_changelog_version"

    data class ReleaseNote(
        val versionName: String,
        val releaseDate: String,
        val features: List<String>
    )

    val RELEASE_NOTES = listOf(
        ReleaseNote(
            versionName = "1.0.19",
            releaseDate = "Mới nhất",
            features = listOf(
                "🧭 Đồng bộ nhật ký phiên bản: Màn hình cập nhật hiển thị đúng versionName/versionCode hiện tại, không còn lệch sang bản cũ.",
                "🪪 Ổn định mã máy kích hoạt: Mã thiết bị không còn phụ thuộc chữ ký APK; giữ ổn định giữa các bản cập nhật tiếp theo.",
                "🔐 Cài đè ổn định: GitHub Actions dùng signing key cố định để các bản từ 1.0.17 trở đi cập nhật trực tiếp.",
                "🫧 HUD Waze gọn hơn: Toàn bộ mẫu bong bóng dùng ô cảnh báo icon + khoảng cách, phù hợp Android Auto."
            )
        ),
        ReleaseNote(
            versionName = "1.0.18",
            releaseDate = "Trước đó",
            features = listOf(
                "🪪 Nâng cấp mã thiết bị: Chuyển khỏi ANDROID_ID bị phụ thuộc chữ ký APK sang mã nhận dạng phần cứng ổn định hơn.",
                "♻️ Migration bản quyền: Tự chuyển chữ ký license cũ sang mã máy mới khi dữ liệu kích hoạt cũ còn hợp lệ.",
                "🧹 Dọn cài đặt Waze: Bỏ quyền đọc thông báo, tiếng Ting, TTS thử nghiệm và các nút mô phỏng cảnh báo không còn dùng."
            )
        ),
        ReleaseNote(
            versionName = "1.0.17",
            releaseDate = "Trước đó",
            features = listOf(
                "🚘 Làm lại bong bóng HUD Waze: Giữ HUD nhỏ gọn, cảnh báo hiện bằng icon lớn + khoảng cách ngay bên phải.",
                "📷 Bổ sung icon rõ hơn cho camera tốc độ, camera đèn đỏ, đèn giao thông và các cảnh báo HLP.",
                "🛠 Tối ưu toàn bộ 5 kiểu HUD có sẵn để dùng chung logic cảnh báo mới."
            )
        )
    )

    fun checkAndShowChangelog(activity: Activity, forceShow: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return

        val prefs = activity.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
        val lastSeenCode = prefs.getInt(PREF_LAST_SEEN_VERSION, 0)
        val currentCode = BuildConfig.VERSION_CODE

        if (forceShow || lastSeenCode < currentCode) {
            showChangelogDialog(activity)
            prefs.edit().putInt(PREF_LAST_SEEN_VERSION, currentCode).apply()
        }
    }

    fun showChangelogDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        var dialog: AlertDialog? = null

        val rootContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 18))
            background = rounded(Color.parseColor("#0F172A"), 18f, Color.parseColor("#38BDF8"), 2)
        }

        // Header Title
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(activity, 12)
            }

            val icon = TextView(activity).apply {
                text = "✨"
                textSize = 22f
                setPadding(0, 0, dp(activity, 8), 0)
            }
            addView(icon)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val title = TextView(activity).apply {
                    text = "NHẬT KÝ CẬP NHẬT THTV PRO"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                addView(title)

                val sub = TextView(activity).apply {
                    text = "Phiên bản v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#38BDF8"))
                }
                addView(sub)
            }
            addView(textCol)
        }
        rootContainer.addView(headerRow)

        // Scrollable Notes
        val scrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(activity, 14)
            }
        }

        val notesList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        for (note in RELEASE_NOTES) {
            val verHeader = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
                background = rounded(Color.parseColor("#1E293B"), 8f, Color.parseColor("#334155"), 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(activity, 8)
                    bottomMargin = dp(activity, 8)
                }

                val verBadge = TextView(activity).apply {
                    text = "v${note.versionName}"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#38BDF8"))
                }
                addView(verBadge)

                val dateTag = TextView(activity).apply {
                    text = " • ${note.releaseDate}"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                addView(dateTag)
            }
            notesList.addView(verHeader)

            for (feature in note.features) {
                val item = TextView(activity).apply {
                    text = feature
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setPadding(dp(activity, 6), dp(activity, 4), dp(activity, 6), dp(activity, 4))
                    setLineSpacing(0f, 1.15f)
                }
                notesList.addView(item)
            }
        }

        scrollView.addView(notesList)
        rootContainer.addView(scrollView)

        // Dismiss Button
        val closeBtn = TextView(activity).apply {
            text = "🚀 ĐÃ HIỂU & BẮT ĐẦU"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10))
            background = rounded(Color.parseColor("#0284C7"), 12f, Color.parseColor("#38BDF8"), 1)
            setOnClickListener {
                dialog?.dismiss()
            }
        }
        rootContainer.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(activity)
            .setView(rootContainer)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusDp * 2.5f
            if (strokeDp > 0) {
                setStroke(strokeDp, stroke)
            }
        }
    }

    private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
