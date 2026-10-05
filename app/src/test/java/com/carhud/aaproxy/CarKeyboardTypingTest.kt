package com.carhud.aaproxy

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class CarKeyboardTypingTest {
    @Test fun everyLetterNumberSymbolAndSpaceReachesInputDuringRapidTyping() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val keyboard = CarKeyboardLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.setContentView(keyboard)
        val input = EditText(activity).apply { setText("") }
        val editor = CarKeyboardEditor(input)
        val keys = "QWERTYUIOPASDFGHJKLZXCVBNM0123456789@.,/ ?!"
        val points = ArrayList<Pair<Float,Float>>()
        keys.chunked(10).forEachIndexed { rowIndex, letters ->
            val row = LinearLayout(activity)
            keyboard.addView(row, LinearLayout.LayoutParams(800, 50))
            letters.forEachIndexed { column, key ->
                row.addView(TextView(activity).apply {
                    text = key.toString()
                    isSoundEffectsEnabled = false
                    setOnClickListener { editor.insert(key.toString(), false) }
                }, LinearLayout.LayoutParams(80, 50))
                points.add(Pair(column * 80f + 40f, rowIndex * 50f + 25f))
            }
        }
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY))
        keyboard.layout(0, 0, 800, 250)
        repeat(20) { points.forEach { (x,y) -> assertTrue(keyboard.clickAt(x,y)) } }
        assertEquals(keys.repeat(20), input.text.toString())
    }
}
