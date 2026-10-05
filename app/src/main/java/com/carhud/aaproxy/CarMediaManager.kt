package com.carhud.aaproxy

import com.carhud.app.R
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaTrackState(
    val title: String = "YouTube Music",
    val artist: String = "THTV",
    val isPlaying: Boolean = false,
    val currentPositionSec: Int = 0,
    val currentDurationSec: Int = 0,
    val artworkUrl: String = "",
    val artworkBitmap: Bitmap? = null,
    val artworkRes: Int = 0,
    val isShuffleEnabled: Boolean = false,
    val isRepeatEnabled: Boolean = false,
    val isLiked: Boolean = false
) {
    val formattedProgress: String
        get() {
            val curM = currentPositionSec / 60
            val curS = currentPositionSec % 60
            val durM = currentDurationSec / 60
            val durS = currentDurationSec % 60
            return String.format(java.util.Locale.US, "%02d:%02d / %02d:%02d", curM, curS, durM, durS)
        }
    val progressPercent: Int
        get() = if (currentDurationSec > 0) ((currentPositionSec.toFloat() / currentDurationSec) * 100).toInt().coerceIn(0, 100) else 0
}

object CarMediaManager {

    @SuppressLint("StaticFieldLeak")
    private var carWebView: WebView? = null
    @SuppressLint("StaticFieldLeak")
    private var phoneWebView: WebView? = null
    var mainActivityRoot: android.view.ViewGroup? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val steeringNextHandler = SteeringNextPressHandler(
        clock = { android.os.SystemClock.uptimeMillis() },
        schedule = { delay, action ->
            val task = Runnable { action() }
            mainHandler.postDelayed(task, delay)
            val cancel: () -> Unit = { mainHandler.removeCallbacks(task) }
            cancel
        }
    )
    private var wakeLock: PowerManager.WakeLock? = null

    var isPlaying = false
        private set
    var currentTitle = "YouTube Music"
        private set
    var currentArtist = "THTV"
        private set
    var currentArtworkUrl = ""
        private set
    var currentArtworkBitmap: android.graphics.Bitmap? = null
        private set
    var lastPlayedUrl: String? = null
    var currentLoadingUrl: String? = null
    private val imageExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val _trackState = MutableStateFlow(MediaTrackState())
    val trackState: StateFlow<MediaTrackState> = _trackState.asStateFlow()

    var isCarConnected = false
    var carScreenWidth = 0
    var carScreenHeight = 0
    var carScreenDpi = 0
    var isWebShowingFullscreen = false
    var isEmbeddedAppShowing = true
    var activeAppId: String = "youtube"
    var lastEmbeddedApp: WebAppItem? = null
    var userWantsPlayback = false
    var activeAudioManager: CarAudioManager? = null

    fun registerPhoneWebView(web: WebView, context: Context) {
        phoneWebView = web
        registerService(context)
    }

    fun unregisterPhoneWebView(web: WebView) {
        if (phoneWebView == web) {
            phoneWebView = null
        }
    }

