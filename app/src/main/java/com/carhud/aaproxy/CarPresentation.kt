package com.carhud.aaproxy

import com.carhud.app.R
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.core.content.ContextCompat
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

class CarPresentation(
    context: Context,
    display: Display,
    private val existingWebView: WebView? = null
) : Presentation(android.view.ContextThemeWrapper(context, R.style.Theme_CarHud), display, R.style.Theme_CarHud), SharedPreferences.OnSharedPreferenceChangeListener {

    private var web: WebView = existingWebView ?: BackgroundAudioWebView(try { context.createDisplayContext(display) } catch (e: Exception) { context }).apply {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }
    private lateinit var root: FrameLayout
    private var sidebarContainer: View? = null
    private var sidebarView: LinearLayout? = null
    private lateinit var statusBanner: TextView
    private var micBtn: ImageView? = null
    private var playPauseBtn: ImageView? = null
    private var isCurrentlyPlaying = false
    private lateinit var searchOverlay: FrameLayout
    private lateinit var searchInput: EditText
    private var searchOverlayMode: String = "youtube" // youtube | web (page field) | address
    private var searchOverlayVoiceButton: TextView? = null
    private var searchOverlaySubmitButton: TextView? = null
    private var searchSuggestionStrip: View? = null
    private var keyboardActionKey: TextView? = null
    private var carKeyboard: CarKeyboardLayout? = null
    private var webKeyboardTargetPending: Boolean = false
    private var webInputSubmissionPending = false
    private var topToolbarContainer: View? = null
    private var browserAddressView: TextView? = null
    private var browserBackButton: TextView? = null
    private var browserForwardButton: TextView? = null
    private var browserReloadButton: TextView? = null
    private var browserProgress: ProgressBar? = null
    private var browserLoading = false
    private var browserNeedsHistoryReset = false
    private var isWebVideoFullscreen = false
    private var systemVoiceGeneration = 0L
    private var systemVoiceSearchPending = false
    private val systemVoiceSearchScript by lazy { context.assets.open("system_voice_search.js").bufferedReader().use { it.readText() } }
    private val systemVoiceReceiver: (SystemVoiceCommand) -> Unit = { handleSystemVoiceCommand(it) }
    private var appGridOverlay: FrameLayout? = null
    private var appGridPanel: LinearLayout? = null
    private var appGridPosToggleBtn: TextView? = null
    private var appGridSizeBtn: TextView? = null
    private var addAppOverlay: FrameLayout? = null
    private var addAppNameInput: EditText? = null
    private var addAppUrlInput: EditText? = null
    private var addAppDesktopCheck: CheckBox? = null
    private var addAppIconPreview: ImageView? = null
    private var downloadedFaviconBitmap: Bitmap? = null
    private var dashboardView: CarDashboardView? = null
    private var isDashboardShowing = true
    private var currentActiveAppId: String = "youtube"
    private val mainHandler = Handler(Looper.getMainLooper())

    // Prevent repeated play/resume commands when Android Auto recreates the surface
    // or YouTube briefly reports a paused/buffering state.
    private var autoResumePending = false

    companion object {
        @Volatile
        private var lastGlobalAutoResumeAt = 0L

        val ASPECT_MODES = listOf("fill", "contain", "cover", "split", "4:3")
        val ASPECT_LABELS = mapOf(
            "fill" to "Toàn màn hình (Tràn viền 100%)",
            "contain" to "Toàn màn hình (Chuẩn 16:9)",
            "cover" to "Toàn màn hình (Phóng to Zoom)",
            "split" to "Chia màn hình (Kèm danh sách kênh)",
            "4:3" to "Tỷ lệ 4:3 (Truyền hình cổ điển)"
        )
    }

    private fun applyEffectiveViewportFromRoot(force: Boolean = false) {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return

        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) root.rootWindowInsets else null
        val insetLeft = insets?.systemWindowInsetLeft ?: 0
        val insetTop = insets?.systemWindowInsetTop ?: 0
        val insetRight = insets?.systemWindowInsetRight ?: 0
        val insetBottom = insets?.systemWindowInsetBottom ?: 0

        val effectiveWidth = (root.width - insetLeft - insetRight).coerceAtLeast(1)
        val effectiveHeight = (root.height - insetTop - insetBottom).coerceAtLeast(1)
        val key = "$effectiveWidth|$effectiveHeight|$carDpi"
        if (!force && key == lastEffectiveViewportKey) return
        lastEffectiveViewportKey = key

        val oldW = carWidth
        val oldH = carHeight
        val oldUltra = isUltrawide
        val oldPortrait = isPortrait

        carWidth = effectiveWidth
        carHeight = effectiveHeight
        aspectRatio = carWidth.toFloat() / carHeight.toFloat()
        isUltrawide = aspectRatio >= 1.85f
        isPortrait = aspectRatio < 0.95f

        // Feed the actual AA viewport into every responsive subsystem.
        dashboardView?.applyAdaptiveScreen(carWidth, carHeight)

        lastAppliedScale = -1
        applyWebScaleForUrl(web.url)
        try {
            YouTubePlayerHelper.inject(
                web,
                isUltrawide,
                isPortrait,
                carWidth,
                carHeight,
                carDpi,
                phoneDpi.toInt(),
                aspectRatio
            )
        } catch (_: Throwable) {}

        updateHudLayoutParams()

        // Only rebuild floating chrome when the effective viewport or layout
        // class actually changed, avoiding loops from minor layout callbacks.
        if (force ||
            kotlin.math.abs(oldW - carWidth) > 8 ||
            kotlin.math.abs(oldH - carHeight) > 8 ||
            oldUltra != isUltrawide ||
            oldPortrait != isPortrait
        ) {
            rebuildSidebar()
            rebuildTopToolbar()
        }

        // Persist the effective viewport so the phone settings/debug UI can
        // show exactly what layout dimensions were used.
        prefs.edit()
            .putInt("car_layout_width", carWidth)
            .putInt("car_layout_height", carHeight)
            .putFloat("car_layout_aspect", aspectRatio)
            .putString(
                "car_layout_class",
                when {
                    isPortrait -> "PORTRAIT"
                    aspectRatio >= 2.40f -> "SUPER_ULTRAWIDE"
                    isUltrawide -> "ULTRAWIDE"
                    aspectRatio >= 1.55f -> "WIDE"
                    else -> "COMPACT"
                }
            )
            .apply()
    }

    private fun scheduleSafeResume(target: WebView, delayMs: Long = 500L) {
        if (autoResumePending) return
        if (!CarMediaManager.userWantsPlayback || CarMediaManager.isPlaying) return

        val now = SystemClock.uptimeMillis()
        if (now - lastGlobalAutoResumeAt < 3000L) return

        autoResumePending = true
        target.postDelayed({
            autoResumePending = false

            val currentUrl = target.url.orEmpty()
            val isYouTubeWatch = currentUrl.contains("youtube.com/watch") ||
                currentUrl.contains("m.youtube.com/watch") ||
                currentUrl.contains("youtu.be/")

            if (isYouTubeWatch &&
                CarMediaManager.userWantsPlayback &&
                !CarMediaManager.isPlaying
            ) {
                lastGlobalAutoResumeAt = SystemClock.uptimeMillis()
                YouTubePlayerHelper.resumePlayback(target)
            }
        }, delayMs)
    }

    private var isUltrawide = false
    private var isPortrait = false
    private var carWidth = 0
    private var carHeight = 0
    private var carDpi = 160
    private var phoneDpi = 160f
    private var aspectRatio = 1.777f
    private var lastEffectiveViewportKey = ""
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
    }
    private var hudOverlay: VietmapHudOverlay? = null
    private val hudPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("waze_hud_settings_prefs", Context.MODE_PRIVATE)
    }
    private val hudPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mainHandler.post {
            updateHudOverlay()
        }
    }

    private val searchLiveTextListener: (String) -> Unit = { text ->
        mainHandler.post {
            if (::searchInput.isInitialized && searchInput.text.toString() != text) {
                searchInput.setText(text)
                searchInput.setSelection(text.length)
            }
        }
    }

    private val voiceManager = VoiceSearchManager(
        context = context,
        onResult = { query ->
            mainHandler.post {
                handleVoiceQuery(query)
            }
        },
        onStateChanged = { state, text ->
            mainHandler.post {
                if (state == VoiceSearchManager.State.LISTENING || state == VoiceSearchManager.State.RECOGNIZING) {
                    if (state == VoiceSearchManager.State.LISTENING) cancelSystemVoiceRequest()
                    YouTubePlayerHelper.setDuckingVolume(web, 0.0f)
                } else if (state == VoiceSearchManager.State.IDLE || state == VoiceSearchManager.State.ERROR || state == VoiceSearchManager.State.SUCCESS) {
                    YouTubePlayerHelper.setDuckingVolume(web, 1.0f)
                }
                updateVoiceState(state, text)
                if (state == VoiceSearchManager.State.LISTENING) {
                    searchInput.hint = if (isBrowserApp() && !isDashboardShowing) "🎙️ Đang lắng nghe từ khóa hoặc địa chỉ web..." else "🎙️ Đang lắng nghe... Nói tên bài hát"
                } else if (state == VoiceSearchManager.State.RECOGNIZING) {
                    if (text.startsWith("🎙️ ")) {
                        val partial = text.removePrefix("🎙️ ").trim()
                        if (partial.isNotEmpty()) {
                            searchInput.setText(partial)
                            searchInput.setSelection(partial.length)
                        }
                    } else {
                        searchInput.hint = "🎙️ Đang nhận diện: $text"
                    }
                } else if (state == VoiceSearchManager.State.ERROR) {
                    searchInput.hint = "⚠️ $text - Gõ bàn phím bên dưới để tìm"
                }
                CarMediaManager.notifyVoiceState(state, text)
            }
        }
    )

    private val voiceListener: (VoiceSearchManager.State, String) -> Unit = { state, text ->
        mainHandler.post {
            updateVoiceState(state, text)
        }
    }

    private val searchQueryListener: (String) -> Unit = { query ->
        mainHandler.post {
            searchInput.setText(query)
            searchInput.setSelection(query.length)
            if (searchOverlayMode == "web" || searchOverlayMode == "address") {
                executeSearch(query)
                return@post
            }
            if (isDashboardShowing) {
                showWebFullscreen()
            }
            YouTubePlayerHelper.search(web, query)
            hideSearchOverlay(notifyPhone = false)
        }
    }

    private val searchDismissListener: () -> Unit = {
        mainHandler.post {
            hideSearchOverlay(notifyPhone = false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (existingWebView != null && !web.url.isNullOrBlank() && CarMediaManager.activeAppId == "web") {
            currentActiveAppId = "web"
        }
        CarMediaManager.activeVoiceManager = voiceManager
        voiceManager.prewarm()
        CarMediaManager.registerVoiceListener(voiceListener)
        CarMediaManager.registerSearchQueryListener(searchQueryListener)
        CarMediaManager.registerSearchDismissListener(searchDismissListener)
        CarMediaManager.registerSearchLiveTextListener(searchLiveTextListener)

        // Generic browser input bridge: lets web-page INPUT/TEXTAREA fields open
        // the large Android Auto on-screen keyboard without relying on the phone IME.
        try {
            web.addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun openKeyboard(initialValue: String?) {
                    mainHandler.post {
                        if (isBrowserApp() && (!::searchOverlay.isInitialized || searchOverlay.visibility != View.VISIBLE)) {
                            showWebKeyboardOverlay(initialValue.orEmpty())
                        }
                    }
                }
            }, "CarHudInput")
        } catch (_: Exception) {}

        try {
            GpsSpeedManager.start(context)
        } catch (e: Exception) {}

        // Force Presentation to fill the entire VirtualDisplay & enable device keyboard (IME)
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window?.decorView?.setBackgroundColor(Color.BLACK)
        try {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        } catch (e: Exception) {}
        // Chromium WebView natively manages AudioFocus; do not call requestAudioFocus here.
        prefs.registerOnSharedPreferenceChangeListener(this)

        root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Auto-detect car screen metrics and form-factor
        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        carWidth = if (metrics.widthPixels > 0) metrics.widthPixels else 800
        carHeight = if (metrics.heightPixels > 0) metrics.heightPixels else 480
        carDpi = if (metrics.densityDpi > 0) metrics.densityDpi else 160
        aspectRatio = if (carHeight > 0) carWidth.toFloat() / carHeight.toFloat() else 1.777f

        isUltrawide = (aspectRatio >= 1.85f)
        isPortrait = (aspectRatio < 0.95f)

        // 1. YouTube WebView (Detach from old parent if any)
        (web.parent as? ViewGroup)?.removeView(web)
        
        // Calculate the scale difference to fix UI sizing on Android Auto
        phoneDpi = web.context.applicationContext.resources.displayMetrics.densityDpi.toFloat()
        applyWebScaleForUrl(web.url)

        // ALWAYS configure webview performance & initial theme background
        val initIsDay = isDayMode()
        web.setBackgroundColor(if (initIsDay) Color.WHITE else Color.BLACK)
        YouTubePlayerHelper.applyUltraPerformance(web)

        // Apply scaling & responsive layout immediately to the active WebView
        YouTubePlayerHelper.inject(web, isUltrawide, isPortrait, carWidth, carHeight, carDpi, phoneDpi.toInt(), aspectRatio)
        YouTubePlayerHelper.applyTheme(web, initIsDay)

        try {
            web.removeJavascriptInterface("AndroidVoice")
        } catch (e: Exception) {}

        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun isAuto(): Boolean = true

            @JavascriptInterface
            fun onUserTouch() {
                mainHandler.post {
                    showBars()
                }
            }

            @JavascriptInterface
            fun onPlaybackStateChanged(playing: Boolean) {
                mainHandler.post {
                    setPlaybackState(playing)
                    CarMediaManager.setPlaybackState(playing)
                }
            }

            @JavascriptInterface
            fun onTrackChanged(title: String, artist: String) {
                mainHandler.post {
                    CarMediaManager.updateTrack(title, artist, null)
                }
            }

            @JavascriptInterface
            fun onTrackChanged(title: String, artist: String, thumbUrl: String) {
                mainHandler.post {
                    CarMediaManager.updateTrack(title, artist, thumbUrl)
                }
            }


            @JavascriptInterface
            fun onUserSelectedVideo(url: String) {
                CarMediaManager.userWantsPlayback = true
                CarMediaManager.saveLastPlayedUrl(context, url)
                mainHandler.post {
                    CarMediaManager.ensureAudioFocus()
                    CarMediaManager.acquireWakeLock(context)
                }
            }

            @JavascriptInterface
            fun saveLastPlayedUrl(url: String) {
                CarMediaManager.saveLastPlayedUrl(context, url)
            }

            @JavascriptInterface
            fun onVideoTimeUpdate(currentSec: Int, totalSec: Int, formattedTime: String) {
                mainHandler.post {
                    updateVideoProgress(currentSec, totalSec, formattedTime)
                    CarMediaManager.updatePlaybackProgress(currentSec, totalSec)
                }
            }

            @JavascriptInterface
            fun openSearchKeyboard() {
                mainHandler.post {
                    showSearchOverlay()
                }
            }

            @JavascriptInterface
            fun startListening() {
                mainHandler.post {
                    startVoiceSearch()
                }
            }

            @JavascriptInterface
            fun fetchSponsorSegments(videoId: String) {
                SponsorBlockManager.fetchSegments(videoId) { jsonString ->
                    mainHandler.post {
                        try {
                            val jsonLiteral = org.json.JSONObject.quote(jsonString)
                            web.evaluateJavascript("if (window.FermataSB && window.FermataSB.onSegmentsLoaded) { window.FermataSB.onSegmentsLoaded('$videoId', $jsonLiteral); }", null)
                        } catch (e: Exception) {}
                    }
                }
            }
        }, "AndroidVoice")

        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun getChannelsJson(): String {
                return IptvManager.getCachedJson(context)
            }

            @android.webkit.JavascriptInterface
            fun getCustomUrl(): String {
                return IptvManager.getM3uUrl(context)
            }

            @android.webkit.JavascriptInterface
            fun saveCustomUrl(newUrl: String) {
                mainHandler.post {
                    IptvManager.setM3uUrl(context, newUrl)
                    IptvManager.refresh(context) { freshList ->
                        val arr = org.json.JSONArray()
                        for (ch in freshList) arr.put(ch.toJson())
                        web.evaluateJavascript("window.onChannelsUpdated('${arr.toString().replace("'", "\\'")}')", null)
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun saveCustomM3uString(fileName: String, content: String) {
                mainHandler.post {
                    IptvManager.setM3uContent(context, content, fileName)
                    IptvManager.refresh(context) { freshList ->
                        val arr = org.json.JSONArray()
                        for (ch in freshList) arr.put(ch.toJson())
                        web.evaluateJavascript("window.onChannelsUpdated('${arr.toString().replace("'", "\\'")}')", null)
                    }
                }
            }

            @android.webkit.JavascriptInterface
            fun refresh() {
                mainHandler.post {
                    IptvManager.refresh(context) { freshList ->
                        val arr = org.json.JSONArray()
                        for (ch in freshList) arr.put(ch.toJson())
                        web.evaluateJavascript("window.onChannelsUpdated('${arr.toString().replace("'", "\\'")}')", null)
                    }
                }
            }
        }, "AndroidIptv")

        IptvManager.getChannels(context) {}

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target == "intent:home") {
                    goHome()
                    return true
                }
                if (target.startsWith("intent:") || 
                    target.startsWith("snssdk") || 
                    target.startsWith("market:") || 
                    target.contains("play.google.com/store")) {
                    return true // Block app redirect, stay in webview
                }
                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return false
                if (target == "intent:home") {
                    goHome()
                    return true
                }
                if (target.startsWith("intent:") || 
                    target.startsWith("snssdk") || 
                    target.startsWith("market:") || 
                    target.contains("play.google.com/store")) {
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                YouTubeAdBlocker.shouldIntercept(request)?.let { return it }
                val url = request?.url?.toString()
                if (url != null && YouTubePlayerHelper.isAdUrl(url)) {
                    val origin = request.requestHeaders?.get("Origin") ?: request.requestHeaders?.get("origin")
                    return YouTubePlayerHelper.createEmptyResponse(origin)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                browserLoading = true
                updateBrowserToolbar(url)
                val isDay = isDayMode()
                applyUniversalWebTheme(view, isDay)
                applyWebScaleForUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateBrowserToolbar(url)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true && currentActiveAppId == "web") {
                    browserLoading = false
                    updateBrowserToolbar()
                    updateVoiceState(VoiceSearchManager.State.ERROR, "Không tải được trang. Kiểm tra kết nối hoặc địa chỉ rồi bấm tải lại.")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (currentActiveAppId == "web" && browserNeedsHistoryReset && view.url == url) {
                    view.clearHistory()
                    browserNeedsHistoryReset = false
                }
                browserLoading = false
                updateBrowserToolbar(view.url ?: url)
                CarMediaManager.currentLoadingUrl = null
                YouTubeAdBlocker.onPageFinished(view, url)
                applyWebScaleForUrl(url)
                val isDay = isDayMode()
                applyUniversalWebTheme(view, isDay)
                val isYouTube = currentActiveAppId == "youtube" && (url.contains("youtube.com") || url.contains("youtu.be"))
                if (isYouTube) {
                    YouTubePlayerHelper.inject(view, isUltrawide, isPortrait, carWidth, carHeight, carDpi, phoneDpi.toInt(), aspectRatio)
                    YouTubePlayerHelper.applyCarSearchHome(view)
                    updateYouTubeHomeObstacles()
                }
                if (isBrowserApp()) {
                    view.evaluateJavascript("""
                        (function() {
                            if (window.__carhudWebKeyboardInjected) return;
                            window.__carhudWebKeyboardInjected = true;
                            function resolveInput(target) {
                                if (!target) return null;
                                var el = target.closest ? target.closest('input, textarea, [contenteditable="true"]') : null;
                                if (!el || el.disabled || el.readOnly) return null;
                                // Keep buttons, toggles, file pickers and password handling native.
                                if (el.tagName === 'INPUT' && !/^(text|search|email|url|tel|number)${'$'}/.test(el.type)) return null;
                                return el;
                            }
                            document.addEventListener('click', function(e) {
                                var el = resolveInput(e.target);
                                if (!el) return;
                                document.querySelectorAll('[data-carhud-input-target="1"]').forEach(function(x){
                                    if (x !== el) x.removeAttribute('data-carhud-input-target');
                                });
                                el.setAttribute('data-carhud-input-target', '1');
                                var value = (el.value !== undefined) ? String(el.value || '') : String(el.textContent || '');
                                if (window.CarHudInput && window.CarHudInput.openKeyboard) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    window.CarHudInput.openKeyboard(value);
                                }
                            }, true);
                        })();
                    """.trimIndent(), null)
                } else if (isYouTube) {
                    view.evaluateJavascript("""
                        (function() {
                            if (window.__carhudInputListenerInjected) return;
                            window.__carhudInputListenerInjected = true;
                            document.addEventListener('click', function(e) {
                                var t = e.target;
                                if (!t) return;
                                var isMic = t.closest && t.closest('button[aria-label*="giọng nói"], button[aria-label*="voice"], button[aria-label*="mic"], .search-box-mic, ytm-search-box-mic');
                                if (isMic) return;
                                var isInput = (t.tagName === 'INPUT' || (t.closest && t.closest('input, [contenteditable="true"], .searchbox-input, #search, ytd-searchbox, ytm-searchbox')));
                                if (isInput && window.AndroidVoice && window.AndroidVoice.openSearchKeyboard) {
                                    window.AndroidVoice.openSearchKeyboard();
                                }
                            }, true);
                        })();
                    """.trimIndent(), null)
                }
                val autoResume = prefs.getBoolean(SettingsActivity.KEY_AUTO_RESUME_LAST_TRACK, true)
                val savedAspect = prefs.getString(if (web.url?.contains("iptv_player.html") == true) IptvAspectRatio.PREF else "car_video_aspect_mode", "fill") ?: "fill"
                view.postDelayed({
                    if (view.url != url) return@postDelayed
                    if (url.contains("iptv_player.html")) {
                        val toolbarScale = prefs.getInt(SettingsActivity.KEY_TOOLBAR_SCALE, 100).coerceIn(50, 150)
                        view.evaluateJavascript("if (typeof window.restoreVideoAspectRatio === 'function') window.restoreVideoAspectRatio();", null)
                        view.evaluateJavascript("if (typeof window.setIptvToolbarScale === 'function') window.setIptvToolbarScale($toolbarScale);", null)
                        view.evaluateJavascript("if (typeof window.setIptvTheme === 'function') window.setIptvTheme(${if (isDay) "true" else "false"});", null)
                    } else if (isYouTube) {
                        YouTubePlayerHelper.setVideoAspectRatio(view, savedAspect)
                    }
                }, 1000L)
                if (url.contains("watch")) {
                    // Never turn a transient buffering pause into a forced playback loop.
                    if (CarMediaManager.userWantsPlayback) {
                        CarMediaManager.ensureAudioFocus()
                        CarMediaManager.acquireWakeLock(context)
                        scheduleSafeResume(view, 700L)
                    }
                } else if (isYouTube && autoResume && CarMediaManager.userWantsPlayback && (url.endsWith("youtube.com") || url.endsWith("youtube.com/"))) {
                    CarMediaManager.ensureAudioFocus()
                    view.postDelayed({
                        if (!CarMediaManager.isPlaying && CarMediaManager.userWantsPlayback) {
                            YouTubePlayerHelper.playFirstAvailableVideo(view)
                        }
                    }, 800L)
                }
            }
        }

        web.setBackgroundColor(Color.BLACK)
        web.visibility = View.VISIBLE

        var customView: View? = null
        var customViewCallback: WebChromeClient.CustomViewCallback? = null

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                browserProgress?.progress = newProgress
                browserProgress?.visibility = if (newProgress < 100) View.VISIBLE else View.INVISIBLE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                isWebVideoFullscreen = true
                customViewCallback = callback
                web.visibility = View.GONE
                sidebarContainer?.visibility = View.GONE
                topToolbarContainer?.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                if (customView == null) return
                root.removeView(customView)
                customView = null
                isWebVideoFullscreen = false
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                web.visibility = View.VISIBLE
                showBars()
                try {
                    web.evaluateJavascript(
                        """
                        (function() {
                            document.documentElement.classList.remove('carhud-force-fullscreen');
                            document.body.classList.remove('carhud-force-fullscreen');
                            var v = document.querySelector('video');
                            if (v) {
                                v.removeAttribute('data-carhud-styled');
                                v.style.removeProperty('position');
                                v.style.removeProperty('top');
                                v.style.removeProperty('left');
                                v.style.removeProperty('right');
                                v.style.removeProperty('bottom');
                                v.style.removeProperty('width');
                                v.style.removeProperty('height');
                                v.style.removeProperty('margin');
                                v.style.removeProperty('object-fit');
                                v.style.removeProperty('z-index');
                                if (v.paused) { try { v.play(); } catch(e){} }
                            }
                            var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (p && typeof p.playVideo === 'function') { try { p.playVideo(); } catch(e){} }
                        })();
                        """.trimIndent(), null
                    )
                } catch (e: Exception) {}
            }
        }

        if (currentActiveAppId == "web") {
            // A recreated car surface must preserve the browser page and its history.
            web.onResume()
        } else {
            applyDesktopMode(web)
            // CRUCIAL: Ensure YouTube page is loaded (with Auto-Resume last played song)
            val isDesktop = prefs.getBoolean(SettingsActivity.KEY_DESKTOP_MODE, false)
            val defaultHome = if (isDesktop) "https://www.youtube.com" else "https://m.youtube.com"
            val autoResume = prefs.getBoolean(SettingsActivity.KEY_AUTO_RESUME_LAST_TRACK, true)
            val lastUrl = CarMediaManager.lastPlayedUrl ?: prefs.getString(SettingsActivity.KEY_LAST_PLAYED_URL, null)
            val currentUrl = web.url
            val shouldResume = autoResume && !lastUrl.isNullOrBlank() && lastUrl.contains("watch")

            if (shouldResume) {
                // Restore the previous watch only when auto-resume is actually requested.
                CarMediaManager.userWantsPlayback = true
                CarMediaManager.ensureAudioFocus()
                CarMediaManager.acquireWakeLock(context)
                val targetUrl = lastUrl!!
                val alreadyLoading = (CarMediaManager.currentLoadingUrl == targetUrl)
                if (!alreadyLoading && (currentUrl.isNullOrBlank() || currentUrl == "about:blank" || !currentUrl.contains("watch"))) {
                    CarMediaManager.currentLoadingUrl = targetUrl
                    web.loadUrl(targetUrl)
                } else {
                    YouTubePlayerHelper.inject(web, isUltrawide, isPortrait, carWidth, carHeight, carDpi, phoneDpi.toInt(), aspectRatio)
                    scheduleSafeResume(web, 700L)
                }
            } else if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") {
                if (CarMediaManager.currentLoadingUrl != defaultHome) {
                    CarMediaManager.currentLoadingUrl = defaultHome
                    web.loadUrl(defaultHome)
                }
            } else if (currentUrl.contains("watch")) {
                YouTubePlayerHelper.inject(web, isUltrawide, isPortrait, carWidth, carHeight, carDpi, phoneDpi.toInt(), aspectRatio)
                scheduleSafeResume(web, 700L)
            } else {
                YouTubePlayerHelper.inject(web, isUltrawide, isPortrait, carWidth, carHeight, carDpi, phoneDpi.toInt(), aspectRatio)
                if (CarMediaManager.userWantsPlayback && autoResume) {
                    web.postDelayed({
                        YouTubePlayerHelper.playFirstAvailableVideo(web)
                    }, 800L)
                }
            }

        }
        CarMediaManager.registerCarWebView(web, context)

        val dash = CarDashboardView(
            context,
            onAppClick = { app -> switchWebApp(app, embedded = false) },
            onAddAppClick = { showAddAppDialog() },
            onAllAppsClick = { showAppGridOverlay() },
            onFullscreenRequested = { app -> showWebFullscreen(app) },
            onBackClick = { goBack() },
            onEmbeddedClosed = {},
            onSearchClick = { showSearchOverlay() },
            onSettingsClick = { openPhoneSettings() },
            onNotificationClick = {
                toggleDayNightMode()
            },
            onVoiceClick = {
                startVoiceSearch()
            }
        )
        dashboardView = dash
        dash.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && systemVoiceSearchPending) cancelSystemVoiceRequest()
            onUserInteraction()
            if (event.actionMasked == MotionEvent.ACTION_UP && isBrowserApp()) {
                openFocusedWebInput(event.x, event.y)
            }
            false
        }
        root.setOnTouchListener { _, _ ->
            onUserInteraction()
            false
        }

        try {
            (web.parent as? ViewGroup)?.removeView(web)
        } catch (e: Exception) {}
        root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(dash, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (CarMediaManager.isWebShowingFullscreen) {
            dash.visibility = View.GONE
            web.visibility = View.VISIBLE
            isDashboardShowing = false
        } else {
            dash.visibility = View.VISIBLE
            web.visibility = View.VISIBLE
            isDashboardShowing = true
            CarMediaManager.isEmbeddedAppShowing = false
            dash.bringToFront()
        }


        // 2. Build and attach dynamic sidebar
        rebuildSidebar()

        // 3. Build and attach dynamic top toolbar
        rebuildTopToolbar()

        // 4. Centered Large Voice Status Banner
        statusBanner = TextView(context).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(16), dp(28), dp(16))
            background = rounded(Color.parseColor("#EE102032"), 16f, Color.parseColor("#00E5FF"), 2)
            elevation = 40f
            visibility = View.GONE
        }
        val bannerParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        root.addView(statusBanner, bannerParams)

        // 5. In-Car Search Overlay
        searchOverlay = buildSearchOverlay()
        root.addView(searchOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 6. CarWeb-style App Grid Launcher Overlay
        appGridOverlay = buildAppGridOverlay()
        root.addView(appGridOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 7. Add Custom App Dialog Overlay
        addAppOverlay = buildAddAppOverlay()
        root.addView(addAppOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 8. Floating Modern Vietmap/Waze HUD Overlay (Styles #1 to #4)
        setupHudOverlay()
        try {
            hudPrefs.registerOnSharedPreferenceChangeListener(hudPrefsListener)
        } catch (e: Exception) {}

        setContentView(root)

        // Re-measure using the ACTUAL laid-out Android Auto content region.
        // Factory head units such as VF6 can project AA inside a viewport that
        // differs from the physical display metrics.
        root.post {
            applyEffectiveViewportFromRoot(force = true)
        }

        // Capture the final Android Auto viewport after layout and sync it to the
        // activated device's row in Google Sheets.
        root.postDelayed({
            try {
                applyEffectiveViewportFromRoot()
                ScreenProfileReporter.captureAndSync(context, display, root, web)
            } catch (_: Throwable) {
            }
        }, 1200L)

        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            updateYouTubeHomeObstacles()
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                root.post { applyEffectiveViewportFromRoot() }
            }
        }

        // Tạm thời bỏ kích hoạt bản quyền trên màn hình xe theo yêu cầu
        // if (!LicenseManager.isLicensed(context)) {
        //     showCarActivationLock(root)
        // }

        val controlReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.carhud.SHOW_DASHBOARD" -> showDashboard()
                    "com.carhud.SHOW_APP_GRID" -> showAppGridOverlay()
                    "com.carhud.SHOW_WEB" -> showWeb()
                    "com.carhud.TOGGLE_DAY_NIGHT" -> toggleDayNightMode()
                }
            }
        }
        val ctrlFilter = android.content.IntentFilter().apply {
            addAction("com.carhud.SHOW_DASHBOARD")
            addAction("com.carhud.SHOW_APP_GRID")
            addAction("com.carhud.SHOW_WEB")
            addAction("com.carhud.TOGGLE_DAY_NIGHT")
        }
        try {
            ContextCompat.registerReceiver(
                context,
                controlReceiver,
                ctrlFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {}
        SystemVoiceModule.attach(systemVoiceReceiver)
    }

    private fun isHudOverlayEnabled(): Boolean {
        return WazeHudManager.isFloatingOverlayEnabled(context)
    }

    private fun setupHudOverlay() {
        val enabled = isHudOverlayEnabled()
        if (!enabled) {
            hudOverlay?.visibility = View.GONE
            return
        }
        if (hudOverlay != null) {
            updateHudOverlay()
            return
        }
        val overlay = VietmapHudOverlay(context).apply {
            elevation = 120f
        }
        hudOverlay = overlay
        root.addView(overlay)
        updateHudLayoutParams()
        overlay.applyHudConfig()
        overlay.visibility = View.VISIBLE
        root.bringChildToFront(overlay)
    }

    private fun updateHudOverlay() {
        val enabled = isHudOverlayEnabled()
        if (!enabled) {
            hudOverlay?.visibility = View.GONE
            return
        }
        if (hudOverlay == null) {
            setupHudOverlay()
        } else {
            updateHudLayoutParams()
            hudOverlay?.applyHudConfig()
            hudOverlay?.visibility = View.VISIBLE
            hudOverlay?.let { root.bringChildToFront(it) }
        }
    }

    private fun updateHudLayoutParams() {
        val overlay = hudOverlay ?: return
        val styleId = WazeHudManager.getActiveStyleId(context)
        val (posX, posY) = WazeHudManager.getPosition(context)
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (posX >= 0 && posY >= 0) {
                leftMargin = dp(posX)
                topMargin = dp(posY)
            } else {
                when (styleId) {
                    3 -> { // Left Dock default
                        leftMargin = dp(10)
                        topMargin = dp(60)
                    }
                    4 -> { // Right Dock default
                        val screenWdp = (carWidth / resources.displayMetrics.density).roundToInt()
                        leftMargin = dp((screenWdp - 95).coerceAtLeast(20))
                        topMargin = dp(60)
                    }
                    else -> { // #1 & #2 Bubble Ngang default
                        leftMargin = dp(24)
                        topMargin = dp(24)
                    }
                }
            }
        }
        overlay.layoutParams = lp
        root.bringChildToFront(overlay)
    }

    private fun applyDesktopMode(targetWeb: WebView) {
        val isDesktop = prefs.getBoolean(SettingsActivity.KEY_DESKTOP_MODE, false)
        if (isDesktop) {
            targetWeb.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else {
            targetWeb.settings.userAgentString = null
        }
    }

    private var lastAppliedScale = -1
    fun applyWebScaleForUrl(url: String?) {
        val effectivePhoneDpi = if (phoneDpi > 0f) phoneDpi else 440f
        val effectiveCarDpi = if (carDpi > 0) carDpi.toFloat() else 160f
        val scalePercent = if (currentActiveAppId == "web") 0 else ((effectiveCarDpi / effectivePhoneDpi) * 100f).coerceIn(20f, 200f).toInt()
        if (scalePercent != lastAppliedScale) {
            lastAppliedScale = scalePercent
            web.setInitialScale(scalePercent)
            web.settings.useWideViewPort = true
            web.settings.loadWithOverviewMode = true
            web.settings.textZoom = 100
        }
    }

    fun isDayMode(): Boolean = SettingsActivity.resolveIsDay(context)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == IptvAspectRatio.PREF || key == "car_keyboard_telex" ||
            key == SettingsActivity.KEY_LAST_PLAYED_URL || key?.startsWith("car_screen_") == true ||
            key?.startsWith("car_layout_") == true || key?.startsWith("screen_profile_") == true) return
        mainHandler.post {
            if (key == SystemVoiceModule.PREF_ENABLED) cancelSystemVoiceRequest()
            if (key == SettingsActivity.KEY_DESKTOP_MODE && currentActiveAppId == "youtube") {
                applyDesktopMode(web)
                val isDesktop = prefs.getBoolean(SettingsActivity.KEY_DESKTOP_MODE, false)
                val currentUrl = web.url ?: ""
                val targetUrl = if (isDesktop) {
                    if (currentUrl.contains("m.youtube.com")) currentUrl.replace("m.youtube.com", "www.youtube.com") else "https://www.youtube.com"
                } else {
                    if (currentUrl.contains("www.youtube.com")) currentUrl.replace("www.youtube.com", "m.youtube.com") else "https://m.youtube.com"
                }
                web.loadUrl(targetUrl)
            }
            if (key == SettingsActivity.KEY_THEME_MODE || key == "carhud_day_mode") {
                applyCurrentTheme(isDayMode())
            } else if (key != null && (key.startsWith("pref_show_") || key == MainActivity.PREF_FAV_CHANNELS_SET)) {
                val showBottomNav = prefs.getBoolean(MainActivity.PREF_SHOW_BOTTOM_NAV, true)
                sidebarContainer?.visibility = if (showBottomNav && !isDashboardShowing && currentActiveAppId != "iptv") View.VISIBLE else View.GONE
                dashboardView?.applyComponentVisibility()
            } else {
                rebuildSidebar()
                rebuildTopToolbar()
            }
        }
    }

    fun setPlaybackState(playing: Boolean) {
        isCurrentlyPlaying = playing
        playPauseBtn?.let { btn ->
            val iconRes = if (playing) R.drawable.ic_bar_square else R.drawable.ic_bar_play
            btn.setImageResource(iconRes)
            val isDay = isDayMode()
            if (isDay) {
                btn.setColorFilter(Color.parseColor("#1E293B"))
            } else {
                btn.clearColorFilter()
            }
        }
    }

    private var videoTimePill: TextView? = null

    fun updateVideoProgress(currentSec: Int, totalSec: Int, formattedTime: String) {
        val showTime = prefs.getBoolean(SettingsActivity.KEY_SHOW_TIME_PILL, true)
        if (showTime && totalSec > 0 && formattedTime.isNotEmpty() && !isDashboardShowing) {
            videoTimePill?.apply {
                text = "⏱ $formattedTime"
                if (visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                }
            }
        } else {
            videoTimePill?.visibility = View.GONE
        }
    }

    private fun rebuildSidebar() {
        sidebarContainer?.let { root.removeView(it) }
        sidebarContainer = null

        // Settings/theme rebuilds must never restore the floating controls on home.
        if (isDashboardShowing || currentActiveAppId == "iptv" || currentActiveAppId == "web") {
            sidebarContainer?.animate()?.cancel()
            sidebarContainer?.visibility = View.GONE
            sidebarContainer = null
            autoHideHandler.removeCallbacks(hideBarsRunnable)
            return
        }

        val showBottomNav = prefs.getBoolean(MainActivity.PREF_SHOW_BOTTOM_NAV, true)
        if (!showBottomNav) {
            sidebarContainer?.visibility = View.GONE
            return
        }

        val scale = prefs.getInt(SettingsActivity.KEY_TOOLBAR_SCALE, 100)

        // YouTube uses its own compact labeled media toolbar. This replaces the
        // old generic search/theme/aspect toolbar while keeping the same scale
        // setting from the phone Settings screen.
        if (currentActiveAppId == "youtube" && !isDashboardShowing) {
            buildYouTubeMediaToolbar(scale)
            resetAutoHideTimer()
            return
        }

        val iconSizeDp = ((56 * scale) / 100).coerceIn(38, 84)
        val pillThicknessDp = iconSizeDp + 10

        val showBack = prefs.getBoolean(SettingsActivity.KEY_SHOW_BACK, true)
        val showHome = prefs.getBoolean(SettingsActivity.KEY_SHOW_HOME, true)
        val showTv = prefs.getBoolean(SettingsActivity.KEY_SHOW_TV, true)
        val showDayNight = prefs.getBoolean(SettingsActivity.KEY_SHOW_DAY_NIGHT, true)
        val showSearch = isBrowserApp() && prefs.getBoolean(SettingsActivity.KEY_SHOW_KEYBOARD_SEARCH, true)
        val showMic = prefs.getBoolean(SettingsActivity.KEY_SHOW_VOICE_SEARCH, true)
        val showPlayPause = false
        val showNext = false
        val showFullscreen = false
        val showSettings = false

        val autoDetect = prefs.getBoolean(SettingsActivity.KEY_AUTO_DETECT_SCREEN, true)
        val configuredPos = prefs.getString(SettingsActivity.KEY_TOOLBAR_POSITION, "bottom") ?: "bottom"
        val positionKey = when {
            configuredPos == "auto" -> {
                if (isPortrait) "bottom" else "left"
            }
            else -> configuredPos
        }
        val isHorizontal = (positionKey == "bottom")

        val isDay = isDayMode()

        val sidebar = LinearLayout(context).apply {
            orientation = if (isHorizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (isDay) {
                rounded(Color.parseColor("#EBF1F1FB"), 999f, Color.parseColor("#0284C7"), 2)
            } else {
                rounded(Color.parseColor("#F50B132B"), 999f, Color.parseColor("#00E5FF"), 2)
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))

            var hasPrev = false

            if (showBack) {
                addView(createBarIcon(R.drawable.ic_bar_back, iconSizeDp) { goBack() })
                hasPrev = true
            }
            if (showHome) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                val homeBtn = createBarIcon(R.drawable.ic_bar_home, iconSizeDp) { showDashboard() }
                if (isDashboardShowing) {
                    homeBtn.background = rounded(
                        if (isDay) Color.parseColor("#E0F2FE") else Color.parseColor("#1E3B5A"),
                        999f,
                        if (isDay) Color.parseColor("#0284C7") else Color.parseColor("#00E5FF"),
                        2
                    )
                }
                addView(homeBtn)
                hasPrev = true
            }
            // Aspect Ratio Selector (YouTube & IPTV - Nút số 2/3)
            if (showTv) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                val aspectBtn = createBarIcon(R.drawable.ic_bar_fullscreen, iconSizeDp) {
                    cycleVideoAspectRatio()
                }
                aspectBtn.setOnLongClickListener {
                    showAspectRatioDialog()
                    true
                }
                if (!isDashboardShowing) {
                    aspectBtn.background = rounded(
                        if (isDay) Color.parseColor("#E0F2FE") else Color.parseColor("#1E3B5A"),
                        999f,
                        if (isDay) Color.parseColor("#0284C7") else Color.parseColor("#00E5FF"),
                        2
                    )
                }
                addView(aspectBtn)
                hasPrev = true
            }

            // Day / Night mode toggle
            if (showDayNight) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                val dayNightIcon = if (isDay) R.drawable.ic_mode_night else R.drawable.ic_mode_day
                val dayNightBtn = createBarIcon(dayNightIcon, iconSizeDp) { toggleDayNightMode() }
                addView(dayNightBtn)
                hasPrev = true
            }
            if (showSearch) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                addView(createBarIcon(R.drawable.ic_bar_search, iconSizeDp) {
                    if (isBrowserApp()) showBrowserAddressOverlay() else showSearchOverlay()
                })
                hasPrev = true
            }
            if (showMic) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                micBtn = createBarIcon(R.drawable.ic_bar_mic, iconSizeDp) { startVoiceSearch() }
                addView(micBtn)
                hasPrev = true
            }
            if (showPlayPause) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                val initialIcon = if (isCurrentlyPlaying) R.drawable.ic_bar_square else R.drawable.ic_bar_play
                val btn = createBarIcon(initialIcon, iconSizeDp) {
                    togglePlayPause()
                }
                playPauseBtn = btn
                addView(btn)
                hasPrev = true
            }
            if (showNext) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                addView(createBarIcon(R.drawable.ic_bar_next, iconSizeDp) { YouTubePlayerHelper.playNext(web) })
                hasPrev = true
            }
            if (showFullscreen) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                addView(createBarIcon(R.drawable.ic_bar_fullscreen, iconSizeDp) { YouTubePlayerHelper.toggleFullscreen(web) })
                hasPrev = true
            }
            if (showSettings) {
                if (hasPrev) addView(createDivider(iconSizeDp, isHorizontal))
                val gearBtn = createBarIcon(R.drawable.ic_bar_setting, iconSizeDp) { openPhoneSettings() }
                addView(gearBtn)
            }
        }
        sidebarView = sidebar

        val targetGravity = when (positionKey) {
            "right" -> Gravity.END or Gravity.CENTER_VERTICAL
            "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }

        val showTimePill = true
        val timePill = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isDay) Color.parseColor("#0284C7") else Color.parseColor("#00E5FF"))
            background = if (isDay) {
                rounded(Color.parseColor("#F1F5F9"), 10f, Color.parseColor("#CBD5E1"), 1)
            } else {
                rounded(Color.parseColor("#EE101C28"), 10f, Color.parseColor("#1E88E5"), 1)
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
            this.gravity = Gravity.CENTER
            visibility = if (isDashboardShowing || !showTimePill || videoTimePill?.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            text = videoTimePill?.text ?: ""
        }
        videoTimePill = timePill

        val wrapper = LinearLayout(context).apply {
            elevation = 60f
            orientation = if (isHorizontal) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            this.gravity = Gravity.CENTER
            if (isHorizontal) {
                val timeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                }
                addView(timePill, timeLp)
                addView(sidebar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(pillThicknessDp)))
            } else {
                if (positionKey == "right") {
                    val timeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        rightMargin = dp(4)
                    }
                    addView(timePill, timeLp)
                    addView(sidebar, LinearLayout.LayoutParams(dp(pillThicknessDp), ViewGroup.LayoutParams.WRAP_CONTENT))
                } else {
                    addView(sidebar, LinearLayout.LayoutParams(dp(pillThicknessDp), ViewGroup.LayoutParams.WRAP_CONTENT))
                    val timeLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(4)
                    }
                    addView(timePill, timeLp)
                }
            }
        }
        sidebarContainer = wrapper

        val webAppPos = prefs.getString(SettingsActivity.KEY_WEBAPP_TOOLBAR_POSITION, "left") ?: "left"
        val isLeftSidebar = (targetGravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.START)
        val initialLeftMargin = dp(6)

        val wrapperParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.gravity = targetGravity
            leftMargin = initialLeftMargin
            rightMargin = dp(6)
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        if (isDashboardShowing) {
            wrapper.visibility = View.GONE
        }
        root.addView(wrapper, wrapperParams)
        if (!isDashboardShowing) {
            root.bringChildToFront(wrapper)
        }

        resetAutoHideTimer()
    }

    private var youtubeAutoplayButton: LinearLayout? = null

    private fun updateYouTubeHomeObstacles() {
        root.post {
            if (web.width <= 0 || web.height <= 0 || currentActiveAppId != "youtube") return@post
            val origin = IntArray(2)
            web.getLocationOnScreen(origin)
            val obstacles = org.json.JSONArray()
            listOfNotNull(sidebarContainer, hudOverlay, topToolbarContainer).forEach { overlay ->
                if (overlay.width <= 0 || overlay.height <= 0 || overlay.visibility != View.VISIBLE) return@forEach
                val pos = IntArray(2)
                overlay.getLocationOnScreen(pos)
                obstacles.put(org.json.JSONObject().apply {
                    put("x", (pos[0] - origin[0]).toDouble() / web.width)
                    put("y", (pos[1] - origin[1]).toDouble() / web.height)
                    put("right", (pos[0] - origin[0] + overlay.width).toDouble() / web.width)
                    put("bottom", (pos[1] - origin[1] + overlay.height).toDouble() / web.height)
                })
            }
            web.evaluateJavascript("window.__carhudHomeObstacles = $obstacles; if(window.__carhudLayoutSearchHome) window.__carhudLayoutSearchHome();", null)
        }
    }

    private fun buildYouTubeMediaToolbar(scale: Int) {
        val btnWidth = ((74 * scale) / 100).coerceIn(56, 108)
        val btnHeight = ((64 * scale) / 100).coerceIn(50, 94)
        val iconSize = ((26 * scale) / 100).coerceIn(20, 38)
        val textSizeSp = (10.5f * scale / 100f).coerceIn(8.5f, 15f)
        val isDay = isDayMode()

        fun toolButton(iconRes: Int, label: String, onClick: (LinearLayout) -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(
                    if (isDay) Color.parseColor("#F1F5F9") else Color.parseColor("#EC111827"),
                    12f,
                    if (isDay) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"),
                    1
                )
                setPadding(dp(4), dp(5), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(dp(btnWidth), dp(btnHeight)).apply {
                    topMargin = dp(2); bottomMargin = dp(2)
                }
                val icon = ImageView(context).apply {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    if (isDay) {
                        setColorFilter(Color.parseColor(if (iconRes == R.drawable.ic_bar_home) "#1D4ED8" else "#1E293B"))
                    } else {
                        clearColorFilter()
                    }
                }
                addView(icon, LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
                addView(TextView(context).apply {
                    text = label
                    textSize = textSizeSp
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(if (isDay) Color.parseColor("#334155") else Color.parseColor("#E5E7EB"))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                })
                enableTouchBounce()
                setOnClickListener { onClick(this) }
            }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = rounded(
                if (isDay) Color.parseColor("#F6FFFFFF") else Color.parseColor("#F20B1220"),
                16f,
                if (isDay) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"),
                1
            )
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = 60f

            addView(toolButton(R.drawable.ic_bar_home, "Trang chủ") { showDashboard() })

            val auto = toolButton(R.drawable.ic_player_shuffle, "Tự phát") { btn ->
                YouTubePlayerHelper.toggleAutoplay(web) { enabled ->
                    mainHandler.post {
                        btn.background = rounded(
                            if (enabled) Color.parseColor(if (isDay) "#DBEAFE" else "#243D5A") else if (isDay) Color.parseColor("#F1F5F9") else Color.parseColor("#EC111827"),
                            12f,
                            if (enabled) Color.parseColor(if (isDay) "#2563EB" else "#22D3EE") else if (isDay) Color.parseColor("#CBD5E1") else Color.parseColor("#334155"),
                            if (enabled) 2 else 1
                        )
                        (btn.getChildAt(0) as? ImageView)?.apply {
                            if (isDay) setColorFilter(Color.parseColor(if (enabled) "#1D4ED8" else "#1E293B"))
                            else clearColorFilter()
                        }
                        (btn.getChildAt(1) as? TextView)?.setTextColor(
                            Color.parseColor(if (isDay) { if (enabled) "#1E40AF" else "#334155" } else "#E5E7EB")
                        )
                    }
                }
            }
            youtubeAutoplayButton = auto
            addView(auto)

            addView(toolButton(R.drawable.ic_bar_play_pause, "Phát / Dừng") { togglePlayPause() })
            addView(toolButton(R.drawable.ic_bar_next, "Tiếp theo") { YouTubePlayerHelper.playNext(web) })
            addView(toolButton(R.drawable.ic_bar_setting, "Cài đặt") { YouTubePlayerHelper.openQuickSettings(web) })
        }

        sidebarView = panel
        val wrapper = FrameLayout(context).apply {
            addView(panel)
        }
        sidebarContainer = wrapper
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(6)
            topMargin = dp(6)
            bottomMargin = dp(6)
        }
        root.addView(wrapper, params)
        root.bringChildToFront(wrapper)
        updateYouTubeHomeObstacles()
    }

    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val hideBarsRunnable = Runnable {
        hideBars()
    }

    fun showBars() {
        if (isWebVideoFullscreen || (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) ||
            appGridOverlay?.visibility == View.VISIBLE || addAppOverlay?.visibility == View.VISIBLE) return
        updateYouTubeHomeObstacles()
        // IPTV has its own on-screen controls. Keep the native Android Auto
        // navigation/control pill hidden for the whole IPTV session.
        if (isDashboardShowing || currentActiveAppId == "iptv") {
            sidebarContainer?.visibility = View.GONE
            topToolbarContainer?.visibility = View.GONE
            autoHideHandler.removeCallbacks(hideBarsRunnable)
            return
        }
        sidebarContainer?.let { sidebar ->
            if (sidebar.visibility != View.VISIBLE) {
                sidebar.bringToFront()
                sidebar.visibility = View.VISIBLE
                sidebar.alpha = 0f
                sidebar.animate().alpha(1f).setDuration(200).start()
            }
        }
        topToolbarContainer?.let { topBar ->
            if (topBar.visibility != View.VISIBLE) {
                topBar.bringToFront()
                topBar.visibility = View.VISIBLE
                topBar.alpha = 0f
                topBar.animate().alpha(1f).setDuration(200).start()
            }
        }
        resetAutoHideTimer()
    }

    fun hideBars() {
        if (currentActiveAppId == "web" && !isDashboardShowing) return
        sidebarContainer?.let { sidebar ->
            if (sidebar.visibility == View.VISIBLE) {
                sidebar.animate().alpha(0f).setDuration(200).withEndAction {
                    sidebar.visibility = View.GONE
                }.start()
            }
        }
        topToolbarContainer?.let { topBar ->
            if (topBar.visibility == View.VISIBLE) {
                topBar.animate().alpha(0f).setDuration(200).withEndAction {
                    topBar.visibility = View.GONE
                }.start()
            }
        }
    }

    fun showSidebar() = showBars()
    fun hideSidebar() = hideBars()

    fun resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(hideBarsRunnable)
        if (currentActiveAppId == "web") return
        autoHideHandler.postDelayed(hideBarsRunnable, 10000L) // 10s auto-hide
    }

    fun onUserInteraction() {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) return
        mainHandler.post {
            showBars()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    private fun rebuildTopToolbar() {
        topToolbarContainer?.let { root.removeView(it) }
        topToolbarContainer = null
        browserAddressView = null
        browserBackButton = null
        browserForwardButton = null
        browserReloadButton = null
        browserProgress = null
        updateBrowserViewport()
        if (currentActiveAppId != "web" || isDashboardShowing) return

        val day = isDayMode()
        val ink = if (day) Color.parseColor("#202124") else Color.parseColor("#E8EAED")
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (day) Color.WHITE else Color.parseColor("#202124"))
            elevation = dp(150).toFloat()
            isClickable = true
        }
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            // NavigationTemplate draws its own back action in the upper-right corner.
            setPadding(dp(4), dp(4), dp(76), dp(4))
        }
        fun button(label: String, description: String, action: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                contentDescription = description
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(ink)
                setOnClickListener { action() }
                row.addView(this, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }
        button("⌂", "Về màn hình chính", ::goHome)
        browserBackButton = button("←", "Quay lại") { if (!browserNeedsHistoryReset && web.canGoBack()) web.goBack() }
        browserForwardButton = button("→", "Tiến tới") { if (!browserNeedsHistoryReset && web.canGoForward()) web.goForward() }
        browserReloadButton = button("↻", "Tải lại") {
            if (browserLoading) {
                web.stopLoading()
                browserLoading = false
                updateBrowserToolbar()
            } else web.reload()
        }
        browserAddressView = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(10), 0)
            setTextColor(ink)
            background = rounded(if (day) Color.parseColor("#F1F3F4") else Color.parseColor("#303134"), 22f, Color.TRANSPARENT, 0)
            contentDescription = "Tìm kiếm Google hoặc nhập địa chỉ web"
            setOnClickListener { showBrowserAddressOverlay() }
            row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        button("🎙", "Tìm kiếm web bằng giọng nói", ::startVoiceSearch)
        toolbar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        browserProgress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4285F4"))
            toolbar.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        }
        topToolbarContainer = toolbar
        root.addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP))
        updateBrowserToolbar()
    }

    private fun updateBrowserViewport() {
        (web.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.topMargin = if (currentActiveAppId == "web" && !isDashboardShowing) dp(58) else 0
            web.layoutParams = params
        }
    }

    private fun updateBrowserToolbar(url: String? = web.url) {
        browserAddressView?.text = url?.takeIf { it.startsWith("http", true) } ?: "Tìm kiếm hoặc nhập địa chỉ web"
        browserBackButton?.apply { isEnabled = !browserNeedsHistoryReset && web.canGoBack(); alpha = if (isEnabled) 1f else .35f }
        browserForwardButton?.apply { isEnabled = !browserNeedsHistoryReset && web.canGoForward(); alpha = if (isEnabled) 1f else .35f }
        browserReloadButton?.apply {
            text = if (browserLoading) "✕" else "↻"
            contentDescription = if (browserLoading) "Dừng tải" else "Tải lại"
        }
        browserProgress?.visibility = if (browserLoading) View.VISIBLE else View.INVISIBLE
    }

    private fun navigateBrowser(query: String) {
        val target = BrowserNavigation.targetFor(query)
        if (target == null) {
            searchInput.error = "Nhập từ khóa hoặc địa chỉ http/https hợp lệ"
            return
        }
        web.loadUrl(target)
        hideSearchOverlay()
    }

    private fun showDockSizeDialog() {
        val currentScale = prefs.getInt(SettingsActivity.KEY_DOCK_SCALE, 100).coerceIn(50, 150)
        val options = arrayOf("Nhỏ gọn (70%)", "Vừa (85%)", "Chuẩn mặc định (100%)", "Lớn (120%)", "Cực lớn (140%)")
        val scales = intArrayOf(70, 85, 100, 120, 140)
        var selectedIndex = scales.indexOf(currentScale)
        if (selectedIndex == -1) {
            selectedIndex = scales.indices.minByOrNull { Math.abs(scales[it] - currentScale) } ?: 2
        }

        try {
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("📏 Kích thước thanh dock")
                .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                    val newScale = scales[which]
                    prefs.edit().putInt(SettingsActivity.KEY_DOCK_SCALE, newScale).apply()
                    appGridSizeBtn?.text = "📏 Cỡ: $newScale%"
                    rebuildTopToolbar()
                    rebuildSidebar()
                    updateVoiceState(VoiceSearchManager.State.SUCCESS, "Đã chỉnh kích thước dock: $newScale%")
                    dialog.dismiss()
                }
                .setNegativeButton("Đóng", null)
                .show()
        } catch (e: Exception) {}
    }

    private fun showDisableAppDialog(app: WebAppItem) {
        val performDisable = {
            WebAppManager.setAppEnabled(context, app.id, false)
            if (app.id == currentActiveAppId) {
                val enabled = WebAppManager.getEnabledApps(context)
                switchWebApp(enabled.first())
                try {
                    web.stopLoading()
                    web.clearCache(false)
                    web.freeMemory()
                } catch (e: Exception) {}
                System.gc()
            } else {
                rebuildTopToolbar()
                rebuildAppGrid()
            }
            updateVoiceState(VoiceSearchManager.State.SUCCESS, "Đã tắt ${app.name} để tăng tốc!")
        }
        try {
            android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("⚙ Tùy chỉnh dock: ${app.name}")
                .setMessage("Tắt ứng dụng này khỏi thanh dock để giải phóng bộ nhớ RAM và tăng tốc hiệu năng xe?")
                .setPositiveButton("🛑 Tắt app") { _, _ -> performDisable() }
                .setNeutralButton("📏 Cỡ dock") { _, _ -> showDockSizeDialog() }
                .setNegativeButton("Hủy", null)
                .show()
        } catch (e: Exception) {
            performDisable()
        }
    }

    private fun cancelSystemVoiceRequest() {
        systemVoiceGeneration++
        systemVoiceSearchPending = false
    }

    private fun handleSystemVoiceCommand(command: SystemVoiceCommand) {
        if (!SystemVoiceModule.isEnabled(context)) return
        cancelSystemVoiceRequest()
        hideSearchOverlay()
        CarMediaManager.userWantsPlayback = true
        when (command) {
            SystemVoiceCommand.Resume -> {
                CarMediaManager.resumePlayback()
                SystemVoiceModule.report(context, "Đã yêu cầu tiếp tục nội dung đang phát.")
            }
            is SystemVoiceCommand.Tv -> {
                if (command.channel.isBlank()) {
                    CarMediaManager.userWantsPlayback = true
                    playTvChannel("")
                    SystemVoiceModule.report(context, "Đã mở Truyền hình.")
                } else {
                    val token = systemVoiceGeneration
                    IptvManager.getChannels(context) { channels ->
                        mainHandler.post {
                            if (token != systemVoiceGeneration || !SystemVoiceModule.isEnabled(context) || !CarMediaManager.userWantsPlayback) return@post
                            val key = SystemVoiceCommandParser.channelKey(command.channel)
                            val channel = channels.firstOrNull { SystemVoiceCommandParser.channelKey(it.name) == key }
                            if (channel == null) {
                                SystemVoiceModule.fail(context, "Không tìm thấy kênh ${command.channel} trong danh sách IPTV.")
                            } else {
                                CarMediaManager.userWantsPlayback = true
                                playTvChannel(channel.name)
                                SystemVoiceModule.report(context, "Đã gửi yêu cầu mở kênh ${channel.name}.")
                            }
                        }
                    }
                }
            }
            is SystemVoiceCommand.Music -> {
                val youtube = WebAppManager.getAllApps(context).find { it.id == "youtube" }
                    ?: WebAppManager.DEFAULT_APPS.first { it.id == "youtube" }
                val last = CarMediaManager.lastPlayedUrl.orEmpty()
                val lastUri = android.net.Uri.parse(last)
                val lastHost = lastUri.host.orEmpty()
                val canResume = command.query.isBlank() && lastUri.scheme == "https" &&
                    (lastHost == "youtube.com" || lastHost.endsWith(".youtube.com")) && lastUri.path == "/watch"
                val query = command.query.ifBlank { "nhạc Việt" }
                val host = if (WebAppManager.isAppDesktop(context, "youtube")) "https://www.youtube.com" else "https://m.youtube.com"
                val target = if (canResume) last else "$host/results?search_query=" + android.net.Uri.encode(query)
                CarMediaManager.userWantsPlayback = true
                CarMediaManager.ensureAudioFocus()
                CarMediaManager.acquireWakeLock(context)
                switchWebApp(youtube, startUrl = target)
                SystemVoiceModule.report(context, if (canResume) "Đang mở bài nhạc gần nhất." else "Đang tìm nhạc: $query")
                if (!canResume) {
                    systemVoiceSearchPending = true
                    pollSystemVoiceMusic(query, systemVoiceGeneration, 0)
                }
            }
        }
    }

    private fun pollSystemVoiceMusic(query: String, token: Long, attempt: Int) {
        web.postDelayed({
            if (token != systemVoiceGeneration || !systemVoiceSearchPending || currentActiveAppId != "youtube" || !SystemVoiceModule.isEnabled(context) || !CarMediaManager.userWantsPlayback) return@postDelayed
            val quoted = org.json.JSONObject.quote(query)
            web.evaluateJavascript(systemVoiceSearchScript + "\nwindow.__thtvSystemVoiceResult($quoted);") { result ->
                if (token != systemVoiceGeneration || !SystemVoiceModule.isEnabled(context) || !CarMediaManager.userWantsPlayback) return@evaluateJavascript
                val target = try { org.json.JSONTokener(result ?: "null").nextValue() as? String } catch (_: Exception) { null }
                val uri = target?.let { android.net.Uri.parse(it) }
                val host = uri?.host.orEmpty()
                if (uri?.scheme == "https" && (host == "youtube.com" || host.endsWith(".youtube.com")) && uri.path == "/watch" && !uri.getQueryParameter("v").isNullOrBlank()) {
                    systemVoiceSearchPending = false
                    web.loadUrl(target!!)
                    SystemVoiceModule.report(context, "Đã chọn kết quả nhạc cho: $query")
                } else if (attempt < 99) {
                    pollSystemVoiceMusic(query, token, attempt + 1)
                } else {
                    systemVoiceSearchPending = false
                    SystemVoiceModule.fail(context, "Chưa tìm được video để phát. Kiểm tra kết nối hoặc thử tên bài khác.")
                }
            }
        }, 300L)
    }

    private fun handleVoiceQuery(query: String) {
        cancelSystemVoiceRequest()
        if (isBrowserApp() && !isDashboardShowing) {
            YouTubePlayerHelper.setDuckingVolume(web, 1.0f)
            navigateBrowser(query)
            return
        }
        val tvCmd = TvVoiceHelper.parse(query)
        if (tvCmd != null) {
            playTvChannel(tvCmd.channelTarget)
            hideSearchOverlay()
            return
        }

        searchInput.setText(query)
        searchInput.setSelection(query.length)
        YouTubePlayerHelper.setDuckingVolume(web, 1.0f)

        // Ensure active app is YouTube when doing a video search
        if (currentActiveAppId != "youtube") {
            val ytApp = WebAppManager.getAllApps(context).find { it.id == "youtube" } ?: WebAppManager.DEFAULT_APPS.first()
            switchWebApp(ytApp, embedded = isDashboardShowing)
        } else if (isDashboardShowing && (dashboardView?.isEmbeddedAppShowing() != true)) {
            showWebFullscreen()
        }

        YouTubePlayerHelper.search(web, query)
        CarMediaManager.notifyVoiceState(VoiceSearchManager.State.SUCCESS, "Đang tìm: $query")
        updateVoiceState(VoiceSearchManager.State.SUCCESS, "Đang tìm: $query")
        hideSearchOverlay()
    }

    fun playTvChannel(channelName: String) {
        cancelSystemVoiceRequest()
        val iptvApp = WebAppManager.getAllApps(context).find { it.id == "iptv" }
            ?: WebAppManager.DEFAULT_APPS.find { it.id == "iptv" }
            ?: return

        val label = if (channelName.isNotBlank()) channelName else "Truyền hình"
        updateVoiceState(VoiceSearchManager.State.SUCCESS, "📺 Đang mở $label...")
        CarMediaManager.notifyVoiceState(VoiceSearchManager.State.SUCCESS, "📺 Đang mở $label...")

        val isAlreadyIptv = (currentActiveAppId == "iptv" && web.url?.contains("iptv_player.html") == true)
        val targetUrl = if (channelName.isNotBlank()) {
            "file:///android_asset/iptv_player.html#channel=" + android.net.Uri.encode(channelName)
        } else iptvApp.url
        if (isAlreadyIptv) {
            if (isDashboardShowing) showWebFullscreen(iptvApp)
            if (channelName.isNotBlank()) {
                val token = systemVoiceGeneration
                val quoted = org.json.JSONObject.quote(channelName)
                web.evaluateJavascript("Boolean(window.playChannelByName && window.playChannelByName($quoted));") { result ->
                    if (result != "true" && token == systemVoiceGeneration && currentActiveAppId == "iptv") web.loadUrl(targetUrl)
                }
            } else {
                web.evaluateJavascript("if (window.setIptvPlaying) window.setIptvPlaying(true);", null)
            }
        } else {
            switchWebApp(iptvApp.copy(url = targetUrl), embedded = isDashboardShowing)
        }
    }

    private fun isBrowserApp(): Boolean = currentActiveAppId != "youtube" && currentActiveAppId != "iptv"

    fun switchWebApp(app: WebAppItem, embedded: Boolean = false, startUrl: String? = null) {
        cancelSystemVoiceRequest()
        if (currentActiveAppId != app.id) CarMediaManager.cancelPendingSteeringNext()
        if (currentActiveAppId != app.id) browserNeedsHistoryReset = app.id == "web"
        val currentUrl = web.url ?: ""
        val isYouTubeApp = (app.id == "youtube" || app.url.contains("youtube.com") || app.url.contains("youtu.be"))
        val isAlreadyLoaded = if (isYouTubeApp) {
            currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be")
        } else {
            (currentActiveAppId == app.id) && currentUrl.isNotEmpty() && currentUrl != "about:blank"
        }
        currentActiveAppId = app.id
        WebAppManager.setActiveAppId(context, app.id)
        CarMediaManager.activeAppId = app.id
        CarMediaManager.lastEmbeddedApp = app
        CarMediaManager.isEmbeddedAppShowing = false

        val isDesktop = WebAppManager.isAppDesktop(context, app.id)
        if (app.id == "tiktok" || app.url.contains("tiktok.com")) {
            web.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
            web.settings.domStorageEnabled = true
            web.settings.databaseEnabled = true
            web.settings.mediaPlaybackRequiresUserGesture = false
            try {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            } catch (e: Exception) {}
        } else if (isDesktop) {
            web.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        } else {
            val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(context)
            web.settings.userAgentString = defaultUa.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.isFocusable = true
        web.isFocusableInTouchMode = true

        applyWebScaleForUrl(app.url)

        val homeUrl = startUrl ?: if (app.id == "youtube") {
            if (isDesktop) "https://www.youtube.com" else "https://m.youtube.com"
        } else {
            app.url
        }
        if (!isAlreadyLoaded || startUrl != null) {
            web.loadUrl(homeUrl)
        }

        // Dashboard's showEmbeddedApp is a placeholder, not a visible browser.
        showWebFullscreen(app)

        rebuildTopToolbar()
        rebuildAppGrid()
        dashboardView?.refreshAppsList()
        resetAutoHideTimer()
    }

    val isShowingDashboard: Boolean
        get() = isDashboardShowing

    fun showWebFullscreen(app: WebAppItem? = null) {
        isDashboardShowing = false
        hudOverlay?.let { if (!it.isHudLocked()) it.toggleLock() }
        CarMediaManager.isWebShowingFullscreen = true
        CarMediaManager.isEmbeddedAppShowing = false
        try {
            if (web.parent == null) {
                root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } catch (e: Exception) {}
        
        // Disable dashboard mode so YouTube script knows it's fullscreen
        web.evaluateJavascript("window.isDashboardMode = false;", null)
        
        applyWebScaleForUrl(app?.url ?: web.url)
        YouTubePlayerHelper.applyTheme(web, isDayMode())
        dashboardView?.detachEmbeddedWeb()
        dashboardView?.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.alpha = 1f
        web.bringToFront()
        topToolbarContainer?.visibility = View.VISIBLE
        rebuildSidebar()
        rebuildTopToolbar()
        sidebarContainer?.visibility = View.VISIBLE
        if (isHudOverlayEnabled()) {
            if (hudOverlay == null) setupHudOverlay()
            hudOverlay?.visibility = View.VISIBLE
            hudOverlay?.let { root.bringChildToFront(it) }
        } else {
            hudOverlay?.visibility = View.GONE
        }
        showBars()
        resetAutoHideTimer()

        // Restore dimensions and force rendering resume when entering fullscreen
        try { 
            web.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            updateBrowserViewport()
            web.requestLayout()
            root.post {
                try {
                    web.onResume() 
                    web.evaluateJavascript(
                        """
                        (function() {
                            if (window.restoreVideoAspectRatio) window.restoreVideoAspectRatio();
                            if (window.__iptvUserPaused) return;
                            var v = document.querySelector('video');
                            if (v && v.paused) { try { if (v.muted) v.muted = false; v.play(); } catch(e){} }
                            var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (p && typeof p.playVideo === 'function' && p.getPlayerState && p.getPlayerState() !== 1) { try { p.playVideo(); } catch(e){} }
                        })();
                        """.trimIndent(), null
                    )
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    fun showDashboard() {
        cancelSystemVoiceRequest()
        isDashboardShowing = true
        autoHideHandler.removeCallbacks(hideBarsRunnable)
        sidebarContainer?.animate()?.cancel()
        topToolbarContainer?.animate()?.cancel()
        CarMediaManager.isWebShowingFullscreen = false
        val isDay = isDayMode()
        dashboardView?.applyDayNightMode(isDay)
        dashboardView?.updateWallpaper()
        
        // Ensure web stays attached at index 0 so background playback never pauses!
        try {
            if (web.parent == null) {
                root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } catch (e: Exception) {}

        // Keep web timers and audio playback running smoothly in background under dashboard
        try {
            web.onResume()
            web.resumeTimers()
        } catch (e: Exception) {}

        topToolbarContainer?.visibility = View.GONE
        sidebarContainer?.visibility = View.GONE
        dashboardView?.visibility = View.VISIBLE
        dashboardView?.bringToFront()
        
        if (isHudOverlayEnabled()) {
            if (hudOverlay == null) setupHudOverlay()
            hudOverlay?.visibility = View.VISIBLE
            hudOverlay?.let { root.bringChildToFront(it) }
        } else {
            hudOverlay?.visibility = View.GONE
        }
    }

    fun showWeb() {
        showWebFullscreen()
    }

    fun toggleDayNightMode() {
        val curMode = prefs.getString(SettingsActivity.KEY_THEME_MODE, SettingsActivity.THEME_AUTO) ?: SettingsActivity.THEME_AUTO
        val nextMode = when (curMode) {
            SettingsActivity.THEME_AUTO -> SettingsActivity.THEME_DAY
            SettingsActivity.THEME_DAY -> SettingsActivity.THEME_NIGHT
            SettingsActivity.THEME_NIGHT -> SettingsActivity.THEME_AUTO
            else -> SettingsActivity.THEME_AUTO
        }
        prefs.edit().putString(SettingsActivity.KEY_THEME_MODE, nextMode).apply()
        val isDay = isDayMode()
        prefs.edit().putBoolean("carhud_day_mode", isDay).apply()
        val modeText = when (nextMode) {
            SettingsActivity.THEME_AUTO -> "🚗 Giao diện Tự động (Cảm biến xe)"
            SettingsActivity.THEME_DAY -> "☀️ Giao diện Ban ngày (Sáng)"
            else -> "🌙 Giao diện Ban đêm (Tối)"
        }
        updateVoiceState(VoiceSearchManager.State.SUCCESS, modeText)
        applyCurrentTheme(isDay)
    }

    fun applyUniversalWebTheme(view: WebView?, isDay: Boolean) {
        YouTubePlayerHelper.applyUniversalWebTheme(view, isDay)
    }

    fun applyCurrentTheme(isDay: Boolean = isDayMode()) {
        dashboardView?.applyDayNightMode(isDay)
        rebuildSidebar()
        rebuildTopToolbar()
        YouTubePlayerHelper.applyUniversalWebTheme(web, isDay)
        if (currentActiveAppId == "iptv") {
            try {
                web.evaluateJavascript("if (typeof window.setIptvTheme === 'function') window.setIptvTheme(${if (isDay) "true" else "false"});", null)
            } catch (_: Throwable) {}
        }
    }

    fun onCarConfigurationChanged(newConfig: android.content.res.Configuration? = null) {
        val mode = prefs.getString(SettingsActivity.KEY_THEME_MODE, SettingsActivity.THEME_AUTO) ?: SettingsActivity.THEME_AUTO
        if (mode == SettingsActivity.THEME_AUTO) {
            val isDay = if (newConfig != null) {
                val isNight = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                !isNight
            } else {
                isDayMode()
            }
            prefs.edit().putBoolean("carhud_day_mode", isDay).apply()
            applyCurrentTheme(isDay)
        }
    }

    private var aspectRatioOverlay: View? = null

    fun cycleVideoAspectRatio() {
        val currentMode = prefs.getString(if (web.url?.contains("iptv_player.html") == true) IptvAspectRatio.PREF else "car_video_aspect_mode", "fill") ?: "fill"
        val nextIdx = (ASPECT_MODES.indexOf(currentMode) + 1) % ASPECT_MODES.size
        val nextMode = ASPECT_MODES[nextIdx]
        applyVideoAspectRatio(nextMode)
    }

    fun applyVideoAspectRatio(mode: String) {
        val aspectPref = if (web.url?.contains("iptv_player.html") == true) IptvAspectRatio.PREF else "car_video_aspect_mode"
        prefs.edit().putString(aspectPref, mode).apply()

        if (isDashboardShowing) {
            showWebFullscreen()
        }

        val targetWeb = (if (isDashboardShowing && dashboardView?.isEmbeddedAppShowing() == true) dashboardView?.currentEmbeddedWeb else null) ?: web

        // 1. Try IPTV JS API
        targetWeb.evaluateJavascript("""
            (function() {
                if (typeof window.setVideoAspectRatio === 'function') {
                    window.setVideoAspectRatio('$mode');
                    return true;
                } else if (typeof window.setFitMode === 'function') {
                    window.setFitMode('$mode');
                    return true;
                }
                return false;
            })()
        """.trimIndent()) { result ->
            if (result != "true") {
                // Apply YouTube / HTML5 video scaling
                YouTubePlayerHelper.setVideoAspectRatio(targetWeb, mode)
            }
        }

        val label = ASPECT_LABELS[mode] ?: mode
        updateVoiceState(VoiceSearchManager.State.SUCCESS, "📐 Tỷ lệ: $label")
    }

    fun showAspectRatioDialog() {
        aspectRatioOverlay?.let { root.removeView(it) }

        val isDay = isDayMode()
        val currentMode = prefs.getString(if (web.url?.contains("iptv_player.html") == true) IptvAspectRatio.PREF else "car_video_aspect_mode", "fill") ?: "fill"

        val overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            isClickable = true
            setOnClickListener {
                root.removeView(this)
                aspectRatioOverlay = null
            }
        }

        val dialogCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = rounded(
                if (isDay) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A"),
                16f,
                if (isDay) Color.parseColor("#0284C7") else Color.parseColor("#00E5FF"),
                2
            )
            isClickable = true

            val title = TextView(context).apply {
                text = "📐 CHỌN TỶ LỆ KHUNG HÌNH (YOUTUBE & IPTV)"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isDay) Color.parseColor("#0F172A") else Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(title)

            val sub = TextView(context).apply {
                text = "Chạm để áp dụng ngay cho video đang phát"
                textSize = 11f
                setTextColor(if (isDay) Color.parseColor("#64748B") else Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(14))
            }
            addView(sub)

            val optionsRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (mode in ASPECT_MODES) {
                val isSelected = (mode == currentMode)
                val label = when(mode) {
                    "split" -> "📋 Hiện kênh"
                    "fill" -> "↔ Tràn viền"
                    "contain" -> "16:9 Chuẩn"
                    "cover" -> "⤢ Phóng to"
                    "4:3" -> "4:3 Cổ điển"
                    "21:9" -> "21:9 Siêu rộng"
                    else -> mode
                }

                val btn = TextView(context).apply {
                    text = label
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isSelected) Color.WHITE else (if (isDay) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")))
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = rounded(
                        if (isSelected) Color.parseColor("#0284C7")
                        else (if (isDay) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")),
                        10f,
                        if (isSelected) Color.parseColor("#00E5FF") else 0,
                        if (isSelected) 2 else 0
                    )
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(5)
                        marginEnd = dp(5)
                    }
                    setOnClickListener {
                        applyVideoAspectRatio(mode)
                        root.removeView(overlay)
                        aspectRatioOverlay = null
                    }
                }
                optionsRow.addView(btn)
            }
            addView(optionsRow)
        }

        val cardParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        overlay.addView(dialogCard, cardParams)

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        aspectRatioOverlay = overlay
    }

    private fun buildAppGridOverlay(): FrameLayout {
        return FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#F5060A10"))
            isClickable = true

            val rootPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(10), dp(18), dp(10))
            }

            // Modern Automotive Header
            val header = RelativeLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }

                val titleIconCol = LinearLayout(context).apply {
                    id = View.generateViewId()
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.ALIGN_PARENT_START)
                        addRule(RelativeLayout.CENTER_VERTICAL)
                    }

                    val appIcon = ImageView(context).apply {
                        setImageResource(R.drawable.ic_app_grid)
                        setColorFilter(Color.parseColor("#00E5FF"))
                        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                            rightMargin = dp(10)
                        }
                    }
                    addView(appIcon)

                    val textCol = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        val title = TextView(context).apply {
                            text = "TRUNG TÂM ỨNG DỤNG XE HƠI"
                            textSize = 14.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                        }
                        addView(title)

                        val sub = TextView(context).apply {
                            text = "Chạm để khởi chạy ứng dụng • Giữ lâu để tùy chỉnh Desktop"
                            textSize = 10.5f
                            setTextColor(Color.parseColor("#94A3B8"))
                        }
                        addView(sub)
                    }
                    addView(textCol)
                }
                addView(titleIconCol)

                val actionButtonsGroup = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        addRule(RelativeLayout.CENTER_IN_PARENT)
                    }

                    val optRamBtn = TextView(context).apply {
                        text = "⚡ Tối ưu RAM"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#FBBF24"))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = rounded(Color.parseColor("#26F59E0B"), 10f, Color.parseColor("#F59E0B"), 1)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(8)
                        }
                        enableTouchBounce()
                        setOnClickListener {
                            onUserInteraction()
                            try {
                                web.stopLoading()
                                web.clearCache(false)
                                web.freeMemory()
                            } catch (e: Exception) {}
                            System.gc()
                            updateVoiceState(VoiceSearchManager.State.SUCCESS, "Đã dọn dẹp RAM & tối ưu hiệu năng!")
                        }
                    }
                    addView(optRamBtn)

                    val addBtn = TextView(context).apply {
                        text = "➕ Thêm Web"
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#34D399"))
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        background = rounded(Color.parseColor("#2610B981"), 10f, Color.parseColor("#10B981"), 1)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(8)
                        }
                        enableTouchBounce()
                        setOnClickListener {
                            onUserInteraction()
                            hideAppGridOverlay()
                            showAddAppDialog()
                        }
                    }
                    addView(addBtn)

                    val closeBtn = TextView(context).apply {
                        text = "✕ Đóng"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                        setPadding(dp(14), dp(6), dp(14), dp(6))
                        background = rounded(Color.parseColor("#881337"), 10f, Color.parseColor("#E11D48"), 1)
                        enableTouchBounce()
                        setOnClickListener {
                            hideAppGridOverlay()
                        }
                    }
                    addView(closeBtn)
                }
                addView(actionButtonsGroup)
            }
            rootPanel.addView(header)

            val scrollContainer = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            }

            val gridContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            appGridPanel = gridContent
            scrollContainer.addView(gridContent)
            rootPanel.addView(scrollContainer)

            addView(rootPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun rebuildAppGrid() {
        val panel = appGridPanel ?: return
        panel.removeAllViews()

        val apps = WebAppManager.getAllApps(context)
        val itemsPerRow = if (isPortrait) 3 else 5

        var currentRow: LinearLayout? = null

        for (i in apps.indices) {
            val app = apps[i]
            val isEnabled = WebAppManager.isAppEnabled(context, app.id)
            val isDesktop = WebAppManager.isAppDesktop(context, app.id)

            if (i % itemsPerRow == 0) {
                currentRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    }
                }
                panel.addView(currentRow)
            }

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(114), dp(112)).apply {
                    leftMargin = dp(6)
                    rightMargin = dp(6)
                }
                alpha = if (isEnabled) 1.0f else 0.5f
                background = rounded(Color.parseColor("#131D2A"), 14f, Color.parseColor("#223348"), 1)

                val iconBox = FrameLayout(context).apply {
                    val boxColor = try { Color.parseColor(app.colorHex) } catch(e: Exception) { Color.parseColor("#1E88E5") }
                    background = rounded(boxColor, 13f, Color.parseColor("#33FFFFFF"), 1)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        bottomMargin = dp(5)
                    }

                    val icon = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        if (!app.iconPath.isNullOrBlank() && java.io.File(app.iconPath).exists()) {
                            val bmp = android.graphics.BitmapFactory.decodeFile(app.iconPath)
                            if (bmp != null) {
                                setImageBitmap(bmp)
                                setPadding(dp(4), dp(4), dp(4), dp(4))
                            } else {
                                setImageResource(app.iconRes)
                                setPadding(dp(8), dp(8), dp(8), dp(8))
                            }
                        } else {
                            setImageResource(app.iconRes)
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        }
                    }
                    addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                    if (isDesktop) {
                        val dtBadge = TextView(context).apply {
                            text = "💻"
                            textSize = 8f
                            gravity = Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16)).apply {
                                gravity = Gravity.TOP or Gravity.END
                            }
                        }
                        addView(dtBadge)
                    }
                }
                addView(iconBox)

                val label = TextView(context).apply {
                    text = app.name
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (isEnabled) Color.WHITE else Color.parseColor("#90A4AE"))
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(label)

                val catTag = TextView(context).apply {
                    text = WebAppManager.getCategoryName(app.id)
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#38BDF8"))
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(2)
                    }
                }
                addView(catTag)

                enableTouchBounce()
                setOnClickListener {
                    onUserInteraction()
                    if (!isEnabled) {
                        WebAppManager.setAppEnabled(context, app.id, true)
                    }
                    hideAppGridOverlay()
                    switchWebApp(app, embedded = false)
                }

                setOnLongClickListener {
                    onUserInteraction()
                    showDisableAppDialog(app)
                    true
                }
            }
            currentRow?.addView(card)
        }

        // Add "+ Thêm App" card
        val addCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(114), dp(112)).apply {
                leftMargin = dp(6)
                rightMargin = dp(6)
            }
            background = rounded(Color.parseColor("#0C1F17"), 14f, Color.parseColor("#10B981"), 1)

            val iconBox = FrameLayout(context).apply {
                background = rounded(Color.parseColor("#133628"), 13f, Color.parseColor("#34D399"), 1)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(5)
                }

                val icon = ImageView(context).apply {
                    setImageResource(R.drawable.ic_app_add)
                    setColorFilter(Color.parseColor("#34D399"))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                }
                addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            addView(iconBox)

            val label = TextView(context).apply {
                text = "+ Thêm Web"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#34D399"))
                gravity = Gravity.CENTER
                maxLines = 1
            }
            addView(label)

            val sub = TextView(context).apply {
                text = "Nhập URL"
                textSize = 9.5f
                setTextColor(Color.parseColor("#6EE7B7"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2)
                }
            }
            addView(sub)

            enableTouchBounce()
            setOnClickListener {
                hideAppGridOverlay()
                showAddAppDialog()
            }
        }
        if (apps.size % itemsPerRow == 0) {
            currentRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            panel.addView(currentRow)
        }
        currentRow?.addView(addCard)
    }

    fun toggleAppGridOverlay() {
        if (appGridOverlay?.visibility == View.VISIBLE) {
            hideAppGridOverlay()
        } else {
            showAppGridOverlay()
        }
    }

    fun showAppGridOverlay() {
        val curPos = prefs.getString(SettingsActivity.KEY_WEBAPP_TOOLBAR_POSITION, "left") ?: "left"
        val curScale = prefs.getInt(SettingsActivity.KEY_DOCK_SCALE, 100).coerceIn(50, 150)
        appGridPosToggleBtn?.text = if (curPos == "left") "📍 Vị trí: Trái" else "📍 Vị trí: Trên"
        appGridSizeBtn?.text = "📏 Cỡ: $curScale%"
        hudOverlay?.visibility = View.GONE
        rebuildAppGrid()
        appGridOverlay?.elevation = dp(180).toFloat()
        appGridOverlay?.bringToFront()
        appGridOverlay?.visibility = View.VISIBLE
        appGridOverlay?.alpha = 0f
        appGridOverlay?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    fun hideAppGridOverlay() {
        // Remove the touch-blocking launcher before loading/rebuilding the app.
        appGridOverlay?.animate()?.cancel()
        appGridOverlay?.visibility = View.GONE
        appGridOverlay?.alpha = 1f
        hudOverlay?.visibility = if (isHudOverlayEnabled()) View.VISIBLE else View.GONE
    }

    private fun buildAddAppOverlay(): FrameLayout {
        return FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#D9000000"))
            isClickable = true

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = rounded(Color.parseColor("#101C28"), 16f, Color.parseColor("#00E5FF"), 2)
            }

            val title = TextView(context).apply {
                text = "➕ Thêm Ứng Dụng Web Mới"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            }
            card.addView(title)

            val iconPreviewRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }

                val previewBox = FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        rightMargin = dp(10)
                    }
                    background = rounded(Color.parseColor("#1B2A3A"), 12f, Color.parseColor("#00E5FF"), 1)
                    val img = ImageView(context).apply {
                        addAppIconPreview = this
                        setImageResource(R.drawable.ic_app_web)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                    }
                    addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
                addView(previewBox)

                val iconHint = TextView(context).apply {
                    text = "🌐 Tự động lấy Icon chất lượng cao từ tên miền URL"
                    textSize = 11f
                    setTextColor(Color.parseColor("#78909C"))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(iconHint)
            }
            card.addView(iconPreviewRow)

            addAppNameInput = EditText(context).apply {
                hint = "Tên ứng dụng (VD: TV360, Facebook, VTV...)"
                setHintTextColor(Color.parseColor("#607D8B"))
                setTextColor(Color.WHITE)
                textSize = 13f
                background = rounded(Color.parseColor("#182A3A"), 10f, Color.parseColor("#37474F"), 1)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            card.addView(addAppNameInput)

            addAppUrlInput = EditText(context).apply {
                hint = "Địa chỉ URL (VD: tv360.vn, facebook.com)"
                setHintTextColor(Color.parseColor("#607D8B"))
                setTextColor(Color.WHITE)
                textSize = 13f
                background = rounded(Color.parseColor("#182A3A"), 10f, Color.parseColor("#37474F"), 1)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }

                addTextChangedListener(object : android.text.TextWatcher {
                    private var fetchTask: Runnable? = null
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        fetchTask?.let { mainHandler.removeCallbacks(it) }
                        val raw = s?.toString()?.trim() ?: ""
                        if (raw.length > 3) {
                            val r = Runnable {
                                val domain = WebIconFetcher.extractDomain(raw)
                                if (domain.contains(".")) {
                                    if (addAppNameInput?.text.isNullOrBlank()) {
                                        addAppNameInput?.setText(WebIconFetcher.extractPrettyName(domain))
                                    }
                                    WebIconFetcher.fetchFaviconAsync(domain) { bmp ->
                                        if (bmp != null) {
                                            downloadedFaviconBitmap = bmp
                                            addAppIconPreview?.setImageBitmap(bmp)
                                            addAppIconPreview?.setPadding(dp(4), dp(4), dp(4), dp(4))
                                        }
                                    }
                                }
                            }
                            fetchTask = r
                            mainHandler.postDelayed(r, 600)
                        }
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            card.addView(addAppUrlInput)

            addAppDesktopCheck = CheckBox(context).apply {
                text = "Chế độ máy tính (Desktop User-Agent)"
                setTextColor(Color.parseColor("#B0BEC5"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            }
            card.addView(addAppDesktopCheck)

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT)

                val cancelBtn = TextView(context).apply {
                    text = "Hủy"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    background = rounded(Color.parseColor("#37474F"), 10f, 0, 0)
                    setOnClickListener {
                        hideAddAppDialog()
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = dp(8)
                    }
                }
                addView(cancelBtn)

                val saveBtn = TextView(context).apply {
                    text = "Lưu & Mở"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    background = rounded(Color.parseColor("#1976D2"), 10f, Color.parseColor("#00E5FF"), 1)
                    setOnClickListener {
                        val name = addAppNameInput?.text?.toString()?.trim() ?: ""
                        val url = addAppUrlInput?.text?.toString()?.trim() ?: ""
                        if (url.isNotEmpty()) {
                            val isDesktop = addAppDesktopCheck?.isChecked == true
                            val customId = "custom_" + System.currentTimeMillis()
                            var savedIconPath: String? = null
                            downloadedFaviconBitmap?.let { bmp ->
                                savedIconPath = WebIconFetcher.saveIconToDisk(context, customId, bmp)
                            }
                            val newApp = WebAppManager.addCustomApp(context, name, url, isDesktop, iconPath = savedIconPath)
                            hideAddAppDialog()
                            switchWebApp(newApp)
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(8)
                    }
                }
                addView(saveBtn)
            }
            card.addView(btnRow)

            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            })
        }
    }

    fun showAddAppDialog() {
        downloadedFaviconBitmap = null
        addAppIconPreview?.setImageResource(R.drawable.ic_app_web)
        addAppIconPreview?.setPadding(dp(8), dp(8), dp(8), dp(8))
        addAppNameInput?.setText("")
        addAppUrlInput?.setText("")
        addAppDesktopCheck?.isChecked = false
        addAppOverlay?.bringToFront()
        addAppOverlay?.visibility = View.VISIBLE
        addAppOverlay?.alpha = 0f
        addAppOverlay?.animate()?.alpha(1f)?.setDuration(200)?.start()
    }

    fun hideAddAppDialog() {
        addAppOverlay?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            addAppOverlay?.visibility = View.GONE
        }?.start()
    }

    private fun createBarIcon(drawableRes: Int, sizeDp: Int, onClick: () -> Unit): ImageView {
        val isDay = isDayMode()
        return ImageView(context).apply {
            setImageResource(drawableRes)
            if (isDay) {
                if (drawableRes != R.drawable.ic_mode_night && drawableRes != R.drawable.ic_mode_day) {
                    setColorFilter(Color.parseColor("#1E293B"))
                } else {
                    clearColorFilter()
                }
            } else {
                clearColorFilter()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (sizeDp * 0.16f).roundToInt().coerceIn(6, 14)
            setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
            enableTouchBounce()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    private fun createDivider(sizeDp: Int, isHorizontal: Boolean): View {
        val isDay = isDayMode()
        val span = (sizeDp * 0.55f).roundToInt()
        return View(context).apply {
            if (isHorizontal) {
                layoutParams = LinearLayout.LayoutParams(dp(1), dp(span)).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
            } else {
                layoutParams = LinearLayout.LayoutParams(dp(span), dp(1)).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
            }
            setBackgroundColor(if (isDay) Color.parseColor("#CBD5E1") else Color.parseColor("#33FFFFFF"))
        }
    }

    private fun executeSearch(query: String, broadcast: Boolean = true) {
        if (searchOverlayMode == "address" && isBrowserApp()) {
            navigateBrowser(query)
            return
        }
        if (searchOverlayMode == "web" && isBrowserApp()) {
            submitWebKeyboardText(query)
            return
        }
        val q = query.trim()
        if (q.isEmpty()) return

        if (currentActiveAppId != "youtube") {
            val ytApp = WebAppManager.getAllApps(context).find { it.id == "youtube" } ?: WebAppManager.DEFAULT_APPS.first()
            switchWebApp(ytApp, embedded = isDashboardShowing)
        }
        if (isDashboardShowing) {
            showWebFullscreen()
        }
        YouTubePlayerHelper.search(web, q)
        if (broadcast) CarMediaManager.submitSearchQuery(q)
        hideSearchOverlay()
    }

    private fun submitWebKeyboardText(value: String, onComplete: ((Boolean) -> Unit)? = null) {
        if (webInputSubmissionPending) { onComplete?.invoke(false); return }
        webInputSubmissionPending = true
        val quoted = org.json.JSONObject.quote(value)
        val js = """
            (function() {
                var el = document.querySelector('[data-carhud-input-target="1"]');
                if (!el) return 'NO_TARGET';
                try {
                    if (el.isContentEditable) {
                        el.textContent = $quoted;
                    } else {
                        var proto = Object.getPrototypeOf(el);
                        var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                        if (desc && desc.set) desc.set.call(el, $quoted); else el.value = $quoted;
                    }
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    el.focus();
                    var form = el.form || (el.closest ? el.closest('form') : null);
                    // Google uses textarea[name=q]; many sites use a plain text field.
                    // Only submit search fields, leaving login and other forms editable.
                    var searchName = /^(q|query|search|search_query|keyword|s)$/i.test(el.name || '');
                    var searchForm = form && (form.getAttribute('role') === 'search' || /\/(search|tim-kiem)(\/|\?|$)/i.test(form.getAttribute('action') || ''));
                    var isSearch = el.type === 'search' || el.getAttribute('role') === 'searchbox' ||
                        ((!el.type || /^(text|textarea)$/.test(el.type)) && (searchName || searchForm));
                    if (isSearch) {
                        var ev = new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true});
                        el.dispatchEvent(ev);
                        if (form && !ev.defaultPrevented) {
                            var submitter = form.querySelector('button[type="submit"]:not(:disabled), input[type="submit"]:not(:disabled), button:not([type]):not(:disabled)');
                            if (typeof form.requestSubmit === 'function') form.requestSubmit(submitter || undefined);
                            else if (submitter) submitter.click();
                            else HTMLFormElement.prototype.submit.call(form);
                        }
                        el.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
                    }
                    el.removeAttribute('data-carhud-input-target');
                    return 'OK';
                } catch (e) { return 'ERROR'; }
            })();
        """.trimIndent()

        web.evaluateJavascript(js) { result ->
            mainHandler.post {
                webInputSubmissionPending = false
                val noTarget = result == null || result.contains("NO_TARGET") || result.contains("ERROR")
                if (noTarget) {
                    searchInput.error = "Ô nhập đã thay đổi. Chạm lại ô trên trang để nhập tiếp."
                    onComplete?.invoke(false)
                    return@post
                }
                webKeyboardTargetPending = false
                hideSearchOverlay()
                onComplete?.invoke(true)
            }
        }
    }

    private fun buildSearchOverlay(): FrameLayout {
        return FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#E6080E17")) // 90% Dark cockpit glass
            isClickable = true
            isFocusable = true
            setOnClickListener { hideSearchOverlay() }

            val rootLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                setPadding(dp(12), dp(4), dp(12), dp(6))
                isClickable = true
            }

            // 1. Top Search Bar Row
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5)
                    rightMargin = dp(56) // Safe margin so top-right floating buttons / system cutouts never overlap
                }

                // Close button [✕]
                val closeBtn = TextView(context).apply {
                    text = "✕"
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(Color.parseColor("#212C3A"), 10f, Color.parseColor("#37474F"), 1)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    enableTouchBounce(0.92f)
                    setOnClickListener { hideSearchOverlay() }
                }
                addView(closeBtn)

                // Search Input Box
                val inputContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.parseColor("#142030"), 10f, Color.parseColor("#1976D2"), 2)
                    setPadding(dp(12), dp(2), dp(6), dp(2))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(8)
                        rightMargin = dp(8)
                    }

                    val searchIcon = TextView(context).apply {
                        text = "🔍"
                        textSize = 15f
                        setPadding(0, 0, dp(6), 0)
                    }
                    addView(searchIcon)

                    searchInput = EditText(context).apply {
                        hint = "Nhập bài hát, ca sĩ, thể loại..."
                        setHintTextColor(Color.parseColor("#607D8B"))
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        background = null
                        isSingleLine = true
                        imeOptions = EditorInfo.IME_ACTION_SEARCH
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        isFocusableInTouchMode = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            showSoftInputOnFocus = false
                        }
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                                executeSearch(text.toString())
                                true
                            } else false
                        }
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                                executeSearch(text.toString())
                                true
                            } else false
                        }
                    }
                    addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    val clearIcon = TextView(context).apply {
                        text = "✕"
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#90A4AE"))
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        enableTouchBounce(0.9f)
                        setOnClickListener {
                            searchInput.text?.clear()
                        }
                    }
                    addView(clearIcon)
                }
                addView(inputContainer)

                // Voice Search Button [🎙️ Nói]
                val voiceBtn = TextView(context).apply {
                    text = "🎙️ Nói"
                    textSize = 13.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(Color.parseColor("#C62828"), 10f, Color.parseColor("#EF5350"), 1)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    enableTouchBounce(0.92f)
                    setOnClickListener {
                        startVoiceSearch()
                    }
                }
                searchOverlayVoiceButton = voiceBtn
                addView(voiceBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = dp(8)
                })

                // Submit Search Button [🔍 TÌM]
                val submitBtn = TextView(context).apply {
                    text = "🔍 Tìm"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = rounded(Color.parseColor("#0288D1"), 10f, Color.parseColor("#00E5FF"), 1)
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    enableTouchBounce(0.92f)
                    setOnClickListener {
                        executeSearch(searchInput.text.toString())
                    }
                }
                searchOverlaySubmitButton = submitBtn
                addView(submitBtn)
            }
            rootLayout.addView(topRow)

            // 2. Quick Suggestion Chips Row
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(5)
                    rightMargin = dp(48)
                }
                val chipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(2), 0, dp(2))
                    val suggestions = arrayOf(
                        "Nhạc trẻ remix", "Nhạc chill TikTok", "Bolero trữ tình", 
                        "EDM Bass cực căng", "Nhạc Tết 2026", "Top Hits Việt Nam", 
                        "Karaoke có lời", "Lofi thư giãn", "Acoustic nhẹ nhàng", 
                        "Nhạc vàng hải ngoại", "Không lời thư giãn"
                    )
                    for (s in suggestions) {
                        val chip = TextView(context).apply {
                            text = s
                            textSize = 12.5f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(Color.parseColor("#80D8FF"))
                            background = rounded(Color.parseColor("#152232"), 8f, Color.parseColor("#263238"), 1)
                            setPadding(dp(12), dp(5), dp(12), dp(5))
                            enableTouchBounce(0.92f)
                            setOnClickListener {
                                searchInput.setText(s)
                                executeSearch(s)
                            }
                        }
                        addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            rightMargin = dp(6)
                        })
                    }
                }
                addView(chipRow)
            }
            searchSuggestionStrip = chipScroll
            rootLayout.addView(chipScroll)

            // 3. Compact & Sleek Automotive Touch Keyboard (5 rows, fixed ergonomic height)
            val keyboardContainer = CarKeyboardLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.parseColor("#0F1722"), 14f, Color.parseColor("#1E2D3E"), 1)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            }
            carKeyboard = keyboardContainer

            var isTelexEnabled = prefs.getBoolean("car_keyboard_telex", true)
            var telexBtnRef: TextView? = null

            val keyboardEditor = CarKeyboardEditor(searchInput)
            fun addChar(ch: String) {
                keyboardEditor.insert(ch, isTelexEnabled && searchOverlayMode != "address")
            }

            fun applyDirectTone(toneIdx: Int) {
                val editable = searchInput.text ?: return
                val selStart = (searchInput.selectionStart.takeIf { it >= 0 } ?: editable.length)
                    .coerceIn(0, editable.length)
                var wordStart = selStart
                while (wordStart > 0 && !editable[wordStart - 1].isWhitespace()) {
                    wordStart--
                }
                val word = editable.subSequence(wordStart, selStart).toString()
                val transformed = VietnameseTelexEngine.applyToneToWord(word, toneIdx)
                if (transformed != null) {
                    editable.replace(wordStart, selStart, transformed)
                    searchInput.setSelection((wordStart + transformed.length).coerceAtMost(editable.length))
                }
            }

            fun deleteChar() = keyboardEditor.delete()

            fun createKey(
                text: String,
                weight: Float,
                bg: Int = Color.parseColor("#15202D"),
                stroke: Int = Color.parseColor("#26384C"),
                textColor: Int = Color.WHITE,
                textSize: Float = 18f,
                onLongClick: (() -> Unit)? = null,
                onClick: () -> Unit
            ): TextView {
                return TextView(context).apply {
                    this.text = text
                    this.textSize = textSize
                    this.typeface = Typeface.DEFAULT_BOLD
                    this.setTextColor(textColor)
                    this.gravity = Gravity.CENTER
                    this.includeFontPadding = false
                    this.background = rounded(bg, 10f, stroke, 1)
                    isClickable = true
                    isFocusable = false
                    isSoundEffectsEnabled = false
                    setOnClickListener { onClick() }
                    if (onLongClick != null) {
                        setOnLongClickListener {
                            onLongClick()
                            true
                        }
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                        setMargins(dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f))
                    }
                }
            }

            var isNumberMode = false

            fun renderKeyboardRows() {
                keyboardContainer.removeAllViews()

                if (!isNumberMode) {
                    // Row 1: Q W E R T Y U I O P - Big ergonomic height 54dp
                    val row1 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        val letters = arrayOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
                        val altNums = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                        for (idx in letters.indices) {
                            val l = letters[idx]
                            val num = altNums[idx]
                            addView(createKey(l, 1f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.WHITE, textSize = 18.5f,
                                onLongClick = { addChar(num) }
                            ) {
                                addChar(l)
                            })
                        }
                    }
                    keyboardContainer.addView(row1)

                    // Row 2: A S D F G H J K L - Big ergonomic height 54dp
                    val row2 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f))
                        val letters = arrayOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
                        for (l in letters) {
                            addView(createKey(l, 1f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.WHITE, textSize = 18.5f) {
                                addChar(l)
                            })
                        }
                        addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f))
                    }
                    keyboardContainer.addView(row2)

                    // Row 3: [Đ] Z X C V B N M [⌫ XÓA] - Big ergonomic height 54dp
                    val row3 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        // Vietnamese Đ
                        addView(createKey("Đ", 1.05f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.parseColor("#38BDF8"), textSize = 18f) {
                            addChar("Đ")
                        })
                        val letters = arrayOf("Z", "X", "C", "V", "B", "N", "M")
                        for (l in letters) {
                            addView(createKey(l, 1f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.WHITE, textSize = 18.5f) {
                                addChar(l)
                            })
                        }
                        val backspaceKey = createKey("⌫ XÓA", 1.6f, bg = Color.parseColor("#881337"), stroke = Color.parseColor("#E11D48"), textColor = Color.WHITE, textSize = 14f) {
                            deleteChar()
                        }.apply {
                            setOnLongClickListener {
                                searchInput.text?.clear()
                                true
                            }
                        }
                        addView(backspaceKey)
                    }
                    keyboardContainer.addView(row3)
                } else {
                    // Number & Symbols Mode
                    // Row 1: 1 2 3 4 5 6 7 8 9 0 - Big height 54dp
                    val row1 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        val nums = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
                        for (n in nums) {
                            addView(createKey(n, 1f, bg = Color.parseColor("#162333"), stroke = Color.parseColor("#2D435E"), textColor = Color.parseColor("#38BDF8"), textSize = 18.5f) {
                                addChar(n)
                            })
                        }
                    }
                    keyboardContainer.addView(row1)

                    // Row 2: @ # $ % & - + ( ) / - Big height 54dp
                    val row2 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        val syms = arrayOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
                        for (s in syms) {
                            addView(createKey(s, 1f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.WHITE, textSize = 18f) {
                                addChar(s)
                            })
                        }
                    }
                    keyboardContainer.addView(row2)

                    // Row 3: * " ' : ; ! ? , . [⌫ XÓA] - Big height 54dp
                    val row3 = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                            bottomMargin = dp(3)
                        }
                        val syms = arrayOf("*", "\"", "'", ":", ";", "!", "?", ",", ".")
                        for (s in syms) {
                            addView(createKey(s, 1f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.WHITE, textSize = 18f) {
                                addChar(s)
                            })
                        }
                        val backspaceKey = createKey("⌫ XÓA", 1.6f, bg = Color.parseColor("#881337"), stroke = Color.parseColor("#E11D48"), textColor = Color.WHITE, textSize = 14f) {
                            deleteChar()
                        }.apply {
                            setOnLongClickListener {
                                searchInput.text?.clear()
                                true
                            }
                        }
                        addView(backspaceKey)
                    }
                    keyboardContainer.addView(row3)
                }

                // Row 4: Action row [?123 / ABC] [📱 Đ.Thoại] [🇻🇳 TELEX] [␣ DẤU CÁCH] [🔍 TÌM KIẾM] - Height 48dp
                val rowAction = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))

                    // ?123 / ABC mode toggle
                    val modeKey = createKey(if (isNumberMode) "🔤 ABC" else "?123", 1.25f,
                        bg = if (isNumberMode) Color.parseColor("#0C4A6E") else Color.parseColor("#1E293B"),
                        stroke = if (isNumberMode) Color.parseColor("#0284C7") else Color.parseColor("#334155"),
                        textColor = if (isNumberMode) Color.parseColor("#38BDF8") else Color.WHITE,
                        textSize = 13f) {
                        isNumberMode = !isNumberMode
                        renderKeyboardRows()
                    }
                    addView(modeKey)

                    // Phone Search Switcher
                    val phoneKey = createKey("📱 Đ.Thoại", 1.2f, bg = Color.parseColor("#1E3A8A"), stroke = Color.parseColor("#3B82F6"), textSize = 12f) {
                        CarMediaManager.launchPhoneSearchActivity(context, searchInput.text.toString())
                    }
                    addView(phoneKey)

                    // Telex Toggle Button
                    val telexBtn = createKey(if (isTelexEnabled) "🇻🇳 TELEX" else "🔤 EN", 1.25f,
                        bg = if (isTelexEnabled) Color.parseColor("#143247") else Color.parseColor("#1E293B"),
                        stroke = if (isTelexEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#475569"),
                        textColor = if (isTelexEnabled) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"),
                        textSize = 12f) {
                        isTelexEnabled = !isTelexEnabled
                        prefs.edit().putBoolean("car_keyboard_telex", isTelexEnabled).apply()
                        renderKeyboardRows()
                    }
                    telexBtnRef = telexBtn
                    addView(telexBtn)

                    // Space Bar
                    val spaceKey = createKey("␣ DẤU CÁCH", 3.4f, bg = Color.parseColor("#15202D"), stroke = Color.parseColor("#26384C"), textColor = Color.parseColor("#CBD5E1"), textSize = 13.5f) {
                        addChar(" ")
                    }
                    addView(spaceKey)

                    // Search Action Button
                    val actionText = when (searchOverlayMode) {
                        "web" -> "↵ NHẬP / MỞ"
                        "address" -> "TÌM KIẾM / ĐI →"
                        else -> "🔍 TÌM KIẾM"
                    }
                    val searchActionKey = createKey(actionText, 2.1f, bg = Color.parseColor("#0284C7"), stroke = Color.parseColor("#38BDF8"), textColor = Color.WHITE, textSize = 13.5f) {
                        executeSearch(searchInput.text.toString())
                    }
                    keyboardActionKey = searchActionKey
                    addView(searchActionKey)
                }
                keyboardContainer.addView(rowAction)
            }

            renderKeyboardRows()

            rootLayout.addView(keyboardContainer)
            addView(rootLayout)
        }
    }

    fun showSearchOverlay() {
        searchOverlayMode = "youtube"
        searchInput.error = null
        searchInput.imeOptions = EditorInfo.IME_ACTION_SEARCH
        webKeyboardTargetPending = false
        searchInput.hint = "Nhập bài hát, ca sĩ, thể loại..."
        searchOverlayVoiceButton?.visibility = View.VISIBLE
        searchOverlaySubmitButton?.apply { visibility = View.VISIBLE; text = "🔍 Tìm" }
        searchSuggestionStrip?.visibility = View.VISIBLE
        keyboardActionKey?.text = "🔍 TÌM KIẾM"
        showKeyboardOverlayInternal()
    }

    private fun showWebKeyboardOverlay(initialValue: String) {
        searchOverlayMode = "web"
        searchInput.error = null
        searchInput.imeOptions = EditorInfo.IME_ACTION_DONE
        webKeyboardTargetPending = true
        searchInput.hint = "Nhập nội dung vào ô trên trang..."
        searchInput.setText(initialValue)
        searchInput.setSelection(searchInput.text.length)
        searchOverlayVoiceButton?.visibility = View.GONE
        searchOverlaySubmitButton?.apply { visibility = View.VISIBLE; text = "↵ Nhập" }
        searchSuggestionStrip?.visibility = View.GONE
        keyboardActionKey?.text = "↵ NHẬP / MỞ"
        showKeyboardOverlayInternal()
    }

    private fun showBrowserAddressOverlay() {
        searchOverlayMode = "address"
        webKeyboardTargetPending = false
        // Address navigation must never reuse a marked input from the previous page.
        web.evaluateJavascript("document.querySelectorAll('[data-carhud-input-target]').forEach(function(n){n.removeAttribute('data-carhud-input-target');});", null)
        searchInput.error = null
        searchInput.imeOptions = EditorInfo.IME_ACTION_GO
        searchInput.hint = "Tìm kiếm Google hoặc nhập địa chỉ web"
        val address = web.url.orEmpty()
        searchInput.setText(if (address == BrowserNavigation.HOME || address == "https://www.google.com") "" else address)
        searchInput.selectAll()
        searchOverlayVoiceButton?.visibility = View.VISIBLE
        searchOverlaySubmitButton?.apply { visibility = View.VISIBLE; text = "Đi →" }
        searchSuggestionStrip?.visibility = View.GONE
        keyboardActionKey?.text = "TÌM KIẾM / ĐI →"
        showKeyboardOverlayInternal()
    }

    private fun openFocusedWebInput(touchX: Float, touchY: Float) {
        val xRatio = touchX / web.width.coerceAtLeast(1)
        val yRatio = touchY / web.height.coerceAtLeast(1)
        web.postDelayed({
            if (!isBrowserApp() || searchOverlay.visibility == View.VISIBLE) return@postDelayed
            // Focus can move after Google's click handler replaces its search field.
            // This native fallback also works on an already-loaded persistent WebView
            // whose newly registered JavascriptInterface is not exposed until reload.
            val js = """
                (function() {
                    var el = document.activeElement;
                    var hit = document.elementFromPoint($xRatio * innerWidth, $yRatio * innerHeight);
                    var hitInput = hit && hit.closest('input,textarea,[contenteditable="true"]');
                    if (hitInput) el = hitInput;
                    if (!el || !el.matches('input,textarea,[contenteditable="true"]') || el.disabled || el.readOnly) return null;
                    if (el.tagName === 'INPUT' && !/^(text|search|email|url|tel|number)${'$'}/.test(el.type)) return null;
                    document.querySelectorAll('[data-carhud-input-target]').forEach(function(n){n.removeAttribute('data-carhud-input-target');});
                    el.setAttribute('data-carhud-input-target','1');
                    return el.value !== undefined ? String(el.value) : String(el.textContent || '');
                })();
            """.trimIndent()
            web.evaluateJavascript(js) { result ->
                if (result != null && result != "null" && searchOverlay.visibility != View.VISIBLE) {
                    val value = try { org.json.JSONTokener(result).nextValue() as? String } catch (_: Exception) { null }
                    if (value != null) showWebKeyboardOverlay(value)
                }
            }
        }, 100)
    }

    private fun showKeyboardOverlayInternal() {
        if (prefs.getString("car_keyboard_input_mode", "native") != "thtv") {
            val mode = searchOverlayMode
            val page = web.url
            val input = CarInputSession(
                initialText = searchInput.text.toString(),
                hint = searchInput.hint.toString(),
                allowEmpty = mode == "web",
                onSubmit = { value, complete ->
                    // Keep the destination captured when opening the keyboard; a later
                    // Presentation or phone search must not turn web input into YouTube.
                    when (mode) {
                        "web" -> {
                            if (web.url != page) complete(false)
                            else submitWebKeyboardText(value, complete)
                        }
                        "address" -> {
                            val target = BrowserNavigation.targetFor(value)
                            if (target == null) complete(false)
                            else { web.loadUrl(target); complete(true) }
                        }
                        else -> {
                            executeSearch(value, broadcast = false)
                            complete(true)
                        }
                    }
                },
                onCancel = { value ->
                    searchInput.setText(value)
                    searchInput.setSelection(value.length)
                }
            )
            if (CarMediaManager.requestCarNativeSearch(input)) return
        }
        autoHideHandler.removeCallbacks(hideBarsRunnable)
        sidebarContainer?.animate()?.cancel()
        topToolbarContainer?.animate()?.cancel()
        searchOverlay.elevation = dp(200).toFloat()
        searchOverlay.visibility = View.VISIBLE
        searchOverlay.bringToFront()

        // Clean UI: hide all background chrome & widgets so screen is 100% focused on keyboard
        hudOverlay?.visibility = View.GONE
        sidebarContainer?.visibility = View.GONE
        topToolbarContainer?.visibility = View.GONE

        searchInput.requestFocus()
        try {
            val displayCtx = context.createDisplayContext(display)
            val imm = displayCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (_: Throwable) {}
        if (searchOverlayMode == "youtube") {
            CarMediaManager.requestSearch(searchInput.text.toString())
        }
    }

    fun hideSearchOverlay(notifyPhone: Boolean = true) {
        try {
            val displayCtx = context.createDisplayContext(display)
            val imm = displayCtx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        } catch (e: Exception) {}
        searchOverlay.visibility = View.GONE

        // Restore elements cleanly based on screen state
        if (isDashboardShowing) {
            sidebarContainer?.visibility = View.GONE
            topToolbarContainer?.visibility = View.GONE
        } else {
            sidebarContainer?.visibility = View.VISIBLE
            sidebarContainer?.let { root.bringChildToFront(it) }
            topToolbarContainer?.visibility = View.VISIBLE
            topToolbarContainer?.let { root.bringChildToFront(it) }
        }
        if (isHudOverlayEnabled()) {
            if (hudOverlay == null) setupHudOverlay()
            hudOverlay?.visibility = View.VISIBLE
            hudOverlay?.let { root.bringChildToFront(it) }
        } else {
            hudOverlay?.visibility = View.GONE
        }

        if (notifyPhone) {
            CarMediaManager.dismissSearch()
        }
    }

    private val voiceBannerHideHandler = Handler(Looper.getMainLooper())
    private val hideVoiceBannerRunnable = Runnable {
        statusBanner.animate().alpha(0f).setDuration(250).withEndAction {
            statusBanner.visibility = View.GONE
            statusBanner.alpha = 1f
        }.start()
        micBtn?.background = null
        if (isDayMode()) {
            micBtn?.setColorFilter(Color.parseColor("#1E293B"))
        } else {
            micBtn?.clearColorFilter()
        }
    }

    private fun scheduleVoiceBannerDismiss() {
        voiceBannerHideHandler.removeCallbacks(hideVoiceBannerRunnable)
        voiceBannerHideHandler.postDelayed(hideVoiceBannerRunnable, 5000L)
    }

    private fun updateVoiceState(state: VoiceSearchManager.State, text: String) {
        voiceBannerHideHandler.removeCallbacks(hideVoiceBannerRunnable)
        val isDay = isDayMode()
        when (state) {
            VoiceSearchManager.State.IDLE -> {
                statusBanner.visibility = View.GONE
                micBtn?.background = null
                if (isDay) micBtn?.setColorFilter(Color.parseColor("#1E293B")) else micBtn?.clearColorFilter()
            }
            VoiceSearchManager.State.LISTENING -> {
                statusBanner.text = "🎙️ Đang lắng nghe..."
                statusBanner.background = rounded(Color.parseColor("#EE102032"), 16f, Color.parseColor("#00E5FF"), 2)
                statusBanner.visibility = View.VISIBLE
                statusBanner.alpha = 1f
                statusBanner.bringToFront()
                micBtn?.background = null
                micBtn?.setColorFilter(Color.parseColor("#00E5FF"))
                scheduleVoiceBannerDismiss()
            }
            VoiceSearchManager.State.RECOGNIZING -> {
                statusBanner.text = "⏳ $text"
                statusBanner.background = rounded(Color.parseColor("#EE102032"), 16f, Color.parseColor("#F39C12"), 2)
                statusBanner.visibility = View.VISIBLE
                statusBanner.alpha = 1f
                statusBanner.bringToFront()
                micBtn?.background = null
                micBtn?.setColorFilter(Color.parseColor("#38BDF8"))
                scheduleVoiceBannerDismiss()
            }
            VoiceSearchManager.State.SUCCESS -> {
                statusBanner.text = "🔍 $text"
                statusBanner.background = rounded(Color.parseColor("#EE102032"), 16f, Color.parseColor("#2ECC71"), 2)
                statusBanner.visibility = View.VISIBLE
                statusBanner.alpha = 1f
                statusBanner.bringToFront()
                micBtn?.background = null
                micBtn?.setColorFilter(Color.parseColor("#10B981"))
                voiceBannerHideHandler.postDelayed(hideVoiceBannerRunnable, 2000L)
            }
            VoiceSearchManager.State.ERROR -> {
                statusBanner.text = "⚠️ $text"
                statusBanner.background = rounded(Color.parseColor("#EE102032"), 16f, Color.parseColor("#E74C3C"), 2)
                statusBanner.visibility = View.VISIBLE
                statusBanner.alpha = 1f
                statusBanner.bringToFront()
                micBtn?.background = null
                micBtn?.setColorFilter(Color.parseColor("#EF4444"))
                voiceBannerHideHandler.postDelayed(hideVoiceBannerRunnable, 2500L)
            }
        }
    }

    fun startVoiceSearch() {
        cancelSystemVoiceRequest()
        CarMediaManager.cancelPendingSteeringNext()
        hideSearchOverlay()
        YouTubePlayerHelper.setDuckingVolume(web, 0.0f)
        updateVoiceState(VoiceSearchManager.State.LISTENING, if (isBrowserApp() && !isDashboardShowing) "Đang lắng nghe... Nói từ khóa hoặc địa chỉ web" else "Đang lắng nghe... Hãy nói tên bài hát")
        voiceManager.startListening()
    }

    fun togglePlayPause() {
        CarMediaManager.togglePlayPause()
    }

    fun getWebView(): WebView = web

    fun dispatchTouch(x: Float, y: Float) {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) {
            carKeyboard?.let { keyboard ->
                val point = android.graphics.Rect(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
                root.offsetRectIntoDescendantCoords(keyboard, point)
                if (keyboard.clickAt(point.left.toFloat(), point.top.toFloat())) return
            }
            dispatchOverlayClick(searchOverlay, x, y)
            return
        }
        // Surface clicks have no physical Android touch stream. Route visible
        // native dialogs directly, before HUD movement or the underlying WebView.
        val overlay = listOfNotNull(addAppOverlay, appGridOverlay, if (::searchOverlay.isInitialized) searchOverlay else null)
            .firstOrNull { it.visibility == View.VISIBLE }
        if (overlay != null && dispatchOverlayClick(overlay, x, y)) return
        if (currentActiveAppId == "web" && !isDashboardShowing &&
            topToolbarContainer?.let { dispatchOverlayClick(it, x, y) } == true) return
        onUserInteraction()

        // 1. Check HUD interactions if HUD overlay is present
        val hud = hudOverlay
        if (hud != null && hud.visibility == View.VISIBLE) {
            // Priority 1: Direct hit on lock button toggles lock state (locked <-> unlocked)
            if (hud.hitTestLock(x, y)) {
                hud.toggleLock()
                return
            }

            // Priority 2: If HUD is unlocked:
            if (!hud.isHudLocked()) {
                // Tapping anywhere on the HUD overlay immediately locks it into place
                if (hud.isTouchOnOverlay(x, y)) {
                    hud.toggleLock()
                    return
                }

                // Tapping outside HUD moves HUD to that new position
                hud.moveTo(x, y, root.width, root.height)
                return
            }
        }

        val decor = window?.decorView ?: root
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, x, y, 0)
        decor.dispatchTouchEvent(down)
        decor.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun dispatchOverlayClick(overlay: View, x: Float, y: Float): Boolean {
        val rootPosition = IntArray(2)
        root.getLocationOnScreen(rootPosition)
        val screenX = rootPosition[0] + x.toInt()
        val screenY = rootPosition[1] + y.toInt()
        fun findTarget(view: View): View? {
            if (view.visibility != View.VISIBLE || !view.isEnabled) return null
            val rect = android.graphics.Rect()
            if (!view.getGlobalVisibleRect(rect) || !rect.contains(screenX, screenY)) return null
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) {
                    findTarget(view.getChildAt(i))?.let { return it }
                }
            }
            return if (view.isClickable) view else null
        }
        return findTarget(overlay)?.performClick() ?: false
    }

    fun dispatchScroll(dx: Float, dy: Float) {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) return
        onUserInteraction()
        val hud = hudOverlay
        if (hud != null && hud.visibility == View.VISIBLE && !hud.isHudLocked()) {
            hud.moveBy(-dx, -dy, root.width, root.height)
            return
        }
        val activeWeb = CarMediaManager.getActiveWebView() ?: web
        val scrollDelta = (dy * 2.0f).roundToInt()
        if (scrollDelta != 0) {
            val js = """
                (function() {
                    var dy = $scrollDelta;
                    if (window.scrollChannelList) {
                        window.scrollChannelList(dy);
                    } else {
                        window.scrollBy(0, dy);
                    }
                })();
            """.trimIndent()
            activeWeb.evaluateJavascript(js, null)
        }
    }

    fun dispatchFling(vx: Float, vy: Float) {
        if (::searchOverlay.isInitialized && searchOverlay.visibility == View.VISIBLE) return
        onUserInteraction()
        val hud = hudOverlay
        if (hud != null && hud.visibility == View.VISIBLE && !hud.isHudLocked()) {
            hud.moveBy(-vx * 0.1f, -vy * 0.1f, root.width, root.height)
            return
        }
        val activeWeb = CarMediaManager.getActiveWebView() ?: web
        val flingAmount = (-vy * 0.45f).roundToInt()
        if (flingAmount != 0) {
            val js = """
                (function() {
                    var dy = $flingAmount;
                    if (window.flingChannelList) {
                        window.flingChannelList(dy);
                    } else {
                        window.scrollBy({ top: dy, behavior: 'smooth' });
                    }
                })();
            """.trimIndent()
            activeWeb.evaluateJavascript(js, null)
        }
    }

    fun reload() {
        web.reload()
    }

    fun goBack() {
        cancelSystemVoiceRequest()
        if (addAppOverlay?.visibility == View.VISIBLE) {
            hideAddAppDialog()
            return
        }
        if (appGridOverlay?.visibility == View.VISIBLE) {
            hideAppGridOverlay()
            return
        }
        if (searchOverlay.visibility == View.VISIBLE) {
            hideSearchOverlay()
            return
        }
        if (currentActiveAppId == "web" && browserNeedsHistoryReset) return
        val targetWeb = if (isDashboardShowing && dashboardView?.isEmbeddedAppShowing() == true) {
            dashboardView?.currentEmbeddedWeb ?: web
        } else {
            web
        }
        if (targetWeb.canGoBack()) {
            targetWeb.goBack()
        }
    }

    fun goHome() {
        showDashboard()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            if (event.action == KeyEvent.ACTION_DOWN) CarMediaManager.handleSteeringNext(context, event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_SEARCH -> {
                    startVoiceSearch()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    CarMediaManager.playPrevious()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    CarMediaManager.togglePlayPause()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    fun openPhoneSettings() {
        try {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                context.startActivity(intent, options.toBundle())
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            try {
                val pi = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pi.send()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    fun detachWebForBackground(): WebView {
        (web.parent as? ViewGroup)?.removeView(web)
        // CRITICAL FIX: Attach to MainActivity's background window so Chromium doesn't kill media playback!
        CarMediaManager.mainActivityRoot?.let { mainRoot ->
            try {
                if (web.parent == null) {
                    mainRoot.addView(web, ViewGroup.LayoutParams(1, 1))
                }
            } catch (e: Exception) {}
        }
        web.onResume()
        web.resumeTimers()
        return web
    }

    fun reattachWebForForeground() {
        (web.parent as? ViewGroup)?.removeView(web)
        try {
            if (web.parent == null) {
                // Add it back at index 0 (behind sidebar and search overlay)
                root.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        } catch (e: Exception) {}
        web.onResume()
        web.resumeTimers()
    }

    override fun onStop() {
        autoHideHandler.removeCallbacks(hideBarsRunnable)
        // Keep the persistent WebView attached to the Presentation window while Android Auto
        // temporarily hides our surface (for example when switching to Maps/Waze). Detaching it
        // makes Chromium treat the page as background/off-screen and can suspend YouTube/HLS audio.
        try {
            val mediaWeb = web
            if (mediaWeb is BackgroundAudioWebView) mediaWeb.enableBackgroundAudio = true
            mediaWeb.visibility = View.VISIBLE
            mediaWeb.onResume()
            mediaWeb.resumeTimers()
            if (CarMediaManager.isPlaying || CarMediaManager.userWantsPlayback) {
                CarMediaManager.registerService(context)
                CarMediaManager.acquireWakeLock(context)
                CarMediaManager.ensureAudioFocus()
            }
        } catch (_: Exception) {}
        super.onStop()
    }


    override fun dismiss() {
        cancelSystemVoiceRequest()
        SystemVoiceModule.detach(systemVoiceReceiver)
        detachInputCallbacks()
        try {
            (web.parent as? ViewGroup)?.removeView(web)
        } catch(e: Exception) {}
        super.dismiss()
    }

    fun destroyWeb() {
        cancelSystemVoiceRequest()
        SystemVoiceModule.detach(systemVoiceReceiver)
        detachInputCallbacks()
        try {
            web.webChromeClient?.onHideCustomView()
            web.evaluateJavascript("try { document.exitFullscreen(); } catch(e) {}", null)
        } catch(e: Exception) {}
        CarMediaManager.unregisterCarWebView(web)
        web.stopLoading()
        web.destroy()
    }

    private var inputCallbacksDetached = false
    private fun detachInputCallbacks() {
        if (inputCallbacksDetached) return
        inputCallbacksDetached = true
        voiceManager.stop()
        if (CarMediaManager.activeVoiceManager === voiceManager) CarMediaManager.activeVoiceManager = null
        CarMediaManager.unregisterVoiceListener(voiceListener)
        CarMediaManager.unregisterSearchQueryListener(searchQueryListener)
        CarMediaManager.unregisterSearchDismissListener(searchDismissListener)
        CarMediaManager.unregisterSearchLiveTextListener(searchLiveTextListener)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        hudPrefs.unregisterOnSharedPreferenceChangeListener(hudPrefsListener)
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) {
                setStroke(dp(strokeDp), stroke)
            }
        }
    }

    private var carLockOverlay: View? = null

    private fun showCarActivationLock(parent: FrameLayout) {
        // Tạm thời bỏ phần kích hoạt bản quyền
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()
}
