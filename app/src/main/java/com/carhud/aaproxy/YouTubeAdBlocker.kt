package com.carhud.aaproxy

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Standalone YouTube ad blocker for Android WebView.
 *
 * Includes:
 * 1) Network-level blocking for known ad/telemetry endpoints.
 * 2) Player-level ad skipping for pre-roll / mid-roll / overlay ads.
 * 3) Optional SponsorBlock integration through [SponsorBlockManager].
 *
 * This class deliberately contains NO Android Auto UI, audio, theme,
 * fullscreen, search, media-session, or dashboard logic.
 */
object YouTubeAdBlocker {

    private const val BRIDGE_NAME = "CarHudAdBridge"

    private data class BridgeHolder(
        val bridge: SponsorBridge,
        val sponsorBlockEnabled: Boolean
    )

    /**
     * Weak keys so the helper does not keep a destroyed WebView alive.
     */
    private val bridges = Collections.synchronizedMap(
        WeakHashMap<WebView, BridgeHolder>()
    )

    /**
     * Call once after creating the YouTube WebView.
     *
     * It is safe to call again; the JS side is idempotent.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun install(
        view: WebView,
        enableSponsorBlock: Boolean = true
    ) {
        view.settings.javaScriptEnabled = true

        if (enableSponsorBlock) {
            val old = bridges[view]
            if (old == null || !old.sponsorBlockEnabled) {
                try {
                    view.removeJavascriptInterface(BRIDGE_NAME)
                } catch (_: Throwable) {
                }

                val bridge = SponsorBridge(view)
                view.addJavascriptInterface(bridge, BRIDGE_NAME)
                bridges[view] = BridgeHolder(bridge, true)
            }
        } else {
            try {
                view.removeJavascriptInterface(BRIDGE_NAME)
            } catch (_: Throwable) {
            }
            bridges.remove(view)
        }

        inject(view, enableSponsorBlock)
    }

    /**
     * Call from WebViewClient.onPageFinished().
     */
    fun onPageFinished(
        view: WebView,
        url: String? = view.url,
        enableSponsorBlock: Boolean = true
    ) {
        if (!isYouTubePage(url)) return
        install(view, enableSponsorBlock)
    }