    fun setCarConnectionState(connected: Boolean) {
        isCarConnected = connected
        mainHandler.post {
            try {
                if (connected) {
                    phoneWebView?.onPause()
                    phoneWebView?.pauseTimers()
                } else {
                    val phone = phoneWebView
                    val backgroundEnabled = phone?.context
                        ?.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                        ?.getBoolean(SettingsActivity.KEY_BACKGROUND_AUDIO, true) ?: true
                    if (backgroundEnabled) {
                        phone?.onResume()
                        phone?.resumeTimers()
                    } else {
                        phone?.onPause()
                        phone?.pauseTimers()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun registerCarWebView(web: WebView, context: Context) {
        setCarConnectionState(true)
        carWebView = web
        registerService(context)
    }

    fun unregisterCarWebView(web: WebView) {
        if (carWebView == web) {
            carWebView = null
        }
        setCarConnectionState(false)
        releaseWakeLock()
    }

    // Backward compatibility
    fun registerWebView(web: WebView, context: Context) {
        if (context is MainActivity) {
            registerPhoneWebView(web, context)
        } else {
            registerCarWebView(web, context)
        }
    }

    fun unregisterWebView(web: WebView) {
        unregisterPhoneWebView(web)
        unregisterCarWebView(web)
    }

    fun getActiveWebView(): WebView? {
        return if (isCarConnected && carWebView != null) {
            carWebView
        } else {
            phoneWebView ?: carWebView
        }
    }

    fun setupAndroidVoiceBridge(web: WebView, context: Context, isAuto: Boolean = false) {
        val appCtx = context.applicationContext
        web.addJavascriptInterface(object : Any() {
            @android.webkit.JavascriptInterface
            fun isAuto(): Boolean = isAuto

            @android.webkit.JavascriptInterface
            fun onPlaybackStateChanged(playing: Boolean) {
                mainHandler.post { setPlaybackState(playing) }
            }

            @android.webkit.JavascriptInterface
            fun onTrackChanged(title: String, artist: String) {
                mainHandler.post { updateTrack(title, artist, null) }
            }

            @android.webkit.JavascriptInterface
            fun onTrackChanged(title: String, artist: String, thumbUrl: String) {
                mainHandler.post { updateTrack(title, artist, thumbUrl) }
            }

            @android.webkit.JavascriptInterface
            fun isAutoFullscreenEnabled(): Boolean {
                val prefs = appCtx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                return prefs.getBoolean(SettingsActivity.KEY_AUTO_FULLSCREEN, true)
            }

            @android.webkit.JavascriptInterface
            fun onVideoTimeUpdate(currentSec: Int, totalSec: Int, formattedTime: String) {
                mainHandler.post {
                    updatePlaybackProgress(currentSec, totalSec)
                }
            }

            @android.webkit.JavascriptInterface
            fun onUserSelectedVideo(url: String) {
                userWantsPlayback = true
                saveLastPlayedUrl(appCtx, url)
                mainHandler.post {
                    ensureAudioFocus()
                    acquireWakeLock(appCtx)
                }
            }

            @android.webkit.JavascriptInterface
            fun saveLastPlayedUrl(url: String) {
                saveLastPlayedUrl(appCtx, url)
            }

            @android.webkit.JavascriptInterface
            fun openSearchKeyboard() {
                mainHandler.post {
                    startGlobalVoiceSearch(appCtx)
                }
            }

            @android.webkit.JavascriptInterface
            fun startListening() {
                mainHandler.post {
                    startGlobalVoiceSearch(appCtx)
                }
            }

            @android.webkit.JavascriptInterface
            fun onUserTouch() {
                // Keep touch interactions alive
            }

            @android.webkit.JavascriptInterface
            fun fetchSponsorSegments(videoId: String) {
                SponsorBlockManager.fetchSegments(videoId) { jsonString ->
                    mainHandler.post {
                        try {
                            val jsonLiteral = org.json.JSONObject.quote(jsonString)
                            getActiveWebView()?.evaluateJavascript("if (window.FermataSB && window.FermataSB.onSegmentsLoaded) { window.FermataSB.onSegmentsLoaded('$videoId', $jsonLiteral); }", null)
                        } catch (e: Exception) {}
                    }
                }
            }
        }, "AndroidVoice")
    }

    fun getPersistentCarWebView(context: Context): WebView {
        val appCtx = context.applicationContext
        if (carWebView == null) {
            val displayCtx = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.createDisplayContext(context.display)
                } else {
                    context
                }
            } catch (e: Exception) {
                context
            }
            val web = BackgroundAudioWebView(displayCtx)
            carWebView = web

            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true
            }

            web.setBackgroundColor(android.graphics.Color.BLACK)
            YouTubePlayerHelper.applyUltraPerformance(web)
            setupAndroidVoiceBridge(web, context, isAuto = true)
            IptvAspectRatio.attach(web)

            web.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    YouTubeAdBlocker.shouldIntercept(request)?.let { return it }
                    val url = request?.url?.toString()
                    if (url != null && YouTubePlayerHelper.isAdUrl(url)) {
                        val origin = request.requestHeaders?.get("Origin") ?: request.requestHeaders?.get("origin")
                        return YouTubePlayerHelper.createEmptyResponse(origin)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    currentLoadingUrl = null
                    YouTubeAdBlocker.onPageFinished(view, url)
                    YouTubePlayerHelper.inject(view)
                    if (url.contains("iptv_player.html")) {
                        view.evaluateJavascript("window.isDashboardMode = $isEmbeddedAppShowing; if (window.isDashboardMode && window.hideChannelsAndControls) window.hideChannelsAndControls();", null)
                    }
                    val prefs = appCtx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                    val autoResume = prefs.getBoolean(SettingsActivity.KEY_AUTO_RESUME_LAST_TRACK, true)
                    if (url.contains("watch")) {
                        if (userWantsPlayback || autoResume) {
                            userWantsPlayback = true
                            ensureAudioFocus()
                            acquireWakeLock(appCtx)
                            view.postDelayed({
                                if (!isPlaying) {
                                    YouTubePlayerHelper.resumePlayback(view)
                                }
                            }, 500L)
                        }
                    } else if (userWantsPlayback || autoResume) {
                        ensureAudioFocus()
                        view.postDelayed({
                            if (!isPlaying) {
                                YouTubePlayerHelper.playFirstAvailableVideo(view)
                            }
                        }, 500L)
                    }
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    val didCrash = detail?.didCrash() ?: false
                    val msg = "Chromium Render Process Gone (didCrash=$didCrash) on Android Auto"
                    Log.e("CarMediaManager", msg)
                    AppCrashHandler.logError(Exception(msg), "AndroidAutoWebViewRenderCrash")
                    if (view != null) {
                        val parent = view.parent as? ViewGroup
                        parent?.removeView(view)
                        view.destroy()
                    }
                    carWebView = null
                    mainHandler.postDelayed({
                        getPersistentCarWebView(appCtx)
                    }, 1000L)
                    return true
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString() ?: "Unknown error"
                        val code = error?.errorCode ?: -1
                        AppCrashHandler.logError(Exception("WebResourceError ($code): $desc for URL ${request.url}"), "AndroidAutoWebResourceError")
                    }
                }
            }

            web.webChromeClient = object : android.webkit.WebChromeClient() {}

            val prefs = appCtx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            val isDesktop = prefs.getBoolean(SettingsActivity.KEY_DESKTOP_MODE, false)
            if (isDesktop) {
                web.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            } else {
                val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(appCtx)
                web.settings.userAgentString = defaultUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
            }
            val autoResume = prefs.getBoolean(SettingsActivity.KEY_AUTO_RESUME_LAST_TRACK, true)
            val lastUrl = prefs.getString(SettingsActivity.KEY_LAST_PLAYED_URL, null)
            val defaultHome = if (isDesktop) "https://www.youtube.com" else "https://m.youtube.com"
            val initialUrl = if (autoResume && !lastUrl.isNullOrBlank() && lastUrl.contains("watch")) {
                userWantsPlayback = true
                lastUrl
            } else {
                defaultHome
            }
            currentLoadingUrl = initialUrl
            web.loadUrl(initialUrl)

            registerService(appCtx)
        } else {
            try {
                val prefs = appCtx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                val autoResume = prefs.getBoolean(SettingsActivity.KEY_AUTO_RESUME_LAST_TRACK, true)
                val lastUrl = prefs.getString(SettingsActivity.KEY_LAST_PLAYED_URL, null)
                val cur = carWebView?.url
                if (autoResume && !lastUrl.isNullOrBlank() && lastUrl.contains("watch")) {
                    userWantsPlayback = true
                    if (cur.isNullOrBlank() || cur == "about:blank" || !cur.contains("watch")) {
                        if (currentLoadingUrl != lastUrl) {
                            currentLoadingUrl = lastUrl
                            carWebView?.loadUrl(lastUrl)
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        try {
            (carWebView?.parent as? android.view.ViewGroup)?.removeView(carWebView)
        } catch (e: Exception) {}

        return carWebView!!
    }

    fun getPersistentWebView(context: Context? = null): WebView? {
        return if (context != null) getPersistentCarWebView(context) else carWebView
    }

    fun registerService(context: Context) {
        try {
            if (CarMediaBrowserService.instance != null) {
                return
            }
            val serviceIntent = Intent(context.applicationContext, CarMediaBrowserService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
            } else {
                context.applicationContext.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CARHUD:MediaWakeLock")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 hours max timeout
            }
        } catch (e: Exception) {}
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
    }

    var currentDurationSec: Int = 0
        private set
    var currentPositionSec: Int = 0
        private set

    fun updatePlaybackProgress(currentSec: Int, totalSec: Int) {
        if (currentSec == currentPositionSec && totalSec == currentDurationSec) return
        if (totalSec <= 0 && currentDurationSec > 0 && currentSec == 0) {
            // Ignore transient 0-duration spikes during video buffering
            return
        }
        currentPositionSec = currentSec
        if (totalSec > 0) currentDurationSec = totalSec
        CarMediaBrowserService.instance?.updatePlaybackProgress(currentSec, currentDurationSec)
        _trackState.value = _trackState.value.copy(
            currentPositionSec = currentSec,
            currentDurationSec = currentDurationSec
        )
    }

    fun extractYouTubeVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val regex = Regex("""(?:v=|/v/|/embed/|youtu\.be/|/shorts/)([a-zA-Z0-9_-]{11})""")
        return regex.find(url)?.groupValues?.get(1)
    }

    fun loadArtworkBitmap(urlStr: String, onLoaded: (android.graphics.Bitmap?) -> Unit) {
        imageExecutor.execute {
            try {
                val url = java.net.URL(urlStr)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val input = connection.inputStream
                    val rawBitmap = android.graphics.BitmapFactory.decodeStream(input)
                    input.close()
                    connection.disconnect()

                    if (rawBitmap != null) {
                        val maxDim = 512
                        val w = rawBitmap.width
                        val h = rawBitmap.height
                        val scaledBitmap = if (w > maxDim || h > maxDim) {
                            val scale = maxDim.toFloat() / kotlin.math.max(w, h)
                            val dstW = (w * scale).toInt()
                            val dstH = (h * scale).toInt()
                            android.graphics.Bitmap.createScaledBitmap(rawBitmap, dstW, dstH, true)
                        } else {
                            rawBitmap
                        }
                        mainHandler.post { onLoaded(scaledBitmap) }
                        return@execute
                    }
                } else {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onLoaded(null) }
        }
    }

    fun resolveChannelIcon(name: String, id: String = ""): Int {
        val lower = (name + " " + id).lowercase()
        return when {
            lower.contains("vtv10") -> R.drawable.ic_ch_vtv10
            lower.contains(Regex("""vtv1(?!\d)""")) -> R.drawable.ic_ch_vtv1
            lower.contains(Regex("""vtv2(?!\d)""")) -> R.drawable.ic_ch_vtv2
            lower.contains(Regex("""vtv3(?!\d)""")) -> R.drawable.ic_ch_vtv3
            lower.contains(Regex("""vtv4(?!\d)""")) -> R.drawable.ic_ch_vtv4
            lower.contains(Regex("""vtv5(?!\d)""")) -> R.drawable.ic_ch_vtv5
            lower.contains(Regex("""vtv6(?!\d)""")) -> R.drawable.ic_ch_vtv6
            lower.contains(Regex("""vtv7(?!\d)""")) -> R.drawable.ic_ch_vtv7
            lower.contains(Regex("""vtv8(?!\d)""")) -> R.drawable.ic_ch_vtv8
            lower.contains(Regex("""vtv9(?!\d)""")) -> R.drawable.ic_ch_vtv9
            lower.contains("htv7") -> R.drawable.ic_ch_htv7
            lower.contains("htv9") -> R.drawable.ic_ch_htv9
            lower.contains("vtv") -> R.drawable.ic_ch_vtv
            lower.contains("k+") || lower.contains("sport") || lower.contains("thể thao") -> R.drawable.ic_ch_kplus
            else -> R.drawable.ic_app_iptv
        }
    }

    fun updateTrack(title: String, artist: String, artworkUrl: String? = null, artworkRes: Int = 0) {
        if (title.isBlank()) return
        val newArtist = if (artist.isNotBlank()) artist else "YouTube"

        // Prefer real video ID from active WebView URL, fallback to artworkUrl or lastPlayedUrl
        val curUrl = getActiveWebView()?.url ?: carWebView?.url ?: phoneWebView?.url
        val isIptv = curUrl?.contains("iptv_player.html") == true || activeAppId == "iptv"
        val vid = if (isIptv) null else (extractYouTubeVideoId(curUrl)
            ?: extractYouTubeVideoId(artworkUrl)
            ?: extractYouTubeVideoId(lastPlayedUrl))

        val effectiveArtUrl = if (vid != null) {
            "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
        } else if (!artworkUrl.isNullOrBlank() && !artworkUrl.contains("yt_1200") && !artworkUrl.contains("desktop/")) {
            artworkUrl
        } else {
            ""
        }

        val effectiveRes = if (artworkRes != 0) {
            artworkRes
        } else if (effectiveArtUrl.isNotBlank() && currentArtworkUrl == effectiveArtUrl && currentArtworkBitmap != null) {
            0
        } else if (vid == null && (newArtist.contains("IPTV", ignoreCase = true) || newArtist.contains("Truyền hình", ignoreCase = true) || title.contains("VTV", ignoreCase = true) || title.contains("HTV", ignoreCase = true) || title.contains("VTC", ignoreCase = true))) {
            resolveChannelIcon(title, newArtist)
        } else {
            0
        }

        val artChanged = effectiveArtUrl != currentArtworkUrl
        if (artChanged || effectiveArtUrl.isBlank()) currentArtworkBitmap = null

        currentTitle = title
        currentArtist = newArtist
        currentArtworkUrl = effectiveArtUrl
        _trackState.value = _trackState.value.copy(
            title = title,
            artist = newArtist,
            artworkUrl = currentArtworkUrl,
            artworkBitmap = if (effectiveRes != 0) null else currentArtworkBitmap,
            artworkRes = effectiveRes
        )
        publishState()

        if (currentArtworkUrl.isNotBlank() && (artChanged || currentArtworkBitmap == null)) {
            val requestedArtworkUrl = currentArtworkUrl
            loadArtworkBitmap(requestedArtworkUrl) { bitmap ->
                if (bitmap != null && currentArtworkUrl == requestedArtworkUrl) {
                    currentArtworkBitmap = bitmap
                    _trackState.value = _trackState.value.copy(artworkBitmap = bitmap, artworkRes = 0)
                    CarMediaBrowserService.instance?.updateArtwork(bitmap, currentArtworkUrl)
                }
            }
        }
    }


    private fun publishState() {
        mainHandler.post {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            CarMediaBrowserService.instance?.updatePlaybackState(state, currentTitle, currentArtist)
        }
    }

    private var audioManager: CarAudioManager? = null
    private var navigationDuckCount = 0

    fun beginNavigationDucking() {
        mainHandler.post {
            val web = getActiveWebView() ?: return@post
            val enabled = web.context
                .getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(SettingsActivity.KEY_AUDIO_DUCKING, true)
            if (!enabled) {
                navigationDuckCount = 0
                YouTubePlayerHelper.setDuckingVolume(web, 1.0f)
                return@post
            }
            navigationDuckCount += 1
            YouTubePlayerHelper.setDuckingVolume(web, 0.35f)
        }
    }

    fun endNavigationDucking() {
        mainHandler.post {
            navigationDuckCount = (navigationDuckCount - 1).coerceAtLeast(0)
            if (navigationDuckCount == 0) {
                getActiveWebView()?.let { YouTubePlayerHelper.setDuckingVolume(it, 1.0f) }
            }
        }
    }

    fun resetNavigationDucking() {
        mainHandler.post {
            navigationDuckCount = 0
            getActiveWebView()?.let { YouTubePlayerHelper.setDuckingVolume(it, 1.0f) }
        }
    }

    fun setPlaybackState(playing: Boolean) {
        if (playing) {
            userWantsPlayback = true
        }
        if (isPlaying == playing) return
        isPlaying = playing
        _trackState.value = _trackState.value.copy(isPlaying = playing)
        publishState()

        mainHandler.post {
            try {
                val activeWeb = getActiveWebView()
                activeWeb?.context?.let { ctx ->
                    if (playing) {
                        acquireWakeLock(ctx)
                    } else {
                        releaseWakeLock()
                    }
                    if (audioManager == null) {
                        val mgr = CarAudioManager(ctx) { volume ->
                            getActiveWebView()?.let { web ->
                                YouTubePlayerHelper.setDuckingVolume(web, volume)
                            }
                        }
                        audioManager = mgr
                        activeAudioManager = mgr
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun ensureAudioFocus() {
        mainHandler.post {
            try {
                val activeWeb = getActiveWebView()
                activeWeb?.context?.let { ctx ->
                    acquireWakeLock(ctx)
                    activeWeb.onResume()
                    activeWeb.resumeTimers()
                    if (audioManager == null) {
                        val mgr = CarAudioManager(ctx) { volume ->
                            getActiveWebView()?.let { web ->
                                YouTubePlayerHelper.setDuckingVolume(web, volume)
                            }
                        }
                        audioManager = mgr
                        activeAudioManager = mgr
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun resumePlayback() {
        userWantsPlayback = true
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    web.onResume()
                    web.resumeTimers()
                    val curUrl = web.url ?: ""
                    if (curUrl.contains("iptv_player.html")) {
                        web.evaluateJavascript("if(window.setIptvPlaying) window.setIptvPlaying(true);", null)
                    } else if (!curUrl.contains("watch")) {
                        val ctx = web.context
                        val prefs = ctx.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                        val lastUrl = lastPlayedUrl ?: prefs.getString(SettingsActivity.KEY_LAST_PLAYED_URL, null)
                        if (!lastUrl.isNullOrBlank() && lastUrl.contains("watch")) {
                            if (currentLoadingUrl != lastUrl) {
                                currentLoadingUrl = lastUrl
                                web.loadUrl(lastUrl)
                            }
                        } else if (!isPlaying) {
                            YouTubePlayerHelper.playFirstAvailableVideo(web)
                        }
                    } else if (!isPlaying) {
                        YouTubePlayerHelper.resumePlayback(web)
                    }
                    web.context?.let { ctx ->
                        acquireWakeLock(ctx)
                    }
                }
                ensureAudioFocus()
                setPlaybackState(true)
            } catch (e: Exception) {}
        }
    }

    fun pausePlayback() {
        userWantsPlayback = false
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    if (web.url?.contains("iptv_player.html") == true) {
                        web.evaluateJavascript("if(window.setIptvPlaying) window.setIptvPlaying(false);", null)
                    } else {
                        YouTubePlayerHelper.pausePlayback(web)
                    }
                }
                setPlaybackState(false)
            } catch (e: Exception) {}
        }
    }

    fun togglePlayPause(forcePlay: Boolean? = null) {
        val nextPlay = forcePlay ?: !isPlaying
        if (nextPlay) {
            resumePlayback()
        } else {
            pausePlayback()
        }
    }

    internal fun handleSteeringNext(context: Context, event: android.view.KeyEvent? = null) {
        val appContext = context.applicationContext
        val keyDownTime = event?.downTime
        val repeatCount = event?.repeatCount ?: 0
        val source = if (event == null) SteeringNextPressHandler.Source.TRANSPORT else SteeringNextPressHandler.Source.KEY_EVENT
        val handle = Runnable {
            val prefs = appContext.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
            val appAtPress = activeAppId
            steeringNextHandler.press(
                source = source,
                keyDownTime = keyDownTime,
                repeatCount = repeatCount,
                doubleClickVoice = prefs.getBoolean(SettingsActivity.KEY_STEERING_DOUBLE_CLICK_VOICE, true),
                windowMs = prefs.getInt(SettingsActivity.KEY_STEERING_DOUBLE_CLICK_SPEED, 500).toLong(),
                singlePressVoice = prefs.getString(SettingsActivity.KEY_STEERING_NEXT_ACTION, "next") == "voice",
                onSingle = { if (activeAppId == appAtPress) playNext() },
                onVoice = { startGlobalVoiceSearch(appContext) }
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) handle.run() else mainHandler.post(handle)
    }

    internal fun cancelPendingSteeringNext() {
        if (Looper.myLooper() == Looper.getMainLooper()) steeringNextHandler.cancelPending()
        else mainHandler.post { steeringNextHandler.cancelPending() }
    }

    fun playNext() {
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    val url = web.url ?: ""
                    if (url.contains("iptv_player.html") || activeAppId == "iptv") {
                        web.evaluateJavascript("if(window.nextChannel) window.nextChannel();", null)
                    } else {
                        YouTubePlayerHelper.playNext(web)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun playPrevious() {
        mainHandler.post {
            steeringNextHandler.cancelPending()
            try {
                getActiveWebView()?.let { web ->
                    val url = web.url ?: ""
                    if (url.contains("iptv_player.html") || activeAppId == "iptv") {
                        web.evaluateJavascript("if(window.prevChannel) window.prevChannel();", null)
                    } else {
                        YouTubePlayerHelper.playPrevious(web)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun seekTo(seconds: Long) {
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    YouTubePlayerHelper.seekTo(web, seconds)
                }
            } catch (e: Exception) {}
        }
    }

    var isShuffleEnabled = false
    var isRepeatEnabled = false
    var isLiked = false

    fun toggleShuffle() {
        isShuffleEnabled = !isShuffleEnabled
        _trackState.value = _trackState.value.copy(isShuffleEnabled = isShuffleEnabled)
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    YouTubePlayerHelper.toggleShuffle(web)
                }
            } catch (e: Exception) {}
        }
    }

    fun toggleRepeat() {
        isRepeatEnabled = !isRepeatEnabled
        _trackState.value = _trackState.value.copy(isRepeatEnabled = isRepeatEnabled)
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    YouTubePlayerHelper.toggleRepeat(web)
                }
            } catch (e: Exception) {}
        }
    }

    fun toggleLike() {
        isLiked = !isLiked
        _trackState.value = _trackState.value.copy(isLiked = isLiked)
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    YouTubePlayerHelper.toggleLike(web)
                }
            } catch (e: Exception) {}
        }
    }

    fun goBack() {
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    if (web.canGoBack()) web.goBack() else web.loadUrl("https://m.youtube.com")
                }
            } catch (e: Exception) {}
        }
    }

    fun reload() {
        mainHandler.post {
            try {
                getActiveWebView()?.reload()
            } catch (e: Exception) {}
        }
    }

    fun loadUrl(url: String) {
        mainHandler.post {
            try {
                getActiveWebView()?.loadUrl(url)
            } catch (e: Exception) {}
        }
    }

    fun search(query: String) {
        mainHandler.post {
            try {
                getActiveWebView()?.let { web ->
                    YouTubePlayerHelper.search(web, query)
                }
            } catch (e: Exception) {}
        }
    }

    var activeVoiceManager: VoiceSearchManager? = null
    private val voiceListeners = java.util.concurrent.CopyOnWriteArraySet<(VoiceSearchManager.State, String) -> Unit>()

    fun registerVoiceListener(listener: (VoiceSearchManager.State, String) -> Unit) {
        voiceListeners.add(listener)
    }

    fun unregisterVoiceListener(listener: (VoiceSearchManager.State, String) -> Unit) {
        voiceListeners.remove(listener)
    }

    fun notifyVoiceState(state: VoiceSearchManager.State, text: String) {
        mainHandler.post {
            for (listener in voiceListeners) {
                try {
                    listener.invoke(state, text)
                } catch (e: Exception) {}
            }
        }
    }

    fun startGlobalVoiceSearch(context: Context) {
        mainHandler.post {
            steeringNextHandler.cancelPending()
            try {
                activeVoiceManager?.startListening()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveLastPlayedUrl(context: Context, url: String) {
        lastPlayedUrl = url
        if (url.contains("watch") || url.contains("/shorts/")) {
            try {
                val prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(SettingsActivity.KEY_LAST_PLAYED_URL, url).apply()
            } catch (e: Exception) {}

            val vid = extractYouTubeVideoId(url)
            if (vid != null) {
                val artUrl = "https://i.ytimg.com/vi/$vid/hqdefault.jpg"
                if (artUrl != currentArtworkUrl || currentArtworkBitmap == null) {
                    currentArtworkUrl = artUrl
                    loadArtworkBitmap(artUrl) { bmp ->
                        if (bmp != null) {
                            currentArtworkBitmap = bmp
                            _trackState.value = _trackState.value.copy(artworkBitmap = bmp, artworkUrl = artUrl)
                            CarMediaBrowserService.instance?.updateArtwork(bmp, artUrl)
                        }
                    }
                }
            }
        }
    }

    // Search Bridge between CarPresentation, PhoneSearchActivity, and CarHudAutoScreen
    private val searchRequestListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    private val searchQueryListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    private val searchDismissListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val searchLiveTextListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()
    private val searchTextLock = Any()
    private var latestSearchText = ""
    private var searchTextQueued = false
    private val carNativeSearchListeners = java.util.concurrent.CopyOnWriteArraySet<(String) -> Unit>()

    fun registerSearchRequestListener(listener: (String) -> Unit) {
        searchRequestListeners.add(listener)
    }

    fun unregisterSearchRequestListener(listener: (String) -> Unit) {
        searchRequestListeners.remove(listener)
    }

    fun requestSearch(initialQuery: String = "") {
        mainHandler.post {
            for (l in searchRequestListeners) {
                try { l.invoke(initialQuery) } catch (e: Exception) {}
            }
        }
    }

    fun registerSearchQueryListener(listener: (String) -> Unit) {
        searchQueryListeners.add(listener)
    }

    fun unregisterSearchQueryListener(listener: (String) -> Unit) {
        searchQueryListeners.remove(listener)
    }

    fun submitSearchQuery(query: String) {
        mainHandler.post {
            for (l in searchQueryListeners) {
                try { l.invoke(query) } catch (e: Exception) {}
            }
        }
    }

    fun registerSearchDismissListener(listener: () -> Unit) {
        searchDismissListeners.add(listener)
    }

    fun unregisterSearchDismissListener(listener: () -> Unit) {
        searchDismissListeners.remove(listener)
    }

    fun dismissSearch() {
        mainHandler.post {
            for (l in searchDismissListeners) {
                try { l.invoke() } catch (e: Exception) {}
            }
        }
    }

    fun registerSearchLiveTextListener(listener: (String) -> Unit) {
        searchLiveTextListeners.add(listener)
    }

    fun unregisterSearchLiveTextListener(listener: (String) -> Unit) {
        searchLiveTextListeners.remove(listener)
    }

    fun updateSearchText(text: String) {
        synchronized(searchTextLock) {
            latestSearchText = text
            if (searchTextQueued) return
            searchTextQueued = true
        }
        mainHandler.post {
            val latest = synchronized(searchTextLock) {
                searchTextQueued = false
                latestSearchText
            }
            for (l in searchLiveTextListeners) {
                try { l.invoke(latest) } catch (e: Exception) {}
            }
        }
    }

    fun registerCarNativeSearchListener(listener: (String) -> Unit) {
        carNativeSearchListeners.add(listener)
    }

    fun unregisterCarNativeSearchListener(listener: (String) -> Unit) {
        carNativeSearchListeners.remove(listener)
    }

    fun requestCarNativeSearch(query: String = "") {
        mainHandler.post {
            for (l in carNativeSearchListeners) {
                try { l.invoke(query) } catch (e: Exception) {}
            }
        }
    }

    fun launchPhoneSearchActivity(context: Context, query: String = "") {
        try {
            val intent = Intent(context, PhoneSearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("INITIAL_QUERY", query)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        showPhoneSearchNotification(context, query)
    }

    @SuppressLint("MissingPermission")
    fun showPhoneSearchNotification(context: Context, query: String = "") {
        try {
            val channelId = "carhud_search_input"
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Nhập liệu THTV từ điện thoại",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Mở bàn phím điện thoại để nhập tìm kiếm cho màn hình xe"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, PhoneSearchActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("INITIAL_QUERY", query)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context,
                1002,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.carhud.app.R.drawable.ic_bar_search)
                .setContentTitle("⌨️ Nhập liệu cho ô tô (THTV)")
                .setContentText("Chạm vào đây để mở bàn phím điện thoại gõ tiếng Việt có dấu")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            notificationManager.notify(8881, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelPhoneSearchNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(8881)
        } catch (e: Exception) {}
    }
}
