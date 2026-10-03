package com.carhud.aaproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserNavigationTest {
    @Test fun wordsBecomeGoogleSearches() {
        assertEquals("https://www.google.com/search?q=th%E1%BB%9Di+ti%E1%BA%BFt+h%C3%B4m+nay", BrowserNavigation.targetFor(" thời tiết hôm nay "))
        assertEquals("https://www.google.com/search?q=node.js+tutorial", BrowserNavigation.targetFor("node.js tutorial"))
        assertEquals("https://www.google.com/search?q=site%3Avnexpress.net", BrowserNavigation.targetFor("site:vnexpress.net"))
    }

    @Test fun addressesOpenDirectly() {
        assertEquals("https://vnexpress.net", BrowserNavigation.targetFor("vnexpress.net"))
        assertEquals("https://www.google.com/search?q=a%20b#top", BrowserNavigation.targetFor("www.google.com/search?q=a%20b#top"))
        assertEquals("http://192.168.1.1:8080/path", BrowserNavigation.targetFor("192.168.1.1:8080/path"))
        assertEquals("http://localhost:3000", BrowserNavigation.targetFor("localhost:3000"))
        assertEquals("https://example.com/a%20b", BrowserNavigation.targetFor("https://EXAMPLE.com/a b"))
        assertEquals("https://xn--bcher-kva.de", BrowserNavigation.targetFor("bücher.de"))
    }

    @Test fun invalidAddressesCannotExecuteLocalContent() {
        listOf("", " ", "https://", "https://bad..host", "https://example.com:99999", "https://user:pass@example.com", "javascript:alert(1)", "data:text/html,x", "file:///sdcard/a", "intent://a", "ftp://example.com").forEach {
            assertNull(it, BrowserNavigation.targetFor(it))
        }
    }
}
