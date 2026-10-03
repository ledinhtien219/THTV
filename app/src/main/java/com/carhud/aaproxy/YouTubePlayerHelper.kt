package com.carhud.aaproxy

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Color
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.net.URLEncoder

object YouTubePlayerHelper {

    private val JS_CAR_SEARCH_HOME = """
        (function initSearchHome() {
            window.__carhudInitSearchHome = initSearchHome;
            if (!/(^|\.)youtube\.com${'$'}/i.test(location.hostname)) return;
            if (!document.body || (!window.__carhudCarSearchHome && (!window.AndroidVoice || !window.AndroidVoice.isAuto))) {
                setTimeout(function() { if (window.__carhudInitSearchHome) window.__carhudInitSearchHome(); }, 250);
                return;
            }
            if (!window.__carhudCarSearchHome && !window.AndroidVoice.isAuto()) return;
            if (window.__carhudSearchHomeRefresh) { window.__carhudSearchHomeRefresh(); return; }
            var panel = document.createElement('section');
            panel.id = 'carhud-search-home';
            panel.style.cssText = 'position:fixed!important;left:0!important;top:48px!important;z-index:100;display:none;background:var(--carhud-home-bg,#0f0f0f);color:var(--carhud-home-fg,#fff);overflow:hidden;box-sizing:border-box;padding:0!important;margin:0!important;max-height:none!important;';
            // Isolate the card from YouTube's global section/heading sizing rules.
            var surface = panel.attachShadow({mode:'open'});
            // DOM APIs work on pages enforcing Trusted Types; innerHTML can fail there.
            function add(parent, tag, css, text) {
                var node = document.createElement(tag);
                node.style.cssText = css || '';
                if (text) node.textContent = text;
                parent.appendChild(node);
                return node;
            }
            function svg(parent, width, height, viewBox, shapes) {
                var node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                node.setAttribute('width', width); node.setAttribute('height', height);
                node.setAttribute('viewBox', viewBox); node.setAttribute('aria-hidden', 'true');
                shapes.forEach(function(shape) {
                    var child = document.createElementNS('http://www.w3.org/2000/svg', shape.tag);
                    Object.keys(shape.attrs).forEach(function(key) { child.setAttribute(key, shape.attrs[key]); });
                    node.appendChild(child);
                });
                parent.appendChild(node);
            }
            var column = add(surface, 'div', 'position:absolute;width:400px;margin:0;text-align:center;font-family:Arial,sans-serif;transform-origin:top left;box-sizing:border-box');
            svg(column, 80, 56, '0 0 80 56', [
                {tag:'rect',attrs:{width:80,height:56,rx:16,fill:'#ff0000'}},
                {tag:'path',attrs:{d:'M32 15L54 28L32 41Z',fill:'white'}}
            ]);
            var row = add(column, 'div', 'display:flex;align-items:center;gap:12px;margin:20px 0 36px');
            add(row, 'span', 'font-size:24px;width:40px', '⌕');
            var keyboard = add(row, 'button', 'flex:1;min-width:0;height:48px;border:0;border-radius:28px;padding:0 18px;background:var(--carhud-home-chip,#272727);color:var(--carhud-home-muted,#aaa);font:inherit;font-size:17px;text-align:left', 'Tìm trên YouTube');
            keyboard.id = 'carhud-home-keyboard'; keyboard.type = 'button';
            var voice = add(row, 'button', 'width:48px;height:48px;flex-shrink:0;border:0;border-radius:50%;background:var(--carhud-home-chip,#272727);color:inherit');
            voice.id = 'carhud-home-voice'; voice.type = 'button'; voice.setAttribute('aria-label', 'Micro');
            svg(voice, 24, 24, '0 0 24 24', [{tag:'path',attrs:{d:'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3ZM5 11v1a7 7 0 0 0 14 0v-1M12 19v3M8 22h8',fill:'none',stroke:'currentColor','stroke-width':2,'stroke-linecap':'round'}}]);
            var card = add(column, 'div', 'padding:24px 20px;border-radius:16px;background:var(--carhud-home-card,#212121);box-shadow:0 6px 24px #0002');
            add(card, 'h2', 'font-size:24px;line-height:1.3;margin:0 0 14px', 'Thử tìm kiếm để bắt đầu');
            add(card, 'p', 'font-size:16px;line-height:1.5;margin:0;color:var(--carhud-home-muted,#aaa)', 'Hãy bắt đầu xem video để giúp chúng tôi tạo trang đề xuất những video mà bạn có thể yêu thích.');
            document.documentElement.appendChild(panel);
            surface.addEventListener('click', function(e) {
                var keyboard = e.target.closest('#carhud-home-keyboard');
                var voice = e.target.closest('#carhud-home-voice');
                if (!keyboard && !voice) return;
                e.preventDefault(); e.stopPropagation();
                if (voice && window.AndroidVoice.startListening) window.AndroidVoice.startListening();
                else if (keyboard && window.AndroidVoice.openSearchKeyboard) window.AndroidVoice.openSearchKeyboard();
            });
            function layoutHome() {
                if (panel.style.display === 'none') return;
                var vp = window.visualViewport;
                var w = vp ? vp.width : window.innerWidth;
                var h = vp ? vp.height : window.innerHeight;
                var top = Math.min(48, h / 5);
                panel.style.setProperty('top', top + 'px', 'important');
                panel.style.setProperty('width', w + 'px', 'important');
                panel.style.setProperty('height', Math.max(1, h-top) + 'px', 'important');
                var areas = [{x:0,y:top,w:w,h:h-top}];
                (window.__carhudHomeObstacles || []).forEach(function(r) {
                    var b = {x:Math.max(0,r.x*w-8),y:Math.max(top,r.y*h-8),right:Math.min(w,r.right*w+8),bottom:Math.min(h,r.bottom*h+8)};
                    var next=[];
                    areas.forEach(function(a) {
                        if(b.right<=a.x||b.x>=a.x+a.w||b.bottom<=a.y||b.y>=a.y+a.h){next.push(a);return;}
                        if(b.x>a.x)next.push({x:a.x,y:a.y,w:b.x-a.x,h:a.h});
                        if(b.right<a.x+a.w)next.push({x:b.right,y:a.y,w:a.x+a.w-b.right,h:a.h});
                        if(b.y>a.y)next.push({x:a.x,y:a.y,w:a.w,h:b.y-a.y});
                        if(b.bottom<a.y+a.h)next.push({x:a.x,y:b.bottom,w:a.w,h:a.y+a.h-b.bottom});
                    });
                    areas=next;
                });
                var best = null;
                areas.forEach(function(a) {
                    if(a.w<80||a.h<60)return;
                    column.style.width = Math.min(440,Math.max(280,a.w-24))+'px';
                    row.style.margin = a.h<330 ? '10px 0 14px' : '20px 0 28px';
                    card.style.padding = a.h<330 ? '14px 16px' : '20px';
                    var cw=column.offsetWidth,ch=column.offsetHeight;
                    var scale=Math.min(1,(a.w-24)/cw,(a.h-16)/ch);
                    if(!best||scale>best.scale||scale===best.scale&&a.w*a.h>best.area.w*best.area.h)best={area:a,scale:scale,width:cw};
                });
                if(!best)return;
                var a=best.area;
                column.style.width=best.width+'px';
                row.style.margin=a.h<330?'10px 0 14px':'20px 0 28px';
                card.style.padding=a.h<330?'14px 16px':'20px';
                var scale=Math.min(1,(a.w-24)/column.offsetWidth,(a.h-16)/column.offsetHeight);
                column.style.transform='scale('+scale+')';
                column.style.left=(a.x+(a.w-column.offsetWidth*scale)/2)+'px';
                column.style.top=(a.y-top+(a.h-column.offsetHeight*scale)/2)+'px';
            }
            window.__carhudLayoutSearchHome = layoutHome;
            window.addEventListener('resize', layoutHome);
            if(window.visualViewport)window.visualViewport.addEventListener('resize', layoutHome);
            window.__carhudSearchHomeRefresh = function() {
                var home = location.pathname === '/' || location.pathname === '/feed/what_to_watch';
                if (!home) {
                    if (panel.style.display !== 'none') panel.style.display = 'none';
                    return;
                }
                if (!document.documentElement.contains(panel)) document.documentElement.appendChild(panel);
                // Detect the actual text wherever YouTube places its empty-home renderer.
                // Exclude our panel so it cannot keep itself visible on a populated feed.
                var walker = document.createTreeWalker(document.body, 4);
                var text = '', node;
                while ((node = walker.nextNode())) {
                    if (panel.contains(node)) continue;
                    var parent = node.parentElement;
                    if (parent && /^(SCRIPT|STYLE|NOSCRIPT)${'$'}/.test(parent.tagName)) continue;
                    text += ' ' + node.textContent;
                }
                var empty = /try\s+searching\s+to\s+get\s+started|thử\s+tìm\s+kiếm\s+để\s+bắt\s+đầu/i.test(text);
                var visible = home && empty;
                var display = visible ? 'block' : 'none';
                if (panel.style.display !== display) panel.style.display = display;
                if (visible) layoutHome();
            };
            window.__carhudSearchHomeRefresh();
            setInterval(window.__carhudSearchHomeRefresh, 500);
        })();
    """.trimIndent()

    fun applyCarSearchHome(view: WebView) {
        // The car presentation identifies the surface directly, independently of
        // bridge replacement timing or a stale dashboard flag from the previous page.
        view.evaluateJavascript("window.__carhudCarSearchHome = true;", null)
        view.evaluateJavascript(JS_CAR_SEARCH_HOME, null)
    }

    // Keep video history separate from browser history (which can contain IPTV).
    private val JS_VIDEO_HISTORY = """
        (function() {
            if (!/(^|\.)youtube\.com${'$'}/i.test(location.hostname)) return;
            if (window.__carhudRecordVideo) { window.__carhudRecordVideo(); return; }
            var key = 'carhud-youtube-video-history';
            var history = [];
            try { history = JSON.parse(sessionStorage.getItem(key) || '[]'); } catch(e) {}
            if (!Array.isArray(history)) history = [];
            history = history.filter(function(path) {
                try { var u = new URL(path, location.origin); return u.origin === location.origin && u.pathname === '/watch' && !!u.searchParams.get('v'); }
                catch(e) { return false; }
            });
            function save() { try { sessionStorage.setItem(key, JSON.stringify(history)); } catch(e) {} }
            window.__carhudRecordVideo = function() {
                var u = new URL(location.href);
                if (u.pathname !== '/watch' || !u.searchParams.get('v')) return;
                var path = u.pathname + u.search;
                var last = history.length ? new URL(history[history.length - 1], location.origin) : null;
                if (last && last.searchParams.get('v') === u.searchParams.get('v')) return;
                history.push(path);
                if (history.length > 100) history.shift();
                save();
            };
            window.__carhudPreviousVideo = function() {
                window.__carhudRecordVideo();
                if (location.pathname !== '/watch' || history.length < 2) return false;
                history.pop();
                var previous = history[history.length - 1];
                save();
                location.assign(new URL(previous, location.origin).href);
                return true;
            };
            window.__carhudRecordVideo();
            // YouTube also navigates between videos without reloading the document.
            setInterval(window.__carhudRecordVideo, 500);
        })();
    """.trimIndent()

    fun trackVideoHistory(view: WebView) {
        view.evaluateJavascript(JS_VIDEO_HISTORY, null)
    }

