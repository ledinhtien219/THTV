package com.carhud.aaproxy

import com.carhud.app.R
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
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
        const val PREF_CUSTOM_WALLPAPER_KIND = "pref_custom_wallpaper_kind"
        const val WALLPAPER_KIND_IMAGE = "image"
        const val WALLPAPER_KIND_GIF = "gif"
        const val WALLPAPER_KIND_VIDEO = "video"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_WALLPAPER_TYPE || key == PREF_CUSTOM_WALLPAPER_PATH || key == PREF_CUSTOM_WALLPAPER_KIND || key == PREF_USER_NAME) {
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
    private lateinit var dashboardVideoBackground: TextureView
    private lateinit var dashboardBackground: ImageView
    private lateinit var dashboardScrim: View
    private lateinit var dashboardMainContent: View
    private lateinit var topRightContainer: LinearLayout
    private lateinit var bottomDock: LinearLayout
    private var wallpaperPlayer: MediaPlayer? = null
    private var animatedWallpaper: AnimatedImageDrawable? = null
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
    private lateinit var weatherIcon: ImageView
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
        dashboardVideoBackground = findViewById(R.id.dashboardVideoBackground)
        dashboardBackground = findViewById(R.id.dashboardBackground)
        dashboardScrim = findViewById(R.id.dashboardScrim)
        dashboardMainContent = findViewById(R.id.dashboardMainContent)
        topRightContainer = findViewById(R.id.topRightContainer)
        bottomDock = findViewById(R.id.bottomDock)
        forceWallpaperLayersFullscreen()
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

    /**
     * Adapt the cockpit to the ACTUAL Android Auto content viewport.
     * This is intentionally based on the laid-out view rather than the physical
     * panel size so cars with AA margins/cropped projection (for example VF6)
     * do not inherit the wrong desktop-sized layout.
     */
    fun applyAdaptiveScreen(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val density = context.resources.displayMetrics.density.coerceAtLeast(0.75f)
        val widthDp = widthPx / density
        val heightDp = heightPx / density

        val compactWidth = widthDp < 820f
        val veryCompactWidth = widthDp < 700f
        val compactHeight = heightDp < 430f

        val padH = when {
            veryCompactWidth -> 10
            compactWidth -> 14
            else -> 22
        }
        val padV = if (compactHeight) 8 else 14
        dashboardMainContent.setPadding(dp(padH), dp(padV), dp(padH), dp(if (compactHeight) 8 else 14))

        // The old fixed 95dp end margin was tuned for one DHU size and pushes
        // controls out of the usable region on some factory head units.
        (topRightContainer.layoutParams as? android.widget.RelativeLayout.LayoutParams)?.let { lp ->
            lp.marginEnd = dp(
                when {
                    veryCompactWidth -> 6
                    compactWidth -> 18
                    widthDp < 1000f -> 42
                    else -> 95
                }
            )
            topRightContainer.layoutParams = lp
        }

        searchBarPill.layoutParams = searchBarPill.layoutParams.apply {
            width = dp(
                when {
                    veryCompactWidth -> 130
                    compactWidth -> 160
                    widthDp < 1000f -> 190
                    else -> 220
                }
            )
            height = dp(if (compactHeight) 36 else 40)
        }

        val cardWidth = when {
            veryCompactWidth -> 68
            compactWidth -> 78
            else -> 92
        }
        val cardHeight = if (compactHeight) 68 else if (compactWidth) 74 else 82
        val cardGap = if (compactWidth) 6 else 10

        listOfNotNull(cardVtv, cardM3u, cardYoutube, cardBrowser, cardBookmark).forEachIndexed { index, card ->
            val lp = card.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            lp.width = dp(cardWidth)
            lp.height = dp(cardHeight)
            lp.marginEnd = dp(if (index == 4) cardGap + 2 else cardGap)
            card.layoutParams = lp
            card.setPadding(dp(if (compactWidth) 4 else 6), dp(4), dp(if (compactWidth) 4 else 6), dp(4))
        }

        (miniPlayerCard.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.height = dp(cardHeight)
            miniPlayerCard.layoutParams = lp
        }

        val artSize = when {
            veryCompactWidth -> 48
            compactWidth -> 54
            else -> 66
        }
        playerArtHolder.layoutParams = playerArtHolder.layoutParams.apply {
            width = dp(artSize)
            height = dp(artSize)
        }

        clockText.textSize = when {
            compactHeight -> 38f
            compactWidth -> 42f
            else -> 48f
        }
        greetingText.textSize = if (compactWidth) 13f else 15f
        dateText.textSize = if (compactWidth) 12.5f else 14.5f
        lunarDateText.textSize = if (compactWidth) 11.5f else 13f

        weatherCard.layoutParams = weatherCard.layoutParams.apply {
            width = dp(if (compactWidth) 132 else 150)
            height = dp(if (compactHeight) 64 else 76)
        }

        bottomDock.requestLayout()
        topRightContainer.requestLayout()
        dashboardMainContent.requestLayout()
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
                    val quickUrl = "file:///android_asset/iptv_player.html#channel=" +
                        android.net.Uri.encode(name)
                    onAppClick(iptvApp.copy(url = quickUrl))
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
        }

        // App M3U: Direct M3U Playlist
        cardM3u?.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val iptvApp = appList.find { it.id == "iptv" }
                ?: WebAppItem("iptv", "IPTV M3U", "file:///android_asset/iptv_player.html", R.drawable.ic_app_iptv)
            onAppClick(iptvApp)
        }

        // App 2: YouTube
        cardYoutube.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val ytApp = appList.find { it.id == "youtube" }
                ?: WebAppItem("youtube", "YouTube", "https://m.youtube.com", R.drawable.ic_app_youtube)
            onAppClick(ytApp)
        }

        // Use the same browser as the application center, including its toolbar/history.
        cardBrowser.setOnClickListener {
            val appList = WebAppManager.getAllApps(context)
            val browserApp = appList.find { it.id == "web" }
                ?: WebAppManager.DEFAULT_APPS.first { it.id == "web" }
            onAppClick(browserApp)
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
        stopDynamicWallpaper()
        forceWallpaperLayersFullscreen()

        val rawType = prefs.getString(PREF_WALLPAPER_TYPE, WALLPAPER_BUGATTI) ?: WALLPAPER_BUGATTI
        val type = if (rawType == "homer") WALLPAPER_BUGATTI else rawType
        when (type) {
            WALLPAPER_BUGATTI -> showStaticWallpaper(R.drawable.bg_wallpaper_bugatti)
            WALLPAPER_SILVER -> showStaticWallpaper(R.drawable.bg_wallpaper_silver)
            WALLPAPER_BLUE -> showStaticWallpaper(R.drawable.bg_wallpaper_blue)
            WALLPAPER_CYBER -> showStaticWallpaper(R.drawable.bg_wallpaper_cyber)
            WALLPAPER_SUNSET -> showStaticWallpaper(R.drawable.bg_wallpaper_sunset)
            WALLPAPER_SCENIC -> showStaticWallpaper(R.drawable.bg_wallpaper_scenic, "#40000000")
            WALLPAPER_DARK -> {
                dashboardVideoBackground.visibility = View.GONE
                dashboardBackground.setImageDrawable(null)
                dashboardBackground.visibility = View.GONE
                dashboardScrim.visibility = View.GONE
                setBackgroundResource(R.drawable.bg_cockpit_charcoal)
            }
            WALLPAPER_CUSTOM -> {
                val path = prefs.getString(PREF_CUSTOM_WALLPAPER_PATH, null)
                val kind = prefs.getString(PREF_CUSTOM_WALLPAPER_KIND, WALLPAPER_KIND_IMAGE)
                    ?: WALLPAPER_KIND_IMAGE
                if (!path.isNullOrBlank() && File(path).exists()) {
                    when (kind) {
                        WALLPAPER_KIND_VIDEO -> playVideoWallpaper(path)
                        WALLPAPER_KIND_GIF -> playGifWallpaper(path)
                        else -> showCustomImage(path)
                    }
                } else {
                    applyBugattiDefault()
                }
            }
            else -> applyBugattiDefault()
        }
    }

    private fun showStaticWallpaper(resId: Int, scrimColor: String = "#35000000") {
        setBackgroundResource(R.drawable.bg_cockpit_charcoal)
        forceWallpaperLayersFullscreen()
        dashboardVideoBackground.visibility = View.GONE
        dashboardBackground.scaleType = ImageView.ScaleType.CENTER_CROP
        dashboardBackground.setImageResource(resId)
        dashboardBackground.visibility = View.VISIBLE
        dashboardScrim.visibility = View.VISIBLE
        dashboardScrim.setBackgroundColor(Color.parseColor(scrimColor))
    }

    private fun showCustomImage(path: String) {
        try {
            val bmp = BitmapFactory.decodeFile(path)
            if (bmp != null) {
                forceWallpaperLayersFullscreen()
                dashboardVideoBackground.visibility = View.GONE
                dashboardBackground.scaleType = ImageView.ScaleType.CENTER_CROP
                dashboardBackground.setImageBitmap(bmp)
                dashboardBackground.visibility = View.VISIBLE
                dashboardScrim.visibility = View.VISIBLE
                dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            } else {
                applyBugattiDefault()
            }
        } catch (_: Throwable) {
            applyBugattiDefault()
        }
    }

    private fun playGifWallpaper(path: String) {
        try {
            forceWallpaperLayersFullscreen()
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path)))
            dashboardVideoBackground.visibility = View.GONE
            dashboardBackground.scaleType = ImageView.ScaleType.CENTER_CROP
            dashboardBackground.adjustViewBounds = false
            dashboardBackground.setImageDrawable(drawable)
            dashboardBackground.visibility = View.VISIBLE
            dashboardBackground.post {
                forceWallpaperLayersFullscreen()
                dashboardBackground.scaleType = ImageView.ScaleType.CENTER_CROP
                dashboardBackground.requestLayout()
                dashboardBackground.invalidate()
            }
            dashboardScrim.visibility = View.VISIBLE
            dashboardScrim.setBackgroundColor(Color.parseColor("#35000000"))
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                animatedWallpaper = drawable
                drawable.start()
            }
        } catch (_: Throwable) {
            applyBugattiDefault()
        }
    }

    private fun playVideoWallpaper(path: String) {
        forceWallpaperLayersFullscreen()
        dashboardBackground.setImageDrawable(null)
        dashboardBackground.visibility = View.GONE
        dashboardVideoBackground.visibility = View.VISIBLE
        dashboardVideoBackground.alpha = 1f
        dashboardScrim.visibility = View.VISIBLE
        dashboardScrim.setBackgroundColor(Color.parseColor("#42000000"))

        val startPlayer: (SurfaceTexture) -> Unit = { surfaceTexture ->
            releaseWallpaperPlayer()
            try {
                val surface = Surface(surfaceTexture)
                val player = MediaPlayer()
                wallpaperPlayer = player
                player.setSurface(surface)
                surface.release()
                player.setDataSource(path)
                player.isLooping = true
                player.setVolume(0f, 0f)
                player.setOnVideoSizeChangedListener { _, videoW, videoH ->
                    applyVideoCenterCrop(videoW, videoH)
                }
                player.setOnPreparedListener {
                    applyVideoCenterCrop(it.videoWidth, it.videoHeight)
                    it.start()
                }
                player.setOnErrorListener { _, _, _ ->
                    post { applyBugattiDefault() }
                    true
                }
                player.prepareAsync()
            } catch (_: Throwable) {
                post { applyBugattiDefault() }
            }
        }

        if (dashboardVideoBackground.isAvailable) {
            dashboardVideoBackground.surfaceTexture?.let(startPlayer)
        } else {
            dashboardVideoBackground.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    startPlayer(surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    forceWallpaperLayersFullscreen()
                    val p = wallpaperPlayer
                    if (p != null) {
                        dashboardVideoBackground.post {
                            applyVideoCenterCrop(p.videoWidth, p.videoHeight)
                        }
                    }
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releaseWallpaperPlayer()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    private fun applyVideoCenterCrop(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return

        forceWallpaperLayersFullscreen()

        val viewW = dashboardVideoBackground.width.toFloat().takeIf { it > 0f } ?: return
        val viewH = dashboardVideoBackground.height.toFloat().takeIf { it > 0f } ?: return

        // TextureView already maps the video buffer to the full view bounds.
        // Only compensate for the DIFFERENCE in aspect ratio. Using
        // viewWidth/videoWidth here would shrink a 1080p video into the
        // top-left corner on a lower-resolution Android Auto display.
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val viewAspect = viewW / viewH

        var scaleX = 1f
        var scaleY = 1f
        if (videoAspect > viewAspect) {
            // Video is wider than the display -> crop the left/right sides.
            scaleX = videoAspect / viewAspect
        } else if (videoAspect < viewAspect) {
            // Video is taller/narrower -> crop top/bottom.
            scaleY = viewAspect / videoAspect
        }

        val matrix = Matrix().apply {
            setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        }
        dashboardVideoBackground.setTransform(matrix)
        dashboardVideoBackground.invalidate()
    }

    private fun forceWallpaperLayersFullscreen() {
        if (::dashboardVideoBackground.isInitialized) {
            dashboardVideoBackground.layoutParams = dashboardVideoBackground.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            dashboardVideoBackground.translationX = 0f
            dashboardVideoBackground.translationY = 0f
            dashboardVideoBackground.scaleX = 1f
            dashboardVideoBackground.scaleY = 1f
        }

        if (::dashboardBackground.isInitialized) {
            dashboardBackground.layoutParams = dashboardBackground.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            dashboardBackground.scaleType = ImageView.ScaleType.CENTER_CROP
            dashboardBackground.adjustViewBounds = false
            dashboardBackground.translationX = 0f
            dashboardBackground.translationY = 0f
            dashboardBackground.scaleX = 1f
            dashboardBackground.scaleY = 1f
        }

        if (::dashboardScrim.isInitialized) {
            dashboardScrim.layoutParams = dashboardScrim.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun stopDynamicWallpaper() {
        try {
            animatedWallpaper?.stop()
        } catch (_: Throwable) {
        }
        animatedWallpaper = null
        releaseWallpaperPlayer()
        if (::dashboardVideoBackground.isInitialized) {
            dashboardVideoBackground.surfaceTextureListener = null
            dashboardVideoBackground.visibility = View.GONE
            dashboardVideoBackground.setTransform(null)
        }
    }

    private fun releaseWallpaperPlayer() {
        val p = wallpaperPlayer
        wallpaperPlayer = null
        if (p != null) {
            try { p.stop() } catch (_: Throwable) {}
            try { p.reset() } catch (_: Throwable) {}
            try { p.release() } catch (_: Throwable) {}
        }
    }

    private fun applyBugattiDefault() {
        stopDynamicWallpaper()
        showStaticWallpaper(R.drawable.bg_wallpaper_bugatti)
    }

    private fun weatherIconRes(iconEmoji: String): Int {
        return when (iconEmoji) {
            "☀️", "☀" -> R.drawable.ic_weather_sunny
            "🌤️", "🌤", "⛅" -> R.drawable.ic_weather_partly_cloudy
            "🌫️", "🌫" -> R.drawable.ic_weather_fog
            "🌦️", "🌦", "🌧️", "🌧" -> R.drawable.ic_weather_rain
            "⛈️", "⛈" -> R.drawable.ic_weather_storm
            else -> R.drawable.ic_weather_partly_cloudy
        }
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
                weatherIcon.setImageResource(weatherIconRes(weather.iconEmoji))
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
        stopDynamicWallpaper()
        timeHandler.removeCallbacksAndMessages(null)
        coroutineScope.cancel()
    }
}
