package com.carhud.aaproxy

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Network bridge for IPTV resources loaded by the local file:// player.
 *
 * Hls.js runs inside a trusted local asset page. Some Vietnamese CDNs reject
 * file-origin requests or require a normal TV/web Referer/Origin, while Android
 * WebView also enforces CORS on XHR/fetch. Proxying the stream request here lets
 * us attach the provider headers and return a CORS-readable response without
 * downloading the whole segment into memory.
 */
object IptvRequestProxy {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    fun shouldIntercept(request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        val method = request.method?.uppercase(Locale.US) ?: "GET"
        if (method != "GET" && method != "HEAD") return null

        val uri = request.url ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null

        return try {
            proxy(
                url = uri.toString(),
                method = method,
                incomingHeaders = request.requestHeaders ?: emptyMap()
            )
        } catch (_: Throwable) {
            // Returning null lets WebView/Hls.js try its normal networking path.
            null
        }
    }

    internal fun providerHeaders(url: String): Map<String, String> {
        val host = try {
            URL(url).host.lowercase(Locale.US)
        } catch (_: Throwable) {
            ""
        }

        return when {
            host.endsWith("fptplay53.net") || host.endsWith("fptplay.net") -> mapOf(
                "Referer" to "https://fptplay.vn/",
                "Origin" to "https://fptplay.vn"
            )
            host.endsWith("vtvdigital.vn") || host.endsWith("vtvprime.vn") -> mapOf(
                "Referer" to "https://vtvgo.vn/",
                "Origin" to "https://vtvgo.vn"
            )
            else -> emptyMap()
        }
    }

    private fun proxy(
        url: String,
        method: String,
        incomingHeaders: Map<String, String>
    ): WebResourceResponse? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.useCaches = false

            val blockedForwardHeaders = setOf(
                "host",
                "connection",
                "content-length",
                "accept-encoding",
                "origin",
                "referer"
            )
            for ((name, value) in incomingHeaders) {
                if (name.lowercase(Locale.US) !in blockedForwardHeaders && value.isNotBlank()) {
                    try { conn.setRequestProperty(name, value) } catch (_: Throwable) {}
                }
            }

            conn.setRequestProperty(
                "User-Agent",
                incomingHeaders.entries.firstOrNull {
                    it.key.equals("User-Agent", ignoreCase = true)
                }?.value?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
            )
            conn.setRequestProperty("Accept", incomingHeaders.entries.firstOrNull {
                it.key.equals("Accept", ignoreCase = true)
            }?.value ?: "*/*")

            for ((name, value) in providerHeaders(url)) {
                conn.setRequestProperty(name, value)
            }

            conn.connect()
            val code = conn.responseCode
            val reason = conn.responseMessage?.takeIf { it.isNotBlank() } ?: when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                204 -> "No Content"
                403 -> "Forbidden"
                404 -> "Not Found"
                else -> "HTTP $code"
            }

            val rawHeaders = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) {
                    rawHeaders[key] = values.joinToString(", ")
                }
            }
            // Hls.js is running from file://. Make the intercepted response readable
            // even when the upstream CDN did not include browser CORS headers.
            rawHeaders["Access-Control-Allow-Origin"] = "*"
            rawHeaders["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
            rawHeaders["Access-Control-Allow-Headers"] = "*"
            rawHeaders["Cache-Control"] = rawHeaders["Cache-Control"] ?: "no-cache"

            val contentType = conn.contentType.orEmpty()
            val mimeType = contentType.substringBefore(';').ifBlank {
                guessMimeType(url)
            }
            val encoding = contentType.substringAfter("charset=", "")
                .substringBefore(';')
                .takeIf { it.isNotBlank() }

            val source = try {
                if (code >= 400) conn.errorStream else conn.inputStream
            } catch (_: Throwable) {
                conn.errorStream
            }

            val data: InputStream = if (source != null) {
                object : FilterInputStream(source) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            conn.disconnect()
                        }
                    }
                }
            } else {
                conn.disconnect()
                java.io.ByteArrayInputStream(ByteArray(0))
            }

            WebResourceResponse(
                mimeType,
                encoding,
                code.coerceIn(100, 599),
                reason,
                rawHeaders,
                data
            )
        } catch (t: Throwable) {
            conn.disconnect()
            throw t
        }
    }

    private fun guessMimeType(url: String): String {
        val path = url.substringBefore('?').lowercase(Locale.US)
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".m4s") -> "video/iso.segment"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".vtt") -> "text/vtt"
            else -> "application/octet-stream"
        }
    }
}
