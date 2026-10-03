package com.carhud.aaproxy

import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.net.URL

/** Address-bar input is independent of the currently focused page field. */
internal object BrowserNavigation {
    const val HOME = "https://www.google.com/"

    fun targetFor(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (text.startsWith("https://", true) || text.startsWith("http://", true)) {
            return normalizedUrl(text)
        }
        // An address bar must never execute a pasted script or open local app files.
        if (Regex("(?i)^(javascript|data|file|content|intent|about|market):").containsMatchIn(text) ||
            Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)) return null

        if (text.none { it.isWhitespace() }) {
            val host = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            if (host.contains('.') || host.equals("localhost", true)) {
                val scheme = if (host.equals("localhost", true) || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) "http://" else "https://"
                normalizedUrl(scheme + text)?.let { return it }
            }
        }
        return HOME + "search?q=" + URLEncoder.encode(text, "UTF-8")
    }

    private fun normalizedUrl(text: String): String? = try {
        val url = URL(text.replace(" ", "%20"))
        val host = IDN.toASCII(url.host).lowercase()
        val validHost = host.isNotEmpty() && host.length <= 253 && host.split('.').all {
            it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
        }
        if (!validHost || url.userInfo != null || url.port !in -1..65535) null else {
            val port = if (url.port >= 0) ":${url.port}" else ""
            URI("${url.protocol.lowercase()}://$host$port${url.file}${url.ref?.let { "#$it" }.orEmpty()}").toASCIIString()
        }
    } catch (_: Exception) { null }
}