    val JS_CLEANUP_AND_ADBLOCK: String = """
(function() {
    if (window.__carhudInjected) return;
    window.__carhudInjected = true;
    try {
        // 1. Spoof Page Visibility & Focus so YouTube NEVER pauses background playback
        try {
            Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
            Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
            Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
            document.hasFocus = function() { return true; };

            var killEvent = function(e) {
                e.stopImmediatePropagation();
            };
            window.addEventListener('blur', killEvent, true);
            window.addEventListener('visibilitychange', killEvent, true);
            document.addEventListener('visibilitychange', killEvent, true);
            window.addEventListener('webkitvisibilitychange', killEvent, true);
            document.addEventListener('webkitvisibilitychange', killEvent, true);
            window.addEventListener('pagehide', killEvent, true);
        } catch(e) {}

        // 2. Intercept EventTarget.prototype.addEventListener to prevent YouTube registering visibility listeners
        try {
            var origAddEventListener = EventTarget.prototype.addEventListener;
            EventTarget.prototype.addEventListener = function(type, listener, options) {
                if (type === 'visibilitychange' || type === 'webkitvisibilitychange' || type === 'pagehide' || type === 'freeze') {
                    return;
                }
                return origAddEventListener.apply(this, arguments);
            };
        } catch(e) {}

        window.__carhudUserPaused = false;
        window.__carhudSetUserPaused = function(val) {
            window.__carhudUserPaused = !!val;
        };

        // 3. Intercept HTMLMediaElement.prototype.pause so background pause events cannot stop audio
        try {
            var origMediaPause = HTMLMediaElement.prototype.pause;
            HTMLMediaElement.prototype.pause = function() {
                if (window.__carhudUserPaused) {
                    return origMediaPause.apply(this, arguments);
                }
            };
        } catch(e) {}

        // 4. Auto-resume on unexpected pause event if not user-paused
        try {
            document.addEventListener('pause', function(e) {
                if (!window.__carhudUserPaused && e.target && e.target.tagName === 'VIDEO') {
                    setTimeout(function() {
                        if (!window.__carhudUserPaused && e.target.paused) {
                            var p = e.target.play();
                            if (p && typeof p.catch === 'function') p.catch(function() {});
                        }
                    }, 120);
                }
            }, true);
        } catch(e) {}

        var css = [
            'ytm-promoted-sparkles-web-renderer',
            'ytm-promoted-video-renderer',
            'ytm-companion-ad-renderer',
            'ytm-display-ad-renderer',
            'ytm-ad-slot-renderer',
            'ytm-statement-banner-renderer',
            '.ytp-ad-module',
            '.ytp-ad-overlay-container',
            '.ytp-ad-image-overlay',
            'div[class*="ad-display"]',
            'div[class*="ad-unit"]',
            'ytm-paid-content-overlay-renderer',
            'ytm-donation-shelf-renderer',
            'ytm-reel-shelf-renderer',
            '#contents ytm-compact-promoted-item-renderer',
            '.ytp-ad-overlay-slot',
            '.ytp-ad-message-container',
            'ytm-ad-badge-renderer',
            'ytm-autonav-bar-renderer',
            '.ytm-autonav-bar',
            '.autonav-bar',
            'ytm-playlist-panel-renderer',
            'ytm-playlist-panel-header-renderer',
            '.playlist-panel-header',
            '.playlist-panel-contents',
            'ytm-playlist-header-renderer',
            '.player-playlist',
            '.playlist-bar',
            'ytm-compact-playlist-renderer',
            'ytm-radio-renderer',
            'ytm-open-app-button-renderer',
            'button[aria-label*="Mở ứng dụng"]',
            'ytm-pivot-bar-renderer',
            'ytm-mealbar-promo-renderer',
            'ytm-engagement-panel-section-list-renderer',
            '.engagement-panel-container',
            'ytm-feed-filter-chip-bar-renderer',
            'ytm-feed-filter-chip-bar-renderer-v2',
            'ytm-chip-cloud-renderer',
            '.chip-bar',
            'ytm-chip-cloud-chip-renderer',
            '.feed-filter-chip-bar',
            'ytm-reel-shelf-renderer',
            'ytm-reel-item-renderer',
            'ytm-shorts-lockup-view-model',
            'ytm-shorts-lockup-view-model-v2',
            '[is-shorts]',
            'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer)',
            'ytm-rich-section-renderer:has([is-shorts])',
            'ytm-video-with-context-renderer:has(a[href*="/shorts/"])',
            'ytm-compact-video-renderer:has(a[href*="/shorts/"])'
        ].join(',') + '{ display: none !important; opacity: 0 !important; visibility: hidden !important; height: 0px !important; min-height: 0px !important; max-height: 0px !important; padding: 0 !important; margin: 0 !important; pointer-events: none !important; } ' +
        'html, body, #app, ytm-app, .page-container { margin: 0 !important; padding: 0 !important; width: 100% !important; max-width: 100% !important; overflow-x: hidden !important; -webkit-overflow-scrolling: touch !important; touch-action: pan-y !important; overscroll-behavior-y: contain !important; } ' +
        'ytm-mobile-topbar-renderer, .mobile-topbar-header { height: 40px !important; max-height: 40px !important; min-height: 40px !important; padding: 0 10px !important; margin: 0 !important; display: flex !important; align-items: center !important; } ' +
        'ytm-logo { width: 72px !important; height: 20px !important; } ' +
        'lazy-list, ytm-rich-grid-renderer, .page-container, ytm-app, body { transform: translateZ(0) !important; -webkit-transform: translateZ(0) !important; backface-visibility: hidden !important; } ' +
        'ytm-thumbnail-cover, ytm-compact-video-renderer, ytm-video-with-context-renderer, ytm-compact-playlist-renderer, ytm-custom-comment-thread-renderer { box-shadow: none !important; text-shadow: none !important; backdrop-filter: none !important; -webkit-backdrop-filter: none !important; contain: none !important; overflow: visible !important; } ' +
        'ytm-search, ytm-section-list-renderer, ytm-section-list-renderer > lazy-list, ytm-item-section-renderer { display: block !important; width: 100% !important; max-width: 100% !important; margin: 0 !important; padding: 0 !important; box-sizing: border-box !important; } ' +
        'ytm-item-section-renderer > lazy-list, ytm-rich-grid-renderer > .rich-grid-renderer-contents, .rich-grid-renderer-contents { display: grid !important; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)) !important; gap: 12px 14px !important; padding: 6px 10px 80px 10px !important; box-sizing: border-box !important; width: 100% !important; max-width: 100% !important; } ' +
        'ytm-compact-video-renderer, ytm-compact-video-renderer > *, ytm-video-with-context-renderer, ytm-video-with-context-renderer > *, ytm-compact-playlist-renderer, ytm-rich-item-renderer, .media-item, .compact-media-item { display: flex !important; flex-direction: column !important; width: 100% !important; max-width: 100% !important; height: auto !important; min-height: 0 !important; max-height: none !important; margin: 0 0 10px 0 !important; padding: 0 !important; box-sizing: border-box !important; overflow: visible !important; contain: none !important; transform: translateZ(0) !important; will-change: transform !important; } ' +
        'a.compact-media-item-image, a.media-item-thumbnail-container, .video-thumbnail-container-compact, .media-item-thumbnail-container, ytm-thumbnail-cover { display: block !important; width: 100% !important; max-width: 100% !important; height: auto !important; aspect-ratio: 16 / 9 !important; flex-shrink: 0 !important; border-radius: 8px !important; overflow: hidden !important; position: relative !important; margin: 0 !important; } ' +
        'a.compact-media-item-image img, .video-thumbnail-container-compact img, .media-item-thumbnail-container img, ytm-thumbnail-cover img { width: 100% !important; height: 100% !important; object-fit: cover !important; display: block !important; } ' +
        '.compact-media-item-metadata, .compact-media-item-metadata-content, .details, .media-item-metadata, ytm-compact-video-renderer .details, ytm-video-with-context-renderer .details, ytm-compact-playlist-renderer .details { display: flex !important; flex-direction: column !important; width: 100% !important; min-width: 100% !important; max-width: 100% !important; height: auto !important; min-height: 48px !important; max-height: none !important; overflow: visible !important; visibility: visible !important; opacity: 1 !important; padding: 6px 2px 2px 2px !important; margin: 0 !important; box-sizing: border-box !important; flex: 1 1 auto !important; } ' +
        '.compact-media-item-headline, .media-item-headline, .video-title, ytm-compact-video-renderer h3, ytm-compact-video-renderer h4, ytm-compact-video-renderer [class*="headline"], ytm-video-with-context-renderer h3, ytm-video-with-context-renderer h4, ytm-video-with-context-renderer [class*="headline"], h3, h3.title, h4, .c3-headline, [class*="headline"], [class*="video-title"], ytm-badge-and-byline-renderer, ytm-compact-video-renderer a:not(.compact-media-item-image) { display: -webkit-box !important; -webkit-line-clamp: 2 !important; -webkit-box-orient: vertical !important; overflow: hidden !important; text-overflow: ellipsis !important; visibility: visible !important; opacity: 1 !important; color: #FFFFFF !important; -webkit-text-fill-color: #FFFFFF !important; font-size: 13.5px !important; line-height: 1.35 !important; font-weight: 600 !important; margin: 3px 0 2px 0 !important; padding: 0 !important; word-break: break-word !important; height: auto !important; max-height: 2.9em !important; width: 100% !important; max-width: 100% !important; } ' +
        '.compact-media-item-headline *, .media-item-headline *, .video-title *, ytm-compact-video-renderer h3 *, ytm-compact-video-renderer h4 *, h3 *, h4 * { color: #FFFFFF !important; -webkit-text-fill-color: #FFFFFF !important; visibility: visible !important; opacity: 1 !important; } ' +
        '.compact-media-item-byline, .media-item-byline, .small-text, [class*="byline"], [class*="channel-name"], [class*="metadata"], .subhead, ytm-badge-and-byline-renderer { display: block !important; visibility: visible !important; opacity: 1 !important; color: #94A3B8 !important; -webkit-text-fill-color: #94A3B8 !important; font-size: 11px !important; line-height: 1.3 !important; margin: 2px 0 0 0 !important; padding: 0 !important; white-space: nowrap !important; overflow: hidden !important; text-overflow: ellipsis !important; height: auto !important; width: 100% !important; } ' +
        '.compact-media-item-byline *, .media-item-byline *, [class*="byline"] *, [class*="channel-name"] *, [class*="metadata"] * { color: #94A3B8 !important; -webkit-text-fill-color: #94A3B8 !important; visibility: visible !important; opacity: 1 !important; } ' +
        'ytm-thumbnail-overlay-time-status-renderer, .thumbnail-overlay-time-status-renderer, .badge-shape-wiz--thumbnail-badge { position: absolute !important; bottom: 6px !important; right: 6px !important; background: rgba(0, 0, 0, 0.8) !important; color: #FFFFFF !important; -webkit-text-fill-color: #FFFFFF !important; border-radius: 4px !important; padding: 2px 5px !important; font-size: 11px !important; font-weight: 600 !important; z-index: 2 !important; } ' +
        'html:not(.carhud-auto-screen) ytm-item-section-renderer > lazy-list, html:not(.carhud-auto-screen) ytm-rich-grid-renderer > .rich-grid-renderer-contents, html:not(.carhud-auto-screen) ytm-section-list-renderer > lazy-list, html:not(.carhud-auto-screen) .rich-grid-renderer-contents, html:not(.carhud-auto-screen) lazy-list, .carhud-phone ytm-item-section-renderer > lazy-list, .carhud-phone ytm-rich-grid-renderer > .rich-grid-renderer-contents, .carhud-phone ytm-section-list-renderer > lazy-list, .carhud-phone .rich-grid-renderer-contents, .carhud-phone lazy-list { display: block !important; width: 100% !important; max-width: 100% !important; padding: 0 0 70px 0 !important; box-sizing: border-box !important; } ' +
        'html:not(.carhud-auto-screen) ytm-video-with-context-renderer, html:not(.carhud-auto-screen) ytm-compact-video-renderer, html:not(.carhud-auto-screen) ytm-rich-item-renderer, .carhud-phone ytm-video-with-context-renderer, .carhud-phone ytm-compact-video-renderer, .carhud-phone ytm-rich-item-renderer { display: block !important; width: 100% !important; max-width: 100% !important; margin: 0 0 16px 0 !important; } ' +
        'html:not(.carhud-auto-screen) .media-item-thumbnail-container, html:not(.carhud-auto-screen) ytm-thumbnail-cover, html:not(.carhud-auto-screen) .video-thumbnail-container-compact, .carhud-phone .media-item-thumbnail-container, .carhud-phone ytm-thumbnail-cover, .carhud-phone .video-thumbnail-container-compact { width: 100% !important; border-radius: 0px !important; overflow: hidden !important; } ' +
        'html:not(.carhud-auto-screen) .compact-media-item-headline, html:not(.carhud-auto-screen) .media-item-headline, html:not(.carhud-auto-screen) .video-title, html:not(.carhud-auto-screen) h3.title, .carhud-phone .compact-media-item-headline, .carhud-phone .media-item-headline, .carhud-phone .video-title, .carhud-phone h3.title { font-size: 15px !important; line-height: 1.4 !important; font-weight: 500 !important; max-height: none !important; -webkit-line-clamp: 2 !important; display: -webkit-box !important; -webkit-box-orient: vertical !important; overflow: hidden !important; margin-top: 6px !important; padding: 0 10px !important; color: #FFFFFF !important; -webkit-text-fill-color: #FFFFFF !important; } ' +
        'html:not(.carhud-auto-screen) .compact-media-item-byline, html:not(.carhud-auto-screen) .media-item-byline, html:not(.carhud-auto-screen) .small-text, .carhud-phone .compact-media-item-byline, .carhud-phone .media-item-byline, .carhud-phone .small-text { font-size: 12px !important; margin-top: 2px !important; padding: 0 10px !important; color: #94A3B8 !important; -webkit-text-fill-color: #94A3B8 !important; } ' +
        '.carhud-force-fullscreen, .carhud-force-fullscreen body, .carhud-force-fullscreen html, .carhud-force-fullscreen ytm-app, .carhud-force-fullscreen #app, .carhud-force-fullscreen .page-container, .carhud-force-fullscreen ytm-watch { position: fixed !important; top: 0 !important; left: 0 !important; width: 100% !important; height: 100% !important; max-width: 100% !important; max-height: 100% !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; background: #000000 !important; } ' +
        '.carhud-force-fullscreen #player-container-id, .carhud-force-fullscreen .player-container, .carhud-force-fullscreen ytm-player, .carhud-force-fullscreen .player-size, .carhud-force-fullscreen #movie_player, .carhud-force-fullscreen .html5-video-player, .carhud-force-fullscreen .html5-video-container { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; height: 100% !important; max-width: 100% !important; max-height: 100% !important; margin: 0 !important; padding: 0 !important; background: #000000 !important; z-index: 9999 !important; } ' +
        '.carhud-force-fullscreen video { position: fixed !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; height: 100% !important; max-width: 100% !important; max-height: 100% !important; margin: auto !important; object-fit: contain !important; display: block !important; visibility: visible !important; opacity: 1 !important; background: #000000 !important; z-index: 10000 !important; pointer-events: auto !important; } ' +
        '.carhud-force-fullscreen .ytp-chrome-bottom, .carhud-force-fullscreen .ytp-chrome-top, .carhud-force-fullscreen .ytp-large-play-button, .carhud-force-fullscreen .player-control-play-pause-icon, .carhud-force-fullscreen .ytp-cued-thumbnail-overlay, .carhud-force-fullscreen .ytp-ad-player-overlay, .carhud-force-fullscreen .ytp-ad-skip-button-container, .carhud-force-fullscreen .ytp-ad-skip-button, .carhud-force-fullscreen .videoAdUiSkipButton, .carhud-force-fullscreen ytm-play-button { z-index: 20000 !important; pointer-events: auto !important; } ' +
        '.carhud-force-fullscreen .watch-below-the-fold, .carhud-force-fullscreen ytm-item-section-renderer, .carhud-force-fullscreen ytm-watch-metadata, .carhud-force-fullscreen ytm-mobile-topbar-renderer { display: none !important; } ' +
        '.video-ads, .ytp-ad-module, .ytp-ad-player-overlay-layout, .ytp-ad-image-overlay, .ytp-ad-text-overlay, ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ytm-promoted-video-renderer, ytd-banner-promo-renderer, ytd-in-feed-ad-layout-renderer { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; } ' +
        '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, [class*="ytp-ad-skip"], .videoAdUiSkipButton { z-index: 2147483647 !important; pointer-events: auto !important; } ' +
        '*, html, body { -webkit-tap-highlight-color: transparent !important; touch-action: pan-y !important; } ' +
        'a, button, input, [role="button"], ytm-compact-video-renderer, ytm-video-with-context-renderer, .media-item { touch-action: manipulation !important; cursor: pointer !important; } ' +
        'video { will-change: transform !important; transform: translateZ(0) !important; -webkit-transform: translateZ(0) !important; backface-visibility: hidden !important; }';

        var s = document.getElementById('carhud-clean');
        if (!s) {
            s = document.createElement('style');
            s.id = 'carhud-clean';
            (document.head || document.documentElement).appendChild(s);
        }
        s.textContent = css;

        if (typeof window.__carhudApplyTheme === 'function') {
            window.__carhudApplyTheme(window.__carhudCurrentIsDay !== undefined ? window.__carhudCurrentIsDay : false);
        }

        function cleanExtraPanels() {
            try {
                var panels = document.querySelectorAll(
                    'ytm-playlist-panel-renderer, ytm-playlist-panel-header-renderer, ytm-autonav-bar-renderer, ' +
                    '.ytm-autonav-bar, .autonav-bar, ytm-playlist-header-renderer, .playlist-panel-header, ' +
                    'ytm-open-app-button-renderer, ytm-mealbar-promo-renderer, .engagement-panel-container, ' +
                    'ytm-pivot-bar-renderer, ytm-watch-metadata ytm-autonav-bar-renderer, ' +
                    'ytm-radio-renderer, ytm-reel-shelf-renderer, ytm-reel-item-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2'
                );
                for (var i = 0; i < panels.length; i++) {
                    panels[i].style.setProperty('display', 'none', 'important');
                    panels[i].style.setProperty('visibility', 'hidden', 'important');
                    panels[i].style.setProperty('height', '0px', 'important');
                }

                // Remove any individual cards linking to Shorts (do NOT hide the entire section ytm-item-section-renderer)
                var shortsLinks = document.querySelectorAll('a[href*="/shorts/"], a[href^="/shorts"]');
                for (var j = 0; j < shortsLinks.length; j++) {
                    var card = shortsLinks[j].closest('ytm-reel-item-renderer, ytm-reel-shelf-renderer, ytm-shorts-lockup-view-model, ytm-shorts-lockup-view-model-v2, ytm-video-with-context-renderer, ytm-compact-video-renderer');
                    if (card) {
                        card.style.setProperty('display', 'none', 'important');
                        card.style.setProperty('visibility', 'hidden', 'important');
                        card.style.setProperty('height', '0px', 'important');
                    }
                }
                ensureSearchResultsTitles();
            } catch(e) {}
        }

        function ensureSearchResultsTitles() {
            try {
                var cards = document.querySelectorAll('ytm-compact-video-renderer, ytm-video-with-context-renderer, ytm-compact-playlist-renderer, .compact-media-item');
                if (!cards || cards.length === 0) return;
                for (var i = 0; i < cards.length; i++) {
                    var card = cards[i];
                    card.style.setProperty('display', 'flex', 'important');
                    card.style.setProperty('flex-direction', 'column', 'important');
                    card.style.setProperty('height', 'auto', 'important');
                    card.style.setProperty('overflow', 'visible', 'important');

                    var children = card.children;
                    for (var c = 0; c < children.length; c++) {
                        var child = children[c];
                        if (child && (child.classList.contains('compact-media-item') || child.tagName === 'DIV')) {
                            child.style.setProperty('display', 'flex', 'important');
                            child.style.setProperty('flex-direction', 'column', 'important');
                            child.style.setProperty('width', '100%', 'important');
                            child.style.setProperty('height', 'auto', 'important');
                            child.style.setProperty('overflow', 'visible', 'important');
                        }
                    }

                    var meta = card.querySelector('.compact-media-item-metadata, .compact-media-item-metadata-content, .details, .media-item-metadata');
                    if (meta) {
                        meta.style.setProperty('display', 'flex', 'important');
                        meta.style.setProperty('flex-direction', 'column', 'important');
                        meta.style.setProperty('width', '100%', 'important');
                        meta.style.setProperty('min-width', '100%', 'important');
                        meta.style.setProperty('visibility', 'visible', 'important');
                        meta.style.setProperty('opacity', '1', 'important');
                        meta.style.setProperty('height', 'auto', 'important');
                        meta.style.setProperty('min-height', '44px', 'important');
                        meta.style.setProperty('overflow', 'visible', 'important');
                    }

                    var titles = card.querySelectorAll('h3, h4, .compact-media-item-headline, .media-item-headline, [class*="headline"], .video-title, a:not([class*="image"]):not([class*="thumb"])');
                    for (var t = 0; t < titles.length; t++) {
                        var el = titles[t];
                        el.style.setProperty('display', '-webkit-box', 'important');
                        el.style.setProperty('-webkit-line-clamp', '2', 'important');
                        el.style.setProperty('-webkit-box-orient', 'vertical', 'important');
                        el.style.setProperty('visibility', 'visible', 'important');
                        el.style.setProperty('opacity', '1', 'important');
                        el.style.setProperty('color', '#ffffff', 'important');
                        el.style.setProperty('-webkit-text-fill-color', '#ffffff', 'important');
                        el.style.setProperty('font-size', '13.5px', 'important');
                        el.style.setProperty('line-height', '1.35', 'important');
                        el.style.setProperty('max-height', 'none', 'important');
                        el.style.setProperty('width', '100%', 'important');
                    }
                }
            } catch(e) {}
        }

        function triggerSyntheticClick(el) {
            if (!el) return;
            try {
                var opts = { bubbles: true, cancelable: true, view: window };
                el.dispatchEvent(new PointerEvent('pointerdown', opts));
                el.dispatchEvent(new MouseEvent('mousedown', opts));
                el.dispatchEvent(new PointerEvent('pointerup', opts));
                el.dispatchEvent(new MouseEvent('mouseup', opts));
                el.dispatchEvent(new MouseEvent('click', opts));
                el.click();
            } catch(e) {
                try { el.click(); } catch(ex) {}
            }
        }

        function isPlayerShowingAd() {
            try {
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player) {
                    if (typeof player.getAdState === 'function' && player.getAdState() > 0) return true;
                    if (typeof player.isAdPlaying === 'function' && player.isAdPlaying()) return true;
                    if (player.classList && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) return true;
                }
            } catch(e) {}
            return false;
        }

        function killYouTubeAds() {
            try {
                var isAd = isPlayerShowingAd();
                if (!isAd) return;

                // 1. YouTube native HTML5 player API skip call
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player && typeof player.skipAd === 'function') {
                    try { player.skipAd(); } catch(e) {}
                }

                // 2. Click genuine skip buttons that YouTube naturally made visible
                var skipSelectors = [
                    '.ytp-ad-skip-button-modern',
                    '.ytp-ad-skip-button',
                    '.ytp-skip-ad-button',
                    'button.ytp-ad-skip-button',
                    '.videoAdUiSkipButton',
                    '.ytp-ad-skip-button-text'
                ];
                for (var i = 0; i < skipSelectors.length; i++) {
                    var btn = document.querySelector(skipSelectors[i]);
                    if (btn && btn.offsetParent !== null) {
                        triggerSyntheticClick(btn);
                        break;
                    }
                }

                // 3. Close banner/overlay ads if any close button exists
                var closeSelectors = [
                    '.ytp-ad-overlay-close-button',
                    '.ytp-ad-overlay-close-container',
                    'button[aria-label*="Đóng"]',
                    'button[aria-label*="Close"]'
                ];
                for (var c = 0; c < closeSelectors.length; c++) {
                    var cBtn = document.querySelector(closeSelectors[c]);
                    if (cBtn) {
                        try { cBtn.click(); } catch(e) {}
                    }
                }

                // 4. For video ads: accelerate at 16x speed and mute during ad playback
                var video = getMainVideo();
                if (video && isAd) {
                    video.muted = true;
                    video.playbackRate = 16.0;
                }

                // 5. Global touch listener to show toolbars on touch
                if (!window.__carhudTouchObs) {
                    window.__carhudTouchObs = true;
                    document.addEventListener('touchstart', function() {
                        if (window.AndroidVoice && typeof window.AndroidVoice.onUserTouch === 'function') {
                            try { window.AndroidVoice.onUserTouch(); } catch(e) {}
                        }
                    }, { passive: true });
                    document.addEventListener('click', function() {
                        if (window.AndroidVoice && typeof window.AndroidVoice.onUserTouch === 'function') {
                            try { window.AndroidVoice.onUserTouch(); } catch(e) {}
                        }
                    }, { passive: true });
                }
            } catch(e) {}
        }
        var skipYouTubeAds = killYouTubeAds;

        function observePlayer() {
            if (window.__carhudPlayerObs) return;
            try {
                var target = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (target) {
                    window.__carhudPlayerObs = new MutationObserver(function() {
                        killYouTubeAds();
                    });
                    window.__carhudPlayerObs.observe(target, {
                        childList: false,
                        subtree: false,
                        attributes: true,
                        attributeFilter: ['class']
                    });
                }
            } catch(e) {}
        }
        observePlayer();
        setInterval(function() {
            if (isPlayerShowingAd()) {
                killYouTubeAds();
            }
        }, 1400);

        // ==========================================
        // SPONSORBLOCK (FermataSB) - Skip Ads & Sponsors Stably
        // ==========================================
        var FermataSB = {
            currentVideoId: '',
            segments: [],
            lastSkippedId: '',
            toastTimer: null,

            onSegmentsLoaded: function(videoId, jsonStr) {
                if (this.currentVideoId !== videoId) return;
                try {
                    var data = typeof jsonStr === 'string' ? JSON.parse(jsonStr) : jsonStr;
                    if (Array.isArray(data)) {
                        this.segments = data.filter(function(item) {
                            return (!item.actionType || item.actionType === 'skip') && Array.isArray(item.segment) && item.segment.length >= 2;
                        }).map(function(item) {
                            return {
                                id: item.UUID || (item.segment[0] + '-' + item.segment[1]),
                                start: Number(item.segment[0]),
                                end: Number(item.segment[1]),
                                category: item.category || 'sponsor'
                            };
                        });
                        console.log('[SponsorBlock] Loaded ' + this.segments.length + ' segments for video: ' + videoId);
                    }
                } catch(e) {
                    console.log('[SponsorBlock] Parse error:', e);
                }
            },

            loadForVideo: function(videoId) {
                if (!videoId || videoId === this.currentVideoId) return;
                this.currentVideoId = videoId;
                this.segments = [];
                this.lastSkippedId = '';

                // 1. Query through native Android bridge
                if (window.AndroidVoice && typeof window.AndroidVoice.fetchSponsorSegments === 'function') {
                    try {
                        window.AndroidVoice.fetchSponsorSegments(videoId);
                    } catch(e) {}
                }

                // 2. Direct web fetch fallback
                var self = this;
                var url = 'https://sponsor.ajay.app/api/skipSegments?videoID=' + encodeURIComponent(videoId) +
                    '&categories=%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22,%22preview%22,%22music_offtopic%22,%22filler%22,%22poi_highlight%22%5D&actionTypes=%5B%22skip%22%5D&service=YouTube';
                fetch(url)
                    .then(function(res) {
                        if (res.ok) return res.json();
                        return [];
                    })
                    .then(function(data) {
                        if (self.currentVideoId === videoId && self.segments.length === 0 && Array.isArray(data)) {
                            self.segments = data.filter(function(item) {
                                return (!item.actionType || item.actionType === 'skip') && Array.isArray(item.segment) && item.segment.length >= 2;
                            }).map(function(item) {
                                return {
                                    id: item.UUID || (item.segment[0] + '-' + item.segment[1]),
                                    start: Number(item.segment[0]),
                                    end: Number(item.segment[1]),
                                    category: item.category || 'sponsor'
                                };
                            });
                            console.log('[SponsorBlock] Web fetch loaded ' + self.segments.length + ' segments');
                        }
                    })
                    .catch(function() {});
            },

            showToast: function(category) {
                try {
                    var toast = document.getElementById('carhud-sb-toast');
                    if (!toast) {
                        toast = document.createElement('div');
                        toast.id = 'carhud-sb-toast';
                        toast.style.cssText = 'position:fixed;bottom:75px;left:16px;z-index:2147483647;background:rgba(15,23,42,0.92);border:1.5px solid #00E5FF;color:#FFFFFF;padding:7px 15px;border-radius:20px;font-size:12.5px;font-weight:600;pointer-events:none;display:flex;align-items:center;gap:6px;box-shadow:0 4px 18px rgba(0,0,0,0.7);transition:opacity 0.3s ease;';
                        document.body.appendChild(toast);
                    }
                    var catLabel = 'Nhà tài trợ';
                    if (category === 'selfpromo') catLabel = 'Tự quảng bá';
                    else if (category === 'interaction') catLabel = 'Kêu gọi tương tác';
                    else if (category === 'intro') catLabel = 'Đoạn mở đầu (Intro)';
                    else if (category === 'outro') catLabel = 'Đoạn kết (Outro)';
                    else if (category === 'preview') catLabel = 'Đoạn preview';
                    else if (category === 'music_offtopic') catLabel = 'Đoạn ngoài bài hát';
                    else if (category === 'filler') catLabel = 'Đoạn phụ';
                    else if (category === 'poi_highlight') catLabel = 'Điểm nổi bật';

                    toast.innerHTML = '⚡ <b>SponsorBlock:</b> Đã bỏ qua ' + catLabel;
                    toast.style.opacity = '1';

                    if (this.toastTimer) clearTimeout(this.toastTimer);
                    this.toastTimer = setTimeout(function() {
                        if (toast) toast.style.opacity = '0';
                    }, 2800);
                } catch(e) {}
            },

            skipIfNeeded: function(video) {
                if (!video || !this.segments || this.segments.length === 0) return;
                var cur = video.currentTime;
                if (!cur || isNaN(cur) || cur <= 0) return;

                for (var i = 0; i < this.segments.length; i++) {
                    var seg = this.segments[i];
                    if (cur >= (seg.start - 0.15) && cur < (seg.end - 0.2)) {
                        if (this.lastSkippedId !== seg.id) {
                            this.lastSkippedId = seg.id;
                            console.log('[SponsorBlock] Skipping ' + seg.category + ' [' + seg.start + 's -> ' + seg.end + 's]');
                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (player && typeof player.seekTo === 'function') {
                                try { player.seekTo(seg.end, true); } catch(ex) { video.currentTime = seg.end; }
                            } else {
                                video.currentTime = seg.end;
                            }
                            this.showToast(seg.category);
                        }
                        break;
                    }
                }
            },

            tick: function() {
                var vMatch = window.location.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/) || window.location.href.match(/\/shorts\/([a-zA-Z0-9_-]{11})/);
                var vId = vMatch ? vMatch[1] : '';
                if (vId && vId !== this.currentVideoId) {
                    this.loadForVideo(vId);
                }
                var v = getMainVideo();
                if (v) {
                    this.skipIfNeeded(v);
                }
            }
        };
        window.FermataSB = FermataSB;

        function clickPlayButtons() {
            var playSelectors = [
                '.ytp-large-play-button'
            ];
            for (var i = 0; i < playSelectors.length; i++) {
                var btns = document.querySelectorAll(playSelectors[i]);
                for (var j = 0; j < btns.length; j++) {
                    var btn = btns[j];
                    if (btn && (btn.offsetWidth > 0 || btn.offsetHeight > 0)) {
                        triggerSyntheticClick(btn);
                        return;
                    }
                }
            }
        }

        function startAggressivePlay() {
            if (window.__carhudPlayTimeout) {
                clearTimeout(window.__carhudPlayTimeout);
            }
            window.__carhudPlayTimeout = setTimeout(function() {
                window.__carhudPlayTimeout = null;
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                var video = document.querySelector('video');

                if (player && typeof player.getPlayerState === 'function') {
                    var s = player.getPlayerState();
                    if (s === 1 || s === 3) return; // 1: playing, 3: buffering
                }
                if (video && !video.paused && video.currentTime > 0) return;

                if (window.__carhudStartingPlayback) return;
                window.__carhudStartingPlayback = true;
                setTimeout(function() { window.__carhudStartingPlayback = false; }, 2000);

                if (player && typeof player.playVideo === 'function') {
                    try { player.playVideo(); } catch(e) {}
                } else if (video && video.paused) {
                    try {
                        if (video.muted) video.muted = false;
                        if (video.volume < 0.5) video.volume = 1.0;
                        video.play().catch(function() {});
                    } catch(e) {}
                }
            }, 350);
        }

        // Hook SPA Navigation (pushState, replaceState, popstate)
        (function() {
            var origPush = history.pushState;
            history.pushState = function() {
                var ret = origPush.apply(this, arguments);
                onUrlChange();
                return ret;
            };
            var origReplace = history.replaceState;
            history.replaceState = function() {
                var ret = origReplace.apply(this, arguments);
                onUrlChange();
                return ret;
            };
            window.addEventListener('popstate', onUrlChange);

            function onUrlChange() {
                if (window.location.pathname.indexOf('/watch') === 0) {
                    window.__carhudPlayAttempts = 0;
                    var curUrl = window.location.href.split('&')[0];
                    try {
                        if (window.AndroidVoice && window.AndroidVoice.onUserSelectedVideo) {
                            window.AndroidVoice.onUserSelectedVideo(curUrl);
                        }
                    } catch(e) {}
                    startAggressivePlay();
                }
            }
        })();

        // Click interceptor for any video cards
        document.addEventListener('click', function(e) {
            var target = e.target;
            var link = target ? target.closest('a') : null;
            if (link && link.href && link.href.indexOf('watch') !== -1) {
                window.__carhudPlayAttempts = 0;
                try {
                    if (window.AndroidVoice && window.AndroidVoice.onUserSelectedVideo) {
                        window.AndroidVoice.onUserSelectedVideo(link.href.split('&')[0]);
                    }
                } catch(ex) {}
                startAggressivePlay();
            }
        }, true);

        function getMainVideo() {
            return document.querySelector('#movie_player video, .html5-video-player video, ytm-player video') || document.querySelector('video');
        }

        function attachVideoListeners(v) {
            if (!v || v.getAttribute('data-carhud-attached') === 'true') return;
            var isMain = v.closest('#movie_player, .html5-video-player, ytm-player, .player-container, #player-container-id');
            if (!isMain && document.querySelector('#movie_player, .html5-video-player')) {
                return;
            }
            v.setAttribute('data-carhud-attached', 'true');

            v.addEventListener('timeupdate', function() {
                updateVideoProgress();
                if (window.FermataSB) window.FermataSB.skipIfNeeded(v);
            }, true);

            v.addEventListener('playing', function() {
                ensureAudio();
                notifyState(true);
                updateVideoProgress();
            }, true);

            v.addEventListener('play', function() {
                ensureAudio();
                notifyState(true);
                updateVideoProgress();
            }, true);

            v.addEventListener('pause', function() {
                if (window.__carhudUserPaused) {
                    notifyState(false);
                    updateVideoProgress();
                } else {
                    // Do not aggressively force unpause in 350ms which causes an infinite play/pause stutter loop.
                    // notifyState(false) has a built-in 1200ms debounce timer with checkActuallyPlaying().
                    notifyState(false);
                }
            }, true);

            v.addEventListener('ended', function() {
                notifyState(false);
                updateVideoProgress();
            }, true);

            v.addEventListener('loadedmetadata', function() {
                ensureAudio();
                extractMetadata();
                updateVideoProgress();
            }, true);
        }

        function scanVideos() {
            try {
                var main = getMainVideo();
                if (main) attachVideoListeners(main);
                ensureSearchResultsTitles();
            } catch(e) {}
        }

        function handleWatchPage() {
            try {
                var currentHref = window.location.href.split('&')[0];
                if (window.__carhudLastCleanedUrl !== currentHref) {
                    window.__carhudLastCleanedUrl = currentHref;
                    cleanExtraPanels();
                }
                var isWatch = window.location.pathname.indexOf('/watch') === 0;

                if (isWatch) {
                    if (!document.documentElement.classList.contains('carhud-force-fullscreen')) {
                        document.documentElement.classList.add('carhud-force-fullscreen');
                    }
                    if (!document.body.classList.contains('carhud-force-fullscreen')) {
                        document.body.classList.add('carhud-force-fullscreen');
                    }

                    var currentUrl = currentHref;
                    if (window.__carhudLastWatchUrl !== currentUrl) {
                        window.__carhudPlayAttempts = 0;
                        window.__carhudLastWatchUrl = currentUrl;
                        try {
                            if (window.AndroidVoice && window.AndroidVoice.saveLastPlayedUrl) {
                                window.AndroidVoice.saveLastPlayedUrl(currentUrl);
                            }
                        } catch(e) {}
                        startAggressivePlay();
                    }

                    if (window.FermataSB) {
                        window.FermataSB.tick();
                    }

                    var video = document.querySelector('video');
                    var isAd = isPlayerShowingAd();
                    
                    if (video) {
                        attachVideoListeners(video);
                        if (!isAd && video.muted && video.volume > 0) {
                            try { video.muted = false; } catch(e) {}
                        }
                        if (window.FermataSB) {
                            window.FermataSB.skipIfNeeded(video);
                        }
                    }
                } else {
                    document.documentElement.classList.remove('carhud-force-fullscreen');
                    document.body.classList.remove('carhud-force-fullscreen');
                    try {
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
                        }
                    } catch(e) {}
                    window.__carhudLastWatchUrl = '';
                    window.__carhudPlayAttempts = 0;
                }
            } catch(e) {}
        }

        setInterval(function() {
            handleWatchPage();
            
            var isHome = window.location.pathname === '/';
            if (isHome) {
                if (!document.body.classList.contains('carhud-home')) document.body.classList.add('carhud-home');
            } else {
                if (document.body.classList.contains('carhud-home')) document.body.classList.remove('carhud-home');
            }

            // Auto dismiss "Video paused. Continue watching?" confirmation dialogs
            try {
                var confirmBtns = document.querySelectorAll('yt-confirm-dialog-renderer button, .yt-spec-button-shape-next--call-to-action, button[aria-label*="Có"], button[aria-label*="Yes"], button[aria-label*="Tiếp tục"], button[aria-label*="Confirm"]');
                for (var b = 0; b < confirmBtns.length; b++) {
                    var cBtn = confirmBtns[b];
                    if (cBtn && (cBtn.offsetWidth > 0 || cBtn.offsetHeight > 0 || cBtn.offsetParent !== null)) {
                        cBtn.click();
                        break;
                    }
                }
            } catch(e) {}

            // Anti-pause watchdog: only unpause if paused for 3 consecutive seconds to avoid buffer flapping
            try {
                if (window.location.pathname.indexOf('/watch') === 0 && !window.__carhudUserPaused) {
                    var curV = getMainVideo();
                    var curPlayer = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    var pState = (curPlayer && typeof curPlayer.getPlayerState === 'function') ? curPlayer.getPlayerState() : -1;
                    var isSeeking = curV && (curV.seeking || curV.ended);
                    if (pState === 2 && curV && curV.paused && !isSeeking && curV.currentTime > 0.5 && !window.__carhudStartingPlayback) {
                        window.__carhudPausedStreak = (window.__carhudPausedStreak || 0) + 1;
                        if (window.__carhudPausedStreak >= 3) {
                            window.__carhudPausedStreak = 0;
                            if (curPlayer && typeof curPlayer.playVideo === 'function') {
                                try { curPlayer.playVideo(); } catch(ex) {}
                            } else if (curV.paused) {
                                try {
                                    if (curV.muted) curV.muted = false;
                                    curV.play().catch(function() {});
                                } catch(e) {}
                            }
                        }
                    } else {
                        window.__carhudPausedStreak = 0;
                    }
                }
            } catch(e) {}
        }, 1200);

        if (window.location.pathname.indexOf('/results') === 0 && window.sessionStorage.getItem('carhud_auto_play') === 'true') {
            var attempts = 0;
            var intv = setInterval(function() {
                var firstVideo = document.querySelector('ytm-video-with-context-renderer a, ytm-compact-video-renderer a, a.media-item-thumbnail-container, a.compact-media-item-metadata-content');
                if (firstVideo && firstVideo.href) {
                    clearInterval(intv);
                    window.sessionStorage.removeItem('carhud_auto_play');
                    triggerSyntheticClick(firstVideo);
                    setTimeout(function() {
                        if (window.location.pathname.indexOf('/watch') === -1) {
                            try { firstVideo.click(); } catch(e) {}
                        }
                    }, 200);
                    setTimeout(function() {
                        if (window.location.pathname.indexOf('/watch') === -1 && firstVideo.href) {
                            window.location.href = firstVideo.href;
                        }
                    }, 500);
                }
                if (++attempts > 40) { clearInterval(intv); window.sessionStorage.removeItem('carhud_auto_play'); }
            }, 60);
        }

        function ensureAudio() {
            try {
                var targetVol = window.__carhudDuckingVolume !== undefined ? window.__carhudDuckingVolume : 1.0;
                var videos = document.querySelectorAll('video');
                var isAd = isPlayerShowingAd();
                for (var i = 0; i < videos.length; i++) {
                    var v = videos[i];
                    if (!isAd) {
                        if (v.muted) v.muted = false;
                        if (Math.abs(v.volume - targetVol) > 0.05) v.volume = targetVol;
                        if (v.playbackRate !== 1.0) v.playbackRate = 1.0;
                    }
                }
            } catch(e) {}
        }

        function extractMetadata() {
            try {
                var title = '';
                var metaOg = document.querySelector('meta[property="og:title"]');
                if (metaOg && metaOg.content && metaOg.content.trim().length > 0) {
                    title = metaOg.content.trim();
                }
                if (!title) {
                    var metaTitle = document.querySelector('meta[name="title"]');
                    if (metaTitle && metaTitle.content && metaTitle.content.trim().length > 0) {
                        title = metaTitle.content.trim();
                    }
                }
                if (!title) {
                    var h1 = document.querySelector('h1.slim-video-information-title, ytm-slim-video-information-renderer h1, .video-details h2, .ytm-item-section-renderer h3, h2.title, [class*="video-information-title"]');
                    if (h1 && (h1.textContent || h1.innerText)) {
                        title = (h1.textContent || h1.innerText).trim();
                    }
                }
                if (!title) {
                    var pt = document.title || '';
                    title = pt.replace(' - YouTube', '').replace('YouTube', '').trim();
                }

                var artist = 'YouTube';
                var channel = document.querySelector('.slim-owner-channel-name, ytm-compact-channel-renderer .compact-media-item-headline, .owner-channel-name, [class*="owner-channel-name"]');
                if (channel && (channel.textContent || channel.innerText)) {
                    artist = (channel.textContent || channel.innerText).trim();
                } else {
                    var metaAuthor = document.querySelector('link[itemprop="name"]');
                    if (metaAuthor && metaAuthor.getAttribute('content')) {
                        artist = metaAuthor.getAttribute('content').trim();
                    }
                }

                var thumbUrl = '';
                // 1. Direct video ID from URL (100% reliable for YouTube videos & shorts)
                var vMatch = window.location.href.match(/[?&]v=([a-zA-Z0-9_-]{11})/) || window.location.href.match(/\/shorts\/([a-zA-Z0-9_-]{11})/);
                if (vMatch && vMatch[1]) {
                    thumbUrl = 'https://i.ytimg.com/vi/' + vMatch[1] + '/hqdefault.jpg';
                }
                if (!thumbUrl) {
                    var metaThumb = document.querySelector('meta[property="og:image"]');
                    if (metaThumb && metaThumb.content && metaThumb.content.trim().length > 0 && !metaThumb.content.includes('yt_1200') && !metaThumb.content.includes('desktop/')) {
                        thumbUrl = metaThumb.content.trim();
                    }
                }
                if (!thumbUrl) {
                    var linkThumb = document.querySelector('link[rel="image_src"]');
                    if (linkThumb && linkThumb.href && linkThumb.href.trim().length > 0 && !linkThumb.href.includes('yt_1200')) {
                        thumbUrl = linkThumb.href.trim();
                    }
                }
                if (!thumbUrl) {
                    var vPoster = document.querySelector('video[poster]');
                    if (vPoster && vPoster.poster) {
                        thumbUrl = vPoster.poster.trim();
                    }
                }

                if (title && title.length > 0 && window.AndroidVoice && window.AndroidVoice.onTrackChanged) {
                    if (window.__carhudLastReportedTitle !== title || window.__carhudLastReportedArtist !== artist || window.__carhudLastReportedThumb !== thumbUrl) {
                        window.__carhudLastReportedTitle = title;
                        window.__carhudLastReportedArtist = artist;
                        window.__carhudLastReportedThumb = thumbUrl;
                        try {
                            window.AndroidVoice.onTrackChanged(title, artist, thumbUrl);
                        } catch(err) {
                            window.AndroidVoice.onTrackChanged(title, artist);
                        }
                    }
                }
            } catch(e) {}
        }


        function formatDuration(sec) {
            if (isNaN(sec) || !isFinite(sec) || sec < 0) return '0:00';
            var s = Math.floor(sec);
            var hrs = Math.floor(s / 3600);
            var mins = Math.floor((s % 3600) / 60);
            var secs = s % 60;
            var strSecs = (secs < 10 ? '0' : '') + secs;
            if (hrs > 0) {
                var strMins = (mins < 10 ? '0' : '') + mins;
                return hrs + ':' + strMins + ':' + strSecs;
            }
            return mins + ':' + strSecs;
        }

        function updateVideoProgress() {
            try {
                var cur = 0;
                var dur = 0;
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player && typeof player.getCurrentTime === 'function' && typeof player.getDuration === 'function') {
                    cur = Math.floor(player.getCurrentTime() || 0);
                    dur = Math.floor(player.getDuration() || 0);
                }
                if (dur <= 0) {
                    var video = document.querySelector('video');
                    if (video && video.duration && !isNaN(video.duration) && isFinite(video.duration) && video.duration > 0) {
                        cur = Math.floor(video.currentTime || 0);
                        dur = Math.floor(video.duration);
                    }
                }
                if (dur > 0) {
                    if (window.__carhudLastCur !== cur || window.__carhudLastDur !== dur) {
                        window.__carhudLastCur = cur;
                        window.__carhudLastDur = dur;
                        var formatted = formatDuration(cur) + ' / ' + formatDuration(dur);
                        if (window.AndroidVoice && window.AndroidVoice.onVideoTimeUpdate) {
                            window.AndroidVoice.onVideoTimeUpdate(cur, dur, formatted);
                        }
                    }
                } else if (window.__carhudLastCur !== 0 || window.__carhudLastDur !== 0) {
                    window.__carhudLastCur = 0;
                    window.__carhudLastDur = 0;
                    if (window.AndroidVoice && window.AndroidVoice.onVideoTimeUpdate) {
                        window.AndroidVoice.onVideoTimeUpdate(0, 0, '');
                    }
                }
            } catch(e) {}
        }

        var __carhudLastReportedState = null;
        var __carhudStateDebounceTimer = null;

        function checkActuallyPlaying() {
            try {
                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                if (player && typeof player.getPlayerState === 'function') {
                    var s = player.getPlayerState();
                    // 1: PLAYING, 3: BUFFERING (loading chunk) -> treat as playing
                    if (s === 1 || s === 3) return true;
                    if (s === 2 || s === 0) return false;
                }
                var v = getMainVideo();
                if (v && (!v.paused || v.seeking) && !v.ended) return true;
            } catch(e) {}
            return false;
        }

        function notifyState(isPlaying) {
            try {
                if (!isPlaying) {
                    if (__carhudStateDebounceTimer) clearTimeout(__carhudStateDebounceTimer);
                    __carhudStateDebounceTimer = setTimeout(function() {
                        __carhudStateDebounceTimer = null;
                        if (!checkActuallyPlaying() && !window.__carhudStartingPlayback) {
                            if (__carhudLastReportedState !== false) {
                                __carhudLastReportedState = false;
                                if (window.AndroidVoice && window.AndroidVoice.onPlaybackStateChanged) {
                                    window.AndroidVoice.onPlaybackStateChanged(false);
                                }
                                extractMetadata();
                                updateVideoProgress();
                            }
                        }
                    }, 3000);
                } else {
                    if (__carhudStateDebounceTimer) {
                        clearTimeout(__carhudStateDebounceTimer);
                        __carhudStateDebounceTimer = null;
                    }
                    if (__carhudLastReportedState !== true) {
                        __carhudLastReportedState = true;
                        if (window.AndroidVoice && window.AndroidVoice.onPlaybackStateChanged) {
                            window.AndroidVoice.onPlaybackStateChanged(true);
                        }
                        extractMetadata();
                        updateVideoProgress();
                    }
                }
            } catch(e) {}
        }

        function isSearchTarget(target) {
            if (!target) return false;
            if (target.tagName === 'INPUT') return true;
            if (target.closest('input, #search, ytd-searchbox, ytm-searchbox, .searchbox-input, input[name="search_query"], [aria-label*="Search"], [aria-label*="Tìm kiếm"], [aria-label*="search"], [aria-label*="Tìm kiếm"], .header-bar-icon[aria-label*="Tìm"]')) return true;
            return false;
        }

        document.addEventListener('click', function(e) {
            var target = e.target;
            if (!target) return;
            
            var mic = target.closest('button[aria-label*="giọng nói"], button[aria-label*="voice"], button[aria-label*="mic"], .search-box-mic, ytm-search-box-mic, [class*="voice-search"]');
            if (mic) {
                e.preventDefault();
                e.stopPropagation();
                if (window.AndroidVoice && window.AndroidVoice.startListening) {
                    window.AndroidVoice.startListening();
                }
                return;
            }

            if (isSearchTarget(target)) {
                e.preventDefault();
                e.stopPropagation();
                if (target.tagName === 'INPUT' || typeof target.blur === 'function') target.blur();
                if (window.AndroidVoice && window.AndroidVoice.openSearchKeyboard) {
                    window.AndroidVoice.openSearchKeyboard();
                }
            }
        }, true);

        document.addEventListener('focusin', function(e) {
            var target = e.target;
            if (!target) return;
            if (isSearchTarget(target)) {
                if (typeof target.blur === 'function') target.blur();
                if (window.AndroidVoice && window.AndroidVoice.openSearchKeyboard) {
                    window.AndroidVoice.openSearchKeyboard();
                }
            }
        }, true);

        function schedule() {
            if (window.__carhudPending) return;
            window.__carhudPending = setTimeout(function() {
                window.__carhudPending = 0;
                scanVideos();
                handleWatchPage();
                killYouTubeAds();
                ensureAudio();
                extractMetadata();
            }, 750);
        }

        scanVideos();
        observePlayer();
        handleWatchPage();
        killYouTubeAds();
        ensureAudio();
        extractMetadata();

        if (!window.__carhudObserver) {
            window.__carhudObserver = new MutationObserver(schedule);
            var watchTarget = document.getElementById('player') || document.getElementById('movie_player') || document.body;
            window.__carhudObserver.observe(watchTarget, { childList: true, subtree: false });
        }
        if (!window.__carhudTimer) {
            window.__carhudTimer = setInterval(function() {
                scanVideos();
                killYouTubeAds();
                ensureAudio();

                // Hide shorts tab in bottom navigation bar
                var tabs = document.querySelectorAll('.pivot-bar-item-tab, ytm-pivot-bar-item-renderer');
                for (var i = 0; i < tabs.length; i++) {
                    var text = tabs[i].innerText || tabs[i].textContent || '';
                    if (text.indexOf('Short') > -1) {
                        tabs[i].style.setProperty('display', 'none', 'important');
                        tabs[i].style.width = '0px';
                    }
                }
                updateVideoProgress();
                extractMetadata();
            }, 1200);
        }

        window.addEventListener('yt-navigate-finish', schedule);
        window.addEventListener('yt-page-data-updated', schedule);
        window.addEventListener('popstate', schedule);
    } catch(e) {}
})();
""".trimIndent()

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("googlevideo.com") ||
            lower.contains("/videoplayback") ||
            lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains("youtubei/v1/player") ||
            lower.contains("youtubei/v1/next")) {
            return false
        }
        return lower.contains("doubleclick.net") ||
               lower.contains("googlesyndication.com") ||
               lower.contains("googleads.g.doubleclick.net") ||
               lower.contains("pagead2.googlesyndication.com") ||
               lower.contains("googleadservices.com") ||
               lower.contains("/pagead/") ||
               lower.contains("/api/stats/ads") ||
               lower.contains("youtube.com/pagead/") ||
               lower.contains("youtube.com/api/stats/ads") ||
               lower.contains("adservice.google.") ||
               lower.contains("ad.doubleclick.net") ||
               lower.contains("static.doubleclick.net") ||
               lower.contains("/ptracking") ||
               lower.contains("adclick") ||
               lower.contains("&ad_type=") ||
               lower.contains("&adformat=")
    }

    fun createEmptyResponse(origin: String? = null): WebResourceResponse {
        val effectiveOrigin = if (!origin.isNullOrBlank() && origin != "null") origin else "https://m.youtube.com"
        val headers = HashMap<String, String>().apply {
            put("Access-Control-Allow-Origin", effectiveOrigin)
            put("Access-Control-Allow-Credentials", "true")
            put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
            put("Access-Control-Allow-Headers", "*")
        }
        val emptyJson = "{}".toByteArray(Charsets.UTF_8)
        return WebResourceResponse("application/json", "UTF-8", 200, "OK", headers, ByteArrayInputStream(emptyJson))
    }

    fun createEmptyResponse(): WebResourceResponse = createEmptyResponse(null)

    fun requestAudioFocus(context: Context) {
        // Safe no-op: Chromium WebView natively requests and holds AudioFocus.
        // Calling am.requestAudioFocus here would steal focus from Chromium and cause playback to pause.
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applyUltraPerformance(web: WebView) {
        web.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isNestedScrollingEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    offscreenPreRaster = true
                }
                @Suppress("DEPRECATION")
                setRenderPriority(WebSettings.RenderPriority.HIGH)
                @Suppress("DEPRECATION")
                setEnableSmoothTransition(true)

                val ua = userAgentString
                userAgentString = ua.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+\\s?"), "")
            }

            val isDay = SettingsActivity.resolveIsDay(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    settings.isAlgorithmicDarkeningAllowed = !isDay
                } catch (e: Exception) {}
            } else {
                @Suppress("DEPRECATION")
                try {
                    settings.forceDark = if (isDay) WebSettings.FORCE_DARK_OFF else WebSettings.FORCE_DARK_ON
                } catch (e: Exception) {}
            }
            setBackgroundColor(if (isDay) Color.WHITE else Color.BLACK)

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cm.setAcceptThirdPartyCookies(this, true)
            }
            try {
                val f6Val = if (isDay) "0" else "400"
                cm.setCookie("https://youtube.com", "PREF=f6=$f6Val&f5=30000; domain=.youtube.com; path=/")
                cm.setCookie("https://m.youtube.com", "PREF=f6=$f6Val&f5=30000; domain=.youtube.com; path=/")
            } catch (e: Exception) {}
        }
    }

    fun applyUniversalWebTheme(view: WebView?, isDay: Boolean? = null) {
        if (view == null) return
        val effectiveIsDay = isDay ?: SettingsActivity.resolveIsDay(view.context)
        view.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        view.settings.isAlgorithmicDarkeningAllowed = !effectiveIsDay
                    } catch (e: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    try {
                        view.settings.forceDark = if (effectiveIsDay) WebSettings.FORCE_DARK_OFF else WebSettings.FORCE_DARK_ON
                    } catch (e: Exception) {}
                }
                view.setBackgroundColor(if (effectiveIsDay) Color.WHITE else Color.BLACK)

                val js = """
                    (function() {
                        try {
                            window.__carhudCurrentIsDay = $effectiveIsDay;
                            var style = document.getElementById('carhud-universal-theme');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'carhud-universal-theme';
                                (document.head || document.documentElement).appendChild(style);
                            }
                            style.textContent = ':root, html, body { color-scheme: ${if (effectiveIsDay) "light" else "dark"} !important; }';
                        } catch (e) {}
                    })();
                """.trimIndent()
                view.evaluateJavascript(js, null)

                val url = view.url ?: ""
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    applyTheme(view, effectiveIsDay)
                }
            } catch (e: Exception) {}
        }
    }

    fun applyTheme(view: WebView?, isDay: Boolean) {
        if (view == null) return
        view.post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    try {
                        view.settings.isAlgorithmicDarkeningAllowed = !isDay
                    } catch (e: Exception) {}
                } else {
                    @Suppress("DEPRECATION")
                    try {
                        view.settings.forceDark = if (isDay) WebSettings.FORCE_DARK_OFF else WebSettings.FORCE_DARK_ON
                    } catch (e: Exception) {}
                }
                view.setBackgroundColor(if (isDay) Color.WHITE else Color.BLACK)

                try {
                    val cm = CookieManager.getInstance()
                    val f6Val = if (isDay) "0" else "400"
                    cm.setCookie("https://youtube.com", "PREF=f6=$f6Val&f5=30000; domain=.youtube.com; path=/")
                    cm.setCookie("https://m.youtube.com", "PREF=f6=$f6Val&f5=30000; domain=.youtube.com; path=/")
                } catch (e: Exception) {}

                val js = """
                    (function() {
                        try {
                            window.__carhudCurrentIsDay = $isDay;
                            var isDay = $isDay;
                            var themeStyle = document.getElementById('carhud-theme');
                            if (!themeStyle) {
                                themeStyle = document.createElement('style');
                                themeStyle.id = 'carhud-theme';
                                (document.head || document.documentElement).appendChild(themeStyle);
                            var isAutoScreen = document.documentElement.classList.contains('carhud-auto-screen');
                            if (isDay && !isAutoScreen) {
                                document.documentElement.classList.remove('dark');
                                document.documentElement.setAttribute('dark', 'false');
                                if (document.body) {
                                    document.body.classList.remove('dark');
                                    document.body.setAttribute('dark', 'false');
                                }
                                var app = document.querySelector('ytm-app');
                                if (app) {
                                    app.classList.remove('dark');
                                    app.setAttribute('dark', 'false');
                                }
                                themeStyle.textContent = 
                                    ':root, html, body { --yt-spec-text-primary: #0f0f0f !important; --yt-spec-text-secondary: #606060 !important; --yt-spec-general-background-a: #ffffff !important; --yt-spec-general-background-b: #f8f8f8 !important; --yt-spec-base-background: #ffffff !important; --yt-spec-raised-background: #f2f2f2 !important; --yt-spec-icon-active-other: #0f0f0f !important; --yt-spec-icon-inactive: #606060 !important; --yt-spec-brand-icon-active: #0f0f0f !important; --yt-spec-static-brand-white: #ffffff !important; --ytm-theme-primary-color: #0f0f0f !important; color-scheme: light !important; } ' +
                                    'html, body, #app, ytm-app, .page-container { background: #ffffff !important; background-color: #ffffff !important; color: #0f0f0f !important; } ' +
                                    'ytm-mobile-topbar-renderer, .mobile-topbar-header { background: #ffffff !important; background-color: #ffffff !important; border-bottom: 1px solid #e5e5e5 !important; } ' +
                                    '.compact-media-item-headline, .media-item-headline, .video-title, h3, h3.title, h4, .c3-headline, [class*="media-item-headline"], [class*="video-title"], .details h3, .compact-media-item-metadata-content h3, .compact-media-item-metadata-content, .compact-media-item-headline *, .media-item-headline *, .video-title *, h3 *, [class*="media-item-headline"] *, .c3-headline *, ytm-media-item h3, ytm-compact-video-renderer h3 { color: #0f0f0f !important; -webkit-text-fill-color: #0f0f0f !important; } ' +
                                    '.compact-media-item-byline, .media-item-byline, .small-text, [class*="byline"], [class*="channel-name"], [class*="metadata"], .subhead, .compact-media-item-byline *, .media-item-byline *, [class*="byline"] *, [class*="channel-name"] *, [class*="metadata"] *, ytm-badge-and-byline-renderer * { color: #606060 !important; -webkit-text-fill-color: #606060 !important; } ' +
                                    '.carhud-auto-screen, .carhud-auto-screen body, .carhud-auto-screen #app, .carhud-auto-screen ytm-app { background: #000000 !important; color: #ffffff !important; } ' +
                                    '.carhud-auto-screen .compact-media-item-headline, .carhud-auto-screen .media-item-headline, .carhud-auto-screen .video-title, .carhud-auto-screen h3, .carhud-auto-screen h4 { color: #ffffff !important; -webkit-text-fill-color: #ffffff !important; } ' +
                                    '.carhud-auto-screen .compact-media-item-byline, .carhud-auto-screen .media-item-byline, .carhud-auto-screen .small-text { color: #94A3B8 !important; -webkit-text-fill-color: #94A3B8 !important; } ' +
                                    'ytm-searchbox input, input.search-input { color: #0f0f0f !important; -webkit-text-fill-color: #0f0f0f !important; background: #f2f2f2 !important; border: 1px solid #ccc !important; } ' +
                                    '.chip-cloud-chip-renderer, ytm-chip-cloud-chip-renderer { background: #f2f2f2 !important; color: #0f0f0f !important; } ' +
                                    '.chip-cloud-chip-renderer[selected], ytm-chip-cloud-chip-renderer[selected] { background: #0f0f0f !important; color: #ffffff !important; } ' +
                                    'ytm-pivot-bar-renderer { background: #ffffff !important; border-top: 1px solid #e5e5e5 !important; } ' +
                                    'ytm-pivot-bar-item-renderer { color: #606060 !important; } ' +
                                    'ytm-pivot-bar-item-renderer[aria-selected="true"] { color: #0f0f0f !important; } ' +
                                    'c3-icon, ytm-icon-button, [class*="icon-button"] { color: #0f0f0f !important; fill: #0f0f0f !important; } ' +
                                    'ytm-feed-nudge-renderer, .feed-nudge, ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-item-section-renderer { background: transparent !important; } ' +
                                    '.feed-nudge, ytm-feed-nudge-renderer { background: #f8f8f8 !important; border: 1px solid #e5e5e5 !important; border-radius: 12px !important; } ' +
                                    '.feed-nudge *, ytm-feed-nudge-renderer * { color: #0f0f0f !important; -webkit-text-fill-color: #0f0f0f !important; } ' +
                                    'ytm-search-suggestion, .search-suggestion { background: #ffffff !important; color: #0f0f0f !important; }';
                            } else {
                                document.documentElement.classList.add('dark');
                                document.documentElement.setAttribute('dark', 'true');
                                if (document.body) {
                                    document.body.classList.add('dark');
                                    document.body.setAttribute('dark', 'true');
                                }
                                var app = document.querySelector('ytm-app');
                                if (app) {
                                    app.classList.add('dark');
                                    app.setAttribute('dark', 'true');
                                }
                                themeStyle.textContent = 
                                    ':root, html, body { --yt-spec-text-primary: #ffffff !important; --yt-spec-text-secondary: #cccccc !important; --yt-spec-general-background-a: #000000 !important; --yt-spec-general-background-b: #000000 !important; --yt-spec-base-background: #000000 !important; --yt-spec-raised-background: #121212 !important; --yt-spec-icon-active-other: #ffffff !important; --yt-spec-icon-inactive: #aaaaaa !important; --yt-spec-brand-icon-active: #ffffff !important; --yt-spec-static-brand-white: #ffffff !important; --ytm-theme-primary-color: #ffffff !important; color-scheme: dark !important; } ' +
                                    'html, body, #app, ytm-app, .page-container { background: #000000 !important; background-color: #000000 !important; color: #ffffff !important; } ' +
                                    'ytm-mobile-topbar-renderer, .mobile-topbar-header { background: #000000 !important; background-color: #000000 !important; border-bottom: 1px solid #222222 !important; } ' +
                                    '.compact-media-item-headline, .media-item-headline, .video-title, h3, h3.title, h4, .c3-headline, [class*="media-item-headline"], [class*="video-title"], .details h3, .compact-media-item-metadata-content h3, .compact-media-item-metadata-content, .compact-media-item-headline *, .media-item-headline *, .video-title *, h3 *, [class*="media-item-headline"] *, .c3-headline *, ytm-media-item h3, ytm-compact-video-renderer h3 { color: #ffffff !important; -webkit-text-fill-color: #ffffff !important; } ' +
                                    '.compact-media-item-byline, .media-item-byline, .small-text, [class*="byline"], [class*="channel-name"], [class*="metadata"], .subhead, .compact-media-item-byline *, .media-item-byline *, [class*="byline"] *, [class*="channel-name"] *, [class*="metadata"] *, ytm-badge-and-byline-renderer * { color: #cccccc !important; -webkit-text-fill-color: #cccccc !important; } ' +
                                    'ytm-searchbox input, input.search-input { color: #ffffff !important; -webkit-text-fill-color: #ffffff !important; background: #222222 !important; border: 1px solid #333 !important; } ' +
                                    '.chip-cloud-chip-renderer, ytm-chip-cloud-chip-renderer { background: #222222 !important; color: #ffffff !important; } ' +
                                    '.chip-cloud-chip-renderer[selected], ytm-chip-cloud-chip-renderer[selected] { background: #ffffff !important; color: #000000 !important; } ' +
                                    'ytm-pivot-bar-renderer { background: #000000 !important; border-top: 1px solid #222222 !important; } ' +
                                    'ytm-pivot-bar-item-renderer { color: #aaaaaa !important; } ' +
                                    'ytm-pivot-bar-item-renderer[aria-selected="true"] { color: #ffffff !important; } ' +
                                    'c3-icon, ytm-icon-button, [class*="icon-button"] { color: #ffffff !important; fill: #ffffff !important; } ' +
                                    'ytm-feed-nudge-renderer, .feed-nudge, ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-item-section-renderer { background: transparent !important; } ' +
                                    '.feed-nudge, ytm-feed-nudge-renderer { background: #181818 !important; border: 1px solid #282828 !important; border-radius: 12px !important; } ' +
                                    '.feed-nudge *, ytm-feed-nudge-renderer * { color: #ffffff !important; -webkit-text-fill-color: #ffffff !important; } ' +
                                    'ytm-search-suggestion, .search-suggestion { background: #000000 !important; color: #ffffff !important; }';
                            }
                        } catch(e) {}
                    })();
                """.trimIndent()
                view.evaluateJavascript(js, null)
            } catch (e: Exception) {}
        }
    }

    fun inject(
        view: WebView,
        isUltrawide: Boolean = false,
        isPortrait: Boolean = false,
        carWidth: Int = 0,
        carHeight: Int = 0,
        carDpi: Int = 160,
        phoneDpi: Int = 0,
        aspectRatio: Float = 1.777f
    ) {
        val currentUrl = view.url ?: ""
        val host = android.net.Uri.parse(currentUrl).host.orEmpty().lowercase()
        if (host != "youtube.com" && !host.endsWith(".youtube.com") && host != "youtu.be") return
        // IPTV owns its media state and HLS recovery. YouTube's pause override
        // and auto-resume script must never be installed in the IPTV document.
        if (currentUrl.contains("iptv_player.html")) return
        try {
            trackVideoHistory(view)
            view.evaluateJavascript(JS_CLEANUP_AND_ADBLOCK, null)
            val isDay = SettingsActivity.resolveIsDay(view.context)
            applyUniversalWebTheme(view, isDay)
            val effectivePhoneDpi = if (phoneDpi > 0) phoneDpi else view.context.applicationContext.resources.displayMetrics.densityDpi
            val screenInitJs = """
                (function() {
                    try {
                        var carW = $carWidth;
                        var carH = $carHeight;
                        var cDpi = $carDpi > 0 ? $carDpi : 160;
                        var pDpi = $effectivePhoneDpi > 0 ? $effectivePhoneDpi : 440;
                        var isUltra = $isUltrawide;
                        var isPort = $isPortrait;
                        var aspect = $aspectRatio;

                        var isAutoMode = (window.AndroidVoice && window.AndroidVoice.isAuto && window.AndroidVoice.isAuto());
                        var targetWidth;
                        if (!isAutoMode) {
                            targetWidth = 'device-width';
                        } else if (isUltra) {
                            targetWidth = 840;
                        } else if (isPort) {
                            targetWidth = 500;
                        } else {
                            targetWidth = 720;
                        }

                        function applyCarViewport() {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            var contentStr = (targetWidth === 'device-width')
                                ? 'width=device-width, initial-scale=1.0, maximum-scale=2.0, user-scalable=yes'
                                : 'width=' + targetWidth + ', user-scalable=yes';
                            if (meta.getAttribute('content') !== contentStr) {
                                meta.setAttribute('content', contentStr);
                            }
                        }

                        var isFordSync = (isPort && (aspect <= 0.82 || carH >= 1000));
                        var isVinfast = (aspect >= 1.7 && carW >= 1200);

                        applyCarViewport();

                        var html = document.documentElement;
                        html.classList.remove('carhud-ultrawide', 'carhud-portrait', 'carhud-landscape', 'carhud-auto-screen', 'carhud-phone', 'carhud-ford-sync', 'carhud-vinfast');
                        if (isAutoMode) {
                            html.classList.add('carhud-auto-screen');
                            if (isFordSync) html.classList.add('carhud-ford-sync');
                            if (isVinfast) html.classList.add('carhud-vinfast');
                            if (isUltra) {
                                html.classList.add('carhud-ultrawide');
                            } else if (isPort) {
                                html.classList.add('carhud-portrait');
                            } else {
                                html.classList.add('carhud-landscape');
                            }
                        } else {
                            html.classList.add('carhud-phone');
                        }
                        if (carW > 0 && carH > 0) {
                            html.style.setProperty('--carhud-screen-w', carW + 'px');
                            html.style.setProperty('--carhud-screen-h', carH + 'px');
                            html.style.setProperty('--carhud-aspect-ratio', aspect);
                        }
                    } catch(e) {}
                })();
            """.trimIndent()
            view.evaluateJavascript(screenInitJs, null)
            view.evaluateJavascript(JS_CAR_SEARCH_HOME, null)
            view.evaluateJavascript(
                """
                (function() {
                    var s = document.documentElement.style;
                    s.setProperty('--carhud-home-bg', '${if (isDay) "#ffffff" else "#0f0f0f"}');
                    s.setProperty('--carhud-home-fg', '${if (isDay) "#0f0f0f" else "#ffffff"}');
                    s.setProperty('--carhud-home-chip', '${if (isDay) "#f2f2f2" else "#272727"}');
                    s.setProperty('--carhud-home-card', '${if (isDay) "#ffffff" else "#212121"}');
                    s.setProperty('--carhud-home-muted', '${if (isDay) "#606060" else "#aaaaaa"}');
                })();
                """.trimIndent(), null
            )
        } catch (e: Exception) {}
    }

    private var lastResumeTime = 0L

    fun resumePlayback(view: WebView?) {
        val now = System.currentTimeMillis()
        if (now - lastResumeTime < 1500L) return
        lastResumeTime = now
        try {
            view?.post {
                view.onResume()
                view.resumeTimers()
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(false);
                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            var video = document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
                            
                            if (player && typeof player.getPlayerState === 'function') {
                                var s = player.getPlayerState();
                                if (s === 1 || s === 3) return; // 1: playing, 3: buffering - do not disrupt!
                            }
                            if (video && !video.paused && video.currentTime > 0) return; // Already actively playing

                            if (window.__carhudStartingPlayback) return;
                            window.__carhudStartingPlayback = true;
                            setTimeout(function() { window.__carhudStartingPlayback = false; }, 2000);

                            if (player && typeof player.playVideo === 'function') {
                                try { player.playVideo(); } catch(e) {}
                            } else if (video && video.paused) {
                                try {
                                    video.muted = false;
                                    if (video.volume < 0.5) video.volume = 1.0;
                                    video.play().catch(function() {});
                                } catch(e) {}
                            }
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            }
        } catch (e: Exception) {}
    }

    fun playFirstAvailableVideo(view: WebView?) {
        try {
            view?.post {
                view.onResume()
                view.resumeTimers()
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(false);
                            var v = document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
                            if (v && !v.paused) return true;

                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (player && typeof player.playVideo === 'function') {
                                try { player.playVideo(); } catch(e) {}
                            }
                            if (v) {
                                v.muted = false;
                                if (v.volume < 0.5) v.volume = 1.0;
                                v.play();
                                return true;
                            }
                            var first = document.querySelector('ytm-video-with-context-renderer a, ytm-compact-video-renderer a, a.media-item-thumbnail-container, a[href*="watch"]');
                            if (first) {
                                first.click();
                                return true;
                            }
                            var btn = document.querySelector('.ytp-large-play-button, .ytp-cued-thumbnail-overlay');
                            if (btn) {
                                btn.click();
                                return true;
                            }
                        } catch(e) {}
                        return false;
                    })();
                    """.trimIndent(),
                    null
                )
            }
        } catch (e: Exception) {}
    }

    fun pausePlayback(view: WebView?) {
        try {
            view?.post {
                view.evaluateJavascript(
                    """
                    (function() {
                        try {
                            if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(true);
                            var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                            if (player && typeof player.pauseVideo === 'function') {
                                try { player.pauseVideo(); } catch(e) {}
                            }
                            var video = document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');
                            if (video && !video.paused) {
                                video.pause();
                            }
                        } catch(e) {}
                    })();
                    """.trimIndent(),
                    null
                )
            }
        } catch (e: Exception) {}
    }

    fun play(view: WebView?) {
        togglePlayPause(view, forcePlay = true)
    }

    fun pause(view: WebView?) {
        togglePlayPause(view, forcePlay = false)
    }

    fun togglePlayPause(view: WebView?, forcePlay: Boolean? = null) {
        try {
            val forceTrue = if (forcePlay == true) "true" else "false"
            val forceFalse = if (forcePlay == false) "true" else "false"
            
            view?.evaluateJavascript(
                """
                (function() {
                    try {
                        var forceTrue = $forceTrue;
                        var forceFalse = $forceFalse;
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        var v = document.querySelector('#movie_player video, .html5-video-player video') || document.querySelector('video');

                        function safePlay(video) {
                            if (!video) return;
                            video.muted = false;
                            if (video.volume < 0.5) video.volume = 1.0;
                            var p = video.play();
                            if (p !== undefined) {
                                p.catch(function() {
                                    video.muted = true;
                                    video.play().then(function() {
                                        setTimeout(function() { video.muted = false; }, 150);
                                    }).catch(function() {});
                                });
                            }
                        }

                        if (forceTrue) {
                            if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(false);
                            if (player && typeof player.playVideo === 'function') {
                                try { player.playVideo(); } catch(e) { safePlay(v); }
                            } else {
                                safePlay(v);
                            }
                            return true;
                        } else if (forceFalse) {
                            if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(true);
                            if (player && typeof player.pauseVideo === 'function') {
                                try { player.pauseVideo(); } catch(e) {}
                            }
                            if (v) {
                                v.pause();
                            }
                            return false;
                        } else {
                            if (player && typeof player.getPlayerState === 'function') {
                                var s = player.getPlayerState();
                                if (s === 1) { // Playing -> Pause
                                    if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(true);
                                    player.pauseVideo();
                                    if (v) v.pause();
                                    return false;
                                } else { // Paused / cued -> Play
                                    if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(false);
                                    try { player.playVideo(); } catch(e) { safePlay(v); }
                                    return true;
                                }
                            }
                            if (v) {
                                if (v.paused) {
                                    if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(false);
                                    safePlay(v);
                                    return true;
                                } else {
                                    if (window.__carhudSetUserPaused) window.__carhudSetUserPaused(true);
                                    v.pause();
                                    return false;
                                }
                            }
                        }
                    } catch(e) {}
                    return false;
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }


    fun toggleAutoplay(view: WebView?, callback: ((Boolean) -> Unit)? = null) {
        val js = """
            (function(){
              try {
                var btn = document.querySelector('.ytp-autonav-toggle-button, button[data-tooltip-target-id="ytp-autonav-toggle-button"], ytm-autonav-toggle button, button[aria-label*="Autoplay"], button[aria-label*="Tự động phát"]');
                if (btn) {
                  btn.click();
                  var checked = btn.getAttribute('aria-checked');
                  if (checked !== null) return checked === 'true';
                  var p = document.querySelector('.ytp-autonav-toggle-button');
                  return !!(p && p.getAttribute('aria-checked') === 'true');
                }
                return false;
              } catch(e) { return false; }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js) { result ->
            callback?.invoke(result == "true")
        } ?: callback?.invoke(false)
    }

    fun openQuickSettings(view: WebView?) {
        view?.evaluateJavascript("""
            (function(){
              try {
                var btn = document.querySelector('.ytp-settings-button, button[aria-label*="Settings"], button[aria-label*="Cài đặt"]');
                if (btn) { btn.click(); return true; }
                var more = document.querySelector('button[aria-label*="More actions"], button[aria-label*="Thao tác khác"]');
                if (more) { more.click(); return true; }
              } catch(e) {}
              return false;
            })();
        """.trimIndent(), null)
    }

    fun playNext(view: WebView?) {
        try {
            val url = view?.url ?: ""
            if (url.contains("iptv_player.html") || url.contains("iptv")) {
                view?.evaluateJavascript("if(window.nextChannel) window.nextChannel();", null)
                return
            }
            view?.evaluateJavascript(
                """
                (function() {
                    var nextBtn = document.querySelector('.ytp-next-button, button[aria-label*="tiếp theo"], button[aria-label*="Next"]');
                    if (nextBtn) {
                        nextBtn.click();
                        return;
                    }
                    var nextLink = document.querySelector('ytm-video-with-context-renderer a, ytm-compact-video-renderer a, a.media-item-thumbnail-container');
                    if (nextLink) {
                        nextLink.click();
                        return;
                    }
                    var v = document.querySelector('video');
                    if (v && v.duration && !isNaN(v.duration)) {
                        v.currentTime = v.duration - 0.5;
                        v.muted = false;
                        v.volume = 1.0;
                        v.play();
                    }
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun playPrevious(view: WebView?) {
        try {
            val url = view?.url ?: ""
            if (url.contains("iptv_player.html") || url.contains("iptv")) {
                view?.evaluateJavascript("if(window.prevChannel) window.prevChannel();", null)
                return
            }
            view?.evaluateJavascript(
                """
                (function() {
                    var prevBtn = document.querySelector('.ytp-prev-button');
                    if (prevBtn && !prevBtn.disabled && prevBtn.getAttribute('aria-disabled') !== 'true' && prevBtn.getClientRects().length) {
                        prevBtn.click();
                        return;
                    }
                    if (window.__carhudPreviousVideo && window.__carhudPreviousVideo()) return;
                    var v = document.querySelector('video');
                    if (v) {
                        v.currentTime = 0;
                    }
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun toggleFullscreen(view: WebView?) {
        try {
            view?.evaluateJavascript(
                """
                (function() {
                    var html = document.documentElement;
                    var body = document.body;
                    var isForce = html.classList.contains('carhud-force-fullscreen');
                    if (isForce || window.isDashboardMode) {
                        html.classList.remove('carhud-force-fullscreen');
                        body.classList.remove('carhud-force-fullscreen');
                        if (document.exitFullscreen) { document.exitFullscreen(); }
                        else if (document.webkitExitFullscreen) { document.webkitExitFullscreen(); }
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
                            if (v.paused) v.play();
                        }
                        return;
                    }

                    var fsSelectors = [
                        'button.fullscreen-icon',
                        '.player-control-fullscreen-icon',
                        'button.ytp-fullscreen-button',
                        '.ytp-fullscreen-button',
                        'button[aria-label*="Toàn màn hình"]',
                        'button[aria-label*="Toàn"]',
                        'button[aria-label*="Fullscreen"]',
                        'button[aria-label*="fullscreen"]',
                        'button[class*="fullscreen"]',
                        '.icon-fullscreen'
                    ];
                    var fsBtn = document.querySelector(fsSelectors.join(','));
                    if (fsBtn && (fsBtn.offsetWidth > 0 || fsBtn.offsetHeight > 0)) {
                        fsBtn.click();
                    } else {
                        html.classList.add('carhud-force-fullscreen');
                        body.classList.add('carhud-force-fullscreen');
                        var v = document.querySelector('video');
                        if (v) {
                            if (v.requestFullscreen) { v.requestFullscreen(); }
                            else if (v.webkitRequestFullscreen) { v.webkitRequestFullscreen(); }
                            else if (v.webkitEnterFullscreen) { v.webkitEnterFullscreen(); }
                        }
                    }
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun search(view: WebView?, query: String) {
        if (view == null) return
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return

        view.post {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val host = if (view.url?.contains("www.youtube.com") == true) "https://www.youtube.com" else "https://m.youtube.com"
                val targetUrl = "$host/results?search_query=$encoded"

                val curUrl = view.url.orEmpty()
                val isAlreadyOnYouTube = curUrl.contains("youtube.com") || curUrl.contains("youtu.be")

                if (isAlreadyOnYouTube) {
                    val fastNavJs = """
                        (function() {
                            try {
                                var query = "${cleanQuery.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")}";
                                var encoded = encodeURIComponent(query);
                                var targetPath = '/results?search_query=' + encoded;

                                // 1. Try filling the existing YouTube search input and submitting form (triggers ultra-fast AJAX search)
                                var searchInput = document.querySelector('input.searchbox-input, input[name="search_query"], input#search, ytm-searchbox input');
                                var form = searchInput ? (searchInput.form || searchInput.closest('form')) : null;
                                if (searchInput && form) {
                                    searchInput.value = query;
                                    searchInput.dispatchEvent(new Event('input', { bubbles: true }));
                                    searchInput.dispatchEvent(new Event('change', { bubbles: true }));
                                    if (typeof form.requestSubmit === 'function') {
                                        form.requestSubmit();
                                        return 'fast_form';
                                    } else if (typeof form.submit === 'function') {
                                        form.submit();
                                        return 'fast_form';
                                    }
                                }

                                // 2. If already on /results, simply update query string directly
                                if (window.location && window.location.pathname.indexOf('/results') !== -1) {
                                    window.location.search = '?search_query=' + encoded;
                                    return 'fast_search';
                                }

                                // 3. Fast SPA location assign without destroying browser instance
                                window.location.assign(targetPath);
                                return 'fast_assign';
                            } catch(e) {
                                return 'fallback';
                            }
                        })();
                    """.trimIndent()

                    view.evaluateJavascript(fastNavJs) { result ->
                        val r = result?.replace("\"", "")?.trim() ?: ""
                        if (r == "fallback" || r == "error" || r == "null" || r.isEmpty()) {
                            view.loadUrl(targetUrl)
                        }
                    }
                    view.postDelayed({
                        try {
                            view.evaluateJavascript("try { if (typeof ensureSearchResultsTitles === 'function') ensureSearchResultsTitles(); } catch(e) {}", null)
                        } catch (e: Exception) {}
                    }, 600L)
                    view.postDelayed({
                        try {
                            view.evaluateJavascript("try { if (typeof ensureSearchResultsTitles === 'function') ensureSearchResultsTitles(); } catch(e) {}", null)
                        } catch (e: Exception) {}
                    }, 1400L)
                } else {
                    view.loadUrl(targetUrl)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setDuckingVolume(view: WebView?, volume: Float) {
        try {
            view?.evaluateJavascript(
                "try { window.__carhudDuckingVolume = $volume; var v = document.querySelector('video'); if (v) { v.volume = $volume; } } catch(e) {}",
                null
            )
        } catch (e: Exception) {}
    }

    fun seekTo(view: WebView?, seconds: Long) {
        try {
            view?.evaluateJavascript(
                """
                (function() {
                    try {
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        if (player && typeof player.seekTo === 'function') {
                            player.seekTo($seconds, true);
                        } else {
                            var v = document.querySelector('video');
                            if (v) { v.currentTime = $seconds; }
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun toggleShuffle(view: WebView?) {
        try {
            view?.evaluateJavascript(
                """
                (function() {
                    try {
                        var shuffleBtn = document.querySelector('.ytp-playlist-shuffle-button, button[aria-label*="Shuffle"], button[aria-label*="Trộn"], .ytm-playlist-shuffle-button');
                        if (shuffleBtn) {
                            shuffleBtn.click();
                        } else {
                            var links = document.querySelectorAll('ytm-video-with-context-renderer a, ytm-compact-video-renderer a, #items ytd-compact-video-renderer a');
                            if (links && links.length > 0) {
                                var randomIndex = Math.floor(Math.random() * links.length);
                                links[randomIndex].click();
                            }
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun toggleRepeat(view: WebView?) {
        try {
            view?.evaluateJavascript(
                """
                (function() {
                    try {
                        var repeatBtn = document.querySelector('.ytp-playlist-repeat-button, button[aria-label*="Repeat"], button[aria-label*="Lặp"], button[aria-label*="loop"]');
                        if (repeatBtn) {
                            repeatBtn.click();
                        } else {
                            var v = document.querySelector('video');
                            if (v) {
                                v.loop = !v.loop;
                            }
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    fun toggleLike(view: WebView?) {
        try {
            view?.evaluateJavascript(
                """
                (function() {
                    try {
                        var likeBtn = document.querySelector('ytm-like-button-renderer button, button[aria-label*="thích"], button[aria-label*="Like"], button[aria-label*="like"]');
                        if (likeBtn) {
                            likeBtn.click();
                        }
                    } catch(e) {}
                })();
                """.trimIndent(),
                null
            )
        } catch (e: Exception) {}
    }

    /**
     * Dynamically sets video aspect ratio and scaling mode for YouTube on Android Auto:
     * - 'fill': Stretch to fill 100% of car screen without black bars
     * - 'contain': 16:9 standard fit with original proportions
     * - 'cover': Zoom and crop edges to fill screen without distortion
     * - '4:3': Classic 4:3 TV aspect ratio
     * - '21:9': Ultrawide car display ratio
     */
    fun setVideoAspectRatio(view: WebView?, mode: String) {
        val js = """
            (function() {
                var m = '$mode';
                window.__carhud_aspect_mode = m;
                var v = document.querySelector('video');
                if (!v) return;

                v.style.setProperty('position', 'fixed', 'important');
                v.style.setProperty('top', '0', 'important');
                v.style.setProperty('left', '0', 'important');
                v.style.setProperty('right', '0', 'important');
                v.style.setProperty('bottom', '0', 'important');
                v.style.setProperty('margin', 'auto', 'important');
                v.style.setProperty('z-index', '9999', 'important');

                if (m === 'contain') {
                    v.style.setProperty('width', '100%', 'important');
                    v.style.setProperty('height', '100%', 'important');
                    v.style.setProperty('object-fit', 'contain', 'important');
                    v.style.removeProperty('aspect-ratio');
                } else if (m === 'fill') {
                    v.style.setProperty('width', '100%', 'important');
                    v.style.setProperty('height', '100%', 'important');
                    v.style.setProperty('object-fit', 'fill', 'important');
                    v.style.removeProperty('aspect-ratio');
                } else if (m === 'cover') {
                    v.style.setProperty('width', '100%', 'important');
                    v.style.setProperty('height', '100%', 'important');
                    v.style.setProperty('object-fit', 'cover', 'important');
                    v.style.removeProperty('aspect-ratio');
                } else if (m === '4:3') {
                    v.style.setProperty('height', '100%', 'important');
                    v.style.setProperty('width', 'auto', 'important');
                    v.style.setProperty('aspect-ratio', '4 / 3', 'important');
                    v.style.setProperty('object-fit', 'fill', 'important');
                } else if (m === '21:9') {
                    v.style.setProperty('width', '100%', 'important');
                    v.style.setProperty('height', 'auto', 'important');
                    v.style.setProperty('aspect-ratio', '21 / 9', 'important');
                    v.style.setProperty('object-fit', 'fill', 'important');
                }
            })();
        """.trimIndent()
        try {
            view?.evaluateJavascript(js, null)
        } catch (e: Exception) {}
    }
}


