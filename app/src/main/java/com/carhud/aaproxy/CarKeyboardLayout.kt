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

/** Cached hit map shared by AA clicks and native, including overlapping finger taps. */
internal class CarKeyboardLayout(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val gapTolerance = (4 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private data class KeyArea(val view: View, val rect: Rect)
    private class Press(val key: KeyArea) {
        var longClicked = false
        var longPress: Runnable? = null
    }
    private val keyAreas = ArrayList<KeyArea>(48)
    private val presses = HashMap<Int, Press>(2)
    private var hitMapDirty = true

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        hitMapDirty = true
    }

    override fun onViewRemoved(child: View) {
        clearPresses()
        hitMapDirty = true
        super.onViewRemoved(child)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        rebuildHitMap()
    }

    private fun rebuildHitMap() {
        keyAreas.clear()
        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                if (child is ViewGroup) visit(child)
                else if (child.isClickable && child.width > 0 && child.height > 0) {
                    val rect = Rect(0, 0, child.width, child.height)
                    offsetDescendantRectToMyCoords(child, rect)
                    keyAreas.add(KeyArea(child, rect))
                }
            }
        }
        visit(this)
        hitMapDirty = false
    }

    private fun keyAt(x: Float, y: Float): KeyArea? {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height) return null
        if (hitMapDirty) rebuildHitMap()
        var closest: KeyArea? = null
        var distance = Float.MAX_VALUE
        for (key in keyAreas) {
            if (!key.view.isEnabled || key.view.visibility != View.VISIBLE) continue
            val dx = maxOf(key.rect.left - x, 0f, x - key.rect.right)
            val dy = maxOf(key.rect.top - y, 0f, y - key.rect.bottom)
            val d = dx * dx + dy * dy
            if (dx <= gapTolerance && dy <= gapTolerance && d < distance) {
                closest = key
                distance = d
                if (d == 0f) break
            }
        }
        return closest
    }

    fun clickAt(x: Float, y: Float): Boolean = keyAt(x, y)?.view?.performClick() ?: false

    private fun startPress(event: MotionEvent, index: Int) {
        val id = event.getPointerId(index)
        cancelPress(id)
        val key = keyAt(event.getX(index), event.getY(index)) ?: return
        val press = Press(key)
        presses[id] = press
        key.view.isPressed = true
        if (key.view.isLongClickable) {
            press.longPress = Runnable {
                if (presses[id] === press && key.view.isEnabled && key.view.isShown)
                    press.longClicked = key.view.performLongClick()
            }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
        }
    }

    private fun contains(press: Press, x: Float, y: Float): Boolean {
        val rect = press.key.rect
        val tolerance = touchSlop + gapTolerance
        return x >= rect.left - tolerance && x < rect.right + tolerance &&
            y >= rect.top - tolerance && y < rect.bottom + tolerance
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearPresses()
                startPress(event, 0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> startPress(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val press = presses[id] ?: continue
                    if (!contains(press, event.getX(i), event.getY(i))) cancelPress(id)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                val press = presses[id]
                val click = press != null && !press.longClicked && press.key.view.isEnabled &&
                    contains(press, event.getX(i), event.getY(i))
                cancelPress(id)
                if (click) press?.key?.view?.performClick()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    clearPresses()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                clearPresses()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun cancelPress(id: Int) {
        val press = presses.remove(id) ?: return
        press.longPress?.let { handler.removeCallbacks(it) }
        press.key.view.isPressed = presses.values.any { it.key.view === press.key.view }
    }

    private fun clearPresses() {
        presses.values.forEach { press ->
            press.longPress?.let { handler.removeCallbacks(it) }
            press.key.view.isPressed = false
        }
        presses.clear()
    }

    override fun onDetachedFromWindow() {
        clearPresses()
        super.onDetachedFromWindow()
    }
}