    /**
     * Re-inject after SPA navigation if needed.
     */
    fun inject(
        view: WebView,
        enableSponsorBlock: Boolean = true
    ) {
        val url = view.url
        if (!isYouTubePage(url)) return

        val js = PLAYER_ADBLOCK_JS
            .replace("__ENABLE_SPONSOR_BLOCK__", if (enableSponsorBlock) "true" else "false")

        view.post {
            try {
                view.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Use from WebViewClient.shouldInterceptRequest().
     *
     * Example:
     *   YouTubeAdBlocker.shouldIntercept(request)?.let { return it }
     */
    fun shouldIntercept(request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        val uri = request.url ?: return null
        if (!isAdUrl(uri)) return null

        val origin = request.requestHeaders["Origin"]
            ?: request.requestHeaders["origin"]
            ?: "https://m.youtube.com"

        return createEmptyResponse(origin)
    }

    /**
     * String overload for projects that only have a URL string.
     */
    fun isAdUrl(url: String): Boolean {
        return try {
            isAdUrl(Uri.parse(url))
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Conservative request filtering:
     * - never blocks googlevideo/videoplayback/player/next media APIs
     * - only blocks known ad hosts or known YouTube ad telemetry paths
     */
    fun isAdUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return false

        val path = uri.path?.lowercase().orEmpty()
        val query = uri.encodedQuery?.lowercase().orEmpty()

        // Never block real media / player API traffic.
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return false
        if (path.contains("/videoplayback")) return false
        if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
        if (path.contains("/youtubei/v1/player") || path.contains("/youtubei/v1/next")) return false

        val blockedHost =
            host == "doubleclick.net" ||
            host.endsWith(".doubleclick.net") ||
            host == "googlesyndication.com" ||
            host.endsWith(".googlesyndication.com") ||
            host == "googleadservices.com" ||
            host.endsWith(".googleadservices.com") ||
            host == "ad.doubleclick.net" ||
            host == "static.doubleclick.net" ||
            host.startsWith("adservice.google.")

        if (blockedHost) return true

        val isYouTubeHost =
            host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtu.be"

        if (isYouTubeHost) {
            if (path.contains("/pagead/")) return true
            if (path.contains("/api/stats/ads")) return true
            if (path.contains("/ptracking")) return true
        }

        return query.contains("ad_type=") ||
            query.contains("adformat=") ||
            query.contains("ad_format=")
    }

    fun createEmptyResponse(origin: String? = null): WebResourceResponse {
        val safeOrigin = origin
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: "https://m.youtube.com"

        val headers = hashMapOf(
            "Access-Control-Allow-Origin" to safeOrigin,
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store"
        )

        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }

    /**
     * Optional cleanup before destroying a WebView.
     */
    fun uninstall(view: WebView) {
        bridges.remove(view)

        try {
            view.removeJavascriptInterface(BRIDGE_NAME)
        } catch (_: Throwable) {
        }

        view.post {
            try {
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            var s = window.__carhudAdBlock;
                            if (!s) return;
                            if (s.timer) clearInterval(s.timer);
                            if (s.playerObserver) s.playerObserver.disconnect();
                            if (s.video && s.timeHandler) {
                                try { s.video.removeEventListener('timeupdate', s.timeHandler, true); } catch(e) {}
                            }
                            window.__carhudAdBlock = null;
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun isYouTubePage(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val host = Uri.parse(url).host?.lowercase().orEmpty()
            host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtu.be"
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Dedicated bridge only for SponsorBlock.
     * No Activity / Presentation / Android Auto reference is kept.
     */
    private class SponsorBridge(webView: WebView) {
        private val webRef = WeakReference(webView)

        @JavascriptInterface
        fun fetchSponsorSegments(videoId: String) {
            SponsorBlockManager.fetchSegments(videoId) { json ->
                val view = webRef.get() ?: return@fetchSegments

                val safeVideoId = JSONObject.quote(videoId)
                val safeJson = JSONObject.quote(json)

                view.post {
                    try {
                        view.evaluateJavascript(
                            """
                            (function() {
                                try {
                                    if (window.CarHudSponsorBlock &&
                                        typeof window.CarHudSponsorBlock.onSegmentsLoaded === 'function') {
                                        window.CarHudSponsorBlock.onSegmentsLoaded($safeVideoId, $safeJson);
                                    }
                                } catch(e) {}
                            })();
                            """.trimIndent(),
                            null
                        )
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    /**
     * Lightweight player-only script.
     *
     * No search/theme/audio/fullscreen/dashboard logic is included here.
     */
    private val PLAYER_ADBLOCK_JS: String = """
(function() {
    try {
        var existing = window.__carhudAdBlock;
        if (existing && existing.version === 3) {
            existing.sponsorEnabled = __ENABLE_SPONSOR_BLOCK__;
            if (typeof existing.tick === 'function') existing.tick();
            return;
        }

        var state = {
            version: 3,
            sponsorEnabled: __ENABLE_SPONSOR_BLOCK__,
            timer: null,
            playerObserver: null,
            playerObserverTarget: null,
            video: null,
            timeHandler: null,
            inAd: false,
            adPrevMuted: null,
            adPrevRate: null,
            lastVideoId: ''
        };
        window.__carhudAdBlock = state;

        // Hide only ad UI; do not alter normal YouTube layout.
        var style = document.getElementById('carhud-adblock-style');
        if (!style) {
            style = document.createElement('style');
            style.id = 'carhud-adblock-style';
            (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = [
            'ytm-promoted-sparkles-web-renderer',
            'ytm-promoted-video-renderer',
            'ytm-companion-ad-renderer',
            'ytm-display-ad-renderer',
            'ytm-ad-slot-renderer',
            'ytm-statement-banner-renderer',
            'ytm-compact-promoted-item-renderer',
            '.ytp-ad-module',
            '.ytp-ad-overlay-container',
            '.ytp-ad-image-overlay',
            '.ytp-ad-overlay-slot',
            '.ytp-ad-message-container',
            '.video-ads',
            '.ytp-ad-player-overlay-layout',
            '.ytp-ad-image-overlay',
            '.ytp-ad-text-overlay',
            'ytd-banner-promo-renderer',
            'ytd-in-feed-ad-layout-renderer'
        ].join(',') + '{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;}';

        function getPlayer() {
            return document.getElementById('movie_player') ||
                   document.querySelector('.html5-video-player');
        }

        function getVideo() {
            return document.querySelector('#movie_player video, .html5-video-player video, ytm-player video') ||
                   document.querySelector('video');
        }

        function isShowingAd() {
            try {
                var p = getPlayer();
                if (!p) return false;

                if (p.classList &&
                    (p.classList.contains('ad-showing') ||
                     p.classList.contains('ad-interrupting'))) {
                    return true;
                }

                if (typeof p.getAdState === 'function') {
                    try {
                        if (p.getAdState() > 0) return true;
                    } catch(e) {}
                }

                if (typeof p.isAdPlaying === 'function') {
                    try {
                        if (p.isAdPlaying()) return true;
                    } catch(e) {}
                }
            } catch(e) {}
            return false;
        }

        function visible(el) {
            if (!el) return false;
            try {
                return el.offsetParent !== null ||
                       el.offsetWidth > 0 ||
                       el.offsetHeight > 0;
            } catch(e) {
                return false;
            }
        }

        function clickElement(el) {
            if (!el) return;
            try {
                el.click();
            } catch(e) {
                try {
                    el.dispatchEvent(new MouseEvent('click', {
                        bubbles: true,
                        cancelable: true,
                        view: window
                    }));
                } catch(ex) {}
            }
        }

        function captureAdVideoState(v) {
            if (!v || state.inAd) return;
            state.inAd = true;
            state.adPrevMuted = !!v.muted;
            state.adPrevRate = (isFinite(v.playbackRate) && v.playbackRate > 0)
                ? v.playbackRate : 1.0;
        }

        function restoreVideoState() {
            if (!state.inAd) return;

            var v = getVideo();
            if (v) {
                try {
                    if (state.adPrevMuted !== null) v.muted = state.adPrevMuted;
                } catch(e) {}
                try {
                    v.playbackRate =
                        (state.adPrevRate && isFinite(state.adPrevRate))
                            ? state.adPrevRate : 1.0;
                } catch(e) {}
            }

            state.inAd = false;
            state.adPrevMuted = null;
            state.adPrevRate = null;
        }

        function skipAd() {
            try {
                if (!isShowingAd()) {
                    restoreVideoState();
                    return;
                }

                var p = getPlayer();
                var v = getVideo();
                if (v) captureAdVideoState(v);

                // 1) YouTube player API, when exposed.
                if (p && typeof p.skipAd === 'function') {
                    try { p.skipAd(); } catch(e) {}
                }

                // 2) Genuine skip buttons.
                var skipSelectors = [
                    '.ytp-ad-skip-button-modern',
                    '.ytp-ad-skip-button',
                    '.ytp-skip-ad-button',
                    'button.ytp-ad-skip-button',
                    '.videoAdUiSkipButton',
                    '[class*="ytp-ad-skip"]'
                ];

                for (var i = 0; i < skipSelectors.length; i++) {
                    var btn = document.querySelector(skipSelectors[i]);
                    if (visible(btn)) {
                        clickElement(btn);
                        break;
                    }
                }

                // 3) Close overlay/banner ads.
                var closeSelectors = [
                    '.ytp-ad-overlay-close-button',
                    '.ytp-ad-overlay-close-container',
                    'button[aria-label*="Close ad"]',
                    'button[aria-label*="Đóng quảng cáo"]'
                ];

                for (var j = 0; j < closeSelectors.length; j++) {
                    var closeBtn = document.querySelector(closeSelectors[j]);
                    if (visible(closeBtn)) clickElement(closeBtn);
                }

                // 4) Video-ad fallback: seek near end, then 16x + mute.
                // Only runs while the player itself says an ad is active.
                if (v) {
                    try {
                        var d = Number(v.duration);
                        var c = Number(v.currentTime);
                        if (isFinite(d) && d > 0 &&
                            isFinite(c) && c >= 0 &&
                            d - c > 0.75) {
                            v.currentTime = Math.max(c, d - 0.10);
                        }
                    } catch(e) {}

                    try { v.muted = true; } catch(e) {}
                    try { v.playbackRate = 16.0; } catch(e) {}
                }
            } catch(e) {}
        }

        function observePlayer() {
            try {
                var target = getPlayer();
                if (!target) return;
                if (state.playerObserverTarget === target &&
                    state.playerObserver) return;

                if (state.playerObserver) {
                    try { state.playerObserver.disconnect(); } catch(e) {}
                }

                state.playerObserverTarget = target;
                state.playerObserver = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        if (mutations[i].attributeName === 'class') {
                            skipAd();
                            break;
                        }
                    }
                });

                state.playerObserver.observe(target, {
                    attributes: true,
                    attributeFilter: ['class'],
                    childList: false,
                    subtree: false
                });
            } catch(e) {}
        }

        // ---------------- SponsorBlock ----------------
        var SB = {
            currentVideoId: '',
            segments: [],
            lastSkippedId: '',

            normalize: function(data) {
                if (!Array.isArray(data)) return [];

                return data.filter(function(item) {
                    if (!item || !Array.isArray(item.segment) ||
                        item.segment.length < 2) return false;

                    if (item.actionType && item.actionType !== 'skip') return false;

                    var start = Number(item.segment[0]);
                    var end = Number(item.segment[1]);

                    return isFinite(start) &&
                           isFinite(end) &&
                           start >= 0 &&
                           end > start;
                }).map(function(item) {
                    return {
                        id: item.UUID || (item.segment[0] + '-' + item.segment[1]),
                        start: Number(item.segment[0]),
                        end: Number(item.segment[1]),
                        category: item.category || 'sponsor'
                    };
                }).sort(function(a, b) {
                    return a.start - b.start;
                });
            },

            onSegmentsLoaded: function(videoId, json) {
                if (this.currentVideoId !== videoId) return;

                try {
                    var data = (typeof json === 'string')
                        ? JSON.parse(json)
                        : json;
                    this.segments = this.normalize(data);
                } catch(e) {
                    this.segments = [];
                }
            },

            load: function(videoId) {
                if (!state.sponsorEnabled ||
                    !videoId ||
                    this.currentVideoId === videoId) {
                    return;
                }

                this.currentVideoId = videoId;
                this.segments = [];
                this.lastSkippedId = '';

                try {
                    if (window.CarHudAdBridge &&
                        typeof window.CarHudAdBridge.fetchSponsorSegments === 'function') {
                        window.CarHudAdBridge.fetchSponsorSegments(videoId);
                    }
                } catch(e) {}
            },

            skipIfNeeded: function(v) {
                if (!state.sponsorEnabled ||
                    !v ||
                    !this.segments ||
                    this.segments.length === 0 ||
                    isShowingAd()) {
                    return;
                }

                var cur = Number(v.currentTime);
                if (!isFinite(cur) || cur < 0) return;

                for (var i = 0; i < this.segments.length; i++) {
                    var seg = this.segments[i];

                    if (cur >= (seg.start - 0.12) &&
                        cur < (seg.end - 0.10)) {
                        if (this.lastSkippedId === seg.id) return;

                        this.lastSkippedId = seg.id;

                        var p = getPlayer();
                        if (p && typeof p.seekTo === 'function') {
                            try {
                                p.seekTo(seg.end, true);
                                return;
                            } catch(e) {}
                        }

                        try { v.currentTime = seg.end; } catch(e) {}
                        return;
                    }
                }
            }
        };

        window.CarHudSponsorBlock = SB;

        function getVideoId() {
            try {
                var m = window.location.href.match(/[?&]v=([A-Za-z0-9_-]{11})/) ||
                        window.location.href.match(/\/shorts\/([A-Za-z0-9_-]{11})/) ||
                        window.location.href.match(/youtu\.be\/([A-Za-z0-9_-]{11})/);
                return m ? m[1] : '';
            } catch(e) {
                return '';
            }
        }

        function attachVideo() {
            var v = getVideo();
            if (!v || state.video === v) return;

            if (state.video && state.timeHandler) {
                try {
                    state.video.removeEventListener(
                        'timeupdate',
                        state.timeHandler,
                        true
                    );
                } catch(e) {}
            }

            state.video = v;
            state.timeHandler = function() {
                try {
                    if (state.sponsorEnabled) SB.skipIfNeeded(v);
                    if (isShowingAd()) skipAd();
                    else restoreVideoState();
                } catch(e) {}
            };

            try {
                v.addEventListener(
                    'timeupdate',
                    state.timeHandler,
                    true
                );
            } catch(e) {}
        }

        function tick() {
            try {
                observePlayer();
                attachVideo();
                skipAd();

                if (state.sponsorEnabled) {
                    var id = getVideoId();
                    if (id && id !== state.lastVideoId) {
                        state.lastVideoId = id;
                        SB.load(id);
                    }

                    if (state.video) SB.skipIfNeeded(state.video);
                }
            } catch(e) {}
        }

        state.tick = tick;

        // Main watchdog is intentionally slow; MutationObserver + timeupdate
        // do the fast-path work.
        state.timer = setInterval(tick, 1500);

        window.addEventListener('yt-navigate-finish', tick);
        window.addEventListener('yt-page-data-updated', tick);
        window.addEventListener('popstate', tick);

        tick();
    } catch(e) {}
})();
""".trimIndent()
}
