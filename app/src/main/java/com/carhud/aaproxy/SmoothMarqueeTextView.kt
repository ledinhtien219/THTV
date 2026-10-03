package com.carhud.aaproxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.appcompat.widget.AppCompatTextView

/**
 * SmoothMarqueeTextView - Butter-smooth 60/90/120 FPS Marquee Text View.
 *
 * Replaces Android's legacy 30 FPS TextView.Marquee implementation which suffers from:
 * 1. 30 Hz timer stutter (1000/30 = 33ms) on 60Hz+ vehicle head units and phones
 * 2. Unwanted reset/flicker whenever sibling views (e.g. scrubber time) trigger relayout
 * 3. 1.2s blank gap restart rather than continuous seamless looping
 *
 * Features:
 * - VSYNC synchronized via Choreographer.FrameCallback
 * - Subpixel text antialiasing with hardware acceleration
 * - Endless looping with clean customizable gap
 * - 1.5s initial pause on track change so drivers can read title start
 * - Zero CPU overhead when text fits or view is invisible/detached
 */
class SmoothMarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var scrollOffset = 0f
    private var textWidth = 0f
    private var isScrolling = false
    private var lastFrameTimeNanos = 0L
    private var initialPauseUntilTimeMs = 0L

    // Smooth automotive scrolling speed: ~36 dp/second
    private val speedPxPerSec: Float = 36f * resources.displayMetrics.density

    // Seamless loop gap between repetitions
    private val gapBetweenRepetitions: Float = 52f * resources.displayMetrics.density

    // Pause for 1.4s at start of new song so title beginning is easily readable
    private val initialPauseMs: Long = 1400L

    private var isFrameScheduled = false

    init {
        paint.flags = paint.flags or Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        setSingleLine(true)
        includeFontPadding = false
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        val current = this.text?.toString().orEmpty()
        val incoming = text?.toString().orEmpty()
        if (current == incoming && textWidth > 0f) {
            // Keep scrolling seamlessly without resetting when title doesn't change
            return
        }

        super.setText(text, type)
        resetMarquee()
    }

    private fun resetMarquee() {
        scrollOffset = 0f
        initialPauseUntilTimeMs = SystemClock.uptimeMillis() + initialPauseMs
        measureTextWidth()
        updateScrollState()
        postInvalidateOnAnimation()
    }

    private fun measureTextWidth() {
        val str = text?.toString().orEmpty()
        textWidth = if (str.isNotEmpty()) paint.measureText(str) else 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureTextWidth()
        updateScrollState()
    }

    private fun availableWidth(): Float {
        return (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(0f)
    }

    private fun needsScroll(): Boolean {
        return textWidth > availableWidth() && availableWidth() > 0f
    }

    private fun updateScrollState() {
        val shouldScroll = isAttachedToWindow && isShown && needsScroll()
        if (shouldScroll != isScrolling) {
            isScrolling = shouldScroll
            if (isScrolling) {
                lastFrameTimeNanos = 0L
                scheduleNextFrame()
            } else {
                stopFrameSchedule()
            }
        }
    }

    private fun scheduleNextFrame() {
        if (!isFrameScheduled && isScrolling) {
            isFrameScheduled = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun stopFrameSchedule() {
        if (isFrameScheduled) {
            isFrameScheduled = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        isFrameScheduled = false
        if (!isScrolling) return

        val nowMs = SystemClock.uptimeMillis()
        if (nowMs < initialPauseUntilTimeMs) {
            scheduleNextFrame()
            return
        }

        if (lastFrameTimeNanos > 0L) {
            val deltaSec = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f
            val clampedDelta = deltaSec.coerceIn(0.001f, 0.05f)
            scrollOffset += speedPxPerSec * clampedDelta

            val cycleLength = textWidth + gapBetweenRepetitions
            if (scrollOffset >= cycleLength) {
                scrollOffset -= cycleLength
            }
            invalidate()
        }
        lastFrameTimeNanos = frameTimeNanos
        scheduleNextFrame()
    }

    override fun onDraw(canvas: Canvas) {
        val str = text?.toString().orEmpty()
        if (str.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        val availWidth = availableWidth()
        if (textWidth <= availWidth) {
            super.onDraw(canvas)
            return
        }

        val count = canvas.save()
        val clipLeft = paddingLeft.toFloat()
        val clipTop = paddingTop.toFloat()
        val clipRight = (width - paddingRight).toFloat()
        val clipBottom = (height - paddingBottom).toFloat()
        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

        val baseline = baseline.toFloat()
        val currentPaint = paint
        currentPaint.color = currentTextColor

        // Draw primary text block
        val x1 = clipLeft - scrollOffset
        canvas.drawText(str, x1, baseline, currentPaint)

        // Draw seamless looping secondary text block
        val cycleLength = textWidth + gapBetweenRepetitions
        val x2 = x1 + cycleLength
        if (x2 < clipRight) {
            canvas.drawText(str, x2, baseline, currentPaint)
        }

        canvas.restoreToCount(count)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        measureTextWidth()
        updateScrollState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopFrameSchedule()
        isScrolling = false
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateScrollState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateScrollState()
    }
}
