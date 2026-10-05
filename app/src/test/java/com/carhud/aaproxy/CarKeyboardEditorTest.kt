package com.carhud.aaproxy

import android.app.Application
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class CarKeyboardEditorTest {
    private lateinit var input: EditText
    private lateinit var editor: CarKeyboardEditor
    @Before fun setup() {
        input = EditText(RuntimeEnvironment.getApplication())
        input.setText("")
        editor = CarKeyboardEditor(input)
    }

    @Test fun rapidAlphabetNumbersAndSpaceKeepEveryCharacter() {
        val keys = "QWERTYUIOPASDFGHJKLZXCVBNM 0123456789 @.?!"
        repeat(20) { keys.forEach { editor.insert(it.toString(), false) } }
        assertEquals(keys.repeat(20), input.text.toString())
        assertEquals(input.length(), input.selectionStart)
    }

    @Test fun telexCanTypeRepeatedVietnameseWordsWithoutDroppingKeys() {
        repeat(20) { "tieengs vieetj ".forEach { editor.insert(it.toString(), true) } }
        assertEquals("tiếng việt ".repeat(20), input.text.toString())
    }

    @Test fun editingSelectionAndMiddleOfWordPreservesRest() {
        input.setText("hello world")
        input.setSelection(5, 2)
        editor.insert("X", false)
        assertEquals("heX world", input.text.toString())
        assertEquals(3, input.selectionStart)
        editor.delete()
        assertEquals("he world", input.text.toString())
    }

    @Test fun deleteRemovesWholeEmojiThenSelectedRange() {
        input.setText("A😀B")
        input.setSelection(3)
        editor.delete()
        assertEquals("AB", input.text.toString())
        input.selectAll()
        editor.delete()
        editor.delete()
        assertEquals("", input.text.toString())
        assertEquals(0, input.selectionStart)
    }
}
