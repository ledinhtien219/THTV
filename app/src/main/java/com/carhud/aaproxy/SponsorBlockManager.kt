package com.carhud.aaproxy

import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * Standalone SponsorBlock client with NO third-party networking dependency.
 *
 * Features:
 * - request de-duplication for the same video ID
 * - bounded LRU cache
 * - different TTLs for success / empty / failure
 * - main-thread callback delivery
 * - strict YouTube video ID validation
 */
object SponsorBlockManager {

    private const val MAX_CACHE_ENTRIES = 128
    private const val SUCCESS_TTL_MS = 6L * 60L * 60L * 1000L
    private const val EMPTY_TTL_MS = 20L * 60L * 1000L
    private const val FAILURE_TTL_MS = 30L * 1000L

    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 5_000
    private const val MAX_BODY_BYTES = 512 * 1024

    private val videoIdRegex = Regex("^[A-Za-z0-9_-]{11}$")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "SponsorBlock").apply {
            isDaemon = true
        }
    }

    private val lock = Any()

    private data class CacheEntry(
        val json: String,
        val expiresAtMs: Long
    )

    private val cache = object :
        LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {

        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CacheEntry>?
        ): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * One network call per video; all simultaneous callers share the result.
     */
    private val inFlight =
        HashMap<String, MutableList<(String) -> Unit>>()

    fun fetchSegments(
        videoId: String,
        onResult: (String) -> Unit
    ) {
        val id = videoId.trim()

        if (!videoIdRegex.matches(id)) {
            postResult(onResult, "[]")
            return
        }

        val now = System.currentTimeMillis()

        synchronized(lock) {
            val cached = cache[id]
            if (cached != null) {
                if (cached.expiresAtMs > now) {
                    postResult(onResult, cached.json)
                    return
                }
                cache.remove(id)
            }

            val existing = inFlight[id]
            if (existing != null) {
                existing += onResult
                return
            }

            inFlight[id] = mutableListOf(onResult)
        }

        executor.execute {
            val result = fetchFromNetwork(id)
            finish(
                id,
                result.json,
                result.ttlMs
            )
        }
    }

    private data class FetchResult(
        val json: String,
        val ttlMs: Long
    )

    private fun fetchFromNetwork(videoId: String): FetchResult {
        var connection: HttpURLConnection? = null

        return try {
            val categories =
                """["sponsor","selfpromo","interaction","intro","outro","preview","music_offtopic","filler","poi_highlight"]"""

            val actionTypes = """["skip"]"""

            val url =
                "https://sponsor.ajay.app/api/skipSegments" +
                    "?videoID=" + encode(videoId) +
                    "&categories=" + encode(categories) +
                    "&actionTypes=" + encode(actionTypes) +
                    "&service=YouTube"

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = true
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "CarHUD-AdBlock/1.0 (Android WebView)"
                )
                setRequestProperty(
                    "Accept",
                    "application/json"
                )
            }

            val code = connection.responseCode

            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return FetchResult(
                    "[]",
                    EMPTY_TTL_MS
                )
            }

            if (code !in 200..299) {
                return FetchResult(
                    "[]",
                    FAILURE_TTL_MS
                )
            }

            val raw = readBodyLimited(connection)
            val json = sanitizeJsonArray(raw)

            FetchResult(
                json,
                if (json == "[]") {
                    EMPTY_TTL_MS
                } else {
                    SUCCESS_TTL_MS
                }
            )
        } catch (_: Throwable) {
            FetchResult(
                "[]",
                FAILURE_TTL_MS
            )
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readBodyLimited(
        connection: HttpURLConnection
    ): String {
        val input = connection.inputStream
        val out = ByteArrayOutputStream()

        input.use { stream ->
            val buffer = ByteArray(8 * 1024)

            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break

                if (out.size() + count > MAX_BODY_BYTES) {
                    return "[]"
                }

                out.write(buffer, 0, count)
            }
        }

        return out.toString(Charsets.UTF_8.name())
    }

    private fun sanitizeJsonArray(raw: String): String {
        if (raw.isBlank()) return "[]"

        val value = raw.trim()

        return if (
            value.startsWith("[") &&
            value.endsWith("]")
        ) {
            value
        } else {
            "[]"
        }
    }

    private fun finish(
        videoId: String,
        json: String,
        ttlMs: Long
    ) {
        val callbacks: List<(String) -> Unit>

        synchronized(lock) {
            cache[videoId] = CacheEntry(
                json = json,
                expiresAtMs =
                    System.currentTimeMillis() + ttlMs
            )

            callbacks =
                inFlight.remove(videoId)
                    ?.toList()
                    .orEmpty()
        }

        if (callbacks.isEmpty()) return

        mainHandler.post {
            callbacks.forEach { callback ->
                try {
                    callback(json)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun postResult(
        callback: (String) -> Unit,
        json: String
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                callback(json)
            } catch (_: Throwable) {
            }
        } else {
            mainHandler.post {
                try {
                    callback(json)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun clearCache() {
        synchronized(lock) {
            cache.clear()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
