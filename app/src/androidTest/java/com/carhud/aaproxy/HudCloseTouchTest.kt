package com.carhud.aaproxy

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HudCloseTouchTest {
    @Test fun closeTouchMatchesRenderedButtonWithHudScaling() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (scale in listOf(60, 120, 150)) {
                val done = CountDownLatch(1)
                var failure: Throwable? = null
                scenario.onActivity { activity ->
                    val originalScale = WazeHudManager.getScale(activity)
                    val originalStyle = WazeHudManager.getActiveStyleId(activity)
                    WazeHudManager.setScale(activity, scale)
                    val parent = activity.findViewById<ViewGroup>(android.R.id.content)
                    val container = FrameLayout(activity)
                    parent.addView(container, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    val hud = VietmapHudOverlay(activity)
                    var closed = 0
                    hud.onCloseRequested = { closed++ }
                    hud.applyHudConfig(1)
                    container.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = 90; topMargin = 200 })
                    hud.post {
                        try {
                            val button = (0 until hud.childCount).map { hud.getChildAt(it) }.first { it.contentDescription == "Tắt bong bóng cảnh báo" }
                            val rect = Rect()
                            assertTrue(button.getGlobalVisibleRect(rect))
                            val origin = IntArray(2); container.getLocationOnScreen(origin)
                            assertTrue("Missed rendered × at scale $scale", hud.hitTestClose(rect.exactCenterX() - origin[0], rect.exactCenterY() - origin[1]))
                            val locked = hud.isHudLocked()
                            hud.closeHud()
                            assertEquals(1, closed); assertEquals(locked, hud.isHudLocked())
                            hud.setPreviewMode(true)
                            assertEquals(View.GONE, button.visibility)
                        } catch (error: Throwable) { failure = error }
                        finally {
                            parent.removeView(container)
                            WazeHudManager.setScale(activity, originalScale)
                            hud.applyHudConfig(originalStyle)
                            done.countDown()
                        }
                    }
                }
                assertTrue("HUD layout timed out", done.await(10, TimeUnit.SECONDS))
                failure?.let { throw it }
            }
        }
    }
}
