package com.carhud.aaproxy

import android.widget.EditText

/** Batch text and cursor updates while keeping the existing Editable. */
internal class CarKeyboardEditor(private val input: EditText) {
    fun insert(value: String, telex: Boolean) = edit { text, start, end ->
        var wordStart = start
        var replacement = value
        if (telex && value.length == 1) {
            while (wordStart > 0 && !text[wordStart - 1].isWhitespace() &&
                text[wordStart - 1] !in ",.?!:;()[]{}\"'-/\\") wordStart--
            val result = VietnameseTelexEngine.transformWord(text.subSequence(wordStart, start).toString(), value[0])
            if (result != null) replacement = result else wordStart = start
        }
        text.replace(wordStart, end, replacement)
        input.setSelection(wordStart + replacement.length)
    }

    fun delete() = edit { text, start, end ->
        val from = if (start != end || start == 0) start else Character.offsetByCodePoints(text, start, -1)
        text.delete(from, end)
        input.setSelection(from)
    }

    private inline fun edit(action: (android.text.Editable, Int, Int) -> Unit) {
        val text = input.text ?: return
        val a = input.selectionStart.takeIf { it >= 0 }?.coerceIn(0, text.length) ?: text.length
        val b = input.selectionEnd.takeIf { it >= 0 }?.coerceIn(0, text.length) ?: a
        input.beginBatchEdit()
        try { action(text, minOf(a, b), maxOf(a, b)) } finally { input.endBatchEdit() }
    }
}
