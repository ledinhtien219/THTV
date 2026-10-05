package com.carhud.aaproxy

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CarKeyboardLayoutTest {
    private lateinit var keyboard: CarKeyboardLayout
    private lateinit var q: TextView
    private lateinit var root: FrameLayout
    private val text = StringBuilder()

    @Before fun setup() {
        text.clear()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        keyboard = CarKeyboardLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(keyboard, FrameLayout.LayoutParams(240, 100).apply {
            leftMargin = 36
            topMargin = 80
        })
        val row = LinearLayout(activity)
        keyboard.addView(row, LinearLayout.LayoutParams(240, 50))
        fun key(letter: String) = TextView(activity).apply {
            setOnClickListener { text.append(letter) }
            layoutParams = LinearLayout.LayoutParams(100, 50).apply {
                leftMargin = 4
                rightMargin = 4
            }
        }
        q = key("Q")
        row.addView(q)
        row.addView(key("W"))
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 300)
    }

    private fun touch(action: Int, x: Float = 50f, y: Float = 25f) {
        val event = MotionEvent.obtain(0, 10, action, x, y, 0)
        assertTrue(keyboard.dispatchTouchEvent(event))
        event.recycle()
    }

    @Test fun rapidSurfaceClicksNeverDropOrRepeatCharacters() {
        repeat(50) { assertTrue(keyboard.clickAt(50f, 25f)) }
        assertEquals("Q".repeat(50), text.toString())
    }

    @Test fun nativeTapCommitsOnceWithoutWaitingForPostedClick() {
        repeat(30) {
            touch(MotionEvent.ACTION_DOWN)
            touch(MotionEvent.ACTION_UP)
        }
        assertEquals("Q".repeat(30), text.toString())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(30, text.length)
    }

    @Test fun gapAndSmallFingerMovementStillAcceptTheKey() {
        assertTrue(keyboard.clickAt(106f, 25f)) // Q ends at 104, next key starts at 112.
        touch(MotionEvent.ACTION_DOWN, 106f)
        touch(MotionEvent.ACTION_MOVE, 107f)
        touch(MotionEvent.ACTION_UP, 107f)
        assertEquals("QQ", text.toString())
    }

    @Test fun surfaceCoordinatesMapFromOffsetRootIntoKeyboard() {
        val point = android.graphics.Rect(86, 105, 87, 106)
        root.offsetRectIntoDescendantCoords(keyboard, point)
        assertTrue(keyboard.clickAt(point.left.toFloat(), point.top.toFloat()))
        assertEquals("Q", text.toString())
    }

    @Test fun dragAwayAndCancelledTouchDoNotType() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 230f, 90f)
        touch(MotionEvent.ACTION_UP, 230f, 90f)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_CANCEL)
        touch(MotionEvent.ACTION_UP)
        assertEquals("", text.toString())
    }

    @Test fun longPressDoesNotAppendTheShortPressLetter() {
        q.setOnLongClickListener { text.append("1"); true }
        touch(MotionEvent.ACTION_DOWN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout().toLong() + 10))
        touch(MotionEvent.ACTION_UP)
        assertEquals("1", text.toString())
    }

    @Test fun modeSwitchCanReplaceKeysInsideClickListener() {
        q.setOnClickListener { keyboard.removeAllViews(); text.append("mode") }
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)
        assertFalse(keyboard.clickAt(50f, 25f))
        assertEquals("mode", text.toString())
    }

    @Test fun blankOutsideDisabledAndInvalidCoordinatesDoNotClick() {
        assertFalse(keyboard.clickAt(50f, 90f))
        assertFalse(keyboard.clickAt(-1f, 25f))
        assertFalse(keyboard.clickAt(Float.NaN, 25f))
        q.isEnabled = false
        assertFalse(keyboard.clickAt(50f, 25f))
        assertEquals("", text.toString())
    }
}
