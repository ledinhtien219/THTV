package com.carhud.aaproxy

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Run on a phone/emulator already connected to DHU with THTV open. */
@RunWith(AndroidJUnit4::class)
class CarHostKeyboardTest {
    @Test fun hostKeyboardDeliversFastInputAsCompleteText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val displayId = InstrumentationRegistry.getArguments().getString("carDisplayId", "3")
        val handler = Handler(Looper.getMainLooper())
        val accepted = CountDownLatch(1)
        val result = AtomicReference<String>()
        val input = CarInputSession("", "Kiểm tra bàn phím", onSubmit = { value, done ->
            result.set(value)
            done(true)
            accepted.countDown()
        })
        val requested = CountDownLatch(1)
        // Instrumentation restarts the app process; let Android Auto reconnect its service.
        val request = object : Runnable {
            var attempts = 0
            override fun run() {
                if (CarMediaManager.requestCarNativeSearch(input)) requested.countDown()
                else if (++attempts < 100) handler.postDelayed(this, 200)
            }
        }
        handler.post(request)
        assertTrue("THTV must be open in Android Auto/DHU", requested.await(25, TimeUnit.SECONDS))
        Thread.sleep(2500)
        val expected = "QWERTYUIOPASDFGHJKLZXCVBNM0123456789".repeat(3)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
        }
        // Android Auto's input connection forwards these key events to its keyboard,
        // rather than injecting into our custom keyboard on the app's virtual display.
        shell("input -d $displayId text $expected")
        shell("input -d $displayId keyevent KEYCODE_ENTER")
        assertTrue("Android Auto did not submit the host keyboard text", accepted.await(10, TimeUnit.SECONDS))
        assertEquals(expected, result.get())
    }
}
