package com.carhud.aaproxy

import com.carhud.app.R
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Modern Automotive Launcher Home Dashboard
 * Matches user's cockpit UI:
 * - Dynamic Greeting ("Chào buổi trưa, Phạm Nam")
 * - Large Bold Clock ("11:49")
 * - Solar Date ("Thứ Ba, 15/09/2026")
 * - Vietnamese Lunar Calendar with Can Chi ("ÂL: 05/08 - Năm Bính Ngọ")
 * - Capsule Search Pill
 * - Top-Right Frosted Action Buttons (Notification & Settings)
 * - Fullscreen Wallpaper (Homer Simpson / Scenic Meadow / Cockpit Dark / Custom)
 * - Bottom Dock:
 *     * 4 App Tiles (VTVgo, YouTube, Trình duyệt, Dấu trang)
 *     * Mini Media Player Widget (Thumbnail, Title, Heart, Scrubber, Prev/Play/Next)
 */
class CarDashboardView(
    context: Context,
    private val onAppClick: (WebAppItem) -> Unit,
    private val onAddAppClick: () -> Unit,
    private val onAllAppsClick: () -> Unit,
    private val onFullscreenRequested: ((WebAppItem) -> Unit)? = null,
    private val onBackClick: (() -> Unit)? = null,
    private val onEmbeddedClosed: (() -> Unit)? = null,
    private val onSearchClick: (() -> Unit)? = null,
    private val onSettingsClick: (() -> Unit)? = null,
    private val onNotificationClick: (() -> Unit)? = null,
    private val onVoiceClick: (() -> Unit)? = null
) : FrameLayout(context) {

    constructor(context: Context, attrs: AttributeSet?) : this(
        context = context,
        onAppClick = {},
        onAddAppClick = {},
        onAllAppsClick = {}
    )

    companion object {
        const val PREF_USER_NAME = "pref_cockpit_user_name"
        const val DEFAULT_USER_NAME = "Phạm Nam"
        const val PREF_WALLPAPER_TYPE = "pref_cockpit_wallpaper_type"
        const val WALLPAPER_BUGATTI = "bugatti"
        const val WALLPAPER_SILVER = "silver"
        const val WALLPAPER_BLUE = "blue"
        const val WALLPAPER_CYBER = "cyber"
        const val WALLPAPER_SUNSET = "sunset"
        const val WALLPAPER_DARK = "dark"
        const val WALLPAPER_SCENIC = "scenic"
        const val WALLPAPER_CUSTOM = "custom"
        const val PREF_CUSTOM_WALLPAPER_PATH = "pref_custom_wallpaper_path"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_WALLPAPER_TYPE || key == PREF_CUSTOM_WALLPAPER_PATH || key == PREF_USER_NAME) {
            post {
                updateWallpaper()
            }
        }
        if (key != null && (key.startsWith("pref_show_") || key == SettingsActivity.KEY_THEME_MODE || key == MainActivity.PREF_FAV_CHANNELS_SET)) {
            post {
                applyComponentVisibility()
                if (key == MainActivity.PREF_FAV_CHANNELS_SET) {
                    populateFavChannels()
                }
            }
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val timeHandler = Handler(Looper.getMainLooper())
    private var isFavorite = false

    // Views
    private lateinit var dashboardBackground: ImageView
    private lateinit var dashboardScrim: View
    private lateinit var topLeftContainer: View
    private lateinit var greetingText: TextView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var lunarDateText: TextView
    private lateinit var searchBarPill: LinearLayout
    private lateinit var btnVoiceSearchCockpit: FrameLayout
    private lateinit var btnNotification: FrameLayout
    private var ivDayNightToggle: ImageView? = null
    private lateinit var btnSettings: FrameLayout

    // Weather card (phone GPS + Open-Meteo)
    private lateinit var weatherCard: LinearLayout
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherCondition: TextView
    private lateinit var weatherLocation: TextView

    // App Tiles
    private lateinit var cardVtv: LinearLayout
    private var cardM3u: LinearLayout? = null
    private lateinit var cardYoutube: LinearLayout
    private lateinit var cardBrowser: LinearLayout
    private lateinit var cardBookmark: LinearLayout

    // Favorite Channels row
    private var favChannelsScroll: View? = null
    private var favChannelsRow: LinearLayout? = null

    // Mini Player
    private lateinit var miniPlayerCard: LinearLayout
    private lateinit var playerArtHolder: FrameLayout
    private lateinit var playerThumbnail: ImageView
    private lateinit var playerFallbackIcon: ImageView
    private lateinit var playerTitleText: TextView
    private lateinit var playerSubtitleText: TextView
    private lateinit var playerCurTime: TextView
    private lateinit var playerProgressBar: ProgressBar
    private lateinit var playerDurTime: TextView
    private lateinit var btnPlayerPrev: ImageView
    private lateinit var btnPlayerPlayPause: ImageView
    private lateinit var btnPlayerNext: ImageView
    private lateinit var playerLiveContainer: View
    private lateinit var playerScrubberContainer: View

    private var currentApp: WebAppItem? = null
    var currentEmbeddedWeb: WebView? = null
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.dashboard, this, true)
        initViews()
        setupListeners()
        updateWallpaper()
        startLiveStreams()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0, strokeDp: Int = 0): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
            if (strokeDp > 0) {
                setStroke(dp(strokeDp), stroke)
            }
        }
    }

    private fun initViews() {
        dashboardBackground = findViewById(R.id.dashboardBackground)
        dashboardScrim = findViewById(R.id.dashboardScrim)
        topLeftContainer = findViewById(R.id.topLeftContainer)
        greetingText = findViewById(R.id.greetingText)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        lunarDateText = findViewById(R.id.lunarDateText)
        searchBarPill = findViewById(R.id.searchBarPill)
        btnVoiceSearchCockpit = findViewById(R.id.btnVoiceSearchCockpit)
        btnNotification = findViewById(R.id.btnNotification)
        ivDayNightToggle = findViewById(R.id.ivDayNightToggle) ?: (btnNotification.getChildAt(0) as? ImageView)
        btnSettings = findViewById(R.id.btnSettings)

        weatherCard = findViewById(R.id.weatherCard)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherCondition = findViewById(R.id.weatherCondition)
        weatherLocation = findViewById(R.id.weatherLocation)

        cardVtv = findViewById(R.id.cardVtv)
        cardM3u = findViewById(R.id.cardM3u)
        cardYoutube = findViewById(R.id.cardYoutube)
        cardBrowser = findViewById(R.id.cardBrowser)
        cardBookmark = findViewById(R.id.cardBookmark)

        favChannelsScroll = findViewById(R.id.favChannelsScroll)
        favChannelsRow = findViewById(R.id.favChannelsRow)

        miniPlayerCard = findViewById(R.id.miniPlayerCard)
        playerArtHolder = findViewById(R.id.playerArtHolder)
        playerThumbnail = findViewById(R.id.playerThumbnail)
        playerFallbackIcon = findViewById(R.id.playerFallbackIcon)
        playerTitleText = findViewById(R.id.playerTitleText)
        playerSubtitleText = findViewById(R.id.playerSubtitleText)
        playerCurTime = findViewById(R.id.playerCurTime)
        playerProgressBar = findViewById(R.id.playerProgressBar)
        playerDurTime = findViewById(R.id.playerDurTime)
        btnPlayerPrev = findViewById(R.id.btnPlayerPrev)
        btnPlayerPlayPause = findViewById(R.id.btnPlayerPlayPause)
        btnPlayerNext = findViewById(R.id.btnPlayerNext)
        playerLiveContainer = findViewById(R.id.playerLiveContainer)
        playerScrubberContainer = findViewById(R.id.playerScrubberContainer)

        playerTitleText.isSelected = true
        applyDayNightMode(SettingsActivity.resolveIsDay(context))
        populateFavChannels()
        applyComponentVisibility()
    }

    fun applyComponentVisibility() {
        val showHeroClock = prefs.getBoolean(MainActivity.PREF_SHOW_HERO_CLOCK, true)
        val showSearchBar = prefs.getBoolean(MainActivity.PREF_SHOW_SEARCH_BAR, true)
        val showCardYt = prefs.getBoolean(MainActivity.PREF_SHOW_CARD_YOUTUBE, true)
        val showCardIptv = prefs.getBoolean(MainActivity.PREF_SHOW_CARD_IPTV, true)
        val showCardM3u = prefs.getBoolean(MainActivity.PREF_SHOW_CARD_M3U, true)
        val showCardBrowser = prefs.getBoolean(MainActivity.PREF_SHOW_CARD_BROWSER, true)
        val showMiniPlayer = prefs.getBoolean(MainActivity.PREF_SHOW_MINI_PLAYER, true)
        val showFavChannels = prefs.getBoolean(MainActivity.PREF_SHOW_FAV_CHANNELS, true)

        if (::topLeftContainer.isInitialized) {
            topLeftContainer.visibility = if (showHeroClock) View.VISIBLE else View.GONE
        }
        if (::searchBarPill.isInitialized) {
            searchBarPill.visibility = if (showSearchBar) View.VISIBLE else View.GONE
        }
        if (::cardYoutube.isInitialized) {
            cardYoutube.visibility = if (showCardYt) View.VISIBLE else View.GONE
        }
        if (::cardVtv.isInitialized) {
            cardVtv.visibility = if (showCardIptv) View.VISIBLE else View.GONE
        }
        cardM3u?.visibility = if (showCardM3u) View.VISIBLE else View.GONE
        if (::cardBrowser.isInitialized) {
            cardBrowser.visibility = if (showCardBrowser) View.VISIBLE else View.GONE
        }
        if (::miniPlayerCard.isInitialized) {
            miniPlayerCard.visibility = if (showMiniPlayer) View.VISIBLE else View.GONE
        }
        favChannelsScroll?.visibility = if (showFavChannels && (favChannelsRow?.childCount ?: 0) > 0) View.VISIBLE else View.GONE
    }

    private fun populateFavChannels() {
        val row = favChannelsRow ?: return
        row.removeAllViews()

        val allChannels = IptvManager.getCachedChannelsList(context)
        val favSet = prefs.getStringSet(MainActivity.PREF_FAV_CHANNELS_SET, null)

        // Keep Android Auto favorites in sync with the phone. A null set means
        // first run (use the same phone defaults); an explicit empty set means
        // the user removed every favorite, so show nothing on Android Auto.
        val channelsToShow = when {
            favSet == null -> allChannels.filter { ch ->
                ch.name.contains("VTV1", ignoreCase = true) ||
                    ch.name.contains("VTV3", ignoreCase = true) ||
                    ch.name.contains("HTV7", ignoreCase = true)
            }
            favSet.isEmpty() -> emptyList()
            else -> allChannels.filter { ch ->
                favSet.contains(ch.id) || favSet.contains(ch.name.lowercase().replace(" ", ""))
            }
        }

        val showFavSetting = prefs.getBoolean(MainActivity.PREF_SHOW_FAV_CHANNELS, true)
        favChannelsScroll?.visibility = if (showFavSetting && channelsToShow.isNotEmpty()) View.VISIBLE else View.GONE
        if (channelsToShow.isEmpty()) return

        fun fallbackIcon(ch: IptvChannel): Int {
            val text = (ch.name + " " + ch.id).lowercase()
            return when {
                text.contains("vtv10") -> R.drawable.ic_ch_vtv10
                text.contains(Regex("""vtv1(?!\d)""")) -> R.drawable.ic_ch_vtv1
                text.contains(Regex("""vtv2(?!\d)""")) -> R.drawable.ic_ch_vtv2
                text.contains(Regex("""vtv3(?!\d)""")) -> R.drawable.ic_ch_vtv3
                text.contains(Regex("""vtv4(?!\d)""")) -> R.drawable.ic_ch_vtv4
                text.contains(Regex("""vtv5(?!\d)""")) -> R.drawable.ic_ch_vtv5
                text.contains(Regex("""vtv6(?!\d)""")) -> R.drawable.ic_ch_vtv6
                text.contains(Regex("""vtv7(?!\d)""")) -> R.drawable.ic_ch_vtv7
                text.contains(Regex("""vtv8(?!\d)""")) -> R.drawable.ic_ch_vtv8
                text.contains(Regex("""vtv9(?!\d)""")) -> R.drawable.ic_ch_vtv9
                text.contains("htv7") -> R.drawable.ic_ch_htv7
                text.contains("htv9") -> R.drawable.ic_ch_htv9
                text.contains("vtv") -> R.drawable.ic_ch_vtv
                text.contains("k+") || text.contains("sport") -> R.drawable.ic_ch_kplus
                else -> R.drawable.ic_app_iptv
            }
        }

        for (channel in channelsToShow) {
            val name = channel.name
            val chId = channel.id
            val iconRes = fallbackIcon(channel)
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minimumWidth = dp(96)
                minimumHeight = dp(48)
                background = rounded(Color.parseColor("#33101E33"), 999f, Color.parseColor("#4038BDF8"), 1)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(10)
                }
                isClickable = true
                isFocusable = true

                val icon = ImageView(context).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                        rightMargin = dp(8)
                    }
                    val logo = channel.logoUrl.trim()
                    if (logo.isNotEmpty()) {
                        tag = logo
                        CarMediaManager.loadArtworkBitmap(logo) { bitmap ->
                            if (bitmap != null && tag == logo) {
                                post { if (tag == logo) setImageBitmap(bitmap) }
                            }
                        }
                    }
                }
                addView(icon)

                val txt = TextView(context).apply {
                    text = name
                    textSize = 13f
                    setSingleLine(true)
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                addView(txt)

                setOnClickListener {
                    val appList = WebAppManager.getAllApps(context)
                    val iptvApp = appList.find { it.id == "iptv" }
                        ?: WebAppItem("iptv", "IPTV", "file:///android_asset/iptv_player.html", R.drawable.ic_app_iptv)
                    onAppClick(iptvApp)
                    onFullscreenRequested?.invoke(iptvApp)
                    postDelayed({
                        currentEmbeddedWeb?.evaluateJavascript("if (typeof window.selectChannelById === 'function') window.selectChannelById('$chId')", null)
                    }, 500)
                }
            }
            row.addView(chip)
        }
    }

    private fun setupListeners() {
        // App 1: VTVgo / IPTV
        cardVtv.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val iptvApp = appList.find { it.id == "iptv" }
                ?: WebAppItem("iptv", "IPTV", "file:///android_asset/iptv_player.html", R.drawable.ic_app_iptv)
            onAppClick(iptvApp)
            onFullscreenRequested?.invoke(iptvApp)
        }

        // App M3U: Direct M3U Playlist
        cardM3u?.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val iptvApp = appList.find { it.id == "iptv" }
                ?: WebAppItem("iptv", "IPTV M3U", "file:///android_asset/iptv_player.html", R.drawable.ic_app_iptv)
            onAppClick(iptvApp)
            onFullscreenRequested?.invoke(iptvApp)
        }

        // App 2: YouTube
        cardYoutube.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val ytApp = appList.find { it.id == "youtube" }
                ?: WebAppItem("youtube", "YouTube", "https://m.youtube.com", R.drawable.ic_app_youtube)
            onAppClick(ytApp)
            onFullscreenRequested?.invoke(ytApp)
        }

        // App 3: Trình duyệt
        cardBrowser.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val browserApp = appList.find { it.id == "chrome" || it.id == "browser" }
                ?: WebAppItem("chrome", "Trình duyệt", "https://google.com", R.drawable.ic_tab_globe)
            onAppClick(browserApp)
            onFullscreenRequested?.invoke(browserApp)
        }

        // App 4: Dấu trang (Bookmarks / Quick links)
        cardBookmark.setOnClickListener {
            onAllAppsClick()
        }

        // Search Bar click
        searchBarPill.setOnClickListener {
            onSearchClick?.invoke()
        }

        // Voice Search click (Red box button)
        btnVoiceSearchCockpit.setOnClickListener {
            onVoiceClick?.invoke()
        }

        // Notification Button click
        btnNotification.setOnClickListener {
            if (onNotificationClick != null) {
                onNotificationClick.invoke()
            } else {
                Toast.makeText(context, "Hệ thống hoạt động bình thường • Đã kích hoạt chặn quảng cáo", Toast.LENGTH_SHORT).show()
            }
        }

        // Settings Button click
        btnSettings.setOnClickListener {
            onSettingsClick?.invoke()
        }

        // Mini player card click -> open current player in fullscreen
        miniPlayerCard.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val targetAppId = CarMediaManager.activeAppId
            val targetApp = appList.find { it.id == targetAppId }
                ?: appList.firstOrNull { it.id == "youtube" }
                ?: WebAppManager.DEFAULT_APPS.first()
            onAppClick(targetApp)
            onFullscreenRequested?.invoke(targetApp)
        }

        // Player Controls
        btnPlayerPlayPause.setOnClickListener {
            CarMediaManager.togglePlayPause()
        }

        btnPlayerPrev.setOnClickListener {
            CarMediaManager.playPrevious()
        }

        btnPlayerNext.setOnClickListener {
            CarMediaManager.playNext()
        }

        // Scrubber seek
        playerProgressBar.max = 100
        playerProgressBar.setOnTouchListener { v, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN ||
                event.action == android.view.MotionEvent.ACTION_MOVE
            ) {
                val width = v.width
                if (width > 0) {
                    val percent = (event.x / width.toFloat()).coerceIn(0f, 1f)
                    val seekPercent = (percent * 100).toInt()
                    playerProgressBar.progress = seekPercent
                    val dur = CarMediaManager.trackState.value.currentDurationSec
                    if (dur > 0) {
                        val targetSec = (dur * seekPercent / 100).toLong()
                        CarMediaManager.seekTo(targetSec)
                    }
                }
                true
            } else false
        }
    }

    fun updateWallpaper() {
        val rawType = prefs.getString(PREF_WALLPAPER_TYPE, WALLPAPER_BUGATTI) ?: WALLPAPER_BUGATTI
        val type = if (rawType == "homer") WALLPAPER_BUGATTI else rawType
        when (type) {
            WALLPAPER_BUGATTI -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_bugatti)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            }
            WALLPAPER_SILVER -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_silver)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            }
            WALLPAPER_BLUE -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_blue)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            }
            WALLPAPER_CYBER -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_cyber)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            }
            WALLPAPER_SUNSET -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_sunset)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            }
            WALLPAPER_DARK -> {
                dashboardBackground.setImageDrawable(null)
                dashboardBackground.visibility = View.GONE
                dashboardScrim.visibility = View.GONE
                setBackgroundResource(R.drawable.bg_cockpit_charcoal)
            }
            WALLPAPER_SCENIC -> {
                dashboardBackground.setImageResource(R.drawable.bg_wallpaper_scenic)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#40000000"))
            }
            WALLPAPER_CUSTOM -> {
                val path = prefs.getString(PREF_CUSTOM_WALLPAPER_PATH, null)
                if (!path.isNullOrBlank() && File(path).exists()) {
                    try {
                        val bmp = BitmapFactory.decodeFile(path)
                        if (bmp != null) {
                            dashboardBackground.setImageBitmap(bmp)
                            dashboardBackground.visibility = View.VISIBLE
                            dashboardScrim.visibility = View.VISIBLE
                            dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
                        } else {
                            applyBugattiDefault()
                        }
                    } catch (e: Exception) {
                        applyBugattiDefault()
                    }
                } else {
                    applyBugattiDefault()
                }
            }
            else -> {
                applyBugattiDefault()
            }
        }
    }

    private fun applyBugattiDefault() {
        dashboardBackground.setImageResource(R.drawable.bg_wallpaper_bugatti)
        dashboardBackground.visibility = View.VISIBLE
        dashboardScrim.visibility = View.VISIBLE
        dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
    }

    private fun startLiveStreams() {
        // 1. Clock, Greeting, Solar Date & Vietnamese Lunar Calendar
        val timeRunnable = object : Runnable {
            override fun run() {
                val cal = Calendar.getInstance()
                val now = cal.time

                // Clock: 11:49
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                clockText.text = timeStr

                // Greeting: Chào buổi trưa, Phạm Nam
                val userName = prefs.getString(PREF_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME
                greetingText.text = VietnameseLunarHelper.getGreeting(cal, userName)

                // Date: Thứ Ba, 15/09/2026
                dateText.text = VietnameseLunarHelper.getFormattedSolarDate(cal)

                // Lunar Date: ÂL: 05/08 - Năm Bính Ngọ
                lunarDateText.text = VietnameseLunarHelper.getFormattedLunarWithCanChi(cal)

                timeHandler.postDelayed(this, 1000)
            }
        }
        timeHandler.post(timeRunnable)

        // 2. Live weather from the phone's current GPS/network location.
        WeatherManager.start(context.applicationContext)
        GpsSpeedManager.start(context.applicationContext)
        coroutineScope.launch {
            WeatherManager.weatherState.collectLatest { weather ->
                weatherIcon.text = weather.iconEmoji
                weatherTemp.text = if (weather.isLoaded) "${weather.tempC}°C" else "—°C"
                weatherCondition.text = if (weather.isLoaded) weather.conditionText else "Đang cập nhật"
                weatherLocation.text = if (weather.isLoaded) "📍 ${weather.location}" else "📍 Vị trí điện thoại"
            }
        }

        // 3. Track State Sync (YouTube / IPTV background playback info)
        coroutineScope.launch {
            CarMediaManager.trackState.collectLatest { track ->
                val title = if (track.title.isNotBlank()) track.title else "Mùa Thu Đi Qua (Acoustic Ver.)"
                val subtitle = if (track.artist.isNotBlank()) track.artist else "YouTube"

                if (playerTitleText.text?.toString() != title) playerTitleText.text = title
                if (playerSubtitleText.text?.toString() != subtitle) playerSubtitleText.text = subtitle

                val isLive = CarMediaManager.activeAppId == "iptv" ||
                        subtitle.contains("IPTV", ignoreCase = true) ||
                        subtitle.contains("Truyền hình", ignoreCase = true) ||
                        title.contains("VTV", ignoreCase = true) ||
                        title.contains("HTV", ignoreCase = true) ||
                        title.contains("VTC", ignoreCase = true) ||
                        (track.currentDurationSec <= 0 && track.title.isNotBlank())

                if (isLive) {
                    playerLiveContainer.visibility = View.VISIBLE
                    playerScrubberContainer.visibility = View.GONE
                } else {
                    playerLiveContainer.visibility = View.GONE
                    playerScrubberContainer.visibility = View.VISIBLE

                    val curM = track.currentPositionSec / 60
                    val curS = track.currentPositionSec % 60
                    val durM = track.currentDurationSec / 60
                    val durS = track.currentDurationSec % 60
                    val curStr = String.format(Locale.US, "%d:%02d", curM, curS)
                    val durStr = if (track.currentDurationSec > 0) String.format(Locale.US, "%d:%02d", durM, durS) else "3:22"

                    if (playerCurTime.text?.toString() != curStr) playerCurTime.text = curStr
                    if (playerDurTime.text?.toString() != durStr) playerDurTime.text = durStr
                    if (playerProgressBar.progress != track.progressPercent) {
                        playerProgressBar.progress = track.progressPercent
                    }
                }

                btnPlayerPlayPause.setImageResource(
                    if (track.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )

                if (track.artworkBitmap != null) {
                    playerThumbnail.setImageBitmap(track.artworkBitmap)
                    playerThumbnail.visibility = VISIBLE
                    playerFallbackIcon.visibility = GONE
                } else if (track.artworkRes != 0) {
                    playerThumbnail.setImageResource(track.artworkRes)
                    playerThumbnail.visibility = View.VISIBLE
                    playerFallbackIcon.visibility = View.GONE
                } else {
                    playerThumbnail.visibility = View.GONE
                    playerFallbackIcon.visibility = View.VISIBLE
                }
            }
        }
    }

    // ==========================================
    // Methods expected by CarPresentation
    // ==========================================

    fun isEmbeddedAppShowing(): Boolean = false

    fun showEmbeddedApp(app: WebAppItem, web: WebView) {
        currentApp = app
        currentEmbeddedWeb = web
    }

    fun detachEmbeddedWeb() {
        currentEmbeddedWeb = null
    }

    fun refreshAppsList() {
        // App tiles are fixed launcher shortcuts (VTVgo, YouTube, Trình duyệt, Dấu trang)
    }

    fun applyDayNightMode(isDay: Boolean) {
        val mode = prefs.getString(SettingsActivity.KEY_THEME_MODE, SettingsActivity.THEME_AUTO) ?: SettingsActivity.THEME_AUTO
        when (mode) {
            SettingsActivity.THEME_DAY -> {
                ivDayNightToggle?.setImageResource(R.drawable.ic_mode_day)
                ivDayNightToggle?.setColorFilter(Color.parseColor("#F59E0B"))
                dashboardScrim.setBackgroundColor(Color.parseColor("#15000000"))
                weatherCard.background = rounded(Color.parseColor("#D9F8FAFC"), 18f)
                weatherTemp.setTextColor(Color.parseColor("#0F172A"))
                weatherCondition.setTextColor(Color.parseColor("#334155"))
                weatherLocation.setTextColor(Color.parseColor("#0369A1"))
            }
            SettingsActivity.THEME_NIGHT -> {
                ivDayNightToggle?.setImageResource(R.drawable.ic_mode_night)
                ivDayNightToggle?.setColorFilter(Color.parseColor("#38BDF8"))
                dashboardScrim.setBackgroundColor(Color.parseColor("#42000000"))
                weatherCard.background = rounded(Color.parseColor("#CC101A24"), 18f)
                weatherTemp.setTextColor(Color.WHITE)
                weatherCondition.setTextColor(Color.parseColor("#E2E8F0"))
                weatherLocation.setTextColor(Color.parseColor("#67E8F9"))
            }
            else -> {
                ivDayNightToggle?.setImageResource(R.drawable.ic_day_night)
                ivDayNightToggle?.setColorFilter(Color.parseColor("#00E5FF"))
                dashboardScrim.setBackgroundColor(if (isDay) Color.parseColor("#18000000") else Color.parseColor("#35000000"))
                if (isDay) {
                    weatherCard.background = rounded(Color.parseColor("#DDF8FAFC"), 18f)
                    weatherTemp.setTextColor(Color.parseColor("#0F172A"))
                    weatherCondition.setTextColor(Color.parseColor("#334155"))
                    weatherLocation.setTextColor(Color.parseColor("#0369A1"))
                } else {
                    weatherCard.background = rounded(Color.parseColor("#CC101A24"), 18f)
                    weatherTemp.setTextColor(Color.WHITE)
                    weatherCondition.setTextColor(Color.parseColor("#E2E8F0"))
                    weatherLocation.setTextColor(Color.parseColor("#67E8F9"))
                }
            }
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        // No-op for launcher view
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        timeHandler.removeCallbacksAndMessages(null)
        coroutineScope.cancel()
    }
}
