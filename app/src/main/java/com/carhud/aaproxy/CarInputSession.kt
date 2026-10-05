package com.carhud.aaproxy

/**
 * A host keyboard supplies complete text snapshots (including paste, IME composition
 * and deletion), not per-key surface gestures. Preserve the last snapshot verbatim.
 */
class CarInputSession(
    initialText: String,
    val hint: String,
    val allowEmpty: Boolean = false,
    private val onSubmit: (String, (Boolean) -> Unit) -> Unit,
    private val onCancel: (String) -> Unit = {}
) {
    var text = initialText
        private set
    var closed = false
        private set
    private var pending = false
    private var submission = 0

    fun update(value: String) {
        if (!closed) text = value
    }

    fun submit(value: String = text, onComplete: (Boolean) -> Unit): Boolean {
        if (closed || pending || (!allowEmpty && value.isBlank())) return false
        text = value
        pending = true
        val token = ++submission
        try {
            onSubmit(value) { accepted ->
                if (closed || !pending || token != submission) return@onSubmit
                pending = false
                if (accepted) closed = true
                onComplete(accepted)
            }
        } catch (_: Exception) {
            if (!closed && pending && token == submission) {
                pending = false
                onComplete(false)
            }
        }
        return true
    }

    fun cancel() {
        if (closed) return
        closed = true
        pending = false
        submission++
        onCancel(text)
    }
}
