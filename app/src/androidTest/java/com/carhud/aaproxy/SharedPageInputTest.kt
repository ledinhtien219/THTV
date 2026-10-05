package com.carhud.aaproxy

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class SharedPageInputTest {
    @Test fun commonInputFillsEachAppWithoutSubmitEnterOrNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val script = context.assets.open("page_input.js").bufferedReader().use { it.readText() }
        for (page in listOf("https://m.youtube.com/", "https://www.google.com/", "file:///android_asset/iptv_player.html")) {
            val done = CountDownLatch(1)
            val result = AtomicReference<String>()
            var web: WebView? = null
            instrumentation.runOnMainSync {
                web = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(script + """
                                (function() {
                                    var field = document.getElementById('query');
                                    var before = location.href, inputs = 0, enters = 0, submits = 0;
                                    field.addEventListener('input', function(){inputs++;});
                                    field.addEventListener('keydown', function(e){if(e.key==='Enter') enters++;});
                                    field.form.addEventListener('submit', function(){submits++;});
                                    var target = window.__thtvPageInput.capture(field);
                                    var status = window.__thtvPageInput.fill(target.token, '24h tiếng Việt');
                                    var duplicate = window.__thtvPageInput.fill(target.token, 'wrong');
                                    return {status:status, value:field.value, inputs:inputs, enters:enters, submits:submits, samePage:before===location.href, duplicate:duplicate};
                                })();
                            """.trimIndent()) { result.set(it); done.countDown() }
                        }
                    }
                    loadDataWithBaseURL(page, "<html><body><form><input type='search' id='query' name='q' placeholder='Nhập nội dung'><button type='submit'>Tìm</button></form></body></html>", "text/html", "UTF-8", null)
                }
            }
            try {
                assertTrue("WebView timed out: $page", done.await(15, TimeUnit.SECONDS))
                val data = JSONObject(result.get())
                assertEquals("OK", data.getString("status"))
                assertEquals("24h tiếng Việt", data.getString("value"))
                assertEquals(1, data.getInt("inputs"))
                assertEquals(0, data.getInt("enters"))
                assertEquals(0, data.getInt("submits"))
                assertTrue(data.getBoolean("samePage"))
                assertEquals("NO_TARGET", data.getString("duplicate"))
            } finally { instrumentation.runOnMainSync { web?.destroy() } }
        }
    }
}
