package com.carhud.aaproxy

import org.junit.Assert.*
import org.junit.Test

class CarInputSessionTest {
    @Test fun rapidSnapshotsDeliverCompleteTextIncludingVietnameseSymbolsAndDeletion() {
        val submitted = mutableListOf<String>()
        val input = CarInputSession("", "Nhập", onSubmit = { text, done -> submitted.add(text); done(true) })
        val text = "Tìm Sơn Tùng 2026 / https://example.com?q=tiếng+Việt!"
        // The host may skip intermediate previews; each callback is a full snapshot.
        repeat(100) {
            for (n in text.indices step 3) input.update(text.substring(0, n))
            input.update(text)
            input.update(text.dropLast(5))
            input.update(text + "  ")
        }
        input.submit { assertTrue(it) }
        assertEquals(listOf(text + "  "), submitted)
        assertTrue(input.closed)
    }
    @Test fun submitUsesFinalArgumentEvenIfLastPreviewIsOlder() {
        var submitted = ""
        val input = CarInputSession("", "Tìm", onSubmit = { text, done -> submitted = text; done(true) })
        input.update("Sơn")
        input.submit("Sơn Tùng MTP") {}
        assertEquals("Sơn Tùng MTP", submitted)
    }
    @Test fun genericWebInputCanBeClearedWhileEmptySearchIsNotExecuted() {
        val values = mutableListOf<String>()
        val web = CarInputSession("cũ", "Nhập", allowEmpty = true, onSubmit = { text, done -> values.add(text); done(true) })
        assertTrue(web.submit("") {})
        assertEquals(listOf(""), values)
        val search = CarInputSession("", "Tìm", onSubmit = { _, _ -> fail("Blank search executed") })
        assertFalse(search.submit("  ") {})
    }
    @Test fun duplicateEnterAndActionAreExecutedOnce() {
        var called = 0
        var finish: ((Boolean) -> Unit)? = null
        val input = CarInputSession("mới", "Nhập", onSubmit = { _, done -> called++; finish = done })
        assertTrue(input.submit {})
        assertFalse(input.submit {})
        finish!!(true)
        finish!!(true)
        assertFalse(input.submit {})
        assertEquals(1, called)
    }
    @Test fun failedWebSubmissionCanBeRetriedAndCancelIgnoresLateCompletion() {
        var finish: ((Boolean) -> Unit)? = null
        var cancelled = ""
        var completions = 0
        val input = CarInputSession("cũ", "Nhập", onSubmit = { _, done -> finish = done }, onCancel = { cancelled = it })
        input.submit { completions++ }
        finish!!(false)
        input.update("mới")
        assertTrue(input.submit { completions++ })
        input.cancel()
        finish!!(true)
        input.update("muộn")
        assertEquals("mới", input.text)
        assertEquals("mới", cancelled)
        assertEquals(1, completions)
    }
}
