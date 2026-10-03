package com.carhud.aaproxy

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * Custom WebView that prevents Chromium's native AwContents from pausing
 * media playback and throttling JavaScript execution when the hosting Activity
 * moves to the background (e.g., user switches to another app, presses Home,
 * or locks the screen).
 */
class BackgroundAudioWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    var enableBackgroundAudio: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (enableBackgroundAudio) {
            super.onWindowVisibilityChanged(View.VISIBLE)
            return
        }
        super.onWindowVisibilityChanged(visibility)
    }

    override fun dispatchWindowVisibilityChanged(visibility: Int) {
        if (enableBackgroundAudio) {
            super.dispatchWindowVisibilityChanged(View.VISIBLE)
            return
        }
        super.dispatchWindowVisibilityChanged(visibility)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (enableBackgroundAudio) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
            return
        }
        super.onVisibilityChanged(changedView, visibility)
    }

    override fun dispatchVisibilityChanged(changedView: View, visibility: Int) {
        if (enableBackgroundAudio) {
            super.dispatchVisibilityChanged(changedView, View.VISIBLE)
            return
        }
        super.dispatchVisibilityChanged(changedView, visibility)
    }

    override fun onPause() {
        if (enableBackgroundAudio) {
            // Keep Chromium media timers and decoding active in background
            return
        }
        super.onPause()
    }
}
