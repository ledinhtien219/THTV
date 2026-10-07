package com.carhud.aaproxy

import com.carhud.app.BuildConfig
import com.carhud.app.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "carhud_settings"
        const val KEY_KEEP_SCREEN = "keep_screen_on"
        const val KEY_BACKGROUND_AUDIO = "background_audio_enabled"
        const val KEY_AUDIO_DUCKING = "audio_ducking_enabled"
        const val KEY_AUTO_FULLSCREEN = "auto_fullscreen_enabled"
        const val KEY_AUTO_DETECT_SCREEN = "auto_detect_screen_layout"
        const val KEY_AUTO_RESUME_LAST_TRACK = "auto_resume_last_track"
        const val KEY_DEFAULT_TO_DASHBOARD = "default_to_dashboard"
        const val KEY_LAST_PLAYED_URL = "last_played_url"
        const val KEY_DESKTOP_MODE = "desktop_mode_enabled"
        const val KEY_BROWSER_ADBLOCK = "browser_adblock_enabled"

        // Toolbar Configuration
        const val KEY_TOOLBAR_POSITION = "toolbar_position" // "auto", "left", "right", "bottom"
        const val KEY_TOOLBAR_SCALE = "toolbar_scale" // 50 to 150 (percentage)
        const val KEY_WEBAPP_TOOLBAR_POSITION = "webapp_toolbar_position" // "left", "top"
        const val KEY_DOCK_SCALE = "dock_scale" // 50 to 150 (percentage)

        // Button Toggles
        const val KEY_SHOW_BROWSER = "toolbar_btn_browser"
        const val KEY_SHOW_PLAYLIST_SCROLL = "toolbar_btn_playlist_scroll"
        const val KEY_SHOW_KEYBOARD_SEARCH = "toolbar_btn_search"
        const val KEY_SHOW_VOICE_SEARCH = "toolbar_btn_mic"
        const val KEY_SHOW_HOME = "toolbar_btn_home"
        const val KEY_SHOW_TV = "toolbar_btn_tv"
        const val KEY_SHOW_DAY_NIGHT = "toolbar_btn_day_night"
        const val KEY_SHOW_BACK = "toolbar_btn_back"
        const val KEY_SHOW_PLAY_PAUSE = "toolbar_btn_play_pause"
        const val KEY_SHOW_NEXT = "toolbar_btn_next"
        const val KEY_SHOW_FULLSCREEN = "toolbar_btn_fullscreen"
        const val KEY_SHOW_SETTINGS = "toolbar_btn_settings"
        const val KEY_SHOW_TIME_PILL = "toolbar_btn_time_pill"

        // Steering Wheel Controls
        const val KEY_STEERING_VOICE_ENABLED = "steering_voice_enabled"
        const val KEY_STEERING_NEXT_ACTION = "steering_next_action" // "next", "voice"
        const val KEY_STEERING_PREV_ACTION = "steering_prev_action" // "prev", "voice"
        const val KEY_STEERING_DOUBLE_CLICK_VOICE = "steering_double_click_voice"
        const val KEY_STEERING_DOUBLE_PLAY_VOICE = "steering_double_play_voice"
        const val KEY_STEERING_DOUBLE_CLICK_SPEED = "steering_double_click_speed" // 300, 500, 700 ms

        // Theme Mode (Auto / Day / Night)
        const val KEY_THEME_MODE = "carhud_theme_mode" // "auto", "day", "night"
        const val THEME_AUTO = "auto"
        const val THEME_DAY = "day"
        const val THEME_NIGHT = "night"

        fun resolveIsDay(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_THEME_MODE, THEME_AUTO) ?: THEME_AUTO
            return when (mode) {
                THEME_DAY -> true
                THEME_NIGHT -> false
                THEME_AUTO -> {
                    val isCarNight = try {
                        if (context is androidx.car.app.CarContext) {
                            context.isDarkMode
                        } else {
                            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        }
                    } catch (e: Exception) {
                        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    }
                    !isCarNight
                }
                else -> prefs.getBoolean("carhud_day_mode", false)
            }
        }

        const val REQ_RECORD_AUDIO = 202

        val BG = Color.parseColor("#F1F5F9")
        val CARD = Color.parseColor("#FFFFFF")
        val BORDER = Color.parseColor("#E2E8F0")
        val TEAL = Color.parseColor("#0284C7")
        val TEAL_ACCENT = Color.parseColor("#0284C7")
        val MUTED = Color.parseColor("#64748B")
        val WHITE = Color.WHITE
    }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private var testDialog: androidx.appcompat.app.AlertDialog? = null
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = SettingsBackupManager.export(applicationContext)
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Không mở được file để lưu.")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(this@SettingsActivity, "Đã xuất cấu hình THTV.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) { showBackupError(e) }
        }
    }
    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val backup = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: error("Không mở được file cấu hình.")
                    stream.use { SettingsBackupManager.read(it) }
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Nạp cấu hình THTV?")
                    .setMessage("File gồm ${backup.count} tùy chọn và ${backup.favorites} kênh yêu thích.\n\nCác nhóm cài đặt trong file sẽ được khôi phục, gồm cả tùy chọn mặc định. Ảnh nền và biểu tượng tùy chỉnh cần chọn lại nếu chuyển điện thoại.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Nạp cấu hình") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { SettingsBackupManager.apply(applicationContext, backup) }
                                CarMediaManager.getActiveWebView()?.let { web ->
                                    if (web.url?.contains("iptv_player.html") == true) web.reload()
                                }
                                Toast.makeText(this@SettingsActivity, "Đã nạp cấu hình. Cài đặt và yêu thích đã được cập nhật.", Toast.LENGTH_LONG).show()
                                recreate()
                            } catch (e: Exception) { showBackupError(e) }
                        }
                    }.show()
            } catch (e: Exception) { showBackupError(e) }
        }
    }

    private fun showBackupError(error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Không thể xuất/nạp cấu hình")
            .setMessage(error.message ?: "Vui lòng kiểm tra file hoặc chọn vị trí lưu khác.")
            .setPositiveButton("Đóng", null).show()
    }
    private var testKeyInfoView: TextView? = null

    // Dynamic High-Contrast Theme Palette (Default Dark Cockpit)
    private var isDarkTheme = true
    private var colorBg = Color.parseColor("#090D16")
    private var colorCard = Color.parseColor("#151D2A")
    private var colorCardBorder = Color.parseColor("#222F43")
    private var colorTextPrimary = Color.parseColor("#F8FAFC")
    private var colorTextSecondary = Color.parseColor("#94A3B8")
    private var colorTextMuted = Color.parseColor("#64748B")
    private var colorAccent = Color.parseColor("#38BDF8")
    private var colorDivider = Color.parseColor("#1E293B")
    private var colorItemBg = Color.parseColor("#1A2434")
    private var colorItemBorder = Color.parseColor("#25364E")
    private var colorPillBg = Color.parseColor("#1E3A5F")
    private var colorPillText = Color.parseColor("#38BDF8")

    private fun updateThemeColors() {
        isDarkTheme = prefs.getBoolean("settings_dark_mode", true)
        if (isDarkTheme) {
            colorBg = Color.parseColor("#090D16")
            colorCard = Color.parseColor("#151D2A")
            colorCardBorder = Color.parseColor("#222F43")
            colorTextPrimary = Color.parseColor("#F8FAFC")
            colorTextSecondary = Color.parseColor("#94A3B8")
            colorTextMuted = Color.parseColor("#64748B")
            colorAccent = Color.parseColor("#38BDF8")
            colorDivider = Color.parseColor("#1E293B")
            colorItemBg = Color.parseColor("#1A2434")
            colorItemBorder = Color.parseColor("#25364E")
            colorPillBg = Color.parseColor("#1E3A5F")
            colorPillText = Color.parseColor("#38BDF8")
        } else {
            colorBg = Color.parseColor("#F1F5F9")
            colorCard = Color.parseColor("#FFFFFF")
            colorCardBorder = Color.parseColor("#E2E8F0")
            colorTextPrimary = Color.parseColor("#0F172A")
            colorTextSecondary = Color.parseColor("#475569")
            colorTextMuted = Color.parseColor("#94A3B8")
            colorAccent = Color.parseColor("#0284C7")
            colorDivider = Color.parseColor("#E2E8F0")
            colorItemBg = Color.parseColor("#F8FAFC")
            colorItemBorder = Color.parseColor("#E2E8F0")
            colorPillBg = Color.parseColor("#E0F2FE")
            colorPillText = Color.parseColor("#0284C7")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (testDialog?.isShowing == true && event.action == KeyEvent.ACTION_DOWN) {
            val keyName = when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST -> "Phím Voice / Micro (KEYCODE_VOICE_ASSIST)"
                KeyEvent.KEYCODE_SEARCH -> "Phím Search / Tìm kiếm (KEYCODE_SEARCH)"
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Phím Next / Chuyển bài (KEYCODE_MEDIA_NEXT)"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Phím Prev / Lùi bài (KEYCODE_MEDIA_PREVIOUS)"
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Phím Play/Pause (KEYCODE_MEDIA_PLAY_PAUSE)"
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Phím Play (KEYCODE_MEDIA_PLAY)"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Phím Pause (KEYCODE_MEDIA_PAUSE)"
                KeyEvent.KEYCODE_HEADSETHOOK -> "Phím Tai nghe/Vô lăng (KEYCODE_HEADSETHOOK)"
                KeyEvent.KEYCODE_VOLUME_UP -> "Phím Tăng âm lượng (KEYCODE_VOLUME_UP)"
                KeyEvent.KEYCODE_VOLUME_DOWN -> "Phím Giảm âm lượng (KEYCODE_VOLUME_DOWN)"
                KeyEvent.KEYCODE_VOLUME_MUTE -> "Phím Tắt tiếng (KEYCODE_VOLUME_MUTE)"
                KeyEvent.KEYCODE_CALL -> "Phím Gọi điện (KEYCODE_CALL)"
                KeyEvent.KEYCODE_ENDCALL -> "Phím Kết thúc cuộc gọi (KEYCODE_ENDCALL)"
                else -> "Mã phím: ${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})"
            }
            val linkedAction = when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> "⚡ Tác vụ: Mở tìm kiếm giọng nói (Micro)"
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    val act = prefs.getString(KEY_STEERING_NEXT_ACTION, "next")
                    if (act == "voice") "⚡ Tác vụ: Mở tìm kiếm giọng nói (Micro)" else "⚡ Tác vụ: Chuyển bài tiếp theo (Đúp bấm: Mở Micro)"
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    val act = prefs.getString(KEY_STEERING_PREV_ACTION, "prev")
                    if (act == "voice") "⚡ Tác vụ: Mở tìm kiếm giọng nói (Micro)" else "⚡ Tác vụ: Quay lại bài trước"
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "⚡ Tác vụ: Phát / Tạm dừng video (Đúp bấm: Mở Micro nếu bật)"
                else -> "⚡ Phím hệ thống xe đã nhận diện"
            }
            testKeyInfoView?.text = "✅ ĐÃ NHẬN DIỆN THÀNH CÔNG!\n\n🎛️ $keyName\n\n$linkedAction\n\n(Phím vô lăng của bạn đã được kết nối chuẩn 100%)"
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showSteeringKeyTesterDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(colorCard)
        }

        val hint = TextView(this).apply {
            text = "Hãy bấm bất kỳ nút nào trên vô lăng xe của bạn (Voice, Next, Prev, Play/Pause)..."
            textSize = 13f
            setTextColor(colorTextSecondary)
            setPadding(0, 0, 0, dp(14))
        }
        layout.addView(hint)

        testKeyInfoView = TextView(this).apply {
            text = "⏳ Đang chờ bạn bấm phím trên vô lăng xe..."
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = rounded(colorItemBg, 12f, colorItemBorder, 1)
        }
        layout.addView(testKeyInfoView)

        testDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kiểm tra & Liên kết phím vô lăng")
            .setView(layout)
            .setPositiveButton("Đóng", null)
            .show()
    }

    private var inAppFloatingHud: VietmapHudOverlay? = null

    private fun updateInAppFloatingHud(enabled: Boolean) {
        if (enabled) {
            if (inAppFloatingHud == null) {
                inAppFloatingHud = VietmapHudOverlay(this).apply {
                    applyHudConfig()
                    elevation = dp(24).toFloat()
                }
                val decor = window.decorView as? ViewGroup
                val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = dp(70)
                }
                decor?.addView(inAppFloatingHud, params)
            } else {
                inAppFloatingHud?.visibility = View.VISIBLE
                inAppFloatingHud?.applyHudConfig()
            }
        } else {
            inAppFloatingHud?.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(exitReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }

    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CarAppShutdownManager.ACTION_FULL_EXIT) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val exitFilter = IntentFilter(CarAppShutdownManager.ACTION_FULL_EXIT)
        ContextCompat.registerReceiver(this, exitReceiver, exitFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!LicenseManager.isLicensed(this)) {
            val intent = Intent(this, ActivationActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        WazeHlpWebSocketManager.start()
        updateThemeColors()
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDarkTheme
        setContentView(buildUi())

        if (WazeHudManager.isInAppPreviewEnabled(this)) {
            window.decorView.post {
                updateInAppFloatingHud(true)
            }
        }

        if (intent.getBooleanExtra("preview_dash", false)) {
            showDashboardPreview()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!LicenseManager.isLicensed(this)) {
            val intent = Intent(this, ActivationActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        // Kiểm tra nền để đồng bộ gói mới nhất từ máy chủ Google Sheet
        LicenseManager.checkStatus(this) { status, plan, expiry ->
            if (status == LicenseManager.STATUS_APPROVED) {
                // Tự động lưu và cập nhật
            }
        }
    }

    private fun showDashboardPreview() {
        val previewDialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            var currentMode = prefs.getString(KEY_THEME_MODE, THEME_AUTO) ?: THEME_AUTO
            var isDay = resolveIsDay(this@SettingsActivity)
            val container = FrameLayout(this@SettingsActivity).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val dash = CarDashboardView(
                this@SettingsActivity,
                onAppClick = { Toast.makeText(this@SettingsActivity, "Mở: ${it.name}", Toast.LENGTH_SHORT).show() },
                onAddAppClick = { Toast.makeText(this@SettingsActivity, "+ Thêm ứng dụng", Toast.LENGTH_SHORT).show() },
                onAllAppsClick = { Toast.makeText(this@SettingsActivity, "Tất cả ứng dụng", Toast.LENGTH_SHORT).show() },
                onBackClick = { dismiss() }
            )
            dash.applyDayNightMode(isDay)
            container.addView(dash)

            // Add VietmapHudOverlay on top of dashboard preview
            val hud = VietmapHudOverlay(this@SettingsActivity).apply {
                elevation = 120f
            }
            val (posX, posY) = WazeHudManager.getPosition(this@SettingsActivity)
            val styleId = WazeHudManager.getActiveStyleId(this@SettingsActivity)
            val hudLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                when (styleId) {
                    3 -> {
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        leftMargin = dp(6)
                    }
                    4 -> {
                        gravity = Gravity.END or Gravity.CENTER_VERTICAL
                        rightMargin = dp(6)
                    }
                    else -> {
                        gravity = Gravity.TOP or Gravity.START
                        val safeX = if (posX in 10..600) posX else 20
                        val safeY = if (posY in 8..260) posY else 10
                        leftMargin = dp(safeX)
                        topMargin = dp(safeY)
                    }
                }
            }
            hud.layoutParams = hudLp
            hud.applyHudConfig()
            container.addView(hud)

            // Floating Day/Night/Auto switch pill in preview dialog
            val dayToggleBtn = TextView(this@SettingsActivity).apply {
                text = when (currentMode) {
                    THEME_DAY -> "☀️ Chế độ Ngày (Sáng)"
                    THEME_NIGHT -> "🌙 Chế độ Đêm (Tối)"
                    else -> "🚗 Tự động (Cảm biến xe)"
                }
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDay) Color.parseColor("#1E2538") else Color.WHITE)
                background = rounded(
                    if (isDay) Color.parseColor("#EBF1F1FB") else Color.parseColor("#D924363F"),
                    14f,
                    if (isDay) Color.parseColor("#D2D4E3") else Color.parseColor("#384F59"),
                    1
                )
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(16)
                    marginEnd = dp(16)
                }
                setOnClickListener {
                    currentMode = when (currentMode) {
                        THEME_AUTO -> THEME_DAY
                        THEME_DAY -> THEME_NIGHT
                        THEME_NIGHT -> THEME_AUTO
                        else -> THEME_AUTO
                    }
                    prefs.edit().putString(KEY_THEME_MODE, currentMode).apply()
                    isDay = resolveIsDay(this@SettingsActivity)
                    prefs.edit().putBoolean("carhud_day_mode", isDay).apply()
                    dash.applyDayNightMode(isDay)
                    text = when (currentMode) {
                        THEME_DAY -> "☀️ Chế độ Ngày (Sáng)"
                        THEME_NIGHT -> "🌙 Chế độ Đêm (Tối)"
                        else -> "🚗 Tự động (Cảm biến xe)"
                    }
                    setTextColor(if (isDay) Color.parseColor("#1E2538") else Color.WHITE)
                    background = rounded(
                        if (isDay) Color.parseColor("#EBF1F1FB") else Color.parseColor("#D924363F"),
                        14f,
                        if (isDay) Color.parseColor("#D2D4E3") else Color.parseColor("#384F59"),
                        1
                    )
                }
            }
            container.addView(dayToggleBtn)

            setContentView(container)
        }
        previewDialog.show()
    }

    private fun buildUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBg)
            setPadding(dp(16), dp(14), dp(16), dp(24))
            fitsSystemWindows = true
        }

        // ==========================================
        // TOP APP HEADER (TITLE, SUBTITLE & THEME TOGGLE)
        // ==========================================
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))

            val textLayout = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                val titleRow = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val title = TextView(this@SettingsActivity).apply {
                        text = "THTV Pro"
                        textSize = 21f
                        setTextColor(colorTextPrimary)
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    addView(title)

                    val versionBadge = TextView(this@SettingsActivity).apply {
                        text = "v${BuildConfig.VERSION_NAME}"
                        textSize = 11f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorAccent)
                        background = rounded(colorPillBg, 8f, colorAccent, 1)
                        setPadding(dp(7), dp(2), dp(7), dp(2))
                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dp(8)
                        }
                        layoutParams = lp
                    }
                    addView(versionBadge)
                }
                addView(titleRow)

                val subtitle = TextView(this@SettingsActivity).apply {
                    text = "Trung tâm cài đặt nâng cao buồng lái & ô tô"
                    textSize = 12f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(2), 0, 0)
                }
                addView(subtitle)
            }
            addView(textLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // Quick Theme Toggle Button (Cycles: 🚗 Tự động -> ☀️ Sáng -> 🌙 Tối)
            val currentMode = prefs.getString(KEY_THEME_MODE, THEME_AUTO) ?: THEME_AUTO
            val themeBtn = TextView(this@SettingsActivity).apply {
                val isDay = resolveIsDay(this@SettingsActivity)
                text = when (currentMode) {
                    THEME_AUTO -> "🚗 Tự động"
                    THEME_DAY -> "☀️ Sáng"
                    THEME_NIGHT -> "🌙 Tối"
                    else -> if (isDay) "☀️ Sáng" else "🌙 Tối"
                }
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
                background = rounded(colorPillBg, 12f, colorAccent, 1)
                setPadding(dp(11), dp(7), dp(11), dp(7))
                setOnClickListener {
                    val nextMode = when (currentMode) {
                        THEME_AUTO -> THEME_DAY
                        THEME_DAY -> THEME_NIGHT
                        THEME_NIGHT -> THEME_AUTO
                        else -> THEME_AUTO
                    }
                    val nextIsDay = when (nextMode) {
                        THEME_DAY -> true
                        THEME_NIGHT -> false
                        else -> resolveIsDay(this@SettingsActivity)
                    }
                    prefs.edit()
                        .putString(KEY_THEME_MODE, nextMode)
                        .putBoolean("carhud_day_mode", nextIsDay)
                        .putBoolean("settings_dark_mode", !nextIsDay)
                        .apply()
                    recreate()
                }
            }
            addView(themeBtn)
        }
        root.addView(headerRow)

        // ==========================================
        // QUICK ACTIONS ROW (XEM TRƯỚC XE & TRỞ VỀ APP)
        // ==========================================
        val quickActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }

            // Left Action: Xem trước Dashboard Ô tô
            val dashPreviewBtn = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(11), dp(12), dp(11))
                val btnBg = if (isDarkTheme) Color.parseColor("#0C2440") else Color.parseColor("#E0F2FE")
                val btnBorder = if (isDarkTheme) Color.parseColor("#0284C7") else Color.parseColor("#BAE6FD")
                background = rounded(btnBg, 12f, btnBorder, 1)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(5)
                }

                val icon = TextView(this@SettingsActivity).apply {
                    text = "📺 "
                    textSize = 16f
                }
                addView(icon)

                val label = TextView(this@SettingsActivity).apply {
                    text = "Xem trước Xe"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isDarkTheme) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                }
                addView(label)

                setOnClickListener {
                    showDashboardPreview()
                }
            }
            addView(dashPreviewBtn)

            // Right Action: Trở về Màn hình chính
            val phoneAppBtn = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(11), dp(12), dp(11))
                val btnBg = if (isDarkTheme) Color.parseColor("#10192A") else Color.parseColor("#F0F9FF")
                val btnBorder = if (isDarkTheme) Color.parseColor("#1E3A5F") else Color.parseColor("#BAE6FD")
                background = rounded(btnBg, 12f, btnBorder, 1)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(5)
                }

                val icon = TextView(this@SettingsActivity).apply {
                    text = "← "
                    textSize = 16f
                    setTextColor(colorTextPrimary)
                }
                addView(icon)

                val label = TextView(this@SettingsActivity).apply {
                    text = "Trở về App"
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                }
                addView(label)

                setOnClickListener {
                    finish()
                }
            }
            addView(phoneAppBtn)
        }
        root.addView(quickActionsRow)

        // ==========================================
        // CARD 1: BỐ CỤC & MÀN HÌNH XE (EXPANDABLE)
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "🚗",
                iconBgColor = if (isDarkTheme) Color.parseColor("#1E3A8A") else Color.parseColor("#DBEAFE"),
                title = "BỐ CỤC & MÀN HÌNH XE",
                subtitle = "Tự nhận diện màn hình, hướng hiển thị & kích thước menu",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                val currentThemeMode = prefs.getString(KEY_THEME_MODE, THEME_AUTO) ?: THEME_AUTO
                val themeBadge = when (currentThemeMode) {
                    THEME_DAY -> "☀️ Ban ngày (Sáng)"
                    THEME_NIGHT -> "🌙 Ban đêm (Tối)"
                    else -> "🚗 Tự động (Cảm biến xe)"
                }
                content.addView(
                    settingCard(
                        title = "CHẾ ĐỘ GIAO DIỆN XE",
                        subtitle = "Tự động đổi theo cảm biến xe, hoặc cố định chế độ Sáng / Tối",
                        badgeText = themeBadge,
                        onClick = {
                            choose(
                                "Chọn chế độ giao diện màn hình xe",
                                arrayOf(
                                    "🚗 Tự động theo cảm biến xe (Khuyên dùng)",
                                    "☀️ Ban ngày (Luôn sáng)",
                                    "🌙 Ban đêm (Luôn tối)"
                                ),
                                arrayOf(THEME_AUTO, THEME_DAY, THEME_NIGHT),
                                KEY_THEME_MODE
                            ) { selected ->
                                val isDay = when (selected) {
                                    THEME_DAY -> true
                                    THEME_NIGHT -> false
                                    else -> resolveIsDay(this@SettingsActivity)
                                }
                                prefs.edit()
                                    .putBoolean("carhud_day_mode", isDay)
                                    .putBoolean("settings_dark_mode", !isDay)
                                    .apply()
                                recreate()
                            }
                        }
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Tự động nhận diện màn hình xe",
                        subtitle = "Tối ưu giao diện theo màn hình Ngang, Dọc hoặc Siêu rộng",
                        key = KEY_AUTO_DETECT_SCREEN,
                        default = true
                    )
                )

                content.addView(createCarScreenDetailsCard())

                content.addView(
                    createSwitchRow(
                        title = "Mặc định mở Màn hình chính khi cắm xe",
                        subtitle = "Luôn hiển thị Dashboard buồng lái 5 Card khi cắm điện thoại kết nối ô tô",
                        key = KEY_DEFAULT_TO_DASHBOARD,
                        default = true
                    )
                )

                content.addView(createSeparator())

                val currentPos = prefs.getString(KEY_TOOLBAR_POSITION, "bottom") ?: "bottom"
                val posName = when (currentPos) {
                    "left" -> "Bên trái"
                    "right" -> "Bên phải"
                    "auto" -> "Tự động"
                    else -> "Phía dưới"
                }
                content.addView(
                    settingCard(
                        title = "VỊ TRÍ THANH MENU",
                        subtitle = "Tùy chỉnh vị trí thanh điều hướng",
                        badgeText = posName,
                        onClick = {
                            choose(
                                "Chọn vị trí thanh menu trên xe",
                                arrayOf("Phía dưới (Mặc định - Khuyên dùng)", "Tự động theo màn hình xe", "Bên trái", "Bên phải"),
                                arrayOf("bottom", "auto", "left", "right"),
                                KEY_TOOLBAR_POSITION
                            )
                        }
                    )
                )

                val currentWebAppPos = prefs.getString(KEY_WEBAPP_TOOLBAR_POSITION, "left") ?: "left"
                val webAppPosName = when (currentWebAppPos) {
                    "top" -> "Phía trên (Ngang)"
                    else -> "Bên trái (Dọc)"
                }
                content.addView(
                    settingCard(
                        title = "VỊ TRÍ THANH ỨNG DỤNG WEB",
                        subtitle = "Thanh chuyển đổi nhanh web app",
                        badgeText = webAppPosName,
                        onClick = {
                            choose(
                                "Chọn vị trí thanh ứng dụng web",
                                arrayOf("Bên trái màn hình (Dọc - Dễ thao tác khi lái xe)", "Phía trên cùng (Ngang)"),
                                arrayOf("left", "top"),
                                KEY_WEBAPP_TOOLBAR_POSITION
                            )
                        }
                    )
                )

                content.addView(createSeparator())

                val currentScale = prefs.getInt(KEY_TOOLBAR_SCALE, 100)
                val scaleLabel = TextView(this).apply {
                    text = "Kích thước thanh công cụ YouTube / IPTV: $currentScale%"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                    setPadding(0, dp(6), 0, dp(2))
                }
                content.addView(scaleLabel)

                val seekBar = SeekBar(this).apply {
                    max = 100 // 50 to 150
                    progress = currentScale - 50
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val actualScale = progress + 50
                            scaleLabel.text = "Kích thước thanh công cụ YouTube / IPTV: $actualScale%"
                            prefs.edit().putInt(KEY_TOOLBAR_SCALE, actualScale).apply()
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
                content.addView(seekBar)

                val currentDockScale = prefs.getInt(KEY_DOCK_SCALE, 100).coerceIn(50, 150)
                val dockScaleLabel = TextView(this).apply {
                    text = "Kích thước thanh dock (ứng dụng): $currentDockScale%"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                    setPadding(0, dp(8), 0, dp(2))
                }
                content.addView(dockScaleLabel)

                val dockSeekBar = SeekBar(this).apply {
                    max = 100 // 50 to 150
                    progress = currentDockScale - 50
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                            val actualScale = progress + 50
                            dockScaleLabel.text = "Kích thước thanh dock (ứng dụng): $actualScale%"
                            prefs.edit().putInt(KEY_DOCK_SCALE, actualScale).apply()
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    })
                }
                content.addView(dockSeekBar)

                val dockPresetRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(8)
                    }
                }
                val dockPresets = listOf(70 to "70% Nhỏ", 85 to "85% Vừa", 100 to "100% Chuẩn", 120 to "120% Lớn", 140 to "140% Cực lớn")
                for ((scaleVal, label) in dockPresets) {
                    val chip = TextView(this).apply {
                        text = label
                        textSize = 11f
                        val isSel = (currentDockScale == scaleVal)
                        setTextColor(if (isSel) colorPillText else colorTextSecondary)
                        typeface = if (isSel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        background = rounded(
                            if (isSel) colorPillBg else colorItemBg,
                            8f,
                            if (isSel) colorPillText else colorItemBorder,
                            1
                        )
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = dp(2)
                            rightMargin = dp(2)
                        }
                        gravity = Gravity.CENTER
                        setOnClickListener {
                            prefs.edit().putInt(KEY_DOCK_SCALE, scaleVal).apply()
                            dockSeekBar.progress = scaleVal - 50
                            dockScaleLabel.text = "Kích thước thanh dock (ứng dụng): $scaleVal%"
                            for (j in 0 until dockPresetRow.childCount) {
                                val c = dockPresetRow.getChildAt(j) as? TextView ?: continue
                                val sel = (j == dockPresets.indexOfFirst { it.first == scaleVal })
                                c.setTextColor(if (sel) colorPillText else colorTextSecondary)
                                c.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                                c.background = rounded(
                                    if (sel) colorPillBg else colorItemBg,
                                    8f,
                                    if (sel) colorPillText else colorItemBorder,
                                    1
                                )
                            }
                        }
                    }
                    dockPresetRow.addView(chip)
                }
                content.addView(dockPresetRow)
            }
        )

        // ==========================================
        // CARD 2: CÔNG TẮC THANH ĐIỀU HƯỚNG XE (EXPANDABLE)
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "🔘",
                iconBgColor = if (isDarkTheme) Color.parseColor("#312E81") else Color.parseColor("#E0E7FF"),
                title = "CÔNG TẮC THANH ĐIỀU HƯỚNG XE",
                subtitle = "Bật hoặc tắt từng nút tùy chỉnh trên thanh điều hướng xe hơi",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                content.addView(createSwitchRow("Nút Quay lại (‹)", "Lùi về trang hoặc video trước", KEY_SHOW_BACK, true))
                content.addView(createSwitchRow("Nút Trang chủ (⌂)", "Quay về trang chủ YouTube / Dashboard", KEY_SHOW_HOME, true))
                content.addView(createSwitchRow("Nút Tỷ lệ khung hình (⤢)", "Chọn tỷ lệ hiển thị YouTube & IPTV (Tràn viền, 16:9, Phóng to...)", KEY_SHOW_TV, true))
                content.addView(createSwitchRow("Nút Giao diện Sáng / Tối (☀️/🌙)", "Chuyển nhanh chế độ Ngày / Đêm", KEY_SHOW_DAY_NIGHT, true))
                content.addView(createSwitchRow("Nút Bàn phím Web (⌨️)", "Hiện bàn phím trên Android Auto khi dùng Trình duyệt Web", KEY_SHOW_KEYBOARD_SEARCH, true))
                content.addView(settingCard(
                    title = "BÀN PHÍM NHẬP TRÊN XE",
                    subtitle = "Android Auto nhận chữ trực tiếp để gõ nhanh; bàn phím THTV dùng chạm trên màn hình.",
                    badgeText = if (prefs.getString("car_keyboard_input_mode", "native") == "thtv") "THTV" else "Android Auto",
                    onClick = {
                        choose("Chọn bàn phím trên xe",
                            arrayOf("Android Auto (Khuyên dùng để gõ nhanh)", "Bàn phím THTV"),
                            arrayOf("native", "thtv"), "car_keyboard_input_mode")
                    }
                ))
                content.addView(createSwitchRow("Nút Tìm kiếm giọng nói (🎙️)", "Kích hoạt micro nói tên bài hát / kênh", KEY_SHOW_VOICE_SEARCH, true))
            }
        )

        // ==========================================
        // CARD: TRÌNH DUYỆT WEB
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "🌐",
                iconBgColor = if (isDarkTheme) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"),
                title = "TRÌNH DUYỆT WEB",
                subtitle = "Quảng cáo, quyền riêng tư và tương thích website",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())
                content.addView(
                    createSwitchRow(
                        title = "Chặn quảng cáo khi duyệt Web",
                        subtitle = "Chặn domain quảng cáo phổ biến và ẩn khung banner. Tắt nếu một website hiển thị lỗi.",
                        key = KEY_BROWSER_ADBLOCK,
                        default = true
                    )
                )
                content.addView(TextView(this).apply {
                    text = "Chỉ áp dụng cho Trình duyệt Web. YouTube và IPTV dùng bộ lọc riêng để tránh ảnh hưởng phát video."
                    textSize = 12f
                    setTextColor(colorTextSecondary)
                    setPadding(dp(12), dp(4), dp(12), dp(10))
                })
            }
        )

        // ==========================================
        // Independent system-assistant integration; separate from our own microphone.
        root.addView(
            createExpandableCard(
                iconEmoji = "🎙️",
                iconBgColor = if (isDarkTheme) Color.parseColor("#123047") else Color.parseColor("#DBEAFE"),
                title = "MIC HỆ THỐNG ANDROID AUTO",
                subtitle = "Nhận lệnh mở nhạc, mở kênh từ trợ lý Google",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSwitchRow(
                    "Nhận lệnh từ mic hệ thống", "Cho phép THTV xử lý lệnh nhạc/TV do trợ lý chuyển tới",
                    SystemVoiceModule.PREF_ENABLED, true
                ))
                content.addView(TextView(this).apply {
                    text = "Mở THTV trên Android Auto, bấm mic hệ thống và nói:\n• Phát bài Nắng ấm xa dần trên THTV\n• Phát VTV1 trên THTV\n• Phát HTV7 trên THTV\n\nNếu trợ lý chưa nhận ra tên app, thử nói THTV Media. Nút mic vẫn mở trợ lý Google."
                    textSize = 13f
                    setTextColor(colorTextSecondary)
                    setPadding(dp(12), dp(8), dp(12), dp(12))
                })
                content.addView(settingCard(
                    title = "THỬ LỆNH TRONG THTV",
                    subtitle = "Nhập lệnh thử khi THTV đang mở trên Android Auto; không kiểm tra mic Google",
                    badgeText = "Thử",
                    onClick = { showSystemVoiceTestDialog() }
                ))
                content.addView(settingCard(
                    title = "LỆNH GẦN NHẤT ĐÃ NHẬN",
                    subtitle = "Xem trợ lý đã chuyển lệnh tới THTV chưa và kết quả xử lý",
                    badgeText = "Xem",
                    onClick = {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Mic hệ thống Android Auto")
                            .setMessage(SystemVoiceModule.diagnostics(this))
                            .setPositiveButton("Đóng", null).show()
                    }
                ))
            }
        )

        // CARD 3: ĐIỀU KHIỂN MEDIA & PHÍM VÔ LĂNG (EXPANDABLE)
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "🎵",
                iconBgColor = if (isDarkTheme) Color.parseColor("#4C1D95") else Color.parseColor("#EDE9FE"),
                title = "ĐIỀU KHIỂN MEDIA & PHÍM VÔ LĂNG",
                subtitle = "Tự động phát, toàn màn hình, phím vô lăng & widget xe",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                content.addView(
                    settingCard(
                        title = "KIỂM TRA PHÍM BẤM VÔ LĂNG",
                        subtitle = "Bấm để kiểm tra xe đã nhận diện phím Voice, Next, Prev chưa",
                        badgeText = "▶ Bắt đầu thử",
                        onClick = { showSteeringKeyTesterDialog() }
                    )
                )

                content.addView(createSeparator())

                content.addView(
                    createSwitchRow(
                        title = "Tự phát bài gần nhất khi vào app",
                        subtitle = "Tiếp tục phát video/bài hát đang nghe dở khi kết nối ô tô",
                        key = KEY_AUTO_RESUME_LAST_TRACK,
                        default = true
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Tự động toàn màn hình khi phát",
                        subtitle = "Tự động phóng to toàn màn hình khi mở bất kỳ video nào",
                        key = KEY_AUTO_FULLSCREEN,
                        default = true
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Đồng bộ widget Media trên xe",
                        subtitle = "Hiển thị bài hát, thời lượng và nút chuyển bài trên thẻ Android Auto",
                        key = "pref_sync_media_widget",
                        default = true
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Phát âm thanh trong nền",
                        subtitle = "Phát nhạc khi chuyển app trên xe",
                        key = KEY_BACKGROUND_AUDIO,
                        default = true
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Audio Ducking",
                        subtitle = "Tự giảm âm lượng khi có thông báo Maps",
                        key = KEY_AUDIO_DUCKING,
                        default = true
                    )
                )

                content.addView(createSeparator())

                content.addView(
                    createSwitchRow(
                        title = "Phím Voice trên vô lăng mở Micro",
                        subtitle = "Bấm nút Voice/Micro trên vô lăng xe để tìm bài hát",
                        key = KEY_STEERING_VOICE_ENABLED,
                        default = true
                    )
                )

                content.addView(
                    createSwitchRow(
                        title = "Bấm đúp phím Next mở Micro",
                        subtitle = "Chờ nhận bấm đôi để mở Micro mà không chuyển bài",
                        key = KEY_STEERING_DOUBLE_CLICK_VOICE,
                        default = true
                    )
                )

                content.addView(createSeparator())

                val currentSpeed = prefs.getInt(KEY_STEERING_DOUBLE_CLICK_SPEED, 500)
                val speedLabel = when (currentSpeed) {
                    400 -> "400 ms (Nhanh)"
                    700 -> "700 ms (Chậm)"
                    else -> "500 ms (Chuẩn)"
                }
                content.addView(
                    settingCard(
                        title = "TỐC ĐỘ BẤM ĐÚP PHÍM VÔ LĂNG",
                        subtitle = "Đang chọn: $speedLabel",
                        badgeText = speedLabel,
                        onClick = {
                            val speeds = arrayOf("400 ms (Bấm nhanh)", "500 ms (Chuẩn - Khuyên dùng)", "700 ms (Dễ bấm / Xe phản hồi chậm)")
                            val values = arrayOf("400", "500", "700")
                            val curIdx = values.indexOf(currentSpeed.toString()).coerceAtLeast(1)
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Chọn tốc độ nhận diện bấm đúp")
                                .setSingleChoiceItems(speeds, curIdx) { dialog, which ->
                                    prefs.edit().putInt(KEY_STEERING_DOUBLE_CLICK_SPEED, values[which].toInt()).apply()
                                    dialog.dismiss()
                                    recreate()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    )
                )

                content.addView(
                    settingCard(
                        title = "CHỨC NĂNG PHÍM NEXT (1 LẦN)",
                        subtitle = "Tác vụ khi bấm 1 lần phím Next",
                        badgeText = if (prefs.getString(KEY_STEERING_NEXT_ACTION, "next") == "voice") "Micro" else "Chuyển bài",
                        onClick = {
                            choose(
                                "Chọn chức năng phím Next trên vô lăng",
                                arrayOf("Chuyển sang bài tiếp theo (Mặc định)", "Kích hoạt tìm kiếm giọng nói"),
                                arrayOf("next", "voice"),
                                KEY_STEERING_NEXT_ACTION
                            )
                        }
                    )
                )

                content.addView(
                    settingCard(
                        title = "CHỨC NĂNG PHÍM PREV (1 LẦN)",
                        subtitle = "Tác vụ khi bấm 1 lần phím Prev",
                        badgeText = if (prefs.getString(KEY_STEERING_PREV_ACTION, "prev") == "voice") "Micro" else "Lùi bài",
                        onClick = {
                            choose(
                                "Chọn chức năng phím Prev trên vô lăng",
                                arrayOf("Quay lại bài trước (Mặc định)", "Kích hoạt tìm kiếm giọng nói"),
                                arrayOf("prev", "voice"),
                                KEY_STEERING_PREV_ACTION
                            )
                        }
                    )
                )
            }
        )

        // ==========================================
        // CARD 4: CẢNH BÁO TỐC ĐỘ (WAZE MOD / GPS)
        // ==========================================
        var activeHudStyleId = WazeHudManager.getActiveStyleId(this)

        root.addView(
            createExpandableCard(
                iconEmoji = "🛡️",
                iconBgColor = if (isDarkTheme) Color.parseColor("#064E3B") else Color.parseColor("#D1FAE5"),
                title = "CẢNH BÁO TỐC ĐỘ (WAZE MOD / GPS)",
                subtitle = "Đồng bộ tốc độ, camera phạt nguội, kết nối HLP & 5 mẫu HUD Waze Mod chuẩn",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                // ----------------------------------------------------
                // PHẦN 1: KẾT NỐI WAZE MOD (WEBSOCKET)
                // ----------------------------------------------------
                val hlpHeader = TextView(this).apply {
                    text = "KẾT NỐI WAZE MOD (WEBSOCKET)"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    setPadding(0, dp(4), 0, dp(6))
                }
                content.addView(hlpHeader)

                // Compact Connection Box
                val hlpInfoBox = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val boxBg = if (isDarkTheme) Color.parseColor("#0C1B2E") else Color.parseColor("#F0F9FF")
                    val boxBorder = if (isDarkTheme) Color.parseColor("#1E3A5F") else Color.parseColor("#BAE6FD")
                    background = rounded(boxBg, 12f, boxBorder, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }

                    val statusTv = TextView(this@SettingsActivity).apply {
                        text = "📡 Trạng thái: ${WazeHlpWebSocketManager.statusText.value}"
                        textSize = 13f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (WazeHlpWebSocketManager.isConnected.value) Color.parseColor("#10B981") else colorAccent)
                    }
                    addView(statusTv)

                    lifecycleScope.launch {
                        WazeHlpWebSocketManager.statusText.collectLatest { status ->
                            statusTv.text = "📡 Trạng thái: $status"
                            val isConn = WazeHlpWebSocketManager.isConnected.value
                            statusTv.setTextColor(if (isConn) Color.parseColor("#10B981") else colorAccent)
                        }
                    }

                    val paramTv = TextView(this@SettingsActivity).apply {
                        text = "Cổng: 8766 • Đường dẫn: /hlp • IP nội bộ: 127.0.0.1"
                        textSize = 11.5f
                        setTextColor(colorTextSecondary)
                        setPadding(0, dp(4), 0, dp(8))
                    }
                    addView(paramTv)

                    // 2 Action Buttons
                    val actionBtnRow = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                        val restartBtn = TextView(this@SettingsActivity).apply {
                            text = "🔄 Khởi động lại Server"
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            background = rounded(colorAccent, 8f)
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginEnd = dp(4)
                            }
                            setOnClickListener {
                                WazeHlpWebSocketManager.restartConnection()
                                Toast.makeText(this@SettingsActivity, "Đang khởi động lại Server WebSocket (Cổng 8766)...", Toast.LENGTH_SHORT).show()
                            }
                        }
                        addView(restartBtn)

                        val openModBtn = TextView(this@SettingsActivity).apply {
                            text = "🔗 Mở Waze Mod"
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorTextPrimary)
                            gravity = Gravity.CENTER
                            background = rounded(colorItemBg, 8f, colorItemBorder, 1)
                            setPadding(dp(10), dp(8), dp(10), dp(8))
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                marginStart = dp(4)
                            }
                            setOnClickListener {
                                WazeHlpWebSocketManager.openWazeMod(this@SettingsActivity)
                            }
                        }
                        addView(openModBtn)
                    }
                    addView(actionBtnRow)
                }
                content.addView(hlpInfoBox)

                // ----------------------------------------------------
                // PHẦN: CÀI ĐẶT ÂM THANH CẢNH BÁO & TIẾNG TING WAZE
                // Khôi phục theo yêu cầu: chỉ phần chế độ âm thanh + 5 kiểu Ting.
                // Không khôi phục quyền đọc thông báo và lưới TTS thử nghiệm cũ.
                // ----------------------------------------------------
                content.addView(createSeparator())

                val audioConfigHeader = TextView(this).apply {
                    text = "🔔 ÂM THANH CẢNH BÁO & TIẾNG TING WAZE"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    setPadding(0, dp(4), 0, dp(6))
                }
                content.addView(audioConfigHeader)

                // 1. Chế độ âm thanh cảnh báo
                lateinit var audioModeCard: LinearLayout
                fun refreshAudioModeCard() {
                    val mode = WazeHudManager.getAlertAudioMode(this@SettingsActivity)
                    val modeText = when (mode) {
                        WazeHudManager.ALERT_AUDIO_BOTH -> "Ting + Giọng nói tiếng Việt (Khuyên dùng)"
                        WazeHudManager.ALERT_AUDIO_TONE -> "Chỉ phát tiếng Ting ngắn"
                        WazeHudManager.ALERT_AUDIO_VOICE -> "Chỉ phát giọng nói tiếng Việt"
                        WazeHudManager.ALERT_AUDIO_OFF -> "Tắt hoàn toàn âm thanh cảnh báo"
                        else -> "Ting + Giọng nói"
                    }
                    val badge = when (mode) {
                        WazeHudManager.ALERT_AUDIO_BOTH -> "Ting + Giọng"
                        WazeHudManager.ALERT_AUDIO_TONE -> "Ting"
                        WazeHudManager.ALERT_AUDIO_VOICE -> "Giọng nói"
                        else -> "Tắt"
                    }
                    val textLayout = audioModeCard.getChildAt(0) as? LinearLayout
                    val subTv = textLayout?.getChildAt(1) as? TextView
                    subTv?.text = modeText
                    val badgeTv = audioModeCard.getChildAt(1) as? TextView
                    badgeTv?.text = badge
                }

                val curMode = WazeHudManager.getAlertAudioMode(this)
                val curModeText = when (curMode) {
                    WazeHudManager.ALERT_AUDIO_BOTH -> "Ting + Giọng nói tiếng Việt (Khuyên dùng)"
                    WazeHudManager.ALERT_AUDIO_TONE -> "Chỉ phát tiếng Ting ngắn"
                    WazeHudManager.ALERT_AUDIO_VOICE -> "Chỉ phát giọng nói tiếng Việt"
                    WazeHudManager.ALERT_AUDIO_OFF -> "Tắt hoàn toàn âm thanh cảnh báo"
                    else -> "Ting + Giọng nói"
                }
                val curBadge = when (curMode) {
                    WazeHudManager.ALERT_AUDIO_BOTH -> "Ting + Giọng"
                    WazeHudManager.ALERT_AUDIO_TONE -> "Ting"
                    WazeHudManager.ALERT_AUDIO_VOICE -> "Giọng nói"
                    else -> "Tắt"
                }

                audioModeCard = settingCard(
                    title = "CHẾ ĐỘ ÂM THANH CẢNH BÁO",
                    subtitle = curModeText,
                    badgeText = curBadge,
                    onClick = {
                        val modes = arrayOf(
                            WazeHudManager.ALERT_AUDIO_BOTH to "Ting + Giọng nói tiếng Việt (Khuyên dùng)",
                            WazeHudManager.ALERT_AUDIO_TONE to "Chỉ tiếng Ting ngắn",
                            WazeHudManager.ALERT_AUDIO_VOICE to "Chỉ giọng nói tiếng Việt",
                            WazeHudManager.ALERT_AUDIO_OFF to "Tắt âm thanh cảnh báo"
                        )
                        val labels = modes.map { it.second }.toTypedArray()
                        val selectedIdx = modes.indexOfFirst {
                            it.first == WazeHudManager.getAlertAudioMode(this@SettingsActivity)
                        }.coerceAtLeast(0)

                        AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("🔔 Chọn chế độ âm thanh cảnh báo")
                            .setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                                val (chosenKey, chosenName) = modes[which]
                                WazeHudManager.setAlertAudioMode(this@SettingsActivity, chosenKey)
                                refreshAudioModeCard()
                                Toast.makeText(this@SettingsActivity, "Đã chọn: $chosenName", Toast.LENGTH_SHORT).show()

                                when (chosenKey) {
                                    WazeHudManager.ALERT_AUDIO_TONE,
                                    WazeHudManager.ALERT_AUDIO_BOTH ->
                                        CarTtsManager.playAlertTone(isPriority = true)
                                    WazeHudManager.ALERT_AUDIO_VOICE ->
                                        CarTtsManager.speakAlert(
                                            "Đã bật chế độ cảnh báo bằng giọng nói tiếng Việt",
                                            isPriority = true
                                        )
                                }
                                dialog.dismiss()
                            }
                            .setNegativeButton("Đóng", null)
                            .show()
                    }
                )
                content.addView(audioModeCard)

                // 2. Kiểu tiếng Ting cảnh báo (5 kiểu)
                lateinit var toneStyleCard: LinearLayout
                fun refreshToneStyleCard() {
                    val style = WazeHudManager.getAlertToneStyle(this@SettingsActivity)
                    val styleText = when (style) {
                        WazeHudManager.ALERT_TONE_BEEP -> "1. Ting ngắn tiêu chuẩn (960Hz)"
                        WazeHudManager.ALERT_TONE_DOUBLE_BEEP -> "2. Ting đôi cao độ (740Hz - 1120Hz)"
                        WazeHudManager.ALERT_TONE_ACK -> "3. Bíp xác nhận 3 nhịp (1280Hz - 960Hz)"
                        WazeHudManager.ALERT_TONE_PROMPT -> "4. Chuông báo nhắc nhở (620Hz - 1080Hz)"
                        WazeHudManager.ALERT_TONE_STRONG -> "5. Cảnh báo khẩn cấp 3 nốt (520Hz - 760Hz - 980Hz)"
                        else -> "1. Ting ngắn tiêu chuẩn (960Hz)"
                    }
                    val textLayout = toneStyleCard.getChildAt(0) as? LinearLayout
                    val subTv = textLayout?.getChildAt(1) as? TextView
                    subTv?.text = styleText
                }

                val curStyle = WazeHudManager.getAlertToneStyle(this)
                val curStyleText = when (curStyle) {
                    WazeHudManager.ALERT_TONE_BEEP -> "1. Ting ngắn tiêu chuẩn (960Hz)"
                    WazeHudManager.ALERT_TONE_DOUBLE_BEEP -> "2. Ting đôi cao độ (740Hz - 1120Hz)"
                    WazeHudManager.ALERT_TONE_ACK -> "3. Bíp xác nhận 3 nhịp (1280Hz - 960Hz)"
                    WazeHudManager.ALERT_TONE_PROMPT -> "4. Chuông báo nhắc nhở (620Hz - 1080Hz)"
                    WazeHudManager.ALERT_TONE_STRONG -> "5. Cảnh báo khẩn cấp 3 nốt (520Hz - 760Hz - 980Hz)"
                    else -> "1. Ting ngắn tiêu chuẩn (960Hz)"
                }

                toneStyleCard = settingCard(
                    title = "KIỂU TIẾNG TING CẢNH BÁO (5 KIỂU)",
                    subtitle = curStyleText,
                    badgeText = "▶ Nghe thử",
                    onClick = {
                        val tones = arrayOf(
                            WazeHudManager.ALERT_TONE_BEEP to "1. Ting ngắn tiêu chuẩn (960Hz)",
                            WazeHudManager.ALERT_TONE_DOUBLE_BEEP to "2. Ting đôi cao độ (740Hz - 1120Hz)",
                            WazeHudManager.ALERT_TONE_ACK to "3. Bíp xác nhận 3 nhịp (1280Hz - 960Hz)",
                            WazeHudManager.ALERT_TONE_PROMPT to "4. Chuông báo nhắc nhở (620Hz - 1080Hz)",
                            WazeHudManager.ALERT_TONE_STRONG to "5. Cảnh báo khẩn cấp 3 nốt (520Hz - 760Hz - 980Hz)"
                        )
                        val labels = tones.map { it.second }.toTypedArray()
                        val selectedIdx = tones.indexOfFirst {
                            it.first == WazeHudManager.getAlertToneStyle(this@SettingsActivity)
                        }.coerceAtLeast(0)

                        AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("🎵 Chọn & Nghe thử tiếng Ting")
                            .setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                                val (chosenKey, chosenName) = tones[which]
                                WazeHudManager.setAlertToneStyle(this@SettingsActivity, chosenKey)
                                refreshToneStyleCard()
                                CarTtsManager.playAlertTone(
                                    overrideToneStyle = chosenKey,
                                    isPriority = true
                                )
                                Toast.makeText(this@SettingsActivity, "Đang phát: $chosenName", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            }
                            .setNeutralButton("▶ Phát tiếng hiện tại") { _, _ ->
                                CarTtsManager.playAlertTone(isPriority = true)
                            }
                            .setNegativeButton("Đóng", null)
                            .show()
                    }
                )
                content.addView(toneStyleCard)

                content.addView(createSeparator())

                // ----------------------------------------------------
                // THẺ: DỮ LIỆU LUỒNG ĐANG NHẬN TRỰC TUYẾN (Real-time Telemetry Stream)
                // ----------------------------------------------------
                val liveCardBg = Color.parseColor("#121418")
                val liveCardBorder = Color.parseColor("#22252F")
                val statBoxBg = Color.parseColor("#181A20")
                val statBoxBorder = Color.parseColor("#252833")
                val statLabelColor = Color.parseColor("#7E8492")

                val liveTelemetryCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = rounded(liveCardBg, 16f, liveCardBorder, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(10)
                    }

                    // Header row
                    val headerRow = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val titleRow = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                            val iconTv = TextView(this@SettingsActivity).apply {
                                text = "⏱"
                                textSize = 15f
                                setTextColor(Color.parseColor("#06B6D4"))
                                setPadding(0, 0, dp(6), 0)
                            }
                            addView(iconTv)

                            val titleTv = TextView(this@SettingsActivity).apply {
                                text = "DỮ LIỆU LUỒNG ĐANG NHẬN TRỰC TUYẾN"
                                textSize = 12.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                            }
                            addView(titleTv)
                        }
                        addView(titleRow)

                        val timeTv = TextView(this@SettingsActivity).apply {
                            text = "13:50:47"
                            textSize = 11.5f
                            typeface = Typeface.MONOSPACE
                            setTextColor(Color.parseColor("#8E929E"))
                        }
                        addView(timeTv)
                    }
                    addView(headerRow)

                    // 2x2 Grid of Stat Boxes
                    val statGrid = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(10)
                        }
                    }

                    // Row 1: TỐC ĐỘ GPS & BIỂN GIỚI HẠN
                    val row1 = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(8)
                        }
                    }

                    // Box 1: TỐC ĐỘ GPS
                    val speedBox = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = rounded(statBoxBg, 12f, statBoxBorder, 1)
                        layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(4) }

                        val labelTv = TextView(this@SettingsActivity).apply {
                            text = "TỐC ĐỘ GPS"
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(statLabelColor)
                            gravity = Gravity.CENTER
                        }
                        addView(labelTv)

                        val valRow = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            setPadding(0, dp(2), 0, 0)

                            val numTv = TextView(this@SettingsActivity).apply {
                                text = "0"
                                textSize = 28f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.WHITE)
                            }
                            addView(numTv)

                            val unitTv = TextView(this@SettingsActivity).apply {
                                text = "km/h"
                                textSize = 9.5f
                                setTextColor(statLabelColor)
                                setPadding(dp(4), 0, 0, dp(3))
                            }
                            addView(unitTv)
                        }
                        addView(valRow)
                    }
                    row1.addView(speedBox)

                    // Box 2: BIỂN GIỚI HẠN
                    val limitBox = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = rounded(statBoxBg, 12f, statBoxBorder, 1)
                        layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginStart = dp(4) }

                        val labelTv = TextView(this@SettingsActivity).apply {
                            text = "BIỂN GIỚI HẠN"
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(statLabelColor)
                            gravity = Gravity.CENTER
                        }
                        addView(labelTv)

                        val badgeFrame = FrameLayout(this@SettingsActivity).apply {
                            val size = dp(32)
                            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = dp(4) }
                            background = circle(Color.parseColor("#222630"), Color.parseColor("#2C3240"), 1.2f)

                            val badgeTv = TextView(this@SettingsActivity).apply {
                                text = "--"
                                textSize = 13f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(statLabelColor)
                                gravity = Gravity.CENTER
                            }
                            addView(badgeTv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                        }
                        addView(badgeFrame)
                    }
                    row1.addView(limitBox)
                    statGrid.addView(row1)

                    // Row 2: HƯỚNG RẼ & THỜI GIAN ĐẾN
                    val row2 = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }

                    // Box 3: HƯỚNG RẼ
                    val turnBox = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = rounded(statBoxBg, 12f, statBoxBorder, 1)
                        layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginEnd = dp(4) }

                        val labelTv = TextView(this@SettingsActivity).apply {
                            text = "HƯỚNG RẼ"
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(statLabelColor)
                            gravity = Gravity.CENTER
                        }
                        addView(labelTv)

                        val turnRowInside = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER
                            setPadding(0, dp(4), 0, 0)

                            val iconTv = TextView(this@SettingsActivity).apply {
                                text = "↑"
                                textSize = 16f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#06B6D4"))
                                setPadding(0, 0, dp(4), 0)
                            }
                            addView(iconTv)

                            val textTv = TextView(this@SettingsActivity).apply {
                                text = "Thẳng"
                                textSize = 13f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#06B6D4"))
                            }
                            addView(textTv)
                        }
                        addView(turnRowInside)
                    }
                    row2.addView(turnBox)

                    // Box 4: THỜI GIAN ĐẾN
                    val etaBox = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                        background = rounded(statBoxBg, 12f, statBoxBorder, 1)
                        layoutParams = LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginStart = dp(4) }

                        val labelTv = TextView(this@SettingsActivity).apply {
                            text = "THỜI GIAN ĐẾN"
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(statLabelColor)
                            gravity = Gravity.CENTER
                        }
                        addView(labelTv)

                        val etaValTv = TextView(this@SettingsActivity).apply {
                            text = "-- : --"
                            textSize = 14f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            setPadding(0, dp(6), 0, 0)
                        }
                        addView(etaValTv)
                    }
                    row2.addView(etaBox)
                    statGrid.addView(row2)
                    addView(statGrid)

                    // Bottom bar: Compass & Road Name + Alert Pill
                    val bottomBar = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = rounded(statBoxBg, 10f, statBoxBorder, 1)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(8)
                        }

                        val leftPart = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                            val compassIcon = TextView(this@SettingsActivity).apply {
                                text = "🧭"
                                textSize = 13f
                                setTextColor(Color.parseColor("#06B6D4"))
                                setPadding(0, 0, dp(6), 0)
                            }
                            addView(compassIcon)

                            val roadTv = TextView(this@SettingsActivity).apply {
                                text = "Đang kết nối định vị Waze..."
                                textSize = 11.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#CBD5E1"))
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }
                            addView(roadTv)
                        }
                        addView(leftPart)

                        val alertBadge = TextView(this@SettingsActivity).apply {
                            text = ""
                            textSize = 10f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor("#F59E0B"))
                            background = rounded(Color.parseColor("#33F59E0B"), 6f, Color.parseColor("#F59E0B"), 1)
                            setPadding(dp(6), dp(2), dp(6), dp(2))
                            visibility = View.GONE
                        }
                        addView(alertBadge)
                    }
                    addView(bottomBar)

                    // Real-time collector for Live Telemetry Card
                    lifecycleScope.launch {
                        VietmapStateRepository.alertState.collectLatest { d ->
                            // Update time
                            val timeStr = d.lastUpdatedFormatted ?: if (d.lastUpdated > 0L) {
                                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(d.lastUpdated))
                            } else "13:50:47"
                            val timeTv = (headerRow.getChildAt(1) as? TextView)
                            timeTv?.text = if (d.lastUpdated > 0L) timeStr else "Đang chờ luồng"

                            // Update speed
                            val valRow = (speedBox.getChildAt(1) as? LinearLayout)
                            val numTv = (valRow?.getChildAt(0) as? TextView)
                            numTv?.text = "${d.currentSpeed}"
                            if (d.isOverSpeed) {
                                numTv?.setTextColor(Color.parseColor("#EF4444"))
                            } else {
                                numTv?.setTextColor(Color.WHITE)
                            }

                            // Update limit
                            val badgeFrame = (limitBox.getChildAt(1) as? FrameLayout)
                            val badgeTv = (badgeFrame?.getChildAt(0) as? TextView)
                            if (d.speedLimit != null && d.speedLimit > 0) {
                                badgeFrame?.background = circle(Color.WHITE, Color.parseColor("#E74C3C"), 2.2f)
                                badgeTv?.text = "${d.speedLimit}"
                                badgeTv?.setTextColor(Color.BLACK)
                            } else {
                                badgeFrame?.background = circle(Color.parseColor("#222630"), Color.parseColor("#2C3240"), 1.2f)
                                badgeTv?.text = "--"
                                badgeTv?.setTextColor(statLabelColor)
                            }

                            // Update turn (QCVN 41:2019/BGTVT traffic standards)
                            val turnRowInside = (turnBox.getChildAt(1) as? LinearLayout)
                            val iconTv = (turnRowInside?.getChildAt(0) as? TextView)
                            val textTv = (turnRowInside?.getChildAt(1) as? TextView)
                            val arrowStr = when {
                                d.turnCode in listOf(2, 9) || d.turnAction in listOf("turn-left", "sharp-left", "left") -> "↰"
                                d.turnCode in listOf(3, 10) || d.turnAction in listOf("turn-right", "sharp-right", "right") -> "↱"
                                d.turnCode in listOf(4, 12) || d.turnAction in listOf("slight-left", "keep-left", "exit-left") -> "↖"
                                d.turnCode in listOf(5, 13) || d.turnAction in listOf("slight-right", "keep-right", "exit-right") -> "↗"
                                d.turnCode == 8 || d.turnAction in listOf("u-turn", "uturn") -> "↩"
                                d.turnCode in listOf(6, 7) || d.turnAction == "roundabout" -> "🔄"
                                d.turnCode == 11 || d.turnAction == "destination" -> "🏁"
                                else -> "↑"
                            }
                            iconTv?.text = arrowStr
                            textTv?.text = when {
                                d.distanceToTurnMeters > 0 -> "${d.distanceToTurnMeters}m"
                                !d.turnDescription.isNullOrBlank() -> d.turnDescription
                                else -> "Thẳng"
                            }

                            // Update ETA
                            val etaValTv = (etaBox.getChildAt(1) as? TextView)
                            etaValTv?.text = d.etaTime ?: "-- : --"

                            // Update bottom road & alert
                            val leftPart = (bottomBar.getChildAt(0) as? LinearLayout)
                            val roadTv = (leftPart?.getChildAt(1) as? TextView)
                            roadTv?.text = if (!d.roadName.isNullOrBlank()) d.roadName else "Đang kết nối định vị Waze..."

                            val alertBadge = (bottomBar.getChildAt(1) as? TextView)
                            val alertDesc = d.alertDescription ?: d.alertTitle
                            if (!alertDesc.isNullOrBlank()) {
                                alertBadge?.text = "⚠️ $alertDesc"
                                alertBadge?.visibility = View.VISIBLE
                            } else {
                                alertBadge?.visibility = View.GONE
                            }
                        }
                    }
                }
                content.addView(liveTelemetryCard)

                content.addView(createSeparator())

                // ----------------------------------------------------
                // PHẦN 2: GIAO DIỆN HUD WAZE MOD (4 MẪU CHUẨN)
                // ----------------------------------------------------
                lateinit var activeBadge: TextView

                val hudSectionHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(6))

                    val titleCol = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@SettingsActivity).apply {
                            text = "GIAO DIỆN HUD WAZE MOD (5 MẪU CHUẨN)"
                            textSize = 13.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorTextPrimary)
                        })
                        addView(TextView(this@SettingsActivity).apply {
                            text = "Chạm để đổi kiểu trực tiếp cho màn xe Android Auto và cửa sổ nổi kính lái"
                            textSize = 11f
                            setTextColor(colorTextSecondary)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                    addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    activeBadge = TextView(this@SettingsActivity).apply {
                        text = "Đang dùng #$activeHudStyleId"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        background = rounded(colorAccent, 10f)
                        setPadding(dp(10), dp(5), dp(10), dp(5))
                    }
                    addView(activeBadge)
                }
                content.addView(hudSectionHeader)

                val floatingHudCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(colorItemBg, 12f, colorItemBorder, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4); bottomMargin = dp(8)
                    }
                    val labels = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@SettingsActivity).apply {
                            text = "Bong bóng cảnh báo trên xe"
                            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorTextPrimary)
                        })
                        addView(TextView(this@SettingsActivity).apply {
                            text = "Hiện HUD trên Android Auto; bật lại sau khi bấm ×."
                            textSize = 11f; setTextColor(colorTextSecondary)
                            setPadding(0, dp(2), 0, 0)
                        })
                    }
                    addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Switch(this@SettingsActivity).apply {
                        isChecked = WazeHudManager.isFloatingOverlayEnabled(this@SettingsActivity)
                        setOnCheckedChangeListener { _, enabled ->
                            WazeHudManager.setFloatingOverlayEnabled(this@SettingsActivity, enabled)
                        }
                    })
                }
                content.addView(floatingHudCard)

                // 1. Tùy chọn hiện HUD nổi trực tiếp trong ứng dụng
                val inAppHudCard = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(colorItemBg, 12f, colorItemBorder, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(8)
                    }

                    val iconTv = TextView(this@SettingsActivity).apply {
                        text = "👁️"
                        textSize = 18f
                        setPadding(0, 0, dp(10), 0)
                    }
                    addView(iconTv)

                    val textCol = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@SettingsActivity).apply {
                            text = "Hiện HUD nổi trực tiếp trong ứng dụng"
                            textSize = 13f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorTextPrimary)
                        })
                        addView(TextView(this@SettingsActivity).apply {
                            text = "Bật HUD kính lái nổi trên màn hình để kiểm tra và kéo thả tự do"
                            textSize = 11f
                            setTextColor(colorTextSecondary)
                            setPadding(0, dp(1), 0, 0)
                        })
                    }
                    addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val hudSwitch = Switch(this@SettingsActivity).apply {
                        isChecked = WazeHudManager.isInAppPreviewEnabled(this@SettingsActivity)
                        setOnCheckedChangeListener { _, isChecked ->
                            WazeHudManager.setInAppPreviewEnabled(this@SettingsActivity, isChecked)
                            updateInAppFloatingHud(isChecked)
                        }
                    }
                    addView(hudSwitch)
                }
                content.addView(inAppHudCard)

                // 2. Khung xem trước HUD trực tiếp (Live Preview Box)
                val livePreviewOverlay = VietmapHudOverlay(this@SettingsActivity).apply {
                    setPreviewMode(true)
                    applyHudConfig(activeHudStyleId)
                    setSamplePreview(activeHudStyleId)
                }

                lateinit var styleTag: TextView

                val previewContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    val pBg = if (isDarkTheme) Color.parseColor("#0C1523") else Color.parseColor("#F1F5F9")
                    val pBorder = if (isDarkTheme) Color.parseColor("#1E3A5F") else Color.parseColor("#CBD5E1")
                    background = rounded(pBg, 14f, pBorder, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }

                    // Header row
                    val pHeader = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        addView(TextView(this@SettingsActivity).apply {
                            text = "📱 KHUNG XEM TRƯỚC HUD TRỰC TIẾP"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(colorAccent)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                        styleTag = TextView(this@SettingsActivity).apply {
                            text = "Kiểu #$activeHudStyleId"
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                            background = rounded(colorAccent, 6f)
                            setPadding(dp(8), dp(3), dp(8), dp(3))
                        }
                        addView(styleTag)
                    }
                    addView(pHeader)

                    // Dashboard Frame window
                    val previewFrame = FrameLayout(this@SettingsActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(8)
                            bottomMargin = dp(8)
                        }
                        minimumHeight = dp(100)
                        setPadding(dp(12), dp(14), dp(12), dp(14))
                        background = rounded(Color.parseColor("#080D14"), 12f, Color.parseColor("#1E293B"), 1)
                    }
                    previewFrame.addView(livePreviewOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    addView(previewFrame)
                }
                content.addView(previewContainer)

                val cardUpdaters = mutableListOf<() -> Unit>()

                // Button: Tùy Chỉnh 4 Mẫu HUD & Cài Đặt Chi Tiết (Opens Dialog)
                val customizeHudBtn = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(colorPillBg, 10f, colorAccent, 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                        bottomMargin = dp(10)
                    }

                    addView(TextView(this@SettingsActivity).apply {
                        text = "🎨 Tùy Chỉnh 5 Mẫu HUD, Thu Phóng & Độ Mờ ›"
                        textSize = 12.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(colorPillText)
                    })

                    setOnClickListener {
                        showWazeHudCustomizerDialog {
                            activeHudStyleId = WazeHudManager.getActiveStyleId(this@SettingsActivity)
                            activeBadge.text = "Đang dùng #$activeHudStyleId"
                            styleTag.text = "Kiểu #$activeHudStyleId"
                            livePreviewOverlay.applyHudConfig(activeHudStyleId)
                            livePreviewOverlay.updateUi(VietmapStateRepository.alertState.value)
                            inAppFloatingHud?.applyHudConfig(activeHudStyleId)
                            cardUpdaters.forEach { it.invoke() }
                        }
                    }
                }
                content.addView(customizeHudBtn)

                // 2-Column Grid of 4 HUD Styles (Matching media_1789455999381.png)
                val gridContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val allStyles = WazeHudManager.STYLES

                for (i in allStyles.indices step 2) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(8)
                        }
                    }

                    for (j in 0..1) {
                        if (i + j < allStyles.size) {
                            val style = allStyles[i + j]

                            val numberTv = TextView(this@SettingsActivity).apply {
                                text = "#${style.id}"
                                textSize = 11.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#94A3B8"))
                            }

                            val activeTagTv = TextView(this@SettingsActivity).apply {
                                text = "✓ Đang dùng"
                                textSize = 10.5f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(Color.parseColor("#06B6D4"))
                            }

                            val titleTv = TextView(this@SettingsActivity).apply {
                                text = style.name.replace(Regex("^#\\d+\\s*"), "")
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(colorTextPrimary)
                                setPadding(0, dp(4), 0, dp(2))
                            }

                            val descTv = TextView(this@SettingsActivity).apply {
                                text = style.description
                                textSize = 9.5f
                                setTextColor(colorTextSecondary)
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
                            }

                            val divider = View(this@SettingsActivity).apply {
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                                    topMargin = dp(6)
                                    bottomMargin = dp(6)
                                }
                                setBackgroundColor(if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"))
                            }

                            val catTv = TextView(this@SettingsActivity).apply {
                                text = style.category
                                textSize = 9.5f
                                setTextColor(colorTextSecondary)
                            }

                            val actionTv = TextView(this@SettingsActivity).apply {
                                text = "Chọn"
                                textSize = 11f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(colorAccent)
                            }

                            val styleCard = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(11), dp(9), dp(11), dp(9))
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                    if (j == 0) marginEnd = dp(4) else marginStart = dp(4)
                                }
                                isClickable = true
                                isFocusable = true

                                val topRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(numberTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                    addView(activeTagTv)
                                }
                                addView(topRow)
                                addView(titleTv)
                                addView(descTv)
                                addView(divider)

                                val bottomRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(catTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                    addView(actionTv)
                                }
                                addView(bottomRow)
                            }

                            val updateCardState = {
                                val isSelected = (style.id == activeHudStyleId)
                                val bgCol = if (isSelected) {
                                    if (isDarkTheme) Color.parseColor("#0D2034") else Color.parseColor("#E0F2FE")
                                } else colorItemBg
                                val borderCol = if (isSelected) Color.parseColor("#06B6D4") else colorItemBorder
                                styleCard.background = rounded(bgCol, 12f, borderCol, if (isSelected) 2 else 1)
                                activeTagTv.visibility = if (isSelected) View.VISIBLE else View.GONE
                                titleTv.setTextColor(if (isSelected) Color.parseColor("#06B6D4") else colorTextPrimary)
                                numberTv.setTextColor(if (isSelected) Color.parseColor("#06B6D4") else Color.parseColor("#94A3B8"))
                                actionTv.text = if (isSelected) "Đang bật" else "Chọn"
                                actionTv.setTextColor(if (isSelected) Color.parseColor("#06B6D4") else colorAccent)
                            }
                            updateCardState()
                            cardUpdaters.add(updateCardState)

                            styleCard.setOnClickListener {
                                WazeHudManager.setActiveStyleId(this@SettingsActivity, style.id)
                                activeHudStyleId = style.id
                                activeBadge.text = "Đang dùng #$activeHudStyleId"
                                styleTag.text = "Kiểu #$activeHudStyleId"
                                livePreviewOverlay.applyHudConfig(style.id)
                                livePreviewOverlay.updateUi(VietmapStateRepository.alertState.value)
                                inAppFloatingHud?.applyHudConfig(style.id)
                                cardUpdaters.forEach { it.invoke() }
                                Toast.makeText(this@SettingsActivity, "Đã kích hoạt: ${style.name}", Toast.LENGTH_SHORT).show()
                            }
                            row.addView(styleCard)
                        } else {
                            val spacer = View(this).apply {
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                                    marginStart = dp(4)
                                }
                            }
                            row.addView(spacer)
                        }
                    }
                    gridContainer.addView(row)
                }
                content.addView(gridContainer)

                val hudFootnote = TextView(this).apply {
                    text = "*Chạm nút 🔓/🔒 trên HUD để khóa vị trí hoặc mở khóa để kéo thả tới vị trí mong muốn trên màn hình xe.*"
                    textSize = 11f
                    setTypeface(typeface, Typeface.ITALIC)
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(6), 0, dp(8))
                }
                content.addView(hudFootnote)
            }
        )

        // ==========================================
        // CARD 5: QUẢN LÝ ỨNG DỤNG XE & THANH DOCK
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "📱",
                iconBgColor = if (isDarkTheme) Color.parseColor("#164E63") else Color.parseColor("#CFFAFE"),
                title = "QUẢN LÝ ỨNG DỤNG XE & THANH DOCK",
                subtitle = "Chọn app hiển thị trên thanh dock, thêm hoặc xóa app",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                val allDockApps = WebAppManager.getAllApps(this)
                val enabledDockCount = allDockApps.count { WebAppManager.isAppEnabled(this, it.id) }
                content.addView(
                    settingCard(
                        title = "ỨNG DỤNG TRÊN THANH DOCK",
                        subtitle = "Bật/tắt các biểu tượng app trên thanh dock ô tô",
                        badgeText = "$enabledDockCount / ${allDockApps.size} app",
                        onClick = {
                            val apps = WebAppManager.getAllApps(this)
                            val names = apps.map { it.name }.toTypedArray()
                            val checked = apps.map { WebAppManager.isAppEnabled(this, it.id) }.toBooleanArray()
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Bật/tắt ứng dụng trên thanh dock")
                                .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                                    checked[which] = isChecked
                                }
                                .setPositiveButton("Lưu") { _, _ ->
                                    for (i in apps.indices) {
                                        WebAppManager.setAppEnabled(this, apps[i].id, checked[i])
                                    }
                                    recreate()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    )
                )

                content.addView(createSeparator())

                val addAppBtn = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    background = rounded(if (isDarkTheme) Color.parseColor("#0284C7") else Color.parseColor("#0284C7"), 10f, Color.parseColor("#38BDF8"), 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(10)
                    }

                    val addIcon = TextView(this@SettingsActivity).apply {
                        text = "➕ "
                        textSize = 15f
                        setTextColor(Color.WHITE)
                    }
                    addView(addIcon)

                    val addText = TextView(this@SettingsActivity).apply {
                        text = "Thêm ứng dụng mới vào xe"
                        textSize = 13.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }
                    addView(addText)

                    setOnClickListener {
                        showAddAppDialog()
                    }
                }
                content.addView(addAppBtn)

                val appsList = WebAppManager.getAllApps(this)
                for (app in appsList) {
                    val isEnabled = WebAppManager.isAppEnabled(this, app.id)
                    val appRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(9), dp(12), dp(9))
                        background = rounded(colorItemBg, 10f, colorItemBorder, 1)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(6)
                        }

                        val appName = TextView(this@SettingsActivity).apply {
                            text = (if (app.isBuiltIn) "📱 " else "🌐 ") + app.name + if (!isEnabled) " (Đã tắt)" else ""
                            textSize = 13.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(if (isEnabled) colorTextPrimary else colorTextMuted)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(appName)

                        val delBtn = TextView(this@SettingsActivity).apply {
                            text = "🗑️ Xóa"
                            textSize = 11.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor("#EF4444"))
                            background = rounded(if (isDarkTheme) Color.parseColor("#33EF4444") else Color.parseColor("#FEE2E2"), 8f, Color.parseColor("#EF4444"), 1)
                            setPadding(dp(10), dp(5), dp(10), dp(5))
                            setOnClickListener {
                                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                    .setTitle("Xóa ứng dụng")
                                    .setMessage("Bạn có chắc muốn xóa \"${app.name}\" khỏi danh sách ứng dụng trên xe không?")
                                    .setPositiveButton("Xóa") { _, _ ->
                                        WebAppManager.deleteApp(this@SettingsActivity, app.id)
                                        Toast.makeText(this@SettingsActivity, "Đã xóa ${app.name}", Toast.LENGTH_SHORT).show()
                                        recreate()
                                    }
                                    .setNegativeButton("Hủy", null)
                                    .show()
                            }
                        }
                        addView(delBtn)
                    }
                    content.addView(appRow)
                }

                val resetAppsBtn = TextView(this).apply {
                    text = "🔄 Khôi phục tất cả ứng dụng mặc định"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorAccent)
                    gravity = Gravity.CENTER
                    background = rounded(colorPillBg, 10f, colorAccent, 1)
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    }
                    setOnClickListener {
                        WebAppManager.restoreDefaultApps(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity, "Đã khôi phục toàn bộ ứng dụng mặc định", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
                content.addView(resetAppsBtn)
            }
        )

        // ==========================================
        // CARD 6: TÍNH NĂNG NÂNG CAO & THÔNG TIN HỆ THỐNG
        // ==========================================
        root.addView(
            createExpandableCard(
                iconEmoji = "⚙️",
                iconBgColor = if (isDarkTheme) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0"),
                title = "TÍNH NĂNG NÂNG CAO & THÔNG TIN",
                subtitle = "Xuất/nạp cấu hình, phiên bản app, phân tích lỗi & bộ nhớ",
                initiallyExpanded = false
            ) { content ->
                content.addView(createSeparator())

                content.addView(
                    settingCard(
                        title = "PHIÊN BẢN THTV PRO",
                        subtitle = "Phiên bản: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\nChạm để xem nhật ký cập nhật ✨",
                        badgeText = "v${BuildConfig.VERSION_NAME}",
                        onClick = {
                            ChangelogManager.showChangelogDialog(this@SettingsActivity)
                        }
                    )
                )

                content.addView(settingCard(
                    title = "KIỂM TRA CẬP NHẬT",
                    subtitle = "Kiểm tra phiên bản mới và xem nội dung cập nhật",
                    onClick = { UpdateNotificationManager.check(this@SettingsActivity, manual = true) }
                ))

                content.addView(settingCard(
                    title = "XUẤT CẤU HÌNH & YÊU THÍCH",
                    subtitle = "Lưu giao diện, HUD, phím vô lăng, ứng dụng và kênh yêu thích vào file JSON. Không kèm ảnh nền/biểu tượng tùy chỉnh.",
                    badgeText = "Xuất file",
                    onClick = {
                        val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                        exportSettings.launch("THTV-settings-$date.json")
                    }
                ))
                content.addView(settingCard(
                    title = "NẠP CẤU HÌNH & YÊU THÍCH",
                    subtitle = "Chọn file cấu hình THTV đã xuất; xem số tùy chọn trước khi nạp.",
                    badgeText = "Chọn file",
                    onClick = { importSettings.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
                ))

                content.addView(createSeparator())

                content.addView(
                    settingCard(
                        title = "BẢN QUYỀN THTV PRO",
                        subtitle = "THTV • Đã mở khóa đầy đủ tính năng\nMã máy: ${LicenseManager.getDeviceId(this@SettingsActivity)}",
                        badgeText = "● ĐÃ MỞ KHÓA",
                        onClick = {
                            val email = LicenseManager.getLicenseEmail(this@SettingsActivity).ifEmpty { "Chưa đăng ký" }
                            val plan = LicenseManager.getLicensePlan(this@SettingsActivity).ifEmpty { "VĨNH VIỄN" }
                            val expiry = LicenseManager.getLicenseExpiry(this@SettingsActivity).ifEmpty { "Trọn đời" }
                            val deviceId = LicenseManager.getDeviceId(this@SettingsActivity)

                            AlertDialog.Builder(this@SettingsActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("🔑 BẢN QUYỀN THTV PRO")
                                .setMessage(
                                    "• Mã máy: $deviceId\n" +
                                    "• Email: $email\n" +
                                    "• Gói: $plan\n" +
                                    "• Hạn dùng: $expiry\n\n" +
                                    "Trạng thái: ĐÃ MỞ KHÓA HOÀN TOÀN"
                                )
                                .setPositiveButton("Đóng", null)
                                .setNeutralButton("Đăng xuất để kiểm duyệt") { _, _ ->
                                    LicenseManager.resetLicense(this@SettingsActivity)
                                    val intent = Intent(this@SettingsActivity, ActivationActivity::class.java)
                                    startActivity(intent)
                                    finish()
                                }
                                .show()
                        }
                    )
                )

                content.addView(createSeparator())

                val logsCount = AppCrashHandler.getErrorLogs(this).size
                content.addView(
                    settingCard(
                        title = "BÁO CÁO & PHÂN TÍCH LỖI (CRASH LOGS)",
                        subtitle = if (logsCount > 0) "Phát hiện $logsCount nhật ký lỗi hệ thống • Chạm để phân tích" else "Chưa phát hiện lỗi nào • Hệ thống hoạt động ổn định",
                        badgeText = if (logsCount > 0) "⚠️ $logsCount lỗi" else "● Báo cáo",
                        onClick = {
                            showErrorLogsDialog()
                        }
                    )
                )

                content.addView(
                    settingCard(
                        title = "XÓA BỘ NHỚ ĐỆM (CLEAR CACHE)",
                        subtitle = "Dọn sạch cache tạm thời của WebView và hình ảnh",
                        badgeText = "Xóa cache",
                        onClick = {
                            try {
                                cacheDir.deleteRecursively()
                                Toast.makeText(this@SettingsActivity, "Đã dọn dẹp bộ nhớ đệm thành công!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "Lỗi khi dọn cache: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                )

                content.addView(
                    settingCard(
                        title = "ĐẶT LẠI CÀI ĐẶT GỐC",
                        subtitle = "Khôi phục toàn bộ tùy chọn về trạng thái ban đầu",
                        badgeText = "Khôi phục",
                        onClick = {
                            androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("Khôi phục cài đặt gốc?")
                                .setMessage("Toàn bộ thiết lập giao diện, thanh công cụ, kích thước và phím bấm sẽ được đặt lại về mặc định ban đầu.")
                                .setPositiveButton("Đặt lại") { _, _ ->
                                    prefs.edit().clear().apply()
                                    Toast.makeText(this@SettingsActivity, "Đã khôi phục toàn bộ cài đặt gốc", Toast.LENGTH_SHORT).show()
                                    recreate()
                                }
                                .setNegativeButton("Hủy", null)
                                .show()
                        }
                    )
                )
            }
        )

        val backBtn = TextView(this).apply {
            text = "← Đóng & Lưu Cài Đặt"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(colorAccent, 14f)
            setOnClickListener { finish() }
        }
        val btnParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(6)
            bottomMargin = dp(16)
        }
        root.addView(backBtn, btnParams)

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(colorBg)
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun createExpandableCard(
        iconEmoji: String,
        iconBgColor: Int,
        title: String,
        subtitle: String,
        initiallyExpanded: Boolean = false,
        builder: (LinearLayout) -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(colorCard, 16f, colorCardBorder, 1)
            val p = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
            layoutParams = p
        }

        val contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        builder(contentContainer)

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true
            isFocusable = true

            // Category Icon Box
            val iconBadge = TextView(this@SettingsActivity).apply {
                text = iconEmoji
                textSize = 19f
                gravity = Gravity.CENTER
                background = rounded(iconBgColor, 12f)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginEnd = dp(12)
                }
            }
            addView(iconBadge)

            val textLayout = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(textLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))

            val chevron = ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_chevron_down)
                setColorFilter(if (initiallyExpanded) colorAccent else colorTextSecondary)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(
                    if (initiallyExpanded) colorPillBg else colorItemBg,
                    15f,
                    if (initiallyExpanded) colorAccent else colorItemBorder,
                    1
                )
                setPadding(dp(7), dp(7), dp(7), dp(7))
                rotation = if (initiallyExpanded) 180f else 0f
            }
            addView(chevron, LinearLayout.LayoutParams(dp(30), dp(30)))

            setOnClickListener {
                val isExp = (contentContainer.visibility == View.VISIBLE)
                if (isExp) {
                    contentContainer.visibility = View.GONE
                    chevron.animate().rotation(0f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    chevron.setColorFilter(colorTextSecondary)
                    chevron.background = rounded(colorItemBg, 15f, colorItemBorder, 1)
                } else {
                    contentContainer.visibility = View.VISIBLE
                    chevron.animate().rotation(180f).setDuration(220).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    chevron.setColorFilter(colorAccent)
                    chevron.background = rounded(colorPillBg, 15f, colorAccent, 1)
                }
            }
        }

        card.addView(headerLayout)
        card.addView(contentContainer)
        return card
    }

    private fun createCarScreenDetailsCard(): LinearLayout {
        val w = prefs.getInt("car_screen_width", if (CarMediaManager.carScreenWidth > 0) CarMediaManager.carScreenWidth else 1024)
        val h = prefs.getInt("car_screen_height", if (CarMediaManager.carScreenHeight > 0) CarMediaManager.carScreenHeight else 600)
        val dpi = prefs.getInt("car_screen_dpi", if (CarMediaManager.carScreenDpi > 0) CarMediaManager.carScreenDpi else 160)
        val isConnected = prefs.getBoolean("car_screen_connected", CarMediaManager.isCarConnected)
        val usableW = prefs.getInt("car_screen_usable_width", w)
        val usableH = prefs.getInt("car_screen_usable_height", h)
        val webW = prefs.getInt("car_screen_web_width", usableW)
        val webH = prefs.getInt("car_screen_web_height", usableH)
        val refreshHz = prefs.getFloat("car_screen_refresh_rate", 0f)
        val screenSignature = prefs.getString("car_screen_signature", "") ?: ""
        val deviceId = LicenseManager.getDeviceId(this)
        val lastSyncMs = ScreenProfileReporter.getLastSyncTime(this)
        val lastSyncText = if (lastSyncMs > 0L) {
            java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastSyncMs))
        } else {
            "Chưa có"
        }

        val ratioVal = if (h > 0) w.toFloat() / h.toFloat() else 1.71f
        val screenTypeName = when {
            ratioVal >= 2.05f -> "Siêu rộng (Ultra-wide ~21:9)"
            ratioVal >= 1.55f -> "Ngang chuẩn (Landscape 16:9)"
            ratioVal >= 1.25f -> "Ngang vuông (4:3 / 3:2)"
            ratioVal <= 0.85f -> "Dọc xe hơi (Portrait)"
            else -> "Vuông chuẩn (~1:1)"
        }
        val densityFactor = String.format(java.util.Locale.US, "%.1f", dpi / 160f)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(colorItemBg, 12f, Color.parseColor("#0284C7"), 1)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }

            // Header Row
            val headerRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }

                val title = TextView(this@SettingsActivity).apply {
                    text = "📺 CHI TIẾT KÍCH THƯỚC MÀN HÌNH XE"
                    textSize = 12.5f
                    setTextColor(Color.parseColor("#38BDF8"))
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(title)

                val statusBadge = TextView(this@SettingsActivity).apply {
                    text = if (isConnected) "🟢 Đã kết nối" else "⚪ Đã ghi nhận"
                    textSize = 10.5f
                    setTextColor(if (isConnected) Color.parseColor("#4ADE80") else Color.parseColor("#94A3B8"))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(7), dp(2), dp(7), dp(2))
                    background = rounded(if (isConnected) Color.parseColor("#1A22C55E") else Color.parseColor("#1A64748B"), 8f, if (isConnected) Color.parseColor("#22C55E") else Color.parseColor("#64748B"), 1)
                }
                addView(statusBadge)
            }
            addView(headerRow)

            // Info Grid: 2 columns
            val grid = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }

            // Col 1: Resolution & DPI
            val col1 = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@SettingsActivity).apply {
                    text = "• Độ phân giải: ${w} × ${h} px"
                    textSize = 12f
                    setTextColor(colorTextPrimary)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "• Mật độ: ${dpi} DPI (${densityFactor}x scale)"
                    textSize = 11.5f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(3), 0, 0)
                })
            }
            grid.addView(col1)

            // Col 2: Ratio & Layout Type
            val col2 = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                    leftMargin = dp(8)
                }

                addView(TextView(this@SettingsActivity).apply {
                    text = "• Tỉ lệ: ${String.format(java.util.Locale.US, "%.2f", ratioVal)}:1"
                    textSize = 12f
                    setTextColor(colorTextPrimary)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "• Kiểu màn: $screenTypeName"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#38BDF8"))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            grid.addView(col2)
            addView(grid)

            addView(TextView(this@SettingsActivity).apply {
                text = "• Vùng dùng được: ${usableW} × ${usableH} px  •  WebView: ${webW} × ${webH} px"
                textSize = 11f
                setTextColor(colorTextSecondary)
                setPadding(0, dp(8), 0, 0)
            })

            addView(TextView(this@SettingsActivity).apply {
                val hz = if (refreshHz > 0f) String.format(java.util.Locale.US, "%.1f Hz", refreshHz) else "chưa rõ"
                text = "• Refresh: $hz  •  Mã máy: $deviceId"
                textSize = 11f
                setTextColor(colorTextSecondary)
                setPadding(0, dp(3), 0, 0)
            })

            if (screenSignature.isNotBlank()) {
                addView(TextView(this@SettingsActivity).apply {
                    text = "• Screen ID: $screenSignature"
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#64748B"))
                    setPadding(0, dp(3), 0, 0)
                })
            }

            val syncRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, 0)
            }

            val syncStatusTv = TextView(this@SettingsActivity).apply {
                val status = ScreenProfileReporter.getLastStatus(this@SettingsActivity)
                text = "☁️ $status • Lần cuối: $lastSyncText"
                textSize = 10.8f
                setTextColor(
                    if (status.startsWith("Đã đồng bộ")) Color.parseColor("#4ADE80")
                    else Color.parseColor("#94A3B8")
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            syncRow.addView(syncStatusTv)

            val syncBtn = TextView(this@SettingsActivity).apply {
                text = "↻ Đồng bộ"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#38BDF8"))
                background = rounded(colorPillBg, 8f, Color.parseColor("#0284C7"), 1)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener {
                    syncStatusTv.text = "☁️ Đang đồng bộ Google Sheet…"
                    syncStatusTv.setTextColor(Color.parseColor("#38BDF8"))
                    ScreenProfileReporter.syncLastProfileIfAvailable(this@SettingsActivity, force = true)
                    postDelayed({
                        val status = ScreenProfileReporter.getLastStatus(this@SettingsActivity)
                        val syncedAt = ScreenProfileReporter.getLastSyncTime(this@SettingsActivity)
                        val whenText = if (syncedAt > 0L) {
                            java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(syncedAt))
                        } else "Chưa có"
                        syncStatusTv.text = "☁️ $status • Lần cuối: $whenText"
                        syncStatusTv.setTextColor(
                            if (status.startsWith("Đã đồng bộ")) Color.parseColor("#4ADE80")
                            else Color.parseColor("#94A3B8")
                        )
                    }, 1800L)
                }
            }
            syncRow.addView(syncBtn)
            addView(syncRow)
        }
    }

    private fun showSystemVoiceTestDialog() {
        val input = EditText(this).apply {
            hint = "Ví dụ: mở kênh VTV1 hoặc mở nhạc Sơn Tùng"
            setText("mở kênh VTV1")
            setSelectAllOnFocus(true)
            setSingleLine(true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Thử lệnh nhạc / TV")
            .setMessage("Lệnh sẽ chạy trên THTV đang mở ở Android Auto. Đây là thử xử lý lệnh, không gọi mic Google.")
            .setView(input)
            .setPositiveButton("Thử lệnh") { _, _ ->
                val result = SystemVoiceModule.submit(this, input.text.toString(), source = "Thử trong Cài đặt")
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Hủy", null).show()
    }

    private fun createSwitchRow(title: String, subtitle: String, key: String, default: Boolean): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(9), dp(2), dp(9))
            isClickable = true
            isFocusable = true

            val textLayout = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 13.5f
                    setTextColor(colorTextPrimary)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(textLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))

            val sw = Switch(this@SettingsActivity).apply {
                isChecked = prefs.getBoolean(key, default)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(key, isChecked).apply()
                }
            }
            addView(sw)

            setOnClickListener {
                sw.toggle()
            }
        }
    }

    private fun createSeparator(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            setBackgroundColor(colorDivider)
        }
    }

    private fun settingCard(
        title: String,
        subtitle: String,
        badgeText: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(12), dp(11))
            background = rounded(colorItemBg, 12f, colorItemBorder, 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            val textLayout = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = title
                    textSize = 13.5f
                    setTextColor(colorTextPrimary)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(textLayout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f))

            if (!badgeText.isNullOrBlank()) {
                val badge = TextView(this@SettingsActivity).apply {
                    text = badgeText
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorPillText)
                    background = rounded(colorPillBg, 8f, colorPillText, 1)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(6)
                    }
                }
                addView(badge)
            }

            val arrow = TextView(this@SettingsActivity).apply {
                text = "›"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(colorTextMuted)
            }
            addView(arrow, LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT))

            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }
    }

    private fun choose(
        title: String,
        labels: Array<String>,
        values: Array<String>,
        key: String,
        onSelected: ((String) -> Unit)? = null
    ) {
        val current = prefs.getString(key, values[0]) ?: values[0]
        val selectedIdx = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                prefs.edit().putString(key, values[which]).apply()
                onSelected?.invoke(values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun requestVoicePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        } else {
            Toast.makeText(this, "Quyền Micro đã được cấp đầy đủ!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Đã cấp quyền Micro thành công!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bạn đã từ chối quyền Micro", Toast.LENGTH_SHORT).show()
            }
            recreate()
        }
    }

    private fun showErrorLogsDialog() {
        val summary = AppCrashHandler.getFormattedSummary(this)
        val logs = AppCrashHandler.getErrorLogs(this)
        val isDark = isDarkTheme

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
            isVerticalScrollBarEnabled = true
        }

        val textView = TextView(this).apply {
            text = summary
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(if (isDark) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B"))
            background = rounded(
                if (isDark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"),
                8f,
                if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"),
                1
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        scroll.addView(textView)
        layout.addView(scroll)

        val builder = AlertDialog.Builder(this)
            .setTitle("📊 Lịch sử phân tích lỗi (${logs.size})")
            .setView(layout)
            .setPositiveButton("📋 Sao chép log") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("THTV Error Logs", summary)
                cm.setPrimaryClip(clip)
                Toast.makeText(this, "📋 Đã sao chép nhật ký lỗi vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
            }

        if (logs.isNotEmpty()) {
            builder.setNeutralButton("🗑️ Xóa nhật ký") { _, _ ->
                AppCrashHandler.clearLogs(this)
                Toast.makeText(this, "Đã xóa toàn bộ nhật ký lỗi!", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }

        builder.setNegativeButton("Đóng", null).show()
    }

    private fun showAddAppDialog() {
        val isDark = isDarkTheme
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val nameLabel = TextView(this).apply {
            text = "Tên ứng dụng:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        dialogLayout.addView(nameLabel)

        val nameInput = EditText(this).apply {
            hint = "Ví dụ: Zalo Web, VOV, Google Maps..."
            setHintTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#0F172A"))
            textSize = 14f
            background = rounded(
                if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9"),
                8f,
                if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"),
                1
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        dialogLayout.addView(nameInput)

        val urlLabel = TextView(this).apply {
            text = "Địa chỉ Web (URL):"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) Color.parseColor("#94A3B8") else Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        dialogLayout.addView(urlLabel)

        val urlInput = EditText(this).apply {
            hint = "Ví dụ: maps.google.com hoặc https://..."
            setHintTextColor(if (isDark) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#0F172A"))
            textSize = 14f
            background = rounded(
                if (isDark) Color.parseColor("#1E293B") else Color.parseColor("#F1F5F9"),
                8f,
                if (isDark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1"),
                1
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        dialogLayout.addView(urlInput)

        val desktopCheck = CheckBox(this).apply {
            text = "Chế độ máy tính (Desktop User-Agent)"
            textSize = 13f
            setTextColor(if (isDark) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        dialogLayout.addView(desktopCheck)

        // Gợi ý thêm nhanh (Quick Suggestions)
        val suggestLabel = TextView(this).apply {
            text = "💡 Gợi ý thêm nhanh (1 chạm):"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        dialogLayout.addView(suggestLabel)

        val chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        val suggestions = listOf(
            Triple("🗺️ Maps", "Google Maps", "https://maps.google.com"),
            Triple("📻 VOV", "VOV Giao thông", "https://vovlive.vn"),
            Triple("📰 VnExpress", "VnExpress", "https://vnexpress.net"),
            Triple("🌐 Google", "Google", "https://www.google.com")
        )

        for ((chipText, sugName, sugUrl) in suggestions) {
            val chip = TextView(this).apply {
                text = chipText
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDark) Color.parseColor("#38BDF8") else Color.parseColor("#0284C7"))
                background = rounded(
                    if (isDark) Color.parseColor("#1E3A5F") else Color.parseColor("#E0F2FE"),
                    8f,
                    if (isDark) Color.parseColor("#0284C7") else Color.parseColor("#BAE6FD"),
                    1
                )
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener {
                    nameInput.setText(sugName)
                    urlInput.setText(sugUrl)
                }
            }
            chipContainer.addView(chip)
        }
        dialogLayout.addView(chipContainer)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("➕ Thêm ứng dụng mới vào xe")
            .setView(dialogLayout)
            .setPositiveButton("Thêm vào xe") { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val finalName = if (name.isNotEmpty()) name else "Web App"
                    WebAppManager.addCustomApp(this, finalName, url, desktopCheck.isChecked)
                    Toast.makeText(this, "Đã thêm \"$finalName\" vào xe!", Toast.LENGTH_SHORT).show()
                    recreate()
                } else {
                    Toast.makeText(this, "Vui lòng nhập địa chỉ web (URL)", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0 && stroke != 0) {
                setStroke(dp(strokeDp), stroke)
            }
        }
    }

    private fun circle(fill: Int, stroke: Int = 0, strokeDp: Float = 0f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeDp > 0f && stroke != 0) {
                setStroke(dp(strokeDp), stroke)
            }
        }
    }

    private fun showWazeHudCustomizerDialog(onApplied: () -> Unit = {}) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

        val rootDialog = FrameLayout(this).apply {
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(if (isDarkTheme) Color.parseColor("#0E1626") else Color.WHITE, 16f, if (isDarkTheme) Color.parseColor("#1E2D44") else Color.parseColor("#CBD5E1"), 1)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Header Row: Title + Subtitle + Close Button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))

            val textCol = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Tùy Chỉnh 5 Mẫu HUD Waze Mod"
                    textSize = 16.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "Chọn 5 mẫu giao diện chuẩn Android Auto, thu phóng, độ mờ và cảnh báo tốc độ"
                    textSize = 11f
                    setTextColor(colorTextSecondary)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val closeBtn = TextView(this@SettingsActivity).apply {
                text = "✕"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextSecondary)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { dialog.dismiss() }
            }
            addView(closeBtn)
        }
        cardContainer.addView(headerRow)

        val scrollContent = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420))
        }
        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(innerLayout)

        // Filter Tabs Row
        val tabs = listOf(
            WazeHudManager.CATEGORY_ALL,
            WazeHudManager.CATEGORY_HORIZONTAL,
            WazeHudManager.CATEGORY_VERTICAL
        )
        var selectedCategory = WazeHudManager.CATEGORY_ALL

        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(10))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tabScroll.addView(tabRow)
        innerLayout.addView(tabScroll)

        val styleListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var selectedStyleId = WazeHudManager.getActiveStyleId(this)

        fun renderStyles() {
            styleListContainer.removeAllViews()
            val filtered = if (selectedCategory == WazeHudManager.CATEGORY_ALL) {
                WazeHudManager.STYLES
            } else {
                WazeHudManager.STYLES.filter { it.category == selectedCategory }
            }

            for (style in filtered) {
                val isSel = (style.id == selectedStyleId)
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    val bgCol = if (isSel) {
                        if (isDarkTheme) Color.parseColor("#152A42") else Color.parseColor("#E0F2FE")
                    } else colorItemBg
                    background = rounded(bgCol, 10f, if (isSel) colorAccent else colorItemBorder, if (isSel) 2 else 1)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    }

                    val infoCol = LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL

                        val titleRow = LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL

                            val titleTv = TextView(this@SettingsActivity).apply {
                                text = style.name
                                textSize = 13f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(if (isSel) colorAccent else colorTextPrimary)
                            }
                            addView(titleTv)

                            val catBadge = TextView(this@SettingsActivity).apply {
                                text = style.category
                                textSize = 9.5f
                                setTextColor(colorTextSecondary)
                                background = rounded(colorCard, 4f, colorItemBorder, 1)
                                setPadding(dp(5), dp(1), dp(5), dp(1))
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                    marginStart = dp(6)
                                }
                            }
                            addView(catBadge)
                        }
                        addView(titleRow)

                        val descTv = TextView(this@SettingsActivity).apply {
                            text = style.description
                            textSize = 10.5f
                            setTextColor(colorTextSecondary)
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(descTv)
                    }
                    addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                    val checkIcon = TextView(this@SettingsActivity).apply {
                        text = if (isSel) "✓" else "○"
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(if (isSel) colorAccent else colorTextSecondary)
                        setPadding(dp(6), 0, 0, 0)
                    }
                    addView(checkIcon)

                    setOnClickListener {
                        selectedStyleId = style.id
                        WazeHudManager.setActiveStyleId(this@SettingsActivity, style.id)
                        inAppFloatingHud?.applyHudConfig(style.id)
                        renderStyles()
                    }
                }
                styleListContainer.addView(item)
            }
        }

        fun updateTabs() {
            tabRow.removeAllViews()
            for (cat in tabs) {
                val isAct = (cat == selectedCategory)
                val tabChip = TextView(this).apply {
                    text = cat
                    textSize = 11.5f
                    typeface = if (isAct) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setTextColor(if (isAct) Color.WHITE else colorTextSecondary)
                    background = rounded(if (isAct) colorAccent else colorItemBg, 14f, if (isAct) colorAccent else colorItemBorder, 1)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6)
                    }
                    setOnClickListener {
                        selectedCategory = cat
                        updateTabs()
                        renderStyles()
                    }
                }
                tabRow.addView(tabChip)
            }
        }

        updateTabs()
        renderStyles()
        innerLayout.addView(styleListContainer)

        // Separator
        innerLayout.addView(createSeparator())

        // Scale Slider: 60% to 150%
        var curScale = WazeHudManager.getScale(this)
        val scaleLabel = TextView(this).apply {
            text = "Kích thước thu phóng (Scale): $curScale%"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(0, dp(4), 0, dp(2))
        }
        innerLayout.addView(scaleLabel)

        val scaleSeekBar = SeekBar(this).apply {
            max = 90 // 60 to 150 (range 90)
            progress = curScale - 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val s = progress + 60
                    curScale = s
                    scaleLabel.text = "Kích thước thu phóng (Scale): $s%"
                    WazeHudManager.setScale(this@SettingsActivity, s)
                    inAppFloatingHud?.applyHudConfig()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        innerLayout.addView(scaleSeekBar)

        // Scale marker row
        val scaleMarkers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SettingsActivity).apply { text = "60%"; textSize = 10f; setTextColor(colorTextSecondary) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply { text = "100%"; textSize = 10f; setTextColor(colorTextSecondary); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply { text = "150%"; textSize = 10f; setTextColor(colorTextSecondary); gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        innerLayout.addView(scaleMarkers)

        // Opacity Slider: 50% to 100%
        var curOpacity = WazeHudManager.getOpacity(this)
        val opacityLabel = TextView(this).apply {
            text = "Độ đậm/trong suốt (Opacity): $curOpacity%"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(0, dp(8), 0, dp(2))
        }
        innerLayout.addView(opacityLabel)

        val opacitySeekBar = SeekBar(this).apply {
            max = 50 // 50 to 100
            progress = curOpacity - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val o = progress + 50
                    curOpacity = o
                    opacityLabel.text = "Độ đậm/trong suốt (Opacity): $o%"
                    WazeHudManager.setOpacity(this@SettingsActivity, o)
                    inAppFloatingHud?.applyHudConfig()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        innerLayout.addView(opacitySeekBar)

        // Opacity marker row
        val opacityMarkers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@SettingsActivity).apply { text = "50%"; textSize = 10f; setTextColor(colorTextSecondary) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply { text = "80%"; textSize = 10f; setTextColor(colorTextSecondary); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@SettingsActivity).apply { text = "100%"; textSize = 10f; setTextColor(colorTextSecondary); gravity = Gravity.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        innerLayout.addView(opacityMarkers)

        innerLayout.addView(createSeparator())

        // Section: CẢNH BÁO & HIỂN THỊ THÔNG MINH
        val smartAlertHeader = TextView(this).apply {
            text = "CẢNH BÁO & HIỂN THỊ THÔNG MINH"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
            setPadding(0, dp(4), 0, dp(6))
        }
        innerLayout.addView(smartAlertHeader)

        // HUD Position Presets & Drag Tip
        val pos = WazeHudManager.getPosition(this)
        val posLabel = TextView(this).apply {
            text = "Vị trí hiển thị HUD trên ô tô:"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            setPadding(0, dp(4), 0, dp(4))
        }
        innerLayout.addView(posLabel)

        val posBadge = TextView(this).apply {
            text = "Tọa độ hiện tại: (${pos.first}dp, ${pos.second}dp)"
            textSize = 11.5f
            setTextColor(colorAccent)
            setPadding(0, 0, 0, dp(6))
        }
        innerLayout.addView(posBadge)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

        fun createPresetChip(title: String, px: Int, py: Int): TextView {
            return TextView(this@SettingsActivity).apply {
                text = title
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorTextPrimary)
                background = rounded(colorItemBg, 8f, colorItemBorder, 1)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    WazeHudManager.setPosition(this@SettingsActivity, px, py)
                    posBadge.text = "Tọa độ hiện tại: (${px}dp, ${py}dp)"
                    Toast.makeText(this@SettingsActivity, "Đã đặt vị trí: $title", Toast.LENGTH_SHORT).show()
                }
            }
        }

        presetRow.addView(createPresetChip("Góc Trái", 20, 10))
        presetRow.addView(createPresetChip("Trên Giữa", 200, 10))
        presetRow.addView(createPresetChip("Góc Phải", 420, 10))
        presetRow.addView(createPresetChip("Mặc Định", 28, 32))
        innerLayout.addView(presetRow)

        val dragTipCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(if (isDarkTheme) Color.parseColor("#132338") else Color.parseColor("#E0F2FE"), 10f, colorAccent, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }

            val tipText = TextView(this@SettingsActivity).apply {
                text = "💡 Mẹo kéo thả trực tiếp trên xe:\nTrên màn hình Android Auto, bạn có thể chạm giữ và kéo thanh HUD đến bất kỳ vị trí nào trên toàn màn hình. Sau khi chọn được vị trí ưng ý, bấm vào nút 🔓 để khóa lại thành 🔒 Đã khóa cố định."
                textSize = 11.5f
                setTextColor(if (isDarkTheme) Color.parseColor("#BAE6FD") else Color.parseColor("#0369A1"))
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }
            addView(tipText)
        }
        innerLayout.addView(dragTipCard)

        // Switch 0: Khóa cố định vị trí HUD
        val lockSwitch = Switch(this).apply {
            isChecked = WazeHudManager.isLocked(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                WazeHudManager.setLocked(this@SettingsActivity, isChecked)
                inAppFloatingHud?.applyHudConfig()
                Toast.makeText(
                    this@SettingsActivity,
                    if (isChecked) "🔒 Đã khóa vị trí HUD" else "🔓 Đã mở khóa vị trí HUD - có thể kéo thả",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            val textCol = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Khóa cố định vị trí HUD"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "Khóa vị trí để tránh bị dịch chuyển khi chạm màn hình"
                    textSize = 11f
                    setTextColor(colorTextSecondary)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(lockSwitch)
        }
        innerLayout.addView(lockRow)

        // Switch 1: Phân làn đường
        val laneSwitch = Switch(this).apply {
            isChecked = WazeHudManager.isLaneGuidanceEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                WazeHudManager.setLaneGuidanceEnabled(this@SettingsActivity, isChecked)
            }
        }
        val laneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            val textCol = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Hiện chỉ dẫn phân làn đường"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(laneSwitch)
        }
        innerLayout.addView(laneRow)

        // Switch 2: Camera phạt nguội
        val camSwitch = Switch(this).apply {
            isChecked = WazeHudManager.isSpeedCameraEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                WazeHudManager.setSpeedCameraEnabled(this@SettingsActivity, isChecked)
            }
        }
        val camRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            val textCol = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Báo camera phạt nguội"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(camSwitch)
        }
        innerLayout.addView(camRow)

        // Switch 3: Âm thanh cảnh báo quá tốc độ
        val audioSwitch = Switch(this).apply {
            isChecked = WazeHudManager.isOverspeedAudioEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                WazeHudManager.setOverspeedAudioEnabled(this@SettingsActivity, isChecked)
            }
        }
        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            val textCol = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@SettingsActivity).apply {
                    text = "Âm thanh cảnh báo quá tốc độ"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(colorTextPrimary)
                })
            }
            addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(audioSwitch)
        }
        innerLayout.addView(audioRow)

        // Tip footer
        val tipFooter = TextView(this).apply {
            text = "* Mẹo: Chạm vào biểu tượng 🔓/🔒 trên HUD để khóa vị trí hoặc mở khóa kéo thả tự do."
            textSize = 11f
            setTypeface(typeface, Typeface.ITALIC)
            setTextColor(colorTextSecondary)
            setPadding(0, dp(8), 0, dp(8))
        }
        innerLayout.addView(tipFooter)

        cardContainer.addView(scrollContent)

        // Bottom Action Button: Đóng & Áp Dụng
        val applyBtn = TextView(this).apply {
            text = "Đóng & Áp Dụng"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(colorAccent, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
            setOnClickListener {
                dialog.dismiss()
                onApplied()
            }
        }
        cardContainer.addView(applyBtn)

        rootDialog.addView(cardContainer)
        dialog.setContentView(rootDialog)
        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
}
