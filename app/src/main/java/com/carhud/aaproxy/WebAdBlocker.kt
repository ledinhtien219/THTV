package com.carhud.aaproxy

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

/**
 * Conservative ad/tracker blocker for the generic Browser only.
 *
 * Network filtering blocks well-known advertising hosts while cosmetic filtering
 * hides leftover ad slots. It deliberately avoids broad substring rules such as
 * ".ad" so normal site content is not accidentally removed.
 */
object WebAdBlocker {

    private val blockedDomains = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "googletagservices.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "adsrvr.org",
        "advertising.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "smartadserver.com",
        "casalemedia.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "media.net",
        "mgid.com",
        "scorecardresearch.com",
        "quantserve.com",
        "admicro.vn",
        "adtima.vn",
        "eclick.vn",
        "ants.vn",
        "adnetwork.vn"
    )

    private val safeDomains = setOf(
        "accounts.google.com",
        "recaptcha.net",
        "gstatic.com"
    )

    fun shouldIntercept(request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        if (request.isForMainFrame) return null
        val uri = request.url ?: return null
        if (!isBlocked(uri)) return null
        return emptyResponse()
    }

    fun isBlocked(url: String): Boolean = try {
        isBlocked(Uri.parse(url))
    } catch (_: Throwable) {
        false
    }

    fun isBlocked(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return false

        if (matchesDomain(host, safeDomains)) return false

        if (host.startsWith("adservice.google.")) return true
        return matchesDomain(host, blockedDomains)
    }

    private fun matchesDomain(host: String, domains: Set<String>): Boolean {
        return domains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    fun applyCosmeticFiltering(view: WebView?, enabled: Boolean) {
        view ?: return
        view.post {
            try {
                val js = if (enabled) ENABLE_COSMETIC_JS else DISABLE_COSMETIC_JS
                view.evaluateJavascript(js, null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private val ENABLE_COSMETIC_JS = """
        (function() {
            try {
                var id = 'thtv-browser-adblock-style';
                var style = document.getElementById(id);
                if (!style) {
                    style = document.createElement('style');
                    style.id = id;
                    (document.head || document.documentElement).appendChild(style);
                }
                style.textContent = [
                    'ins.adsbygoogle',
                    '.adsbygoogle',
                    'iframe[src*="doubleclick.net"]',
                    'iframe[src*="googlesyndication.com"]',
                    'iframe[id^="google_ads_iframe"]',
                    '[id^="google_ads_iframe"]',
                    '[id^="div-gpt-ad"]',
                    '[data-ad-client]',
                    '[data-ad-slot]',
                    'amp-ad',
                    '.advertisement',
                    '.advertisement-container',
                    '.banner-ad',
                    '.sticky-ad',
                    '.floating-ad',
                    '.popup-ad',
                    '.ad-slot',
                    '.ad-container',
                    '.ad_container',
                    '[class*="ad-slot"]',
                    '[class*="ad_container"]',
                    '[class*="ad-container"]',
                    '[id*="taboola"]',
                    '.taboola',
                    '[data-widget-id^="taboola"]',
                    '.OUTBRAIN',
                    '[id*="outbrain"]'
                ].join(',') + '{display:none!important;visibility:hidden!important;opacity:0!important;pointer-events:none!important;max-height:0!important;min-height:0!important;}';

                if (!window.__thtvBrowserAdObserver) {
                    window.__thtvBrowserAdObserver = new MutationObserver(function() {
                        // CSS handles matching nodes; observing keeps dynamically
                        // inserted ad frames from flashing before style recalculation.
                        if (!document.getElementById(id) && document.documentElement) {
                            document.documentElement.appendChild(style);
                        }
                    });
                    window.__thtvBrowserAdObserver.observe(document.documentElement, {
                        childList: true,
                        subtree: true
                    });
                }
            } catch(e) {}
        })();
    """.trimIndent()

    private val DISABLE_COSMETIC_JS = """
        (function() {
            try {
                var style = document.getElementById('thtv-browser-adblock-style');
                if (style && style.parentNode) style.parentNode.removeChild(style);
                if (window.__thtvBrowserAdObserver) {
                    try { window.__thtvBrowserAdObserver.disconnect(); } catch(e) {}
                    window.__thtvBrowserAdObserver = null;
                }
            } catch(e) {}
        })();
    """.trimIndent()
}
