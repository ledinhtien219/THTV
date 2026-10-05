package com.carhud.aaproxy

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout

/** One hit map for AA Surface clicks and native touch; key margins are usable tap area. */
internal class CarKeyboardLayout(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val gapTolerance = (4 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressedKey: View? = null
    private var pointerId = -1
    private var longClicked = false
    private val longPress = Runnable {
        pressedKey?.let { key ->
            if (key.isEnabled && key.isShown && key.isLongClickable) longClicked = key.performLongClick()
        }
    }

    private fun bounds(key: View): Rect = Rect(0, 0, key.width, key.height).also {
        offsetDescendantRectToMyCoords(key, it)
    }

    private fun keyAt(x: Float, y: Float): View? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return null
        var closest: View? = null
        var distance = Float.MAX_VALUE
        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE || !child.isEnabled) continue
                if (child is ViewGroup) {
                    visit(child)
                } else if (child.isClickable) {
                    val rect = bounds(child)
                    // Distance to the key's edge, rather than its centre, supports wide space/delete keys.
                    val dx = maxOf(rect.left - x, 0f, x - rect.right)
                    val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
                    val d = dx * dx + dy * dy
                    if (dx <= gapTolerance && dy <= gapTolerance && d < distance) {
                        closest = child
                        distance = d
                    }
                }
            }
        }
        visit(this)
        return closest
    }

    /** SurfaceCallback supplies complete clicks, not DOWN/UP: execute once, synchronously. */
    fun clickAt(x: Float, y: Float): Boolean = keyAt(x, y)?.performClick() ?: false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPress()
                pointerId = event.getPointerId(0)
                pressedKey = keyAt(event.x, event.y)
                pressedKey?.let { key ->
                    key.isPressed = true
                    if (key.isLongClickable)
                        handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                val key = pressedKey
                if (index < 0 || key == null) clearPress()
                else {
                    val rect = bounds(key).apply { inset(-touchSlop - gapTolerance, -touchSlop - gapTolerance) }
                    if (!rect.contains(event.getX(index).toInt(), event.getY(index).toInt())) clearPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                val key = pressedKey
                val index = event.findPointerIndex(pointerId)
                val rect = key?.let { bounds(it).apply { inset(-touchSlop - gapTolerance, -touchSlop - gapTolerance) } }
                val click = !longClicked && key?.isEnabled == true && index >= 0 &&
                    rect?.contains(event.getX(index).toInt(), event.getY(index).toInt()) == true
                clearPress()
                if (click) key?.performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                clearPress()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        // Never allow a missed key/gap to become a click in the WebView under the keyboard.
        return true
    }

    private fun clearPress() {
        handler.removeCallbacks(longPress)
        pressedKey?.isPressed = false
        pressedKey = null
        pointerId = -1
        longClicked = false
    }

    override fun onDetachedFromWindow() {
        clearPress()
        super.onDetachedFromWindow()
    }
}
